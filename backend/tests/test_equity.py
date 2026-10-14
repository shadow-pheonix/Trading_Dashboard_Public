"""Source-aware reference equity; synthetic history, no bot/exchange imports."""
from dataclasses import replace
import csv
import os
from pathlib import Path
import unittest

from dashboard_backend.grouping import normalized_equity, paired_trades, validate_v2
from dashboard_backend.validation import InvalidData, period_start
import test_backend as fixtures

COLUMNS, NOW = fixtures.COLUMNS, fixtures.NOW


def row(id='a', account='a', pnl=20, source='live', cumulative=1020,
        position=400, leverage=2, combined=1020, closed=300000, model='M1'):
    return dict(id=id, accountId=account, strategy=model, symbol='BTCUSDT', side='LONG',
        openedAt=100000, closedAt=closed, source=source, netPnl=pnl, fees=1,
        entryPrice=100, exitPrice=110, funding=None, _equity=None,
        _chart=dict(cumulative=cumulative, position=position, leverage=leverage, combined=combined))


class EquityMathCase(unittest.TestCase):
    def test_recorded_live_preclose_capital_and_legacy_model_weights(self):
        self.assertAlmostEqual(10200, normalized_equity([row()], 0)['points'][-1]['equity'])
        for model, value in [('M1', 10400), ('M2', 10200), ('M3', 10400)]:
            result = normalized_equity([row(model=model, combined=None)], 0)
            self.assertAlmostEqual(value, result['points'][-1]['equity'])
            self.assertTrue(result['legacyCapitalEstimate'])
        self.assertFalse(normalized_equity([row()], 0)['legacyCapitalEstimate'])

    def test_backtest_capital_rebuild_and_mixed_source_returns(self):
        rows = [row(pnl=100, source='backtest', cumulative=1100, combined=None),
            row(id='b', pnl=20, cumulative=1020, closed=400000),
            row(id='c', pnl=-30, source='backtest', cumulative=999999, closed=500000)]
        points = normalized_equity(rows, 200000)['points']
        self.assertEqual(dict(timestamp=200000, equity=10000), points[0])
        self.assertAlmostEqual(11000, points[1]['equity'])
        self.assertAlmostEqual(11220, points[2]['equity'])
        # The later backtest return follows rebuilt capital, not raw 999999.
        self.assertAlmostEqual(10000 * 1.1 * 1.02 * 1090 / 1100, points[-1]['equity'])

    def test_missing_capital_is_not_fabricated_as_zero_return(self):
        self.assertIsNone(normalized_equity([], 0))
        for changes in [dict(combined=None, position=None), dict(combined=None, leverage=0),
                        dict(source='backtest', cumulative=None),
                        dict(source='backtest', cumulative=-1), dict(pnl=-2000, combined=None)]:
            self.assertIsNone(normalized_equity([row(**changes)], 0))

    def test_same_timestamp_has_one_final_point(self):
        result = normalized_equity([row(pnl=20), row(id='b', pnl=-10, combined=990)], 0)
        self.assertEqual(1, len(result['points']))
        self.assertAlmostEqual(10000 * 1.02 * .99, result['points'][0]['equity'])

    def test_edge_monitor_drawdown_includes_starting_peak_and_six_compounded_losses(self):
        rows = [row(id=str(i), pnl=-100, combined=900, closed=300000 + i) for i in range(6)]
        result = normalized_equity(rows, 0)
        self.assertAlmostEqual(46.8559, result['drawdown']['pct'])
        self.assertAlmostEqual(4685.59, result['drawdown']['usd'])
        self.assertAlmostEqual(10, normalized_equity(rows[:1], 0)['drawdown']['pct'])
        # Changing raw USD scale must not change the Edge Monitor result.
        scaled = [dict(r, netPnl=r['netPnl'] * 100,
            _chart=dict(r['_chart'], combined=r['_chart']['combined'] * 100)) for r in rows]
        self.assertEqual(result, normalized_equity(scaled, 0))

    def test_drawdown_uses_running_peak_and_keeps_intra_timestamp_trough(self):
        rows = [row(pnl=100, combined=1100), row(id='b', pnl=-200, combined=800, closed=400000),
                row(id='c', pnl=250, combined=1250, closed=400000)]
        result = normalized_equity(rows, 0)
        self.assertAlmostEqual(20, result['drawdown']['pct'])
        self.assertAlmostEqual(2200, result['drawdown']['usd'])
        self.assertAlmostEqual(11000, result['points'][-1]['equity'])
        # Coalesced chart points have recovered; calculating from them would lose the trough.
        self.assertEqual(2, len(result['points']))
        winners = normalized_equity([row(pnl=100, combined=1100)], 0)
        self.assertEqual(dict(usd=0, pct=0), winners['drawdown'])

    def test_drawdown_matches_edge_monitor_generated_golden_cases(self):
        import json
        cases = json.loads((Path(__file__).parent / 'fixtures' / 'edge_drawdown.json').read_text())
        for case in cases['cases']:
            with self.subTest(case=case['name']):
                result = normalized_equity(case['rows'], 0)
                self.assertAlmostEqual(case['pct'], result['drawdown']['pct'])
                self.assertAlmostEqual(case['usd'], result['drawdown']['usd'])
                self.assertAlmostEqual(case['equity'], result['points'][-1]['equity'])


class EquityReaderCase(unittest.TestCase):
    setUp = fixtures.BackendCase.setUp
    state = fixtures.BackendCase.state
    history = fixtures.BackendCase.history
    row = fixtures.BackendCase.row

    def build(self, period='All', model='all', mode='real'):
        return self.files.build(self.main, period, model, self.prices, NOW, mode, 2)

    def write_capital_history(self, account, pnl, cumulative, source='live'):
        with Path(account.history_path).open('w', newline='') as file:
            writer = csv.writer(file)
            # These are real-trade CSV names, rather than edge column names.
            writer.writerow(COLUMNS + ['final_capital', 'pos_size', 'leverage'])
            writer.writerow(self.row(pnl=str(pnl), source=source) + [cumulative, 200, 2])

    def test_unique_backtest_twins_preserve_raw_start_and_sum_pnl_once(self):
        rows = [row(pnl=9, source='backtest', cumulative=1009, combined=None),
                row(id='b', account='b', pnl=9, source='backtest', cumulative=1009, combined=None)]
        paired = paired_trades(rows, self.accounts)
        self.assertEqual(1, len(paired))
        self.assertEqual(18, paired[0]['netPnl'])
        self.assertEqual(2, paired[0]['fees'])
        self.assertEqual(800, paired[0]['_chart']['position'])
        self.assertAlmostEqual(10180, normalized_equity(paired, 0)['points'][-1]['equity'])
        self.assertEqual(2, len(paired_trades([rows[0], dict(rows[1], source='live')], self.accounts)))
        self.assertEqual(3, len(paired_trades(rows + [dict(rows[1], id='b2')], self.accounts)))

    def test_real_trade_columns_and_source_are_isolated_from_edge(self):
        self.write_capital_history(self.accounts[0], 9, 1009)
        self.write_capital_history(self.accounts[1], 9, 1009)
        # An invalid/unreadable edge path must not affect Real money.
        self.main = replace(self.main, edge_path=str(self.path / 'missing-edge.csv'))
        real = self.build()
        self.assertEqual(18, real['metrics']['realizedPnl'])
        self.assertEqual(1, real['metrics']['closedTrades'])
        self.assertAlmostEqual(10360, real['normalizedEquity']['points'][-1]['equity'])
        self.assertTrue(real['normalizedEquity']['legacyCapitalEstimate'])
        self.assertNotIn('_chart', str(real))
        with self.assertRaises(InvalidData):
            self.build(mode='edge')
        edge = self.path / 'edge.csv'
        edge.write_text('trade_num,model,side,entry_time,exit_time,entry_price,exit_price,pnl_usd,total_fees,source,cum_capital,pos_size_quote,leverage,combined_equity\n'
            + ','.join(['1','M1-Fast','LONG',self.row()[2],self.row()[3],'100','110','100','1','backtest','1100','200','2','']) + '\n'
            + ','.join(['1','M1-Fast','LONG',self.row(offset=0)[2],self.row(offset=0)[3],'100','110','20','1','live','1020','200','2','1020']) + '\n')
        os.utime(edge, ns=((NOW - 100000) * 1000000,) * 2)
        self.main = replace(self.main, edge_path=str(edge), accounts=tuple(
            replace(a, edge_model='M1-Fast' + ('-B' if a.id == 'b' else '')) for a in self.accounts))
        backtest = self.build(mode='edge')
        self.assertEqual({'backtest', 'live'}, {t['source'] for t in backtest['trades']})
        self.assertEqual(120, backtest['metrics']['realizedPnl'])
        self.assertAlmostEqual(11220, backtest['normalizedEquity']['points'][-1]['equity'])
        self.assertEqual(18, self.build()['metrics']['realizedPnl'])

    def test_optional_bad_capital_does_not_break_real_pnl_and_missing_history_cannot_make_curve(self):
        self.write_capital_history(self.accounts[0], 9, 'not-a-number')
        path = Path(self.accounts[0].history_path)
        path.write_text(path.read_text().replace(',200,2', ',not-a-number,2'))
        value = self.build()
        self.assertEqual(9, value['metrics']['realizedPnl'])
        self.assertIsNone(value['normalizedEquity'])
        Path(self.accounts[1].history_path).unlink()
        self.assertIsNone(self.build()['metrics']['realizedPnl'])
        self.assertIsNone(self.build()['normalizedEquity'])

    def test_ytd_baseline_and_contract_reject_invalid_points(self):
        self.write_capital_history(self.accounts[0], 9, 1009)
        value = self.build('YTD', 'M1')
        self.assertEqual(dict(timestamp=period_start('YTD', NOW), equity=10000), value['normalizedEquity']['points'][0])
        for change in [dict(baseEquity=0), dict(points=[]),
                dict(points=[dict(timestamp=NOW+1, equity=10000)]),
                dict(points=[dict(timestamp=NOW, equity=-1)]),
                dict(points=[dict(timestamp=NOW, equity=10000)] * 2)]:
            invalid = dict(value, selectedAccount=value['selectedModel'])
            invalid.pop('selectedModel')
            invalid['normalizedEquity'] = dict(value['normalizedEquity'], **change)
            with self.assertRaises(InvalidData):
                validate_v2(invalid, 'Main')
        for dd in [dict(usd=-1, pct=1), dict(usd=1, pct=-1), dict(usd=1, pct=101),
                   dict(usd=0, pct=1), dict(usd='bad', pct=1), dict(usd=1, pct=1, address='private')]:
            invalid = dict(value, selectedAccount=value['selectedModel'])
            invalid.pop('selectedModel')
            invalid['normalizedEquity'] = dict(value['normalizedEquity'], drawdown=dd)
            with self.assertRaises(InvalidData):
                validate_v2(invalid, 'Main')


if __name__ == '__main__':
    unittest.main()
