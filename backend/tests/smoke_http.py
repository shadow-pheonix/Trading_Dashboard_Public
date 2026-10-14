"""Real HTTP/WebSocket smoke test. Synthetic bot files and prices only."""
import contextlib
from datetime import datetime, timezone
import json
from pathlib import Path
import socket
import sqlite3
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

from websockets.sync.client import connect
from websockets.exceptions import ConnectionClosed, InvalidStatus
from dashboard_backend.storage import Store


def main():
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        config_path = root / 'config.json'
        state = root / 'state.json'
        idle_state = root / 'idle-state.json'
        history = root / 'trades.csv'
        idle_history = root / 'idle-trades.csv'
        edge = root / 'edge.csv'
        opened = datetime.fromtimestamp(time.time() - 7200, timezone.utc).isoformat()
        closed = datetime.fromtimestamp(time.time() - 3600, timezone.utc).isoformat()
        later = datetime.fromtimestamp(time.time() - 3599, timezone.utc).isoformat()
        edge.write_text('trade_num,source,model,side,entry_time,exit_time,entry_price,exit_price,pnl_usd,total_fees,cum_capital,pos_size_quote,leverage,combined_equity\n'
            + f'1,backtest,M1-Fast,LONG,{opened},{closed},100,110,8,2,1008,200,2,\n'
            + f'1,live,M1-Fast,SHORT,{opened},{later},110,112,-3,1,997,200,2,997\n')
        edge.chmod(0o400)
        state.write_text(json.dumps({'trade': {'side': 'LONG', 'sz': 2, 'entry_price': 100,
            'entry_time': datetime.fromtimestamp(time.time() - 3600, timezone.utc).isoformat(), 'leverage': 3, 'sl': 90,
            'status': 'ACTIVE', 'entry_capital': 1000}}))
        idle_state.write_text(json.dumps({'trade': None}))
        idle_state.chmod(0o400)
        history.write_text('trade_num,side,entry_time,exit_time,entry_price,exit_price,pnl_usd,total_fees\n')
        idle_history.write_text(history.read_text())
        idle_history.chmod(0o400)
        state.chmod(0o400); history.chmod(0o400)
        alerts = root / 'dashboard_alerts.sqlite'
        with contextlib.closing(sqlite3.connect(alerts)) as db, db:
            db.execute('CREATE TABLE alerts(sequence INTEGER PRIMARY KEY, id TEXT, timestamp INTEGER, message TEXT)')
            db.execute('INSERT INTO alerts VALUES(1,?,?,?)', ('actual_event', int(time.time()*1000), 'M1 trade opened'))
        alerts.chmod(0o400)
        config_path.write_text(json.dumps({'instances': [{'id': 'main', 'label': 'Main', 'edgePath': str(edge), 'originalEquity': 150, 'alertsPath': str(alerts),
            'accounts': [{'id': 'a', 'label': 'Test account', 'statePath': str(state),
            'historyPath': str(history), 'strategy': 'M1', 'edgeModel': 'M1-Fast', 'wallet': 'A', 'publicAddress': '0x' + '1' * 40},
            {'id': 'b', 'label': 'Idle account', 'statePath': str(idle_state), 'historyPath': str(idle_history),
             'strategy': 'M1', 'edgeModel': 'M1-Fast-B', 'wallet': 'B', 'publicAddress': '0x' + '2' * 40}]}]}))
        store = Store(root / 'dashboard.sqlite')
        store.initialize()
        store.provision('main', root / 'key.txt')
        # Exercise the real candidate startup check with pinned runtime dependencies.
        backend = Path(__file__).resolve().parents[1]
        subprocess.run([sys.executable, '-B', str(backend / 'deploy/check-startup.py'),
                        str(backend), str(config_path), str(store.path)], check=True)
        token = (root / 'key.txt').read_text().strip()
        with contextlib.closing(socket.socket()) as probe:
            probe.bind(('127.0.0.1', 0))
            port = probe.getsockname()[1]
        process = subprocess.Popen([sys.executable, '-m', 'tests.smoke_server', str(config_path),
            str(store.path), str(port)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            base = f'http://127.0.0.1:{port}'
            def call(path, auth=True, method='GET'):
                request = urllib.request.Request(base + path, method=method,
                    headers={'Authorization': 'Bearer ' + token} if auth else {})
                try:
                    with urllib.request.urlopen(request, timeout=5) as response:
                        return response.status, json.load(response)
                except urllib.error.HTTPError as response:
                    with response:
                        return response.code, json.load(response)
            for _ in range(50):
                if process.poll() is not None:
                    raise AssertionError('API process exited before becoming ready.')
                try:
                    if call('/healthz', False)[0] == 200:
                        break
                except (OSError, urllib.error.URLError):
                    pass
                time.sleep(0.1)
            else:
                raise AssertionError('API did not become ready.')
            assert call('/v2/alerts?after=0', False)[0] == 401
            assert call('/v2/dashboard?view=macd', False)[0] == 401
            indicator_status, indicator_chart = call('/v2/dashboard?view=macd')
            assert indicator_status == 200 and indicator_chart['status'] == 'ok'
            assert indicator_chart['source'] == 'binance_spot_closed'
            assert [m['id'] for m in indicator_chart['models']] == ['M1', 'M2', 'M3']
            assert all(len(m['points']) == 96 for m in indicator_chart['models'])
            status, event_batch = call('/v2/alerts?after=0')
            assert status == 200 and event_batch['status'] == 'ready'
            assert event_batch['events'][0]['message'] == 'M1 trade opened'
            with connect(f'ws://127.0.0.1:{port}/v2/alerts/live?after=0', additional_headers={'Authorization': 'Bearer ' + token}) as alert_socket:
                event_batch = json.loads(alert_socket.recv(timeout=5))
                assert event_batch['cursor'] == 1 and event_batch['events'][0]['id'] == 'actual_event'
            status, body = call('/v1/dashboard?period=1M&account=all')
            assert status == 200 and body['metrics']['equity'] is None and body['live']
            assert body['metrics']['unrealizedPnl'] == 20
            assert body['metrics']['realizedPnl'] == 0
            assert call('/v1/dashboard', False)[0] == 401
            assert call('/v1/dashboard', True, 'POST')[0] == 405
            assert call('/v1/dashboard?account=foreign')[0] == 403
            assert call('/v1/dashboard?period=bad')[0] == 400
            assert call('/v1/orders')[0] == 404
            status, body = call('/v1/dashboard?period=All&account=a&mode=edge')
            assert status == 200 and body['historyMode'] == 'edge' and not body['live']
            assert body['metrics']['realizedPnl'] == 5 and body['metrics']['winRatePct'] == 50
            assert body['curve'][-1]['pnl'] == 5 and body['monthlyReturns'][0]['pnl'] == 5
            assert {t['source'] for t in body['trades']} == {'backtest', 'live'}
            assert body['positions'] == [] and body['stateUpdatedAt'] is None
            with connect(f'ws://127.0.0.1:{port}/v1/live?period=All&account=a&mode=edge',
                         proxy=None, open_timeout=3, additional_headers={'Authorization': 'Bearer ' + token}) as stream:
                streamed = json.loads(stream.recv(timeout=5))
                assert streamed['historyMode'] == 'edge' and streamed['metrics']['realizedPnl'] == 5
            status, body = call('/v2/dashboard?period=All&model=M1')
            assert status == 200 and body['schemaVersion'] == 2 and body['selectedModel'] == 'M1'
            assert body['positions'][0]['accountId'] == 'M1' and body['metrics']['unrealizedPnl'] == 20
            assert body['positions'][0]['stopPrice'] == 90 and body['strategies'][0]['signal'] == 'LONG'
            assert body['btcTicker']['price'] == 111 and body['btcTicker']['change24hPct'] == 2.5
            assert body['openPerformance']['investedUsd'] > 0
            assert body['openPerformance']['entryEquityReturnPct'] is None
            assert body['normalizedEquity'] is None
            status, ytd = call('/v2/dashboard?period=YTD&model=all')
            assert status == 200 and ytd['period'] == 'YTD'
            assert ytd['normalizedEquity'] is None
            assert call('/v2/dashboard?model=foreign')[0] == 403
            assert call('/v2/dashboard?account=a')[0] == 400
            assert call('/v2/balances', False)[0] == 401
            assert call('/v2/balances?instance=foreign')[0] == 400
            status, balance = call('/v2/balances')
            assert status == 200 and balance['equity'] == 200 and balance['status'] == 'ok'
            assert balance['originalEquity'] == 150 and balance['pnl'] == 50
            assert abs(balance['returnPct'] - 50 / 150 * 100) < 1e-6
            assert balance['sampledAt'] and [m['id'] for m in balance['models']] == ['M1','M2','M3']
            assert call('/v2/balances')[1] == balance
            for mode in ['real','edge']:
                with connect(f'ws://127.0.0.1:{port}/v2/live?period=All&model=all&mode={mode}',
                             proxy=None, open_timeout=3, additional_headers={'Authorization': 'Bearer ' + token}) as stream:
                    body = json.loads(stream.recv(timeout=5))
                    assert body['schemaVersion'] == 2 and body['historyMode'] == mode
                    assert (body['btcTicker'] is not None) == (mode == 'real')
                    assert (body['openPerformance'] is not None) == (mode == 'real')
                    if mode == 'edge':
                        assert abs(body['normalizedEquity']['points'][-1]['equity'] - 10049.76) < 1e-6
                        assert not body['normalizedEquity']['legacyCapitalEstimate']
                        drawdown = body['normalizedEquity']['drawdown']
                        assert abs(drawdown['pct'] - 0.3) < 1e-6
                        assert abs(drawdown['usd'] - 30.24) < 1e-6
                        status, http_snapshot = call('/v2/dashboard?period=All&model=all&mode=edge')
                        assert status == 200 and http_snapshot['normalizedEquity']['drawdown'] == drawdown
                    else:
                        assert body['normalizedEquity'] is None  # No real closed trades yet.
                    if mode == 'real':
                        assert body['openPerformance']['entryEquityUsd'] == 1100
                        assert abs(body['openPerformance']['entryEquityReturnPct'] - 20 / 1100 * 100) < 1e-6
                        assert body['openPerformance']['usesIdleBalances']
                        assert body['openPerformance']['idleBalanceSampledAt'] == balance['sampledAt']
                    assert all(t['accountId'] in ['M1','M2','M3'] for t in body['trades'])
                    stream.send('unexpected command')
                    try:
                        while True: stream.recv(timeout=5)
                    except ConnectionClosed as error:
                        assert error.rcvd.code == 1008
            try:
                with connect(f'ws://127.0.0.1:{port}/v2/live', proxy=None, open_timeout=3):
                    raise AssertionError('Unauthenticated v2 socket was accepted.')
            except InvalidStatus as error:
                assert error.response.status_code == 403
            url = f'ws://127.0.0.1:{port}/v1/live?period=All&account=a'
            try:
                with connect(url, proxy=None, open_timeout=3):
                    raise AssertionError('Unauthenticated socket was accepted.')
            except InvalidStatus as error:
                assert error.response.status_code == 403
            with connect(url, proxy=None, open_timeout=3,
                         additional_headers={'Authorization': 'Bearer ' + token}) as stream:
                body = json.loads(stream.recv(timeout=5))
                assert body['selectedAccount'] == 'a' and body['period'] == 'All'
                assert body['positions'][0]['unrealizedPnl'] == 20
                assert body['pnlBasis'] == 'logged_fees_only'
                assert body['monthlyReturns'] == []
                store.revoke('main')
                try:
                    stream.recv(timeout=5)
                    raise AssertionError('Revoked stream remained open.')
                except ConnectionClosed as error:
                    assert error.rcvd.code == 1008
            assert call('/v1/dashboard')[0] == 401
            assert call('/v2/balances')[0] == 401
            assert call('/v2/dashboard')[0] == 401
            assert state.stat().st_mode & 0o777 == 0o400
            assert history.stat().st_mode & 0o777 == 0o400
            assert idle_state.stat().st_mode & 0o777 == 0o400
            assert idle_history.stat().st_mode & 0o777 == 0o400
            assert edge.stat().st_mode & 0o777 == 0o400
            print('HTTP/WebSocket smoke passed: real/edge modes, combined calculations, authentication, scope, revocation, read-only inputs.')
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(timeout=5)


if __name__ == '__main__':
    main()
