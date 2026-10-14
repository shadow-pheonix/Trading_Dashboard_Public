"""Public market startup, sharing and failure cases; all providers are mocked."""
import json
import threading
import unittest
from unittest.mock import Mock, patch

from dashboard_backend.market import BinancePrices, fetch_btc_ticker
from dashboard_backend.validation import InvalidData
from test_backend import NOW


class PriceStartupCase(unittest.TestCase):
    def row(self, **changes):
        return dict(symbol='BTCUSDT', closeTime=NOW, lastPrice='82000', priceChangePercent='2.3', **changes)

    def pool(self, fetch):
        value = BinancePrices(clock=lambda: NOW, ticker_fetcher=fetch)
        # No real socket or immortal test thread is needed for REST startup cases.
        value.thread = Mock(); value.thread.is_alive.return_value = True
        return value

    def test_no_idle_requests_and_shared_nonblocking_startup_before_socket_connects(self):
        release = threading.Event(); started = threading.Event()
        def fetch():
            started.set(); release.wait(1); return self.row()
        query = Mock(side_effect=fetch); pool = self.pool(query)
        query.assert_not_called()
        try:
            pool.acquire({'BTCUSDT'}); self.assertTrue(started.wait(1))
            for _ in range(6): pool.acquire({'BTCUSDT'})
            self.assertEqual(1, query.call_count)
            release.set(); pool.seed_thread.join(1)
            self.assertFalse(pool.seed_thread.is_alive())
            self.assertEqual(82000, pool.ticker('BTCUSDT')['price'])
            self.assertIsNone(pool.get('BTCUSDT'))  # Trade P&L still needs live marks.
            for _ in range(7): pool.release({'BTCUSDT'})
            self.assertIsNone(pool.ticker('BTCUSDT'))
            self.assertEqual(1, query.call_count)
        finally: release.set(); pool.seed_thread.join(1)

    def test_fresh_sample_reused_and_failure_cooldown_has_no_retry_poll(self):
        query = Mock(return_value=self.row()); pool = self.pool(query)
        pool.acquire({'BTCUSDT'}); pool.seed_thread.join(1)
        pool.release({'BTCUSDT'}); pool.acquire({'BTCUSDT'})
        self.assertEqual(1, query.call_count)
        pool.release({'BTCUSDT'})
        query = Mock(side_effect=OSError('synthetic private response')); pool = self.pool(query)
        pool.acquire({'BTCUSDT'}); pool.seed_thread.join(1)
        pool.acquire({'BTCUSDT'}); pool.acquire({'BTCUSDT'})
        self.assertEqual(1, query.call_count); self.assertIsNone(pool.ticker('BTCUSDT'))
        for _ in range(3): pool.release({'BTCUSDT'})
        self.assertEqual(1, query.call_count)

    def test_disconnect_invalidates_inflight_seed_and_late_rest_cannot_overwrite_stream(self):
        release = threading.Event(); started = threading.Event()
        def fetch(): started.set(); release.wait(1); return self.row()
        pool = self.pool(fetch)
        try:
            pool.acquire({'BTCUSDT'}); self.assertTrue(started.wait(1))
            pool.release({'BTCUSDT'}); pool.acquire({'BTCUSDT'})
            release.set(); pool.seed_thread.join(1)
            self.assertIsNone(pool.ticker('BTCUSDT')); pool.release({'BTCUSDT'})
        finally: release.set(); pool.seed_thread.join(1)
        pool = self.pool(lambda: self.row())
        pool.references = {'BTCUSDT': 1}
        pool.accept(json.dumps({'e':'24hrTicker','s':'BTCUSDT','E':NOW,'c':'83000','P':'3'}))
        pool.ticker_fetcher = lambda: dict(self.row(), closeTime=NOW-1)
        pool._seed_ticker(pool.ticker_generation)
        self.assertEqual(83000, pool.ticker('BTCUSDT')['price'])

    def test_invalid_future_stale_wrong_symbol_and_nonfinite_rest_are_unavailable(self):
        for change in ({'symbol':'ETHUSDT'},{'closeTime':NOW+1},{'closeTime':NOW-15001},
                       {'lastPrice':'nan'},{'lastPrice':'0'},{'priceChangePercent':'-100.1'},
                       {'priceChangePercent':'inf'},{'closeTime':True}):
            pool = self.pool(lambda: dict(self.row(), **change)); pool.references = {'BTCUSDT':1}
            pool._seed_ticker(0)
            self.assertIsNone(pool.ticker('BTCUSDT'))

    def test_fixed_public_rest_request_timeout_and_response_size(self):
        with patch('dashboard_backend.market.urlopen') as opened:
            response = opened.return_value.__enter__.return_value
            response.read.return_value = json.dumps(self.row()).encode()
            self.assertEqual(self.row(), fetch_btc_ticker())
            opened.assert_called_once_with('https://fapi.binance.com/fapi/v1/ticker/24hr?symbol=BTCUSDT', timeout=2)
            response.read.assert_called_once_with(4097)
            response.read.return_value = b'x'*4097
            with self.assertRaises(InvalidData): fetch_btc_ticker()

    def test_non_btc_viewer_does_not_request_btc(self):
        query=Mock(return_value=self.row()); pool=self.pool(query)
        pool.acquire({'ETHUSDT'}); pool.release({'ETHUSDT'}); query.assert_not_called()
