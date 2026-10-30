import asyncio
import copy
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock

from dashboard_backend.candles import CandleSeries, ohlc_page
from dashboard_backend.indicators import Indicators, INTERVAL, DISPLAY
from dashboard_backend.validation import InvalidData

NOW = 1800000000000
TARGET = NOW // INTERVAL * INTERVAL - INTERVAL
START = TARGET - (DISPLAY - 1) * INTERVAL


def rows(start=START):
    return [[start + i*INTERVAL, '80000', '80100', '79900', '80050', '1',
             start + (i+1)*INTERVAL - 1, '1', 1, '1', '1', '0'] for i in range(DISPLAY)]


class CandleCase(unittest.IsolatedAsyncioTestCase):
    async def test_lazy_one_request_shared_window_and_next_closed_boundary(self):
        now, calls = [NOW], []
        def fetch(start, end):
            calls.append((start, end)); return rows(start)
        service = CandleSeries(lambda: now[0], fetch)
        self.assertIsNone(service.task); self.assertEqual(calls, [])
        self.assertEqual(service.get()['candleStatus'], 'loading')
        task = service.task; service.get(); self.assertIs(task, service.task)
        await task
        value = service.get()
        self.assertEqual(value['candleStatus'], 'ok')
        self.assertEqual(len(value['candles']), 96)
        self.assertEqual(calls, [(START, TARGET + INTERVAL - 1)])
        value['candles'][0]['open'] = 1
        self.assertEqual(service.get()['candles'][0]['open'], 80000)
        now[0] += INTERVAL; await asyncio.sleep(0)
        self.assertEqual(len(calls), 1)  # No idle collector.
        self.assertEqual(service.get()['candleStatus'], 'stale')
        await service.task
        self.assertEqual(service.get()['candles'][-1]['timestamp'], TARGET + INTERVAL)
        self.assertEqual(len(calls), 2)

    async def test_bad_update_retains_complete_window_and_failure_has_cooldown(self):
        now = [NOW]
        service = CandleSeries(lambda: now[0], lambda start, end: rows(start))
        service.get(); await service.task
        previous = service.get()['candles']
        now[0] += INTERVAL
        service.fetch = lambda *_: rows()[:-1]
        service.get(); await service.task
        self.assertEqual(service.get()['candles'], previous)
        self.assertEqual(service.get()['candleStatus'], 'stale')
        task = service.task; service.get(); self.assertIs(service.task, task)
        now[0] += 30000; service.get(); self.assertIsNot(service.task, task)
        await service.task
        empty = CandleSeries(lambda: NOW, lambda *_: [])
        empty.get(); await empty.task
        self.assertEqual(empty.get(), dict(candles=[], candleStatus='failed', candleSampledAt=None))

    async def test_chart_adds_prices_without_replacing_instance_bot_values(self):
        service = Indicators(lambda: NOW, candle_fetch=lambda start, end: rows(start))
        bot = dict(source='bot_spot_closed', models=[{'id': 'M1', 'points': [{'macd': 123}]}])
        service.get = AsyncMock(return_value=copy.deepcopy(bot))
        instance = SimpleNamespace(label='Main')
        await service.chart(instance); await service.price_candles.task
        value = await service.chart(instance)
        self.assertEqual(value['models'], bot['models'])
        self.assertEqual(value['source'], 'bot_spot_closed')
        self.assertEqual(len(value['candles']), 96)
        self.assertIsNone(service.task)  # No independent EMA seed.

    async def test_clock_boundary_rechecks_both_series_and_future_samples_are_withheld(self):
        service = Indicators(lambda: NOW+INTERVAL)
        service.get = AsyncMock(return_value=dict(status='ok', generatedAt=NOW,
            models=[{'points': [{'timestamp': TARGET}]}]))
        service.price_candles = SimpleNamespace(get=lambda: dict(candleStatus='ok',
            candleSampledAt=NOW, candles=[{'timestamp': TARGET}]))
        value = await service.chart(SimpleNamespace(label='Main'))
        self.assertEqual(value['generatedAt'], NOW+INTERVAL)
        self.assertEqual((value['status'], value['candleStatus']), ('stale', 'stale'))
        price = CandleSeries(lambda: NOW, lambda start, end: rows(start))
        price.sampled_at = NOW+1; price.candles = ohlc_page(rows(), START, TARGET)
        self.assertEqual(price.get()['candles'], [])
        self.assertIsNone(price.task)

    def test_strict_ohlc_order_prices_closed_times_and_complete_window(self):
        self.assertEqual(ohlc_page(rows(), START, TARGET)[-1]['close'], 80050)
        invalid = [[], rows()[:-1], rows() + rows()[:1]]
        for field, bad in [(0, START+1), (1, '0'), (1, '80200'), (2, '79999'),
                           (3, '80001'), (4, 'NaN'), (4, 'Infinity'), (4, True),
                           (6, START+INTERVAL-2)]:
            value = rows(); value[0][field] = bad; invalid.append(value)
        value = rows(); value[1][0] = START; invalid.append(value)
        for value in invalid:
            with self.assertRaises((InvalidData, ValueError)):
                ohlc_page(value, START, TARGET)
