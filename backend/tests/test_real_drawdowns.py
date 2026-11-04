"""Lifetime real-trade drawdowns, isolated from visible Analytics filters."""
import csv
import copy
from dataclasses import replace
import os
from pathlib import Path
import unittest
from unittest.mock import patch

import test_backend as fixtures
from dashboard_backend.grouping import real_closed_drawdowns, normalized_equity, validate_v2
from dashboard_backend.validation import InvalidData
from test_equity import row


class RealDrawdownMathCase(unittest.TestCase):
    def test_actual_dollars_are_not_the_reference_scale(self):
        rows = [row(pnl=-100, combined=900)]
        result = real_closed_drawdowns(rows)
        self.assertAlmostEqual(10, result['current']['pct'])
        self.assertEqual(100, result['current']['usd'])
        self.assertAlmostEqual(1000, normalized_equity(rows, 0)['drawdown']['usd'])

    def test_maximum_percentage_keeps_its_own_cash_loss_even_if_current_dollars_are_larger(self):
        rows = [row(pnl=-20, combined=80), row(id='gain', pnl=1000, combined=1100, closed=400000),
                row(id='loss', pnl=-500, combined=9500, closed=500000)]
        result = real_closed_drawdowns(rows)
        self.assertAlmostEqual(20, result['maximum']['pct'])
        self.assertEqual(20, result['maximum']['usd'])
        self.assertAlmostEqual(5, result['current']['pct'])
        self.assertEqual(500, result['current']['usd'])

    def test_recovery_to_the_same_high_starts_a_new_cash_interval(self):
        rows = [row(pnl=-20, combined=80), row(id='gain', pnl=25, combined=125, closed=400000),
                row(id='loss', pnl=-10, combined=90, closed=500000)]
        result = real_closed_drawdowns(rows)
        self.assertAlmostEqual(10, result['current']['pct'])
        self.assertEqual(10, result['current']['usd'])
        self.assertAlmostEqual(20, result['maximum']['pct'])
        self.assertEqual(20, result['maximum']['usd'])

    def test_simultaneous_closes_cannot_hide_the_maximum_and_zero_loss_is_honest(self):
        rows = [row(pnl=100, combined=1100), row(id='b', pnl=-200, combined=800, closed=400000),
                row(id='c', pnl=250, combined=1250, closed=400000)]
        result = real_closed_drawdowns(rows)
        self.assertAlmostEqual(20, result['maximum']['pct'])
        self.assertEqual(200, result['maximum']['usd'])
        self.assertEqual(dict(pct=0, usd=0), result['current'])
        self.assertEqual(dict(pct=0, usd=0), real_closed_drawdowns([row(pnl=0)])['maximum'])

    def test_return_drawdown_with_net_cash_gain_does_not_invent_a_dollar_loss(self):
        result = real_closed_drawdowns([row(pnl=-20, combined=80),
            row(id='gain', pnl=100, combined=1100, closed=400000)])
        self.assertAlmostEqual(12, result['current']['pct'])
        self.assertIsNone(result['current']['usd'])

    def test_empty_backtest_and_missing_capital_are_unavailable_and_scale_changes_only_real_dollars(self):
        for rows in [[], [row(source='backtest')], [row(combined=None, position=None)]]:
            self.assertIsNone(real_closed_drawdowns(rows))
        rows = [row(pnl=-100, combined=900)]
        scaled = [dict(r, netPnl=r['netPnl'] * 100, _chart=dict(r['_chart'], combined=r['_chart']['combined'] * 100)) for r in rows]
        self.assertEqual(real_closed_drawdowns(rows)['maximum']['pct'], real_closed_drawdowns(scaled)['maximum']['pct'])
        self.assertEqual(10000, real_closed_drawdowns(scaled)['maximum']['usd'])


class RealDrawdownReaderCase(unittest.TestCase):
    setUp = fixtures.BackendCase.setUp
    state = fixtures.BackendCase.state
    history = fixtures.BackendCase.history
    row = fixtures.BackendCase.row

    def write_history(self, account, entries):
        with Path(account.history_path).open('w', newline='') as file:
            writer = csv.writer(file)
            writer.writerow(fixtures.COLUMNS + ['pos_size', 'leverage', 'entry_capital'])
            for number, (offset, pnl, position, leverage, entry) in enumerate(entries, 1):
                values = self.row(offset=offset, number=str(number), pnl=str(pnl))
                values[-1] = account.strategy
                writer.writerow(values + [position, leverage, entry])

    def write_edge(self):
        self.main = replace(self.main, edge_path=str(self.path / 'edge.csv'), accounts=tuple(
            replace(a, edge_model='M1-Fast' + ('-B' if a.id == 'b' else '')) for a in self.accounts))
        with Path(self.main.edge_path).open('w', newline='') as file:
            writer = csv.writer(file)
            writer.writerow(fixtures.COLUMNS + ['final_capital', 'pos_size', 'leverage'])
            values = self.row(pnl='1000', source='backtest')
            values[-1] = 'M1-Fast'
            writer.writerow(values + [2000, 400, 2])
        os.utime(self.main.edge_path, ns=((fixtures.NOW - 10000) * 1000000,) * 2)

    def build(self, period='All', selected='all', mode='real', version=2):
        return self.files.build(self.main, period, selected, self.prices, fixtures.NOW, mode, version)

    def test_lifetime_includes_trades_older_than_one_year_despite_short_visible_period(self):
        self.write_history(self.accounts[0], [(400 * 86400000, -10, 40, 1, ''), (1000, 5, 40, 1, '')])
        value = self.build('1W')
        self.assertEqual(5, value['metrics']['realizedPnl'])
        self.assertIsNotNone(value.get('realDrawdowns'))
        drawdown = value['realDrawdowns']
        self.assertEqual('All', drawdown['period'])
        self.assertEqual('real', drawdown['source'])
        self.assertEqual(2, drawdown['closedTrades'])
        self.assertAlmostEqual(5.5, drawdown['current']['pct'])
        self.assertEqual(5, drawdown['current']['usd'])
        self.assertAlmostEqual(10, drawdown['maximum']['pct'])
        self.assertEqual(10, drawdown['maximum']['usd'])
        self.assertEqual(drawdown, self.build('1Y')['realDrawdowns'])

    def test_two_wallets_combine_before_the_lifetime_return_and_cash_loss(self):
        for account in self.accounts:
            self.write_history(account, [(1000, -10, 40, 1, '')])
        drawdown = self.build().get('realDrawdowns')
        self.assertIsNotNone(drawdown)
        self.assertEqual(1, drawdown['closedTrades'])
        self.assertAlmostEqual(10, drawdown['current']['pct'])
        self.assertEqual(20, drawdown['current']['usd'])
        self.assertEqual(drawdown['current'], drawdown['maximum'])

    def test_single_wallet_can_use_recorded_entry_capital_when_legacy_leverage_is_missing(self):
        self.main = replace(self.main, accounts=self.accounts[:1])
        self.write_history(self.accounts[0], [(1000, -10, 40, '', 40)])
        drawdown = self.build().get('realDrawdowns')
        self.assertIsNotNone(drawdown)
        self.assertAlmostEqual(10, drawdown['current']['pct'])
        self.assertEqual(10, drawdown['current']['usd'])
        self.assertTrue(drawdown['legacyCapitalEstimate'])

    def test_backtest_and_timeframe_filters_cannot_change_real_drawdowns_or_query_prices(self):
        self.write_history(self.accounts[0], [(400 * 86400000, -10, 40, 1, ''), (1000, 5, 40, 1, '')])
        real = self.build('1W')['realDrawdowns']
        self.write_edge()
        with patch.object(self.files, 'read_position', side_effect=AssertionError('Must not read open positions')), \
                patch.object(self.prices, 'get', side_effect=AssertionError('Must not query marks')):
            edge = self.build('1W', mode='edge')
        self.assertEqual(1000, edge['metrics']['realizedPnl'])
        self.assertEqual(real, edge['realDrawdowns'])
        self.assertEqual(real, self.build('1Y', mode='edge')['realDrawdowns'])
        Path(self.accounts[0].history_path).unlink()
        self.assertIsNone(self.build('1W', mode='edge')['realDrawdowns'])
        self.assertEqual(1000, self.build('1W', mode='edge')['metrics']['realizedPnl'])

    def test_model_scope_missing_histories_and_legacy_v1_are_independent(self):
        self.accounts = (self.accounts[0], replace(self.accounts[1], strategy='M2'))
        self.main = replace(self.main, accounts=self.accounts)
        for account in self.accounts:
            self.write_history(account, [(1000, -10, 40, 1, '')])
        one = self.build(selected='M1')['realDrawdowns']
        self.assertEqual(1, one['closedTrades'])
        self.assertEqual(10, one['current']['usd'])
        Path(self.accounts[1].history_path).unlink()
        self.assertEqual(one, self.build(selected='M1')['realDrawdowns'])
        self.assertIsNone(self.build()['realDrawdowns'])
        with patch.object(self.files, 'lifetime_drawdowns', side_effect=AssertionError('v1 must not calculate new metrics')):
            self.assertNotIn('realDrawdowns', self.build(version=1))

    def test_contract_validates_real_lifetime_source_finite_values_and_matched_points(self):
        self.write_history(self.accounts[0], [(1000, -10, 40, 1, '')])
        payload = self.build()
        payload['schemaVersion'] = 1
        payload['selectedAccount'] = payload.pop('selectedModel')
        self.assertIsNotNone(validate_v2(payload, 'Main')['realDrawdowns'])
        changes = [dict(period='1Y'), dict(source='edge'), dict(closedTrades=0), dict(closedTrades=20001),
            dict(legacyCapitalEstimate='yes'), dict(address='private'),
            dict(current=dict(pct=101, usd=10)), dict(current=dict(pct=-1, usd=10)),
            dict(current=dict(pct=float('nan'), usd=10)), dict(current=dict(pct=1, usd=-1)),
            dict(current=dict(pct=1, usd=float('inf'))), dict(current=dict(pct=0, usd=10)),
            dict(current=dict(pct=20, usd=10)), dict(current=dict(pct=1, usd=1, secret='private'))]
        for change in changes:
            invalid = copy.deepcopy(payload)
            invalid['realDrawdowns'].update(change)
            with self.subTest(change=change), self.assertRaises(InvalidData):
                validate_v2(invalid, 'Main')
        for current in [dict(pct=1, usd=None), dict(pct=1, usd=0), dict(pct=0, usd=0)]:
            valid = copy.deepcopy(payload)
            valid['realDrawdowns']['current'] = current
            validate_v2(valid, 'Main')


if __name__ == '__main__':
    unittest.main()
