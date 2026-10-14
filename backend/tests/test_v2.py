"""Synthetic model grouping and demand-only balance regression cases."""
import asyncio
from dataclasses import replace
import json
import os
import threading
import unittest
from unittest.mock import Mock, patch
from dashboard_backend.balances import Balances, NoRedirect, query_equity
from dashboard_backend.config import Config, Instance, load_config
from dashboard_backend.grouping import paired_trades
from dashboard_backend.validation import InvalidData, period_start
from test_backend import BackendCase, NOW, iso

class V2Case(unittest.TestCase):
    setUp=BackendCase.setUp
    state=BackendCase.state
    history=BackendCase.history
    row=BackendCase.row
    open_trade=BackendCase.open_trade
    request=BackendCase.request
    scope=BackendCase.scope

    def dashboard(self,query='period=All&model=all',**kwargs):
        return self.request(path='/v2/dashboard',query=query,**kwargs)

    def test_ytd_pairs_before_filtering_and_retains_old_api_period(self):
        self.app.config = Config((replace(self.main, original_equity=1000),))
        boundary = period_start('YTD', NOW)
        self.assertEqual('2026-01-01T00:00:00+00:00', iso(boundary))
        self.history(self.accounts[0], [self.row(offset=NOW-boundary+30000, pnl='9'),
            self.row(offset=NOW-boundary+120000, number='2', pnl='-5')])
        self.history(self.accounts[1], [self.row(offset=NOW-boundary-30000, pnl='-2')])
        status, body, _ = self.dashboard('period=YTD&model=all')
        self.assertEqual(200, status)
        self.assertEqual(1, body['metrics']['closedTrades'])
        self.assertEqual(7, body['metrics']['realizedPnl'])
        self.assertIsNone(body['normalizedEquity'])  # These rows have no return-capital metadata.
        self.assertIsNone(body['curve'][0]['equity'])  # Trade equity is not recorded balance history.
        self.assertIsNone(self.dashboard('period=YTD&model=M1')[1]['normalizedEquity'])
        self.assertEqual(200, self.dashboard('period=1D&model=all')[0])
        self.assertEqual(200, self.request(query='period=1D&account=all')[0])

    def test_ytd_calendar_boundary_leap_year_and_equity_requires_capital_history(self):
        from datetime import datetime, timezone
        for now in ('2024-12-31T23:59:59+00:00', '2025-01-01T00:00:00+00:00'):
            stamp = int(datetime.fromisoformat(now).timestamp() * 1000)
            start = period_start('YTD', stamp)
            self.assertEqual(int(datetime(datetime.fromisoformat(now).year, 1, 1, tzinfo=timezone.utc).timestamp()*1000), start)
        self.assertIsNone(self.dashboard()[1]['normalizedEquity'])
        self.app.config = Config((replace(self.main, original_equity=1000),))
        self.assertIsNone(self.dashboard()[1]['normalizedEquity'])
        from pathlib import Path
        Path(self.accounts[0].history_path).unlink()
        self.assertIsNone(self.dashboard()[1]['normalizedEquity'])

    def test_pair_boundaries_ambiguity_source_model_symbol_direction_and_unknown_time(self):
        def row(id,account,opened=100000,closed=300000,source='live',side='LONG',symbol='BTCUSDT'):
            return dict(id=id,accountId=account,openedAt=opened,closedAt=closed,source=source,
                        symbol=symbol,side=side,strategy='M1',netPnl=9,fees=1,
                        entryPrice=100,exitPrice=110,funding=None,_equity=None)
        a=row('a','a')
        for opened,closed,count in [(160000,360000,1),(160001,360000,2),(160000,360001,2),
                                    (40000,240000,1),(39999,240000,2)]:
            self.assertEqual(count,len(paired_trades([a,row('b','b',opened,closed)],self.accounts)))
        for change in [dict(source='backtest'),dict(side='SHORT'),dict(symbol='ETHUSDT'),dict(opened=None)]:
            self.assertEqual(2,len(paired_trades([a,row('b','b',**change)],self.accounts)))
        for ambiguous in [[a,row('a2','a'),row('b','b')],[a,row('b','b'),row('b2','b')]]:
            for rows in [ambiguous,ambiguous[::-1]]:
                self.assertEqual(3,len(paired_trades(rows,self.accounts)))
        self.assertEqual(2,len(paired_trades([a,row('b','b')],(self.accounts[0],replace(self.accounts[1],strategy='M2')))))

    def test_combined_counts_pnl_fees_winrate_curve_months_v1_unchanged(self):
        self.history(self.accounts[0],[self.row(pnl='9')]);self.history(self.accounts[1],[self.row(pnl='-12')])
        status,body,_=self.dashboard()
        self.assertEqual(200,status);self.assertEqual(2,body['schemaVersion']);self.assertEqual('all',body['selectedModel'])
        self.assertEqual(1,body['metrics']['closedTrades']);self.assertEqual(-3,body['metrics']['realizedPnl'])
        self.assertEqual(0,body['metrics']['winRatePct']);self.assertEqual(2,body['trades'][0]['fees'])
        self.assertEqual(-3,body['curve'][-1]['pnl']);self.assertEqual(-3,body['monthlyReturns'][0]['pnl'])
        self.assertEqual(['M1','M2','M3'],[a['id'] for a in body['accounts']])
        self.assertEqual(2,self.request()[1]['metrics']['closedTrades']);self.assertEqual(50,self.request()[1]['metrics']['winRatePct'])

    def test_period_after_pairing_earliest_open_latest_close_unknown_time(self):
        before=self.row(offset=86400000+30000,pnl='9');after=self.row(offset=86400000-30000,pnl='-2')
        self.history(self.accounts[0],[before]);self.history(self.accounts[1],[after])
        body=self.dashboard('period=1D&model=M1')[1]
        self.assertEqual(1,len(body['trades']));self.assertEqual(7,body['trades'][0]['netPnl'])
        self.assertEqual(NOW-86400000-40000,body['trades'][0]['openedAt'])
        self.assertEqual(NOW-86400000+30000,body['trades'][0]['closedAt'])
        before[2]=after[2]='unknown';self.history(self.accounts[0],[before]);self.history(self.accounts[1],[after])
        self.assertEqual(2,len(self.dashboard()[1]['trades']))

    def test_same_direction_combines_opposing_remain_explicit_and_missing_price(self):
        self.state(self.accounts[0],self.open_trade());self.state(self.accounts[1],self.open_trade())
        body=self.dashboard()[1];self.assertEqual(1,len(body['positions']));self.assertEqual(4,body['positions'][0]['size'])
        self.assertEqual(40,body['metrics']['unrealizedPnl'])
        self.state(self.accounts[1],self.open_trade('SHORT'));body=self.dashboard()[1]
        self.assertEqual({'LONG','SHORT'},{p['side'] for p in body['positions']});self.assertEqual(0,body['metrics']['unrealizedPnl'])
        self.prices.available=False;self.assertIsNone(self.dashboard()[1]['metrics']['unrealizedPnl'])
        self.state(self.accounts[0]);self.state(self.accounts[1]);self.assertEqual(0,self.dashboard()[1]['metrics']['unrealizedPnl'])

    def test_single_wallet_model_scope_and_invalid_selectors(self):
        self.app.config=Config((replace(self.main,accounts=(self.accounts[0],)),));self.history(rows=[self.row()])
        self.assertEqual(1,self.dashboard()[1]['metrics']['closedTrades'])
        self.assertEqual(0,self.dashboard('model=M3')[1]['metrics']['closedTrades'])
        self.assertEqual(403,self.dashboard('model=a')[0]);self.assertEqual(400,self.dashboard('account=a')[0])
        self.assertEqual(401,self.dashboard(token=None)[0])

    def test_model_direction_and_saved_stops_flat_twins_and_opposing_positions(self):
        self.assertEqual('FLAT', self.dashboard()[1]['strategies'][0]['signal'])
        self.state(self.accounts[0], dict(self.open_trade(), sl=98))
        self.state(self.accounts[1], dict(self.open_trade(), sl=98))
        body = self.dashboard()[1]
        self.assertEqual('LONG', body['strategies'][0]['signal'])
        self.assertEqual(98, body['positions'][0]['stopPrice'])
        self.assertEqual(400, body['positions'][0]['size'] * body['positions'][0]['entryPrice'])
        self.assertEqual('UNAVAILABLE', self.request()[1]['strategies'][0]['signal'])
        self.state(self.accounts[1], dict(self.open_trade(), sl=99))
        self.assertIsNone(self.dashboard()[1]['positions'][0]['stopPrice'])
        self.assertEqual([98, 99], self.dashboard()[1]['positions'][0]['stopPrices'])
        self.state(self.accounts[1], dict(self.open_trade(), sl=None))
        self.assertEqual([], self.dashboard()[1]['positions'][0]['stopPrices'])
        self.state(self.accounts[1], dict(self.open_trade(), sl=98))
        self.assertEqual([98], self.dashboard()[1]['positions'][0]['stopPrices'])
        self.assertNotIn('stopPrices', self.request()[1]['positions'][0])
        self.state(self.accounts[1], dict(self.open_trade('SHORT'), sl=102))
        body = self.dashboard()[1]
        self.assertEqual('LONG + SHORT', body['strategies'][0]['signal'])
        self.assertEqual({'LONG': 98, 'SHORT': 102}, {p['side']: p['stopPrice'] for p in body['positions']})

    def test_stop_list_validation_and_older_client_contract(self):
        from dashboard_backend.grouping import validate_v2
        self.state(self.accounts[0], dict(self.open_trade(), sl=98))
        original = self.request()[1]
        original['accounts'] = [dict(id=model, label=model, equity=None, unrealizedPnl=0) for model in ('M1','M2','M3')]
        original['positions'][0]['accountId'] = 'M1'
        original['strategies'] = []
        for stops in ([0], [float('nan')], [99, 98], [98, 98], list(range(1, 8)), [97, 98], 'private', [dict(secret='synthetic')]):
            position = dict(original['positions'][0], stopPrices=stops)
            with self.assertRaises(InvalidData): validate_v2(dict(original, positions=[position]), 'Main')
        position = dict(original['positions'][0], stopPrices=[98])
        self.assertEqual([98], validate_v2(dict(original, positions=[position]), 'Main')['positions'][0]['stopPrices'])
        position.pop('stopPrices')
        self.assertEqual([], validate_v2(dict(original, positions=[position]), 'Main')['positions'][0]['stopPrices'])

    def test_ticker_received_during_build_uses_snapshot_completion_clock(self):
        self.prices.ticker = Mock(return_value=dict(symbol='BTCUSDT', price=111, change24hPct=2.5, updatedAt=NOW+50))
        before = self.files.build(self.main, 'All', 'all', self.prices, NOW, version=2)
        self.assertIsNone(before['btcTicker'])
        after = self.files.build(self.main, 'All', 'all', self.prices, NOW, version=2, clock=lambda: NOW+100)
        self.assertEqual(NOW+100, after['generatedAt'])
        self.assertEqual(NOW+50, after['btcTicker']['updatedAt'])
        future = self.files.build(self.main, 'All', 'all', self.prices, NOW, version=2, clock=lambda: NOW+49)
        self.assertIsNone(future['btcTicker'])
        self.assertNotIn('btcTicker', self.files.build(self.main, 'All', 'all', self.prices, NOW, clock=lambda: NOW+100))

    def test_original_equity_return_uses_only_authenticated_instance_and_complete_balance(self):
        calls = Mock(return_value=100)
        main = self.configure_idle_balances(calls)
        self.app.config = Config((replace(main, original_equity=150),
            replace(main, id='foreign', label='Foreign', original_equity=999)))
        body = self.request(path='/v2/balances', query='')[1]
        self.assertEqual(150, body['originalEquity'])
        self.assertEqual(50, body['pnl']); self.assertAlmostEqual(50/150*100, body['returnPct'])
        self.assertEqual(2, calls.call_count)
        self.app.balances = Balances(Mock(side_effect=[100, RuntimeError('private')]), clock=lambda:NOW)
        body = self.request(path='/v2/balances', query='')[1]
        self.assertEqual(150, body['originalEquity'])
        self.assertIsNone(body['equity']); self.assertIsNone(body['pnl']); self.assertIsNone(body['returnPct'])
        self.app.config = Config((main,))
        self.app.balances = Balances(calls, clock=lambda:NOW)
        body = self.request(path='/v2/balances', query='')[1]
        self.assertIsNone(body['originalEquity']); self.assertIsNone(body['pnl']); self.assertIsNone(body['returnPct'])
        for equity, pnl, pct in [(75, 0, 0), (50, -50, -100/3), (0, -150, -100)]:
            self.app.config = Config((replace(main, original_equity=150),))
            self.app.balances = Balances(Mock(return_value=equity), clock=lambda:NOW)
            body = self.request(path='/v2/balances', query='')[1]
            self.assertEqual(pnl, body['pnl']); self.assertAlmostEqual(pct, body['returnPct'])

    def test_open_returns_sum_capital_before_model_merging_and_require_all_wallets(self):
        first = dict(self.open_trade(), pos_size_quote=200, leverage=2, entry_capital=1000)
        second = dict(self.open_trade(), pos_size_quote=200, leverage=10, entry_capital=2000)
        self.state(self.accounts[0], first); self.state(self.accounts[1], second)
        body = self.dashboard()[1]
        performance = body['openPerformance']
        self.assertEqual(120, performance['investedUsd'])  # 200/2 + 200/10, never 400/weighted leverage.
        self.assertEqual(3000, performance['entryEquityUsd'])
        self.assertAlmostEqual(40/120*100, performance['investedReturnPct'])
        self.assertAlmostEqual(40/3000*100, performance['entryEquityReturnPct'])
        self.assertEqual(111, body['btcTicker']['price'])
        self.assertEqual(2.5, body['btcTicker']['change24hPct'])
        self.assertEqual(40, body['metrics']['unrealizedPnl'])  # Mark 110; ticker's last price is separate.
        legacy = self.request()[1]
        self.assertNotIn('openPerformance', legacy); self.assertNotIn('btcTicker', legacy)
        self.state(self.accounts[1])  # A flat wallet has no equity-at-entry snapshot.
        performance = self.dashboard()[1]['openPerformance']
        self.assertEqual(100, performance['investedUsd'])
        self.assertEqual(20, performance['investedReturnPct'])
        self.assertIsNone(performance['entryEquityUsd']); self.assertIsNone(performance['entryEquityReturnPct'])

    def test_open_returns_missing_capital_unknown_entry_flat_and_missing_marks(self):
        first = dict(self.open_trade(), pos_size_quote=200, entry_capital=1000)
        second = dict(self.open_trade(), pos_size_quote=0, entry_capital=None)
        self.state(self.accounts[0], first); self.state(self.accounts[1], second)
        performance = self.dashboard()[1]['openPerformance']
        self.assertIsNone(performance['investedUsd']); self.assertIsNone(performance['investedReturnPct'])
        self.assertIsNone(performance['entryEquityUsd'])
        for invalid in [None, 0, -1, 'unknown', True, 1e21]:
            self.state(self.accounts[1], dict(first, entry_capital=invalid))
            self.assertIsNone(self.dashboard()[1]['openPerformance']['entryEquityReturnPct'])
        self.state(self.accounts[1], dict(first, entry_capital=float('nan')))
        self.assertEqual(503, self.dashboard()[0])  # Nonfinite JSON is rejected before optional field parsing.
        self.state(self.accounts[1], dict(first, entry_time='unknown'))
        self.assertIsNone(self.dashboard()[1]['openPerformance']['entryEquityUsd'])
        self.prices.available = False
        body = self.dashboard()[1]
        self.assertIsNone(body['btcTicker']); self.assertIsNone(body['openPerformance']['investedReturnPct'])
        self.state(self.accounts[0]); self.state(self.accounts[1])
        performance = self.dashboard()[1]['openPerformance']
        self.assertEqual(0, performance['investedUsd']); self.assertIsNone(performance['investedReturnPct'])

    def test_open_returns_use_entire_instance_equity_even_for_one_model(self):
        accounts = (self.accounts[0], replace(self.accounts[1], strategy='M2'))
        self.app.config = Config((replace(self.main, accounts=accounts),))
        self.state(accounts[0], dict(self.open_trade(), entry_capital=1000))
        self.state(accounts[1], dict(self.open_trade('SHORT'), entry_capital=2000))
        body = self.dashboard('period=All&model=M1')[1]
        self.assertEqual(3000, body['openPerformance']['entryEquityUsd'])
        self.assertAlmostEqual(20/3000*100, body['openPerformance']['entryEquityReturnPct'])
        body = self.dashboard()[1]
        self.assertEqual(0, body['openPerformance']['investedReturnPct'])  # Opposing P&L cancels, capital adds.
        self.app.config = Config((replace(self.main, accounts=(accounts[0],)),))
        self.assertEqual(1000, self.dashboard()[1]['openPerformance']['entryEquityUsd'])

    def configure_idle_balances(self, query, clock=None, monotonic=None):
        main = replace(self.main, accounts=tuple(replace(a, public_address='synthetic-'+a.id) for a in self.accounts))
        self.app.config = Config((main,))
        self.app.balances = Balances(query, clock=clock or (lambda: NOW), monotonic=monotonic or (lambda: 0))
        return main

    def test_flat_wallet_uses_current_idle_equity_active_wallet_keeps_entry_snapshot(self):
        self.state(self.accounts[0], dict(self.open_trade(), entry_capital=1000))
        calls = Mock(side_effect=lambda address: 9000 if address.endswith('a') else 500)
        main = self.configure_idle_balances(calls)
        self.assertIsNone(self.dashboard()[1]['openPerformance']['entryEquityUsd'])
        calls.assert_not_called()
        self.assertEqual(9500, self.request(path='/v2/balances', query='')[1]['equity'])
        body = self.dashboard()[1]
        performance = body['openPerformance']
        self.assertEqual(1500, performance['entryEquityUsd'])  # Entry 1000 + idle 500, not current total 9500.
        self.assertAlmostEqual(20 / 1500 * 100, performance['entryEquityReturnPct'])
        self.assertTrue(performance['usesIdleBalances']); self.assertEqual(NOW, performance['idleBalanceSampledAt'])
        self.assertEqual(2, calls.call_count)
        self.assertNotIn('synthetic-', json.dumps(body))
        self.assertNotIn('accounts', performance)  # Per-wallet samples stay private.
        self.assertEqual({'a', 'b'}, set(self.app.balances.peek(main)['accounts']))
        self.assertIsNone(self.app.balances.peek(replace(main, id='foreign')))
        self.app.config = Config((replace(main, accounts=(replace(main.accounts[0], strategy='M2'), main.accounts[1])),))
        self.assertEqual(1500, self.dashboard('model=M2')[1]['openPerformance']['entryEquityUsd'])
        self.assertEqual(2, calls.call_count)  # Model/period and stream snapshots do not query balances.

    def test_idle_failure_clears_denominator_but_active_balance_failure_does_not_replace_entry(self):
        self.state(self.accounts[0], dict(self.open_trade(), entry_capital=1000))
        tick = [0]
        def active_failure(address):
            if address.endswith('a'): raise RuntimeError('private data')
            return 500
        calls = Mock(side_effect=active_failure)
        self.configure_idle_balances(calls, monotonic=lambda: tick[0])
        self.assertIsNone(self.request(path='/v2/balances', query='')[1]['equity'])
        self.assertEqual(1500, self.dashboard()[1]['openPerformance']['entryEquityUsd'])
        def idle_failure(address):
            if address.endswith('b'): raise RuntimeError('private data')
            return 9000
        tick[0] = 10; calls.side_effect = idle_failure
        self.request(path='/v2/balances', query='')
        performance = self.dashboard()[1]['openPerformance']
        self.assertIsNone(performance['entryEquityUsd']); self.assertIsNone(performance['entryEquityReturnPct'])
        self.assertIsNone(performance['idleBalanceSampledAt'])
        tick[0] = 20; calls.side_effect = lambda _: 500
        self.request(path='/v2/balances', query='')
        self.state(self.accounts[0], self.open_trade())  # Missing active entry must not use its current 500.
        self.assertIsNone(self.dashboard()[1]['openPerformance']['entryEquityUsd'])
        self.assertEqual(6, calls.call_count)

    def test_newly_flat_wallet_requires_refresh_zero_idle_is_valid_and_idle_samples_do_not_poll(self):
        self.state(self.accounts[0], dict(self.open_trade(), entry_capital=1000))
        moment = [NOW - 10]; tick = [0]
        calls = Mock(return_value=0)
        self.configure_idle_balances(calls, clock=lambda: moment[0], monotonic=lambda: tick[0])
        self.request(path='/v2/balances', query='')
        self.assertEqual(1000, self.dashboard()[1]['openPerformance']['entryEquityUsd'])
        os.utime(self.accounts[1].state_path, ns=(NOW * 1000000,) * 2)
        self.assertIsNone(self.dashboard()[1]['openPerformance']['entryEquityUsd'])
        tick[0] = 10; moment[0] = NOW
        self.request(path='/v2/balances', query='')
        self.assertEqual(1000, self.dashboard()[1]['openPerformance']['entryEquityUsd'])  # Inclusive boundary.
        tick[0] = 3600
        for _ in range(3):
            self.assertEqual(NOW, self.dashboard()[1]['openPerformance']['idleBalanceSampledAt'])
        self.assertEqual(4, calls.call_count)  # Old samples retain their timestamp; never start idle refresh.
        self.state(self.accounts[0])
        performance = self.dashboard()[1]['openPerformance']
        self.assertEqual(0, performance['entryEquityUsd']); self.assertIsNone(performance['entryEquityReturnPct'])

    def test_stream_reuses_wallet_samples_after_explicit_balance_request(self):
        self.state(self.accounts[0], dict(self.open_trade(), entry_capital=1000))
        calls = Mock(return_value=500)
        main = self.configure_idle_balances(calls)
        self.app.config = replace(self.app.config, poll_seconds=0.01)
        async def exercise():
            queue = asyncio.Queue(); await queue.put({'type': 'websocket.connect'})
            values = []
            async def send(event):
                if event['type'] == 'websocket.send':
                    values.append(json.loads(event['text']))
                    if len(values) == 1:
                        self.assertIsNone(values[0]['openPerformance']['entryEquityUsd'])
                        calls.assert_not_called()
                        await self.app.balances.get(main)
                    else:
                        await queue.put({'type': 'websocket.disconnect'})
            await self.app(self.scope(path='/v2/live', query='period=All&model=all'), queue.get, send)
            self.assertEqual(1500, values[1]['openPerformance']['entryEquityUsd'])
            self.assertEqual(2, calls.call_count)
        self.loop.run_until_complete(exercise())

    def test_v2_extensions_are_allowlisted_and_do_not_accept_wrong_returns(self):
        from dashboard_backend.grouping import validate_v2
        original = self.request()[1]
        original['selectedAccount'] = 'all'
        original['accounts'] = [dict(id=model, label=model, equity=None, unrealizedPnl=0) for model in ('M1','M2','M3')]
        original['strategies'] = []
        for bad in [dict(investedUsd=10, entryEquityUsd=None, investedReturnPct=2, entryEquityReturnPct=None),
                    dict(investedUsd=None, entryEquityUsd=None, investedReturnPct=0, entryEquityReturnPct=None),
                    dict(investedUsd=10, entryEquityUsd=20, investedReturnPct=0, entryEquityReturnPct=0, internal='private')]:
            with self.assertRaises(InvalidData): validate_v2(dict(original, openPerformance=bad), 'Main')
        valid_performance = dict(investedUsd=10, entryEquityUsd=20, investedReturnPct=0, entryEquityReturnPct=0)
        for bad in [dict(valid_performance, usesIdleBalances=1),
                    dict(valid_performance, usesIdleBalances=True),
                    dict(valid_performance, usesIdleBalances=False, idleBalanceSampledAt=NOW),
                    dict(valid_performance, usesIdleBalances=True, idleBalanceSampledAt=NOW+1),
                    dict(valid_performance, usesIdleBalances=True, idleBalanceSampledAt=True)]:
            with self.assertRaises(InvalidData): validate_v2(dict(original, openPerformance=bad), 'Main')
        valid = dict(symbol='BTCUSDT', price=111, change24hPct=2.5, updatedAt=NOW)
        for bad in [dict(valid, symbol='ETHUSDT'), dict(valid, price=0), dict(valid, updatedAt=NOW+1),
                    dict(valid, updatedAt=NOW-15001), dict(valid, internal='private')]:
            with self.assertRaises(InvalidData): validate_v2(dict(original, btcTicker=bad), 'Main')

    def test_edge_sources_pair_separately_real_keeps_regular_csv(self):
        accounts=tuple(replace(a,edge_model='M1-Fast'+('-B' if a.id=='b' else '')) for a in self.accounts)
        edge=self.path/'edge.csv'
        edge.write_text('trade_num,source,model,side,entry_time,exit_time,entry_price,exit_price,pnl_usd,total_fees\n'+
            '\n'.join(f'{i},{source},{model},LONG,{iso(NOW-11000)},{iso(NOW-1000)},100,110,{pnl},1'
                for i,source,model,pnl in [(1,'backtest','M1-Fast',9),(2,'backtest','M1-Fast-B',-2),
                                         (3,'live','M1-Fast',5),(4,'live','M1-Fast-B',1)])+'\n')
        import os
        os.utime(edge,ns=((NOW-1000)*1000000,)*2)
        self.app.config=Config((replace(self.main,accounts=accounts,edge_path=str(edge)),))
        body=self.dashboard('period=All&model=all&mode=edge')[1]
        self.assertEqual(2,len(body['trades']));self.assertEqual(13,body['metrics']['realizedPnl'])
        self.assertEqual({'backtest','live'},{t['source'] for t in body['trades']})
        self.assertEqual(0,self.prices.acquisitions);self.assertEqual(0,self.dashboard()[1]['metrics']['realizedPnl'])
        self.assertIsNone(body['btcTicker']); self.assertIsNone(body['openPerformance'])

    def test_balances_auth_scope_revocation_and_zero_implicit_requests(self):
        calls=Mock(return_value=100);self.app.balances=Balances(calls,clock=lambda:NOW,monotonic=lambda:0)
        main=replace(self.main,accounts=tuple(replace(a,public_address='synthetic-'+a.id) for a in self.accounts))
        foreign=Instance('foreign','Foreign',(replace(main.accounts[0],public_address='foreign'),))
        self.app.config=Config((main,foreign))
        for path in ['/v2/balances','/v2/dashboard']:
            self.assertEqual(401,self.request(path=path,query='',token=None)[0])
        self.assertEqual(400,self.request(path='/v2/balances',query='instance=foreign')[0])
        self.dashboard();self.request(path='/healthz',query='',token=None)
        async def stream():
            queue=asyncio.Queue();await queue.put({'type':'websocket.connect'})
            async def send(event):
                if event['type']=='websocket.send':await queue.put({'type':'websocket.disconnect'})
            await self.app(self.scope(path='/v2/live',query='model=all'),queue.get,send)
        self.loop.run_until_complete(stream());calls.assert_not_called()
        status,body,_=self.request(path='/v2/balances',query='')
        self.assertEqual(200,status);self.assertEqual(200,body['equity']);self.assertEqual('ok',body['status'])
        self.assertEqual(NOW,body['sampledAt']);self.assertEqual({'synthetic-a','synthetic-b'},{c.args[0] for c in calls.call_args_list})
        self.assertNotIn('synthetic-',json.dumps(body));self.store.revoke('main')
        self.assertEqual(401,self.request(path='/v2/balances',query='')[0]);self.assertEqual(2,calls.call_count)

    def test_request_sharing_ten_second_cache_failures_and_cancelled_viewer(self):
        async def exercise():
            clock=[0.0];entered,release=threading.Event(),threading.Event()
            def query(address):entered.set();release.wait(3);return 100
            calls=Mock(side_effect=query);balances=Balances(calls,clock=lambda:NOW,monotonic=lambda:clock[0])
            instance=replace(self.main,accounts=tuple(replace(a,public_address=a.id) for a in self.accounts))
            first=asyncio.create_task(balances.get(instance));await asyncio.to_thread(entered.wait,1)
            second=asyncio.create_task(balances.get(instance));first.cancel()
            with self.assertRaises(asyncio.CancelledError):await first
            release.set();body=await second
            self.assertEqual(200,body['equity']);self.assertEqual(2,calls.call_count)
            clock[0]=9.999;self.assertEqual(body,await balances.get(instance));self.assertEqual(2,calls.call_count)
            clock[0]=10
            def failing(address):
                if address=='b':raise RuntimeError('private provider data')
                return 120
            calls.side_effect=failing;failed=await balances.get(instance)
            self.assertIsNone(failed['equity']);self.assertEqual('failed',failed['status'])
            self.assertIsNone(failed['models'][0]['equity']);self.assertIsNone(failed['sampledAt'])
            self.assertEqual(NOW,failed['attemptedAt']);await asyncio.sleep(.02);self.assertEqual(4,calls.call_count)
            self.assertEqual('unconfigured',(await Balances(calls,clock=lambda:NOW).get(self.main))['status'])
            self.assertEqual(4,calls.call_count)
        self.loop.run_until_complete(exercise())

    def test_main_six_vinay_madhu_three_balance_scope_and_partial_failure(self):
        configured=[]
        tokens={}
        for name, wallets in [('main',('A','B')),('vinay',('A',)),('madhu',('A',))]:
            accounts=tuple(replace(self.accounts[0],id=f'{name}-{model}-{wallet}',strategy=model,
                wallet=wallet,public_address=f'{name}-{model}-{wallet}')
                for model in ('M1','M2','M3') for wallet in wallets)
            configured.append(Instance(name,name.title(),accounts))
            tokens['synthetic-'+name+'-'+'x'*32]=name
        self.app.config=Config(tuple(configured))
        self.store.authenticate=Mock(side_effect=tokens.get)
        calls=Mock(return_value=100)
        self.app.balances=Balances(calls,clock=lambda:NOW,monotonic=lambda:0)
        for token,name in tokens.items():
            calls.reset_mock()
            status,body,_=self.request(path='/v2/balances',query='',token=token)
            expected=6 if name=='main' else 3
            self.assertEqual(200,status);self.assertEqual(expected*100,body['equity'])
            self.assertEqual(expected,calls.call_count)
            self.assertTrue(all(call.args[0].startswith(name+'-') for call in calls.call_args_list))
            self.assertEqual([expected/3*100]*3,[model['equity'] for model in body['models']])
        def fail_one(address):
            if address=='main-M2-B':raise RuntimeError('provider failure')
            return 100
        self.app.balances=Balances(fail_one,clock=lambda:NOW)
        main_token=next(token for token,name in tokens.items() if name=='main')
        body=self.request(path='/v2/balances',query='',token=main_token)[1]
        self.assertIsNone(body['equity']);self.assertEqual('failed',body['status'])
        self.assertEqual([200,None,200],[model['equity'] for model in body['models']])

    def test_public_info_query_extracts_perpetual_equity_and_rejects_bad_payloads(self):
        response=Mock()
        response.__enter__=Mock(return_value=response)
        response.__exit__=Mock(return_value=False)
        opener=Mock();opener.open.return_value=response
        with patch('dashboard_backend.balances.urllib.request.build_opener',return_value=opener):
            response.read.return_value=b'{"marginSummary":{"accountValue":"101.25"},"withdrawable":"99"}'
            self.assertEqual(101.25,query_equity('synthetic-public-address'))
            request=opener.open.call_args.args[0]
            self.assertEqual('https://api.hyperliquid.xyz/info',request.full_url)
            self.assertEqual('POST',request.method)
            self.assertEqual({'type':'clearinghouseState','user':'synthetic-public-address'},json.loads(request.data))
            self.assertNotIn('Authorization',dict(request.header_items()))
            for payload in [b'{"marginSummary":{"accountValue":"NaN"}}',
                            b'{"marginSummary":{"accountValue":null}}',b'{}',b'x'*262145]:
                response.read.return_value=payload
                with self.assertRaises((InvalidData,KeyError)):
                    query_equity('synthetic-public-address')
        with self.assertRaises(InvalidData):
            NoRedirect().redirect_request(None,None,302,'redirect',{},'https://elsewhere.invalid')

    def test_private_address_config_validation_and_no_secrets_in_repr(self):
        value={'instances':[{'id':'main','label':'Main','accounts':[{'id':'a','label':'M1','strategy':'M1',
               'wallet':'A','publicAddress':'0x'+'1'*40,'statePath':'/test/a.json','historyPath':'/test/a.csv'}]}]}
        file=self.path/'config.json';file.write_text(json.dumps(value));config=load_config(file)
        self.assertNotIn('0x',repr(config))
        for address in ['0x'+'0'*40,'bad','0x'+'1'*64]:
            value['instances'][0]['accounts'][0]['publicAddress']=address;file.write_text(json.dumps(value))
            with self.assertRaises(InvalidData):load_config(file)


def load_tests(loader, tests, pattern):
    return loader.loadTestsFromTestCase(V2Case)
