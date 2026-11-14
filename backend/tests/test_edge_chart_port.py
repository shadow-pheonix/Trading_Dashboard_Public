"""Synthetic regression for the complete Edge chart preparation pipeline."""
import base64
import csv
from dataclasses import replace
import hashlib
import io
from pathlib import Path
import unittest
from unittest.mock import patch
import zlib

from dashboard_backend.edge_history import reviewed_rows
from dashboard_backend.files import read_stable
from dashboard_backend.validation import InvalidData
import test_edge_analytics as analytics_fixtures
import test_backend as fixtures


class EdgeChartPortCase(unittest.TestCase):
    setUp = analytics_fixtures.EdgeAnalyticsCase.setUp
    state = analytics_fixtures.EdgeAnalyticsCase.state
    history = analytics_fixtures.EdgeAnalyticsCase.history
    row = analytics_fixtures.EdgeAnalyticsCase.row
    open_trade = analytics_fixtures.EdgeAnalyticsCase.open_trade
    edge_row = analytics_fixtures.EdgeAnalyticsCase.edge_row
    write_edge = analytics_fixtures.EdgeAnalyticsCase.write_edge
    build = analytics_fixtures.EdgeAnalyticsCase.build

    def dated(self, exit_time, pnl, capital, source='offline_backtest', **changes):
        row = self.edge_row(pnl=pnl, cumulative=capital, source=source)
        row.update(entry_time=exit_time.replace('12:00:00', '11:00:00'), exit_time=exit_time)
        row.update(changes)
        return row

    def artifact(self, rows, root=None, corrupt=False):
        text = io.StringIO()
        writer = csv.DictWriter(text, fieldnames=list(rows[0]))
        writer.writeheader(); writer.writerows(rows)
        raw = text.getvalue().encode()
        target = (root or self.path) / 'edge_engine.py'
        digest = '0' * 64 if corrupt else hashlib.sha256(raw).hexdigest()
        target.write_text('_EMBEDDED_OFFLINE_SHA256 = ' + repr(digest) + '\n' +
            '_EMBEDDED_OFFLINE_B85 = ' + repr(base64.b85encode(zlib.compress(raw)).decode()) + '\n' +
            'raise RuntimeError("The dashboard must never execute this module")\n')
        return target

    def canonical_history(self):
        self.main = replace(self.main, id='vinay', label='Vinay', accounts=self.accounts[:1])
        self.artifact([
            self.dated('2025-12-30 12:00:00', 100, 1100),
            self.dated('2026-01-05 12:00:00', 200, 1300),
            self.dated('2026-02-05 12:00:00', -130, 1170),
        ])
        self.write_edge([
            # Exact and non-exact overlapping seed/live rows must both disappear.
            self.dated('2026-01-05 12:00:00', 170, 1170, 'backtest'),
            self.dated('2026-01-06 12:00:00', 999, 2998, 'live'),
            self.dated('2026-09-01 12:00:00', 117, 1287, 'backtest'),
            self.dated('2026-10-01 12:00:00', -50, 999999, 'live', combined_equity=950),
        ])

    def test_january_baseline_overlap_precedence_and_compounding_match_edge(self):
        self.canonical_history()
        value = self.build('YTD')
        points = value['normalizedEquity']['points']
        # Exact Edge pipeline: prior-year 1,100 baseline; +200, -130, +117;
        # then live -50 / (950 - -50), compounded rather than dollar-added.
        self.assertEqual(5, len(points))
        for expected, point in zip([10000, 13000 / 1.1, 11700 / 1.1, 12870 / 1.1], points[:-1]):
            self.assertAlmostEqual(expected, point['equity'])
        self.assertAlmostEqual(11115, points[-1]['equity'])
        self.assertEqual(5, value['edgeDrawdowns']['closedTrades'])
        self.assertAlmostEqual(5.95, value['edgeDrawdowns']['current']['pct'])
        self.assertEqual(63, value['edgeDrawdowns']['current']['usd'])
        self.assertAlmostEqual(10, value['edgeDrawdowns']['maximum']['pct'])
        self.assertEqual(130, value['edgeDrawdowns']['maximum']['usd'])
        self.assertAlmostEqual(12226.5, self.build()['normalizedEquity']['points'][-1]['equity'])

    def test_source_selector_does_not_change_complete_equity_or_lifetime_dd(self):
        self.canonical_history()
        real, backtest = self.build('YTD'), self.build('YTD', mode='edge')
        self.assertEqual(real['normalizedEquity'], backtest['normalizedEquity'])
        self.assertEqual(real['edgeDrawdowns'], backtest['edgeDrawdowns'])
        self.assertEqual(real['edgeDrawdowns'], self.build('1W')['edgeDrawdowns'])

    def test_private_embedded_artifact_precedes_stale_csv_and_is_never_executed(self):
        self.canonical_history()
        (self.path / 'edge_history_offline.csv').write_text('invalid stale file\n')
        self.assertAlmostEqual(11115, self.build('YTD')['normalizedEquity']['points'][-1]['equity'])

    def test_bad_checksum_or_partial_literal_pair_never_falls_back_to_smaller_history(self):
        self.canonical_history()
        path = self.artifact([self.dated('2026-01-05 12:00:00', 200, 1300)], corrupt=True)
        self.assertIsNone(self.build('YTD')['normalizedEquity'])
        self.assertIsNone(self.build()['edgeDrawdowns'])
        path.write_text('_EMBEDDED_OFFLINE_SHA256 = "missing payload"\n')
        self.assertIsNone(self.build('YTD')['normalizedEquity'])

    def test_new_artifact_invalidates_cached_equity_even_when_live_csv_is_unchanged(self):
        self.canonical_history()
        first = self.build('YTD')['normalizedEquity']
        self.artifact([self.dated('2026-01-05 12:00:00', 300, 1300)])
        self.assertNotEqual(first, self.build('YTD')['normalizedEquity'])

    def test_instance_reads_only_its_adjacent_reviewed_artifact(self):
        self.canonical_history()
        other = self.path / 'other'; other.mkdir()
        second = replace(self.main, id='madhu', label='Madhu', edge_path=str(other / 'edge.csv'))
        self.write_edge([self.dated('2026-09-01 12:00:00', 10, 1010, 'backtest')], second)
        self.artifact([self.dated('2026-01-05 12:00:00', -100, 900)], other)
        self.assertAlmostEqual(9090, self.build('YTD', instance=second)['normalizedEquity']['points'][-1]['equity'])
        self.assertAlmostEqual(11115, self.build('YTD')['normalizedEquity']['points'][-1]['equity'])

    def test_main_keeps_recorded_combined_capital_to_match_telegram_exactly(self):
        self.canonical_history()
        self.main = replace(self.main, id='main', label='Main', accounts=self.accounts)
        value = self.build('YTD')
        self.assertAlmostEqual(11115, value['normalizedEquity']['points'][-1]['equity'])
        self.assertFalse(value['normalizedEquity']['legacyCapitalEstimate'])
        self.assertEqual('all', value['normalizedEquity']['walletScope'])

    def test_artifact_symlink_is_rejected_and_regular_csv_reads_cannot_open_python(self):
        self.canonical_history()
        path = self.path / 'edge_engine.py'; saved = path.read_text()
        path.unlink(); target = self.path / 'target.py'; target.write_text(saved)
        path.symlink_to(target)
        self.assertIsNone(self.build('YTD')['normalizedEquity'])
        with self.assertRaises(InvalidData):
            read_stable(str(target), 100000)

    def test_engine_without_embedded_data_uses_its_own_legacy_offline_csv(self):
        self.canonical_history()
        engine = self.path / 'edge_engine.py'
        raw = reviewed_rows(engine, lambda path, limit: read_stable(path, limit, reviewed_engine=True))
        out = io.StringIO(); writer = csv.DictWriter(out, fieldnames=list(raw[0]))
        writer.writeheader(); writer.writerows(raw)
        (self.path / 'edge_history_offline.csv').write_text(out.getvalue())
        engine.write_text('raise RuntimeError("Do not import legacy engine")\n')
        self.assertAlmostEqual(11115, self.build('YTD')['normalizedEquity']['points'][-1]['equity'])

    def test_bounded_decompression_rejects_oversized_private_artifact(self):
        path = self.artifact([self.dated('2026-01-05 12:00:00', 200, 1300, model='x' * 10000)])
        with patch('dashboard_backend.edge_history.MAX_ARTIFACT', 2048), self.assertRaises(InvalidData):
            reviewed_rows(path, lambda path, limit: read_stable(path, limit, reviewed_engine=True))

    def test_updated_edge_preserves_independent_source_scale_before_overlap_cut(self):
        self.main = replace(self.main, accounts=tuple(replace(self.accounts[0 if side == 'A' else 1],
            id=model+side, strategy=model, wallet=side, edge_model=label+('-B' if side == 'B' else ''))
            for model, label in [('M1','M1-Fast'),('M2','M2-Med'),('M3','M3-Slow')]
            for side in ('A','B')))
        for offline_scale, seed_scale in [(1,1), (.001,1), (1000,1), (1,.01), (1,10000)]:
            self.artifact([
                self.dated('2026-06-02 12:00:00', 10000000*offline_scale, 20000000*offline_scale),
                self.dated('2026-08-11 12:00:00', -5000000*offline_scale, 15000000*offline_scale, model='M2-Med'),
            ])
            rows=[]; capital=600*seed_scale
            for model, date, pnl in [('M1-Fast','2026-08-10',600), ('M1-Fast','2026-08-17',12),
                                     ('M2-Med','2026-08-17',-12), ('M3-Slow','2026-08-18',-60)]:
                for suffix, weight in [('',.6),('-B',.4)]:
                    amount=pnl*weight*seed_scale; capital+=amount
                    clock = '06:00:00' if model=='M1-Fast' else '21:15:00' if model=='M2-Med' else '11:15:00'
                    rows.append(self.dated(date+' '+clock, amount, capital, 'backtest', model=model+suffix))
            rows.append(self.dated('2026-08-25 12:00:00',100,1100,'live',combined_equity=1100))
            self.write_edge(rows)
            curve=self.build('YTD')['normalizedEquity']
            self.assertIsNotNone(curve)
            expected=[10000,20000,15000,15150,15000,14250,15675]
            self.assertEqual(len(expected),len(curve['points']))
            for actual, value in zip(curve['points'],expected):
                self.assertAlmostEqual(value,actual['equity'])
            self.assertAlmostEqual(28.75,curve['drawdown']['pct'])


if __name__ == '__main__':
    unittest.main()
