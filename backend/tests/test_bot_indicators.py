import asyncio
import copy
import json
from pathlib import Path
import sqlite3
import tempfile
from types import SimpleNamespace
import unittest

from dashboard_backend.bot_indicators import bot_chart
from dashboard_backend.indicators import Indicators, INTERVAL, SPANS

NOW = 1800000000000
LAST = NOW // INTERVAL * INTERVAL - INTERVAL


def payload(delta=0):
    return {'schemaVersion': 2, 'source': 'bot_spot_closed', 'symbol': 'BTCUSDT',
            'intervalMinutes': 15, 'sampledAt': NOW,
            'models': [{'id': key, 'fastPeriod': f, 'slowPeriod': s, 'signalPeriod': g,
                        'points': [{'timestamp': LAST-(95-i)*INTERVAL, 'macd': i+delta,
                                    'signal': 20, 'histogram': i+delta-20} for i in range(96)]}
                       for key, f, s, g in SPANS]}


class BotIndicatorCase(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name)/'outbox.sqlite'
        self.instance = SimpleNamespace(label='Main', alerts_path=str(self.path))
        db = sqlite3.connect(self.path)
        try:
            db.execute('CREATE TABLE macd_chart (slot INTEGER PRIMARY KEY,last_candle INTEGER,sampled_at INTEGER,payload TEXT)')
            db.commit()
        finally:
            db.close()

    def store(self, value=None, last=LAST, sampled=NOW):
        db = sqlite3.connect(self.path)
        try:
            db.execute('INSERT OR REPLACE INTO macd_chart VALUES(1,?,?,?)', (last, sampled, json.dumps(value or payload())))
            db.commit()
        finally:
            db.close()

    async def test_bot_series_are_read_without_market_fetch_or_database_write(self):
        self.store(); before = self.path.read_bytes()
        calls = []
        service = Indicators(lambda: NOW, lambda *a: calls.append(a))
        value = await service.get(self.instance)
        self.assertEqual(value['source'], 'bot_spot_closed'); self.assertEqual(value['status'], 'ok')
        self.assertEqual(value['models'], payload()['models'])
        self.assertEqual(calls, []); self.assertIsNone(service.task)
        self.assertEqual(self.path.read_bytes(), before)
        value['models'][0]['points'].clear()
        self.assertEqual(len((await service.get(self.instance))['models'][0]['points']), 96)

    async def test_missing_after_export_retains_stale_bot_chart_without_public_rebuild(self):
        self.store(); now = [NOW]; calls = []
        service = Indicators(lambda: now[0], lambda *a: calls.append(a))
        await service.get(self.instance)
        self.path.unlink(); now[0] += INTERVAL
        value = await service.get(self.instance)
        self.assertEqual(value['status'], 'stale'); self.assertEqual(value['source'], 'bot_spot_closed')
        self.assertEqual(value['models'], payload()['models']); self.assertEqual(calls, [])
        now[0] = NOW-1
        value = await service.get(self.instance)
        self.assertEqual(value['status'], 'failed'); self.assertEqual(value['models'], [])

    async def test_sources_are_instance_scoped_and_stale_bot_export_is_not_recalculated(self):
        self.store()
        other_path = Path(self.directory.name)/'other.sqlite'
        db = sqlite3.connect(other_path)
        try:
            db.execute('CREATE TABLE macd_chart (slot INTEGER PRIMARY KEY,last_candle INTEGER,sampled_at INTEGER,payload TEXT)')
            db.execute('INSERT INTO macd_chart VALUES(1,?,?,?)', (LAST, NOW, json.dumps(payload(200))))
            db.commit()
        finally:
            db.close()
        other = SimpleNamespace(label='Vinay', alerts_path=str(other_path))
        service = Indicators(lambda: NOW+INTERVAL, lambda *a: self.fail('Unexpected market fetch'))
        main = await service.get(self.instance); vinay = await service.get(other)
        self.assertEqual(main['status'], 'stale'); self.assertEqual(vinay['status'], 'stale')
        self.assertEqual(main['models'][0]['points'][0]['macd'], 0)
        self.assertEqual(vinay['models'][0]['points'][0]['macd'], 200)
        self.assertEqual(vinay['instance'], 'Vinay')

    def test_empty_missing_and_symlink_are_unavailable(self):
        self.assertIsNone(bot_chart(self.instance, NOW))
        link = Path(self.directory.name)/'link.sqlite'; link.symlink_to(self.path)
        self.assertIsNone(bot_chart(SimpleNamespace(label='Main', alerts_path=str(link)), NOW))
        self.path.unlink(); self.assertIsNone(bot_chart(self.instance, NOW))

    def test_allowlist_size_nonfinite_gaps_future_and_metadata_validation(self):
        invalid = []
        v = payload(); v['private'] = 'SYNTHETIC-PRIVATE'; invalid.append(v)
        v = payload(); v['source'] = 'independent_guess'; invalid.append(v)
        v = payload(); v['models'][0]['fastPeriod'] = 300.0; invalid.append(v)
        v = payload(); v['models'][0]['points'][5]['macd'] = float('nan'); invalid.append(v)
        v = payload(); v['models'][0]['points'][5]['timestamp'] += INTERVAL; invalid.append(v)
        v = payload(); v['models'][0]['points'][5]['histogram'] += 1; invalid.append(v)
        v = payload(); v['models'].reverse(); invalid.append(v)
        v = payload(); v['models'][0]['points'].pop(); invalid.append(v)
        v = payload(); v['models'][0]['points'][5]['private'] = 'SYNTHETIC-PRIVATE'; invalid.append(v)
        for v in invalid:
            self.store(v); self.assertIsNone(bot_chart(self.instance, NOW))
        self.store(last=LAST+1); self.assertIsNone(bot_chart(self.instance, NOW))
        self.store(sampled=NOW+1); self.assertIsNone(bot_chart(self.instance, NOW))
        db = sqlite3.connect(self.path)
        try:
            db.execute('UPDATE macd_chart SET payload=?', ('x'*65537,)); db.commit()
        finally:
            db.close()
        self.assertIsNone(bot_chart(self.instance, NOW))
