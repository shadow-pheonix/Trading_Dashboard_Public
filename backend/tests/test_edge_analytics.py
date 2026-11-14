"""Equity/DD history and open estimates share explicit instance/wallet scope."""
import copy
import csv
from dataclasses import replace
import os
from pathlib import Path
import unittest
from unittest.mock import patch

import test_backend as fixtures
from dashboard_backend.grouping import validate_v2
from dashboard_backend.validation import InvalidData, period_start


class EdgeAnalyticsCase(unittest.TestCase):
    state = fixtures.BackendCase.state
    history = fixtures.BackendCase.history
    row = fixtures.BackendCase.row
    open_trade = fixtures.BackendCase.open_trade

    def setUp(self):
        fixtures.BackendCase.setUp(self)
        self.accounts = tuple(replace(a, wallet=side, edge_model='M1-Fast' + ('-B' if side == 'B' else ''))
                              for a, side in zip(self.accounts, ('A', 'B')))
        self.main = replace(self.main, accounts=self.accounts, edge_path=str(self.path / 'edge_trades_combined.csv'))
        self.write_edge([self.edge_row(pnl=100, source='backtest', cumulative=1100, offset=2000),
                         self.edge_row(number='2', pnl=-110, combined=990)])

    def edge_row(self, number='1', pnl=0, source='live', cumulative=1000, combined=1000,
                 model='M1-Fast', offset=1000):
        result = dict(zip(fixtures.COLUMNS, self.row(offset=offset, number=number, pnl=str(pnl), source=source)))
        result.update(model=model, cum_capital=cumulative, combined_equity=combined,
                      pos_size_quote=400, leverage=2, entry_capital=200)
        return result

    def write_edge(self, rows, instance=None):
        instance = instance or self.main
        with Path(instance.edge_path).open('w', newline='') as handle:
            writer = csv.DictWriter(handle, fieldnames=fixtures.COLUMNS +
                                    ['cum_capital', 'combined_equity', 'pos_size_quote', 'leverage', 'entry_capital'])
            writer.writeheader()
            writer.writerows(rows)
        os.utime(instance.edge_path, ns=((fixtures.NOW - 100) * 1000000,) * 2)

    def build(self, period='All', selected='all', mode='real', instance=None, **kwargs):
        return self.files.build(instance or self.main, period, selected, self.prices, fixtures.NOW, mode, 2, **kwargs)

    def test_complete_edge_history_is_identical_in_both_modes_and_regular_csv_cannot_change_it(self):
        self.history(self.accounts[0], [self.row(pnl='999')])
        real, edge = self.build(), self.build(mode='edge')
        self.assertEqual(999, real['metrics']['realizedPnl'])
        self.assertEqual(-10, edge['metrics']['realizedPnl'])
        self.assertEqual(real['normalizedEquity'], edge['normalizedEquity'])
        self.assertAlmostEqual(9900, real['normalizedEquity']['points'][-1]['equity'])
        dd = real['edgeDrawdowns']
        self.assertEqual('edge', dd['source'])
        self.assertEqual(2, dd['closedTrades'])
        self.assertAlmostEqual(10, dd['current']['pct'])
        self.assertEqual(110, dd['current']['usd'])
        self.assertEqual(dd, edge['edgeDrawdowns'])
        self.assertIsNone(real['realDrawdowns'])
        self.history(self.accounts[0], [self.row(pnl='-999')])
        self.assertEqual(dd, self.build()['edgeDrawdowns'])

    def test_main_uses_wallet_a_only_for_closed_history(self):
        self.write_edge([self.edge_row(pnl=-10, combined=90),
                         self.edge_row(pnl=-900, combined=100, model='M1-Fast-B')])
        value = self.build()
        self.assertEqual('A', value['normalizedEquity']['walletScope'])
        self.assertAlmostEqual(9000, value['normalizedEquity']['points'][-1]['equity'])
        self.assertEqual(1, value['edgeDrawdowns']['closedTrades'])
        self.assertEqual(10, value['edgeDrawdowns']['current']['usd'])

    def test_instance_paths_never_share_or_substitute_history(self):
        for identity, loss in [('madhu', 20), ('vinay', 30)]:
            instance = replace(self.main, id=identity, label=identity.title(),
                               edge_path=str(self.path / (identity + '-edge_trades_combined.csv')))
            self.write_edge([self.edge_row(pnl=-loss, combined=100-loss)], instance)
            value = self.build(instance=instance)
            self.assertAlmostEqual(loss, value['edgeDrawdowns']['current']['pct'])
            self.assertEqual('all', value['edgeDrawdowns']['walletScope'])
            Path(instance.edge_path).unlink()
            self.assertIsNone(self.build(instance=instance)['normalizedEquity'])
        self.assertAlmostEqual(9900, self.build()['normalizedEquity']['points'][-1]['equity'])

    def test_other_instances_pair_their_own_wallets_before_returns_and_cash_losses(self):
        instance = replace(self.main, id='madhu', label='Madhu')
        self.write_edge([self.edge_row(pnl=-10, combined=180),
                         self.edge_row(pnl=-10, combined=180, model='M1-Fast-B')])
        dd = self.build(instance=instance)['edgeDrawdowns']
        self.assertEqual(1, dd['closedTrades'])
        self.assertAlmostEqual(10, dd['current']['pct'])
        self.assertEqual(20, dd['current']['usd'])

    def test_lifetime_cards_include_seed_older_than_one_year_and_ignore_period_filter(self):
        self.write_edge([self.edge_row(pnl=-100, source='backtest', cumulative=900, offset=400*86400000),
                         self.edge_row(number='2', pnl=50, combined=1050)])
        short, full = self.build('1W'), self.build()
        self.assertEqual(short['edgeDrawdowns'], full['edgeDrawdowns'])
        self.assertEqual(2, short['edgeDrawdowns']['closedTrades'])
        self.assertEqual(100, short['edgeDrawdowns']['maximum']['usd'])
        self.assertEqual(50, short['edgeDrawdowns']['current']['usd'])
        self.assertNotEqual(short['normalizedEquity']['points'], full['normalizedEquity']['points'])
        self.assertEqual(period_start('1W', fixtures.NOW), short['normalizedEquity']['points'][0]['timestamp'])

    def test_missing_regular_history_or_capital_does_not_hide_edge_equity_or_dd(self):
        Path(self.accounts[0].history_path).unlink()
        value = self.build()
        self.assertIsNone(value['metrics']['realizedPnl'])
        self.assertIsNotNone(value['normalizedEquity'])
        self.assertIsNotNone(value['edgeDrawdowns'])

    def test_missing_corrupt_empty_or_future_edge_history_never_falls_back_to_real_csv(self):
        self.history(self.accounts[0], [self.row(pnl='100')])
        for raw in (None, 'invalid\n', ''):
            if raw is None:
                Path(self.main.edge_path).unlink(missing_ok=True)
            else:
                Path(self.main.edge_path).write_text(raw)
            value = self.build()
            self.assertEqual(100, value['metrics']['realizedPnl'])
            self.assertIsNone(value['normalizedEquity'])
            self.assertIsNone(value['edgeDrawdowns'])
        self.write_edge([self.edge_row(offset=-1)])
        self.assertIsNone(self.build()['edgeDrawdowns'])
        self.write_edge([])
        self.assertIsNone(self.build()['normalizedEquity'])

    def test_model_scope_and_edge_mode_never_read_regular_csvs_for_analytics(self):
        with patch.object(self.files, 'history', side_effect=AssertionError('No regular CSV access')), \
                patch.object(self.files, 'read_position', side_effect=AssertionError('No state/marks')):
            self.assertEqual(2, self.build(selected='M1', mode='edge')['edgeDrawdowns']['closedTrades'])
            self.assertIsNone(self.build(selected='M2', mode='edge')['normalizedEquity'])

    def test_main_wallet_a_history_and_dotted_point_exclude_wallet_b_pnl_and_capital(self):
        for account, size in zip(self.accounts, (2, 20)):
            trade = dict(self.open_trade(), sz=size, entry_capital=200)
            self.state(account, trade)
        value = self.build()
        estimate = value['normalizedEquity']['openEstimate']
        self.assertAlmostEqual(10, estimate['returnPct'])
        self.assertEqual(fixtures.NOW, estimate['sampledAt'])
        self.assertEqual(220, value['metrics']['unrealizedPnl'])
        self.assertIsNone(self.build(mode='edge')['normalizedEquity']['openEstimate'])
        self.prices.available = False
        self.assertIsNone(self.build()['normalizedEquity']['openEstimate'])

    def test_flat_scoped_wallets_use_only_their_own_demand_sample_for_dotted_point(self):
        self.state(self.accounts[0], dict(self.open_trade(), entry_capital=200))
        self.main = replace(self.main, id='vinay', label='Vinay')
        samples = dict(attemptedAt=fixtures.NOW, accounts={
            'b': dict(status='ok', equity=800, sampledAt=fixtures.NOW)})
        value = self.build(idle_balances=samples)
        self.assertAlmostEqual(2, value['normalizedEquity']['openEstimate']['returnPct'])
        self.assertIsNone(self.build()['normalizedEquity']['openEstimate'])

    def test_model_filter_keeps_full_instance_capital_for_the_existing_open_return(self):
        self.accounts = (self.accounts[0], replace(self.accounts[1], strategy='M2', wallet='A', edge_model='M2-Med'))
        self.main = replace(self.main, accounts=self.accounts)
        for account in self.accounts:
            self.state(account, dict(self.open_trade(), entry_capital=200))
        value = self.build(selected='M1')
        self.assertAlmostEqual(5, value['normalizedEquity']['openEstimate']['returnPct'])
        self.assertAlmostEqual(5, value['openPerformance']['entryEquityReturnPct'])

    def test_new_contract_rejects_wrong_source_wallet_scope_and_unscoped_open_estimates(self):
        value = self.build()
        value['selectedAccount'] = value.pop('selectedModel')
        for field, changes in [('normalizedEquity', [dict(historySource='real_csv'), dict(walletScope='B'),
                dict(openEstimate=dict(returnPct=-100, sampledAt=fixtures.NOW)),
                dict(openEstimate=dict(returnPct=1, sampledAt=fixtures.NOW-15001))]),
                ('edgeDrawdowns', [dict(source='real'), dict(period='1Y'), dict(walletScope='B'),
                 dict(closedTrades=0), dict(current=dict(pct=101, usd=1)), dict(secret='withheld')])]:
            for change in changes:
                invalid = copy.deepcopy(value)
                invalid[field].update(change)
                with self.subTest(change=change), self.assertRaises(InvalidData):
                    validate_v2(invalid, 'Main')


if __name__ == '__main__':
    unittest.main()
