"""Original notional / venue leverage, using synthetic read-only HL samples."""
import asyncio
from dataclasses import replace
import json
import unittest
from unittest.mock import AsyncMock, Mock, patch

import test_v2 as fixtures
from dashboard_backend.balances import query_account
from test_backend import NOW, TOKEN


class HlMarginCase(unittest.TestCase):
    setUp = fixtures.V2Case.setUp
    state = fixtures.V2Case.state
    history = fixtures.V2Case.history
    open_trade = fixtures.V2Case.open_trade
    request = fixtures.V2Case.request
    scope = fixtures.V2Case.scope
    dashboard = fixtures.V2Case.dashboard
    configure_idle_balances = fixtures.V2Case.configure_idle_balances

    def margin_headers(self):
        return [(b'authorization', ('Bearer ' + TOKEN).encode()), (b'x-dashboard-margin', b'1')]

    def test_venue_entry_margin_populates_missing_saved_margin(self):
        self.state(self.accounts[0], dict(self.open_trade(), entry_capital=5000))
        calls = Mock(return_value={'equity': 9000, 'positions': {
            'BTC': {'size': 2.0, 'entryPrice': 100.0, 'entryMargin': 40.0}}})
        self.configure_idle_balances(calls)
        status, body, _ = self.dashboard(headers=self.margin_headers())
        self.assertEqual(200, status)
        self.assertEqual(40, body['openPerformance']['investedUsd'])
        self.assertEqual(50, body['openPerformance']['investedReturnPct'])
        self.assertEqual('entry_margin', body['openPerformance']['investedBasis'])
        self.assertNotIn('synthetic-', str(body))

    def test_venue_margin_sums_wallets_separately_and_keeps_entry_equity(self):
        for account, side in zip(self.accounts, ('LONG', 'SHORT')):
            self.state(account, dict(self.open_trade(side), entry_capital=5000))
        calls = Mock(side_effect=lambda address: {'equity': 9000, 'positions': {'BTC': {
            'size': 2.0 if address.endswith('a') else -2.0, 'entryPrice': 100.0,
            'entryMargin': 40.0 if address.endswith('a') else 20.0}}})
        self.configure_idle_balances(calls)
        performance = self.dashboard(headers=self.margin_headers())[1]['openPerformance']
        self.assertEqual(60, performance['investedUsd'])
        self.assertEqual(0, performance['investedReturnPct'])
        self.assertEqual(10000, performance['entryEquityUsd'])
        self.assertEqual(0, performance['entryEquityReturnPct'])
        self.assertEqual(2, calls.call_count)
        self.dashboard(headers=self.margin_headers())
        self.assertEqual(2, calls.call_count)  # HTTP and live viewers share the same 10s cache.

    def test_position_mismatch_and_old_or_failed_sample_do_not_supply_margin(self):
        self.state(self.accounts[0], self.open_trade())
        calls = Mock(return_value={'equity': 9000, 'positions': {
            'BTC': {'size': 2.0, 'entryPrice': 100.0, 'entryMargin': 40.0}}})
        main = self.configure_idle_balances(calls)
        self.request(path='/v2/balances', query='')
        samples = self.app.balances.peek(main)
        position = samples['accounts']['a']['positions']['BTC']
        for key, value in [('size', -2), ('size', 1.5), ('entryPrice', 101), ('entryMargin', 0),
                           ('entryMargin', float('nan'))]:
            with self.subTest(key=key, value=value):
                original = position[key]; position[key] = value
                self.assertIsNone(self.dashboard()[1]['openPerformance']['investedUsd'])
                position[key] = original
        for field, value in [('attemptedAt', NOW-6000), ('sampledAt', NOW+1), ('status', 'failed')]:
            target = samples if field == 'attemptedAt' else samples['accounts']['a']
            original = target[field]; target[field] = value
            self.assertIsNone(self.dashboard()[1]['openPerformance']['investedUsd'])
            target[field] = original
        samples['accounts']['a']['positions'] = {}
        self.assertIsNone(self.dashboard()[1]['openPerformance']['investedUsd'])

    def test_recorded_original_margin_takes_precedence_over_current_venue_setting(self):
        self.state(self.accounts[0], dict(self.open_trade(), entry_margin_usd=70))
        self.configure_idle_balances(Mock(return_value={'equity': 9000, 'positions': {
            'BTC': {'size': 2, 'entryPrice': 100, 'entryMargin': 40}}}))
        performance = self.dashboard(headers=self.margin_headers())[1]['openPerformance']
        self.assertEqual(70, performance['investedUsd'])
        self.assertAlmostEqual(20/70*100, performance['investedReturnPct'])

    def test_opt_in_is_authenticated_and_does_not_query_for_backtest_or_charts(self):
        calls = Mock(return_value=500)
        self.configure_idle_balances(calls)
        self.assertEqual(401, self.dashboard(headers=[(b'x-dashboard-margin', b'1')])[0])
        self.dashboard()
        self.dashboard(headers=self.margin_headers()+[(b'x-dashboard-margin', b'1')])
        self.dashboard('period=All&model=all&mode=edge', headers=self.margin_headers())
        self.app.indicators.get = AsyncMock(return_value={})
        self.dashboard('view=macd', headers=self.margin_headers())
        calls.assert_not_called()

    def test_revocation_during_margin_fetch_prevents_http_response(self):
        def revoke(_):
            self.store.revoke('main')
            return 500
        self.configure_idle_balances(Mock(side_effect=revoke))
        self.assertEqual(401, self.dashboard(headers=self.margin_headers())[0])

    def test_live_stream_requests_margin_without_a_manual_balance_refresh(self):
        self.state(self.accounts[0], self.open_trade())
        calls = Mock(return_value={'equity': 9000, 'positions': {
            'BTC': {'size': 2, 'entryPrice': 100, 'entryMargin': 40}}})
        self.configure_idle_balances(calls)
        async def exercise():
            queue = asyncio.Queue(); await queue.put({'type': 'websocket.connect'})
            values = []
            async def send(event):
                if event['type'] == 'websocket.send':
                    values.append(json.loads(event['text']))
                    await queue.put({'type': 'websocket.disconnect'})
            await self.app(self.scope(path='/v2/live', query='period=All&model=all',
                                     headers=self.margin_headers()), queue.get, send)
            self.assertEqual(40, values[0]['openPerformance']['investedUsd'])
            self.assertEqual(2, calls.call_count)
        self.loop.run_until_complete(exercise())

    def test_live_stream_refreshes_changed_size_after_cache_expiry(self):
        self.state(self.accounts[0], self.open_trade())
        tick, size = [0], [2]
        calls = Mock(side_effect=lambda _: {'equity': 9000, 'positions': {
            'BTC': {'size': size[0], 'entryPrice': 100, 'entryMargin': size[0]*20}}})
        self.configure_idle_balances(calls, monotonic=lambda: tick[0])
        self.app.config = replace(self.app.config, poll_seconds=0.01)
        async def exercise():
            queue = asyncio.Queue(); await queue.put({'type': 'websocket.connect'})
            values = []
            async def send(event):
                if event['type'] == 'websocket.send':
                    values.append(json.loads(event['text']))
                    if len(values) == 1:
                        size[0], tick[0] = 3, 10
                        self.state(self.accounts[0], dict(self.open_trade(), sz=3))
                    else:
                        await queue.put({'type': 'websocket.disconnect'})
            await self.app(self.scope(path='/v2/live', query='period=All&model=all',
                                     headers=self.margin_headers()), queue.get, send)
            self.assertEqual([40,60], [v['openPerformance']['investedUsd'] for v in values])
            self.assertEqual([50,50], [v['openPerformance']['investedReturnPct'] for v in values])
            self.assertEqual(4, calls.call_count)
        self.loop.run_until_complete(exercise())

    def test_account_parser_uses_exchange_leverage_at_entry_and_handles_zero_pnl(self):
        response = Mock(); response.__enter__ = Mock(return_value=response); response.__exit__ = Mock(return_value=False)
        opener = Mock(); opener.open.return_value = response
        with patch('dashboard_backend.balances.urllib.request.build_opener', return_value=opener):
            for mode in ('cross', 'isolated'):
                for pnl in (-1000, 0, 1000):
                    position = dict(coin='BTC', szi='2', entryPx='5000', leverage={'type':mode, 'value':5},
                                    marginUsed='9999', unrealizedPnl=str(pnl), returnOnEquity=str(pnl/2000))
                    body = {'marginSummary':{'accountValue':'5000'}, 'assetPositions':[{'position':position}]}
                    response.read.return_value = json.dumps(body).encode()
                    parsed = query_account('synthetic-public-address')
                    self.assertEqual(5000, parsed['equity'])
                    self.assertEqual(2000, parsed['positions']['BTC']['entryMargin'])
                    self.assertEqual(pnl/2000, float(position['returnOnEquity']))
                    self.assertNotIn('marginUsed', str(parsed))
            for invalid in (None, True, 0, -1, 2.5, 101, '5'):
                position['leverage']['value'] = invalid
                response.read.return_value = json.dumps(body).encode()
                parsed = query_account('synthetic-public-address')
                self.assertEqual({}, parsed['positions'])
                self.assertEqual(5000, parsed['equity'])
