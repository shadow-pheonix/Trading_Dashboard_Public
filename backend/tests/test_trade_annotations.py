import json
from pathlib import Path
from dataclasses import replace
import unittest
from unittest.mock import AsyncMock, Mock

from dashboard_backend.config import Config
import test_backend as fixtures
from test_backend import NOW, SECRET, iso


class TradeAnnotationsCase(unittest.TestCase):
    state = fixtures.BackendCase.state
    history = fixtures.BackendCase.history
    row = fixtures.BackendCase.row
    open_trade = fixtures.BackendCase.open_trade
    request = fixtures.BackendCase.request
    scope = fixtures.BackendCase.scope

    def setUp(self):
        fixtures.BackendCase.setUp(self)
        self.accounts = tuple(replace(a, wallet=w) for a, w in zip(self.accounts, ('A', 'B')))
        self.main = replace(self.main, accounts=self.accounts)
        self.app.config = Config((self.main,))

    def annotations(self):
        return self.files.chart_annotations(self.main, NOW)

    def test_real_entries_are_per_wallet_allowlisted_and_read_only(self):
        self.state(self.accounts[0], dict(self.open_trade(), entry_execution_price=101, sl_confirmed=True))
        self.state(self.accounts[1], dict(self.open_trade('SHORT'), sl=110, manual=True))
        before = {a.state_path: Path(a.state_path).read_bytes() for a in self.accounts}
        value = self.annotations()
        self.assertEqual(value['annotationStatus'], 'ok')
        self.assertEqual([(t['wallet'], t['side'], t['entryPrice'], t['stopPrice']) for t in value['tradeAnnotations']],
                         [('A', 'LONG', 101, 90), ('B', 'SHORT', 100, 110)])
        self.assertEqual(value['tradeAnnotations'][0]['stopState'], 'confirmed')
        for forbidden in (SECRET, self.accounts[0].state_path, 'cloid', 'manual', 'sl_oid', 'entry_execution_price'):
            self.assertNotIn(forbidden, json.dumps(value))
        for a in self.accounts:
            with open(a.state_path, 'rb') as stream: self.assertEqual(stream.read(), before[a.state_path])

    def test_flat_skipped_pending_approval_and_closed_records_never_create_entries(self):
        self.assertEqual(self.annotations()['tradeAnnotations'], [])
        for status in ('SKIPPED', 'PENDING_ENTRY', 'PENDING_APPROVAL', 'CLOSED'):
            self.state(trade=dict(self.open_trade(), status=status))
            self.assertEqual(self.annotations()['tradeAnnotations'], [])

    def test_unknown_or_bad_entry_cannot_be_guessed_from_file_time(self):
        for changes in (dict(entry_time='unknown'), dict(entry_time=None), dict(entry_time=iso(NOW+1)),
                        dict(sz=0), dict(sz=True), dict(entry_price=0), dict(side='BUY')):
            self.state(trade=dict(self.open_trade(), **changes))
            value = self.annotations()
            self.assertEqual(value['annotationStatus'], 'partial')
            self.assertEqual(value['tradeAnnotations'], [])

    def test_partial_fill_entry_survives_pending_stop_and_missing_sl_is_not_invented(self):
        self.state(trade=dict(self.open_trade(), status='PENDING_PROTECTION', sz=0.000001, sl_confirmed=False))
        value = self.annotations()['tradeAnnotations'][0]
        self.assertEqual((value['stopState'], value['stopPrice']), ('pending', 90))
        for stop in (None, 0, -1, 'bad', True):
            self.state(trade=dict(self.open_trade(), sl=stop))
            value = self.annotations()['tradeAnnotations'][0]
            self.assertIsNone(value['stopPrice']); self.assertEqual(value['stopState'], 'unavailable')

    def test_stop_repricing_keeps_original_entry_and_allows_profit_protection(self):
        trade = dict(self.open_trade(), entry_execution_price=100)
        self.state(trade=trade); original = self.annotations()['tradeAnnotations'][0]
        self.state(trade=dict(trade, sl=108, entry_price=105))
        moved = self.annotations()['tradeAnnotations'][0]
        self.assertEqual((moved['id'], moved['enteredAt'], moved['entryPrice']),
                         (original['id'], original['enteredAt'], 100))
        self.assertEqual(moved['stopPrice'], 108)

    def test_closing_write_boundary_removes_stop_and_failed_wallet_does_not_restore_old_data(self):
        trade = self.open_trade(); self.state(trade=trade)
        row = self.row(); row[2] = trade['entry_time']; row[3] = iso(NOW-1)
        self.history(rows=[row])
        self.assertEqual(self.annotations()['tradeAnnotations'], [])
        self.history(rows=[])
        self.state(self.accounts[1], self.open_trade('SHORT'))
        Path(self.accounts[0].state_path).write_text('{bad')
        value = self.annotations()
        self.assertEqual(value['annotationStatus'], 'partial')
        self.assertEqual([t['wallet'] for t in value['tradeAnnotations']], ['B'])

    def test_chart_annotations_are_authenticated_and_legacy_macd_does_not_read_them(self):
        self.app.indicators.chart = AsyncMock(return_value={'status': 'loading', 'models': [], 'candleStatus': 'loading'})
        self.app.indicators.get = AsyncMock(return_value={'status': 'loading', 'models': []})
        original = self.files.chart_annotations
        self.files.chart_annotations = Mock(wraps=original)
        self.assertEqual(self.request('/v2/dashboard', 'view=chart', token=None)[0], 401)
        self.assertEqual(self.request('/v2/dashboard', 'view=macd')[0], 200)
        self.files.chart_annotations.assert_not_called()
        status, value, _ = self.request('/v2/dashboard', 'view=chart')
        self.assertEqual(status, 200); self.assertEqual(value['annotationStatus'], 'ok')
        self.files.chart_annotations.assert_called_once_with(self.main, NOW)
        self.assertEqual(self.prices.acquisitions, 0)
        self.assertEqual(self.app.balances.tasks, {})
