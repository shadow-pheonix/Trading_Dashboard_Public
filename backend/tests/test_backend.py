"""Offline verification: synthetic files/keys, no bot imports or exchange connections."""
import asyncio
import contextlib
import copy
import csv
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from dashboard_backend.api import Application, Limits
from dashboard_backend.config import Account, Config, Instance, load_config
from dashboard_backend.files import BotFiles, read_stable
from dashboard_backend.market import BinancePrices
from dashboard_backend.storage import Store
from dashboard_backend.validation import InvalidData, MAX_BYTES, PERIODS, decode, encode, snapshot

NOW = 1791456000000
TOKEN = 'synthetic-dashboard-key-' + 'x' * 32
SECRET = 'synthetic-secret-never-export'
COLUMNS = ['trade_num', 'side', 'entry_time', 'exit_time', 'entry_price', 'exit_price',
           'pnl_usd', 'total_fees', 'exit_equity', 'source', 'model']


def iso(milliseconds):
    return datetime.fromtimestamp(milliseconds / 1000, timezone.utc).isoformat()


class Prices:
    def __init__(self):
        self.refs = 0
        self.acquisitions = 0
        self.available = True

    def acquire(self, symbols):
        self.refs += 1
        self.acquisitions += 1

    def release(self, symbols):
        self.refs -= 1

    def wait_ready(self, symbols):
        pass

    def get(self, symbol):
        return (110, NOW) if self.available else None

    def ticker(self, symbol):
        return {"symbol": symbol, "price": 111, "updatedAt": NOW, "change24hPct": 2.5} if self.available else None


class BackendCase(unittest.TestCase):
    def test_indicator_view_auth_scope_and_no_prices_or_balances(self):
        from unittest.mock import AsyncMock
        self.app.indicators.get = AsyncMock(return_value={'source': 'binance_spot_closed'})
        self.assertEqual(self.request('/v2/dashboard', 'view=macd', token=None)[0], 401)
        self.app.indicators.get.assert_not_called()
        status, value, _ = self.request('/v2/dashboard', 'view=macd')
        self.assertEqual(status, 200)
        self.app.indicators.get.assert_awaited_once_with(self.main)
        self.assertEqual(value['source'], 'binance_spot_closed')
        self.assertEqual(self.prices.acquisitions, 0)
        self.assertEqual(self.app.balances.tasks, {})
        for query in ('view=bad', 'view=macd&mode=edge', 'view=macd&model=M1', 'view=macd&view=macd'):
            self.assertEqual(self.request('/v2/dashboard', query)[0], 400)
        self.assertEqual(self.request('/v1/dashboard', 'view=macd')[0], 400)
        self.assertEqual(self.app.indicators.get.await_count, 1)

    def test_indicator_view_rechecks_revoked_key_before_response(self):
        from unittest.mock import AsyncMock
        async def revoked(_):
            with contextlib.closing(sqlite3.connect(self.store.path)) as db, db:
                db.execute('DELETE FROM dashboard_keys')
            return {'status': 'ok'}
        self.app.indicators.get = AsyncMock(side_effect=revoked)
        self.assertEqual(self.request('/v2/dashboard', 'view=macd')[0], 401)

    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.path = Path(temp.name)
        self.accounts = tuple(Account(a, 'Wallet ' + a, str(self.path / (a + '.json')),
            str(self.path / (a + '.csv')), 'M1', 'BTCUSDT') for a in ('a', 'b'))
        self.main = Instance('main', 'Main', self.accounts)
        self.config = Config((self.main,))
        for account in self.accounts:
            self.state(account)
            self.history(account)
        self.store = Store(self.path / 'dashboard.sqlite')
        self.store.initialize()
        with contextlib.closing(sqlite3.connect(self.store.path)) as db, db:
            db.execute('INSERT INTO dashboard_keys(digest,instance_id) VALUES(?,?)',
                       (hashlib.sha256(TOKEN.encode()).hexdigest(), 'main'))
        self.prices = Prices()
        self.files = BotFiles()
        self.app = Application(self.config, self.store, clock=lambda: NOW,
                               limits=Limits(clock=lambda: 0), file_reader=self.files, prices=self.prices)
        self.loop = asyncio.new_event_loop()
        self.addCleanup(self.loop.close)
        # Restricted environments may block socketpair thread wakeups. Keep real worker calls.
        def heartbeat():
            self.loop.call_later(0.01, heartbeat)
        heartbeat()

    def state(self, account=None, trade=None):
        account = account or self.accounts[0]
        path = Path(account.state_path)
        path.write_text(json.dumps({'trade': trade, 'internal': SECRET}))
        os.utime(path, ns=((NOW - 100000) * 1000000,) * 2)

    def open_trade(self, side='LONG'):
        return {'side': side, 'sz': 2, 'entry_price': 100, 'entry_time': iso(NOW - 5000),
                'leverage': 3, 'sl': 90, 'status': 'ACTIVE', 'cloid': SECRET}

    def row(self, offset=1000, number='1', pnl='9', source='live'):
        return [number, 'LONG', iso(NOW - offset - 10000), iso(NOW - offset),
                '100', '110', pnl, '1', '1009', source, 'M1']

    def history(self, account=None, rows=()):
        account = account or self.accounts[0]
        with Path(account.history_path).open('w', newline='') as file:
            writer = csv.writer(file)
            writer.writerow(COLUMNS)
            writer.writerows(rows)

    def value(self, period='1M', account='all'):
        return self.files.build(self.main, period, account, self.prices, NOW)

    def request(self, path='/v1/dashboard', query='period=1M&account=all', token=TOKEN,
                method='GET', headers=None):
        messages = []
        async def send(event):
            messages.append(event)
        async def receive():
            return {'type': 'http.request', 'body': b'', 'more_body': False}
        scope = self.scope('http', path, query, token, headers)
        scope['method'] = method
        self.loop.run_until_complete(self.app(scope, receive, send))
        return messages[0]['status'], decode(messages[1]['body']), dict(messages[0]['headers'])

    def scope(self, kind='websocket', path='/v1/live', query='period=1M&account=all', token=TOKEN, headers=None):
        return {'type': kind, 'path': path, 'query_string': query.encode(), 'client': ('test-peer', 1),
                'headers': headers if headers is not None else ([] if token is None else
                    [(b'authorization', ('Bearer ' + token).encode())])}

    def websocket(self, token=TOKEN, query='period=1M&account=all', after_frame=None, disconnect=True):
        messages = []
        async def exercise():
            queue = asyncio.Queue()
            await queue.put({'type': 'websocket.connect'})
            async def send(event):
                messages.append(event)
                if event['type'] == 'websocket.send':
                    if after_frame:
                        after_frame()
                    if disconnect:
                        await queue.put({'type': 'websocket.disconnect', 'code': 1000})
            await asyncio.wait_for(self.app(self.scope(token=token, query=query), queue.get, send), 5)
        self.loop.run_until_complete(exercise())
        return messages

    def test_dashboard_contract_privacy_and_null_balances(self):
        self.state(trade=self.open_trade())
        self.history(rows=[self.row()])
        status, body, headers = self.request()
        self.assertEqual(200, status)
        self.assertEqual(20, body['metrics']['unrealizedPnl'])
        self.assertEqual(9, body['metrics']['realizedPnl'])
        self.assertIsNone(body['metrics']['equity'])
        self.assertIsNone(body['trades'][0]['funding'])
        self.assertEqual('bot_files_binance', body['dataSource'])
        self.assertNotIn(SECRET, json.dumps(body))
        self.assertNotIn(str(self.path), json.dumps(body))
        self.assertEqual(b'no-store', headers[b'cache-control'])
        self.assertEqual(0, self.prices.refs)

    def test_manual_unknown_entry_keeps_history_and_open_position(self):
        trade = self.open_trade()
        trade['entry_time'] = 'unknown'
        trade['manual'] = True
        self.state(trade=trade)
        row = self.row()
        row[2] = 'unknown'
        self.history(rows=[row])
        status, body, _ = self.request()
        self.assertEqual(200, status)
        self.assertIsNone(body['trades'][0]['openedAt'])
        self.assertEqual(NOW - 1000, body['trades'][0]['closedAt'])
        self.assertEqual(9, body['metrics']['realizedPnl'])
        self.assertEqual(20, body['positions'][0]['unrealizedPnl'])
        self.assertNotIn(SECRET, json.dumps(body))

    def test_unknown_entry_does_not_allow_bad_or_missing_exit_times(self):
        for invalid in ('', 'UNKNOWN', 'not-a-time', None):
            trade = self.open_trade()
            trade['entry_time'] = invalid
            self.state(trade=trade)
            self.assertEqual(503, self.request()[0])
        self.state()
        for invalid in ('unknown', '', 'not-a-time'):
            row = self.row()
            row[2] = 'unknown'
            row[3] = invalid
            self.history(rows=[row])
            self.assertEqual(503, self.request()[0])

    def test_auth_rejections_do_not_read_files_or_start_prices(self):
        for token in (None, 'wrong', TOKEN + ' ', 'x' * 43):
            self.assertEqual(401, self.request(token=token)[0])
        duplicate = [(b'authorization', ('Bearer ' + TOKEN).encode())] * 2
        self.assertEqual(401, self.request(headers=duplicate)[0])
        self.assertEqual(401, self.request(headers=[(b'authorization', b'Bearer \xff')])[0])
        self.assertEqual(0, self.prices.acquisitions)
        self.assertEqual({}, self.files.history_cache)

    def test_revocation_and_scope_precede_collection(self):
        self.assertEqual(401, self.request(query='account=foreign', token=None)[0])
        self.assertEqual(403, self.request(query='account=foreign')[0])
        self.store.revoke('main')
        self.assertEqual(401, self.request()[0])
        self.assertEqual(0, self.prices.acquisitions)

    def test_no_trading_or_write_routes(self):
        for method in ('POST', 'PUT', 'DELETE', 'PATCH', 'OPTIONS', 'HEAD'):
            self.assertEqual(405, self.request(method=method)[0])
        for path in ('/v1/trade', '/v1/order', '/keys', '/metrics', '/v1/dashboard/'):
            self.assertEqual(404, self.request(path=path)[0])
        self.assertEqual(0, self.prices.acquisitions)

    def test_health_does_not_start_collection(self):
        status, body, _ = self.request('/healthz', token=None)
        self.assertEqual((200, {'status': 'ok'}), (status, body))
        self.assertEqual(0, self.prices.acquisitions)
        self.assertEqual({}, self.files.history_cache)

    def test_invalid_queries_do_not_start_collection(self):
        for query in ('period=bad', 'period=1M&period=1D', 'account=', 'account=../a',
                      'instance=other', 'account=a&account=b', 'period', 'period=' + 'x' * 600):
            self.assertEqual(400, self.request(query=query)[0])
        self.assertEqual(0, self.prices.acquisitions)

    def test_rate_limits_expire_and_are_bounded(self):
        now = [0]
        limits = Limits(clock=lambda: now[0])
        for _ in range(12):
            limits.failure('peer')
        self.assertFalse(limits.allow('peer'))
        now[0] = 60
        self.assertTrue(limits.allow('peer'))
        for i in range(1200):
            limits.failure(str(i))
        self.assertLessEqual(len(limits.failures), 1024)

    def test_forwarded_headers_cannot_bypass_failed_auth_limits(self):
        for _ in range(12):
            self.assertEqual(401, self.request(token='wrong')[0])
        headers = [(b'authorization', ('Bearer ' + TOKEN).encode()), (b'x-forwarded-for', b'different')]
        status, _, result = self.request(headers=headers)
        self.assertEqual(429, status)
        self.assertEqual(b'60', result[b'retry-after'])

    def test_backend_errors_are_sanitized_and_release_prices(self):
        with patch.object(self.files, 'build', side_effect=RuntimeError(SECRET)):
            status, body, _ = self.request()
        self.assertEqual(503, status)
        self.assertNotIn(SECRET, str(body))
        self.assertEqual(0, self.prices.refs)

    def test_oversized_response_rejected_whole(self):
        with patch.object(self.files, 'build', return_value={'huge': 'x' * MAX_BYTES}):
            self.assertEqual(503, self.request()[0])
        self.assertEqual(0, self.prices.refs)

    def test_key_hash_private_file_and_no_overwrite(self):
        output = self.path / 'key.txt'
        self.store.provision('main', output)
        token = output.read_text().strip()
        self.assertEqual(43, len(token))
        self.assertEqual(0o600, output.stat().st_mode & 0o777)
        self.assertEqual('main', self.store.authenticate(token))
        with contextlib.closing(self.store.connect(True)) as db:
            self.assertNotIn(token, str(db.execute('SELECT digest FROM dashboard_keys').fetchall()))
        with self.assertRaises(FileExistsError):
            self.store.provision('main', output)
        self.assertEqual(token, output.read_text().strip())

    def test_key_failure_removes_unregistered_file(self):
        output = self.path / 'key.txt'
        with patch.object(self.store, 'connect', side_effect=sqlite3.OperationalError()):
            with self.assertRaises(sqlite3.OperationalError):
                self.store.provision('main', output)
        self.assertFalse(output.exists())

    def test_readonly_database_rejects_writes(self):
        with contextlib.closing(self.store.connect(True)) as db:
            with self.assertRaises(sqlite3.OperationalError):
                db.execute('DELETE FROM dashboard_keys')

    def test_ws_authentication_before_accept_and_collection(self):
        events = self.websocket(token=None)
        self.assertEqual([{'type': 'websocket.close', 'code': 1008}], events)
        self.assertEqual(0, self.prices.acquisitions)
        self.assertEqual(0, self.app.viewers)

    def test_ws_scope_rejection_before_collection(self):
        events = self.websocket(query='account=foreign')
        self.assertEqual('websocket.close', events[0]['type'])
        self.assertEqual(0, self.prices.acquisitions)

    def test_ws_full_initial_dashboard_disconnect_and_idle(self):
        events = self.websocket()
        self.assertEqual('websocket.accept', events[0]['type'])
        body = decode(events[1]['text'])
        self.assertEqual('Main', body['instance'])
        self.assertIn('monthlyReturns', body)
        self.assertEqual(0, self.app.viewers)
        self.assertEqual(0, self.prices.refs)
        with patch.object(self.files, 'build') as build:
            self.loop.run_until_complete(asyncio.sleep(0.04))
            build.assert_not_called()

    def test_ws_open_connection_revoked_on_next_update(self):
        events = self.websocket(after_frame=lambda: self.store.revoke('main'), disconnect=False)
        self.assertEqual({'type': 'websocket.close', 'code': 1008}, events[-1])
        self.assertEqual(0, self.prices.refs)

    def test_ws_errors_release_price_subscription(self):
        with patch.object(self.files, 'build', side_effect=RuntimeError(SECRET)):
            events = self.websocket()
        self.assertEqual({'type': 'websocket.close', 'code': 1011}, events[-1])
        self.assertNotIn(SECRET, str(events))
        self.assertEqual(0, self.prices.refs)

    def test_ws_task_cancellation_releases_subscription(self):
        async def exercise():
            queue = asyncio.Queue()
            await queue.put({'type': 'websocket.connect'})
            sent = asyncio.Event()
            async def send(event):
                if event['type'] == 'websocket.send':
                    sent.set()
            task = asyncio.create_task(self.app(self.scope(), queue.get, send))
            await asyncio.wait_for(sent.wait(), 2)
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task
        self.loop.run_until_complete(exercise())
        self.assertEqual(0, self.prices.refs)
        self.assertEqual(0, self.app.viewers)

    def test_ws_receive_error_still_releases_subscription(self):
        async def exercise():
            started = False
            async def receive():
                nonlocal started
                if not started:
                    started = True
                    return {'type': 'websocket.connect'}
                raise OSError(SECRET)
            async def send(event):
                pass
            await self.app(self.scope(), receive, send)
        self.loop.run_until_complete(exercise())
        self.assertEqual(0, self.prices.refs)
        self.assertEqual(0, self.app.viewers)

    def test_ws_limit_rejects_before_collection(self):
        self.app.viewers = 16
        events = self.websocket()
        self.assertEqual('websocket.close', events[0]['type'])
        self.assertEqual(0, self.prices.acquisitions)

    def test_missing_history_is_unknown_not_zero(self):
        Path(self.accounts[0].history_path).unlink()
        self.assertIsNone(self.value()['metrics']['closedTrades'])
        self.assertIsNone(self.value()['metrics']['realizedPnl'])
        self.assertEqual([], self.value()['curve'])

    def test_present_empty_history_is_zero_without_fabricated_return(self):
        value = self.value()
        self.assertEqual(0, value['metrics']['closedTrades'])
        self.assertEqual(0, value['metrics']['realizedPnl'])
        self.assertIsNone(value['metrics']['winRatePct'])
        for key in ('sharpeRatio', 'maxDrawdownPct', 'totalReturnPct'):
            self.assertIsNone(value['metrics'][key])

    def test_all_periods_and_account_scoping(self):
        self.history(rows=[self.row(), self.row(40 * 86400000, '2', '-5')])
        self.history(self.accounts[1], [self.row(pnl='7')])
        for period in PERIODS:
            value = self.value(period, 'a')
            self.assertTrue(all(t['accountId'] == 'a' for t in value['trades']))
            self.assertEqual({'a', 'b'}, {a['id'] for a in value['accounts']})
            self.assertEqual(4 if period in ('3M', '1Y', 'YTD', 'All') else 9, value['metrics']['realizedPnl'])
        self.assertEqual(16, self.value()['metrics']['realizedPnl'])

    def test_logged_fees_not_deducted_twice_and_dollar_months(self):
        self.history(rows=[self.row()])
        value = self.value(account='a')
        self.assertEqual(9, value['trades'][0]['netPnl'])
        self.assertEqual(1, value['trades'][0]['fees'])
        self.assertEqual(9, value['monthlyReturns'][0]['pnl'])
        self.assertIsNone(value['monthlyReturns'][0]['returnPct'])
        self.assertEqual(1009, value['curve'][0]['equity'])
        self.assertIsNone(self.value()['curve'][0]['equity'])

    def test_binance_long_short_estimates_and_disconnection(self):
        for side, expected in (('LONG', 20), ('SHORT', -20)):
            self.state(trade=self.open_trade(side))
            self.assertEqual(expected, self.value()['metrics']['unrealizedPnl'])
        self.prices.available = False
        value = self.value()
        self.assertFalse(value['live'])
        self.assertIsNone(value['positions'][0]['markPrice'])
        self.assertIsNone(value['metrics']['unrealizedPnl'])
        self.assertEqual(NOW - 100000, value['stateUpdatedAt'])

    def test_partial_csv_backtests_foreign_model_and_duplicates_rejected(self):
        path = Path(self.accounts[0].history_path)
        for rows in ([self.row(source='backtest')], [self.row(), self.row()]):
            self.history(rows=rows)
            with self.assertRaises(InvalidData):
                self.value()
        self.history(rows=[self.row()])
        path.write_bytes(path.read_bytes().rstrip(b'\r\n'))
        with self.assertRaises(InvalidData):
            self.value()
        row = self.row(); row[-1] = 'M2'
        self.history(rows=[row])
        with self.assertRaises(InvalidData):
            self.value()

    def test_csv_cache_refreshes_after_append(self):
        self.history(rows=[self.row()])
        self.assertEqual(9, self.value()['metrics']['realizedPnl'])
        self.history(rows=[self.row(), self.row(2000, '2', '-4')])
        self.assertEqual(5, self.value()['metrics']['realizedPnl'])

    def test_atomic_state_replace_followed_and_files_unchanged(self):
        path = Path(self.accounts[0].state_path)
        original = path.read_bytes()
        self.value()
        self.assertEqual(original, path.read_bytes())
        replacement = path.with_name('replace.json')
        replacement.write_text(json.dumps({'trade': self.open_trade()}))
        os.utime(replacement, ns=(NOW * 1000000,) * 2)
        replacement.replace(path)
        self.assertEqual(1, len(self.value()['positions']))

    def test_symlink_and_wrong_extension_rejected(self):
        link = self.path / 'link.json'
        link.symlink_to(self.accounts[0].state_path)
        with self.assertRaises(OSError):
            read_stable(link, 1024)
        with self.assertRaises(InvalidData):
            read_stable(self.path / '.env', 1024)

    def test_state_history_close_race_rejected(self):
        trade = self.open_trade()
        self.state(trade=trade)
        row = self.row(); row[2] = trade['entry_time']
        self.history(rows=[row])
        with self.assertRaises(InvalidData):
            self.value()

    def test_invalid_state_and_future_history_rejected(self):
        trade = self.open_trade(); trade['sz'] = float('nan')
        self.state(trade=trade)
        with self.assertRaises(InvalidData):
            self.value()
        self.state()
        self.history(rows=[self.row(-1000)])
        with self.assertRaises(InvalidData):
            self.value()

    def test_config_strict_paths_and_no_address_or_secret_fields(self):
        value = {'instances': [{'id': 'main', 'label': 'Main', 'accounts': [{'id': 'a',
            'label': 'A', 'statePath': self.accounts[0].state_path,
            'historyPath': self.accounts[0].history_path, 'strategy': 'M1'}]}]}
        path = self.path / 'config.json'
        path.write_text(json.dumps(value))
        self.assertEqual(3, load_config(path).poll_seconds)
        self.assertNotIn(str(self.path), repr(load_config(path)))
        for key, bad in (('statePath', '/tmp/.env'), ('statePath', 'relative.json'),
                         ('historyPath', '/tmp/backtest/a.csv'), ('address', SECRET), ('symbol', 'bad')):
            changed = copy.deepcopy(value); changed['instances'][0]['accounts'][0][key] = bad
            path.write_text(json.dumps(changed))
            with self.assertRaises(InvalidData):
                load_config(path)

    def test_snapshot_unknown_fields_and_nonfinite_values_rejected(self):
        base = self.value()
        for mutate in (lambda v: v.update(secret=SECRET), lambda v: v['metrics'].update(equity=float('inf')),
                       lambda v: v.update(selectedAccount='foreign')):
            value = copy.deepcopy(base); mutate(value)
            with self.assertRaises(InvalidData):
                snapshot(value, ['a', 'b'], 'Main')
        with self.assertRaises(InvalidData):
            decode('{"trade":null,"trade":null}')


class MarketCase(unittest.TestCase):
    def message(self, timestamp=NOW, price='110'):
        return json.dumps({'e': 'markPriceUpdate', 's': 'BTCUSDT', 'E': timestamp, 'p': price, 'st': 1})

    def test_events_freshness_order_and_invalid_prices(self):
        now = [NOW]
        pool = BinancePrices(clock=lambda: now[0])
        pool.references = {'BTCUSDT': 1}; pool.connected = True
        pool.accept(self.message())
        self.assertEqual((110, NOW), pool.get('BTCUSDT'))
        pool.accept(self.message(NOW - 1, '99'))
        self.assertEqual((110, NOW), pool.get('BTCUSDT'))
        for message in (self.message(NOW + 1), self.message(price='nan'), self.message(price='0')):
            with self.assertRaises(InvalidData):
                pool.accept(message)
        now[0] += 15001
        self.assertIsNone(pool.get('BTCUSDT'))
        pool.connected = False
        self.assertIsNone(pool.get('BTCUSDT'))

    def test_ticker_is_separate_from_marks_and_rejects_invalid_or_stale_events(self):
        now = [NOW]
        pool = BinancePrices(clock=lambda: now[0])
        pool.references = {'BTCUSDT': 1}; pool.connected = True
        pool.accept(self.message())
        event = {'e': '24hrTicker', 's': 'BTCUSDT', 'E': NOW, 'c': '111', 'P': '-2.5', 'st': 1}
        pool.accept(json.dumps({'stream': 'btcusdt@ticker', 'data': event}))
        self.assertEqual((110, NOW), pool.get('BTCUSDT'))
        self.assertEqual({'symbol':'BTCUSDT','price':111,'updatedAt':NOW,'change24hPct':-2.5}, pool.ticker('BTCUSDT'))
        pool.accept(json.dumps(dict(event, E=NOW-1, c='99')))
        self.assertEqual(111, pool.ticker('BTCUSDT')['price'])
        for invalid in [dict(event,E=NOW+1),dict(event,c='0'),dict(event,c='nan'),dict(event,P='nan'),
                        dict(event,P='-100.1'),dict(event,s='ETHUSDT'),dict(event,st=2)]:
            with self.assertRaises(InvalidData): pool.accept(json.dumps(invalid))
        now[0] += 15000
        self.assertIsNotNone(pool.ticker('BTCUSDT'))
        now[0] += 1
        self.assertIsNone(pool.ticker('BTCUSDT'))
        now[0] = NOW; pool.connected = False
        self.assertIsNotNone(pool.ticker('BTCUSDT'))
        self.assertIsNone(pool.get('BTCUSDT'))

    def test_shared_stream_last_viewer_disconnect_and_idle(self):
        opened, closed = threading.Event(), threading.Event()
        calls = []
        class Socket:
            def __enter__(self):
                opened.set(); return self
            def __exit__(self, *args):
                closed.set()
            def recv(self, timeout):
                closed.wait(2)
                raise OSError('synthetic disconnect')
            def close(self):
                closed.set()
        def connector(url, **kwargs):
            calls.append(url)
            return Socket()
        pool = BinancePrices(clock=lambda: NOW, connector=connector, ticker_fetcher=lambda: {})
        self.assertEqual([], calls)
        pool.acquire({'BTCUSDT'})
        self.assertTrue(opened.wait(1))
        pool.acquire({'BTCUSDT'})
        pool.release({'BTCUSDT'})
        self.assertFalse(closed.is_set())
        pool.release({'BTCUSDT'})
        self.assertTrue(closed.wait(1))
        time.sleep(0.05)
        self.assertEqual(1, len(calls))
        self.assertEqual({}, pool.references)
        self.assertFalse(pool.connected)
        self.assertTrue(calls[0].startswith('wss://fstream.binance.com/market/stream?streams=btcusdt@markPrice@1s'))
        self.assertIn('/btcusdt@ticker', calls[0])
