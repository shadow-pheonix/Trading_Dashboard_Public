import asyncio
import math
import unittest
from types import SimpleNamespace
from urllib.error import HTTPError
from unittest.mock import patch, Mock

from dashboard_backend.indicators import Indicators, HISTORY, INTERVAL, candle_page, macd_models, fetch_candles
from dashboard_backend.validation import InvalidData

NOW = 1800000000000
TARGET = NOW // INTERVAL * INTERVAL - INTERVAL
START = TARGET - (HISTORY - 1) * INTERVAL


def row(stamp, close):
    return [stamp, '1', '2', '1', str(close), '1', stamp + INTERVAL - 1, '1', 1, '1', '1', '0']


class IndicatorCase(unittest.IsolatedAsyncioTestCase):
    async def test_lazy_shared_seed_cache_and_incremental_updates(self):
        calls = []
        now = [NOW]
        def fetch(start, end):
            calls.append((start, end))
            return [row(t, 80000 + (t // INTERVAL % 50))
                    for t in range(start, min(end // INTERVAL * INTERVAL, start + 999 * INTERVAL) + 1, INTERVAL)]
        service = Indicators(lambda: now[0], fetch)
        self.assertEqual(calls, [])
        main, vinay = SimpleNamespace(label='Main'), SimpleNamespace(label='Vinay')
        first = await service.get(main)
        self.assertEqual(first['status'], 'loading')
        task = service.task
        await service.get(vinay)
        self.assertIs(service.task, task)
        await task
        self.assertEqual(len(calls), 25)
        value = await service.get(vinay)
        self.assertEqual(value['instance'], 'Vinay')
        self.assertEqual(value['status'], 'ok')
        self.assertEqual([m['id'] for m in value['models']], ['M1', 'M2', 'M3'])
        self.assertTrue(all(len(m['points']) == 96 for m in value['models']))
        value['models'][0]['points'].clear()
        self.assertEqual(len((await service.get(main))['models'][0]['points']), 96)
        self.assertEqual(len(calls), 25)
        # No timer or automatic requests while idle, including across a new boundary.
        now[0] += INTERVAL
        await asyncio.sleep(0)
        self.assertEqual(len(calls), 25)
        stale = await service.get(main)
        self.assertEqual(stale['status'], 'stale')
        await service.task
        self.assertEqual(len(calls), 26)
        self.assertEqual(len(service.candles), HISTORY)
        self.assertEqual(service.candles[-1][0], TARGET + INTERVAL)

    async def test_failure_gap_and_cooldown_never_publish_partial_chart(self):
        now = [NOW]
        calls = []
        def fail(start, end):
            calls.append(start)
            raise RuntimeError('provider response must stay private')
        service = Indicators(lambda: now[0], fail)
        instance = SimpleNamespace(label='Main')
        await service.get(instance); await service.task
        value = await service.get(instance)
        self.assertEqual(value['status'], 'failed')
        self.assertEqual(value['models'], [])
        self.assertEqual(len(calls), 1)
        now[0] += 30000
        service.fetch = lambda start, end: [row(start + INTERVAL, 80000)]
        await service.get(instance); await service.task
        self.assertEqual(service.models, [])
        service.candles = [(START + i * INTERVAL, 80000.) for i in range(HISTORY)]
        service.models = macd_models(service.candles)
        service.sampled_at = now[0]
        now[0] += INTERVAL
        await service.get(instance); await service.task
        stale = await service.get(instance)
        self.assertEqual(stale['status'], 'stale')
        self.assertEqual(stale['models'][0]['points'][-1]['timestamp'], TARGET)

    async def test_seed_failure_resumes_validated_pages_without_exposing_partial_data(self):
        now = [NOW]
        calls = []
        broken = [True]
        def fetch(start, end):
            calls.append(start)
            if broken[0] and start == START + 1000 * INTERVAL:
                raise OSError('synthetic timeout')
            return [row(t, 80000) for t in range(start, min(TARGET, start + 999 * INTERVAL) + 1, INTERVAL)]
        service = Indicators(lambda: now[0], fetch)
        instance = SimpleNamespace(label='Main')
        await service.get(instance); await service.task
        failed = await service.get(instance)
        self.assertEqual(failed['status'], 'failed')
        self.assertEqual(failed['models'], [])
        self.assertEqual(service.candles, [])
        self.assertEqual(len(service.pending_candles), 1000)
        now[0] += 30000; broken[0] = False
        retry = await service.get(instance)
        self.assertEqual(retry['status'], 'loading')
        self.assertEqual(retry['models'], [])
        await service.task
        self.assertEqual((await service.get(instance))['status'], 'ok')
        self.assertEqual(calls.count(START), 1)
        self.assertEqual(calls[2], START + 1000 * INTERVAL)
        self.assertEqual(service.pending_candles, [])
        self.assertEqual(len(service.candles), HISTORY)
        self.assertEqual(len(calls), 26)  # 25 valid pages plus the failed request.

    async def test_seed_retry_trims_window_at_next_candle_boundary(self):
        now = [NOW]
        first = [True]
        calls = []
        def fetch(start, end):
            calls.append(start)
            if first[0] and len(calls) == 2:
                raise OSError()
            return [row(t, 80000) for t in range(start, min(end // INTERVAL * INTERVAL, start + 999 * INTERVAL) + 1, INTERVAL)]
        service = Indicators(lambda: now[0], fetch)
        instance = SimpleNamespace(label='Main')
        await service.get(instance); await service.task
        first[0] = False; now[0] += INTERVAL
        await service.get(instance); await service.task
        self.assertEqual((await service.get(instance))['status'], 'ok')
        self.assertEqual(service.candles[0][0], START + INTERVAL)
        self.assertEqual(service.candles[-1][0], TARGET + INTERVAL)
        self.assertEqual(len(service.candles), HISTORY)
        self.assertEqual(calls.count(START), 1)

    def test_public_spot_primary_and_validated_fallback_without_credentials(self):
        valid = [row(TARGET, 80000)]
        def response(raw):
            r = Mock(); r.status = 200; r.read.return_value = raw
            r.__enter__ = Mock(return_value=r); r.__exit__ = Mock(return_value=False)
            return r
        import json
        with patch('dashboard_backend.indicators.urlopen', return_value=response(json.dumps(valid).encode())) as open_url:
            self.assertEqual(fetch_candles(TARGET, TARGET + INTERVAL - 1), valid)
            self.assertEqual(open_url.call_count, 1)
            url = open_url.call_args.args[0]
            self.assertTrue(url.startswith('https://api.binance.com/api/v3/klines?'))
            self.assertNotIn('key', url.lower())
            self.assertEqual(open_url.call_args.kwargs, {'timeout': 4})
        for failure in (OSError(), response(b'bad JSON'), response(b'[]'), response(b'x' * (1024 * 1024 + 1))):
            with patch('dashboard_backend.indicators.urlopen', side_effect=[failure, response(json.dumps(valid).encode())]) as open_url:
                self.assertEqual(fetch_candles(TARGET, TARGET + INTERVAL - 1), valid)
                self.assertEqual(open_url.call_count, 2)
                self.assertTrue(open_url.call_args.args[0].startswith('https://data-api.binance.vision/'))
        with patch('dashboard_backend.indicators.urlopen', side_effect=OSError('private provider body')) as open_url:
            with self.assertRaises(InvalidData) as error:
                fetch_candles(TARGET, TARGET + INTERVAL - 1)
            self.assertEqual(open_url.call_count, 2)
            self.assertNotIn('private', str(error.exception))

    def test_rate_limits_and_ip_bans_do_not_switch_hosts(self):
        for code in (418, 429):
            with patch('dashboard_backend.indicators.urlopen', side_effect=HTTPError('public', code, 'private', None, None)) as open_url:
                with self.assertRaises(InvalidData):
                    fetch_candles(TARGET, TARGET + INTERVAL - 1)
                self.assertEqual(open_url.call_count, 1)

    def test_strict_candle_boundaries_gap_duplicates_nonfinite_and_partial(self):
        valid = row(TARGET, 80000)
        self.assertEqual(candle_page([valid], TARGET, TARGET), [(TARGET, 80000.)])
        invalid = [[], [valid, valid], [valid, row(TARGET + 2 * INTERVAL, 80000)],
                   [row(TARGET + INTERVAL, 80000)], [row(TARGET, math.inf)], [row(TARGET, 0)]]
        partial = valid.copy(); partial[6] -= 1
        invalid.append([partial])
        for rows in invalid:
            with self.assertRaises((InvalidData, ValueError)):
                candle_page(rows, TARGET, TARGET + 2 * INTERVAL)

    def test_macd_and_signal_against_independent_weighted_ema(self):
        closes = [80000 + math.sin(i / 4) * 100 + i for i in range(40)]
        def weighted(values, span):
            a = 2 / (span + 1)
            return [(1-a)**i * values[0] + sum(a*(1-a)**(i-j)*values[j] for j in range(1, i+1))
                    for i in range(len(values))]
        with patch('dashboard_backend.indicators.HISTORY', len(closes)):
            models = macd_models([(START + i * INTERVAL, c) for i, c in enumerate(closes)])
        for model in models:
            fast = weighted(closes, model['fastPeriod']); slow = weighted(closes, model['slowPeriod'])
            macd = [a-b for a, b in zip(fast, slow)]
            signal = weighted(macd, model['signalPeriod'])
            for i, point in enumerate(model['points']):
                self.assertAlmostEqual(point['macd'], macd[i], places=7)
                self.assertAlmostEqual(point['signal'], signal[i], places=7)
                self.assertAlmostEqual(point['histogram'], macd[i] - signal[i], places=7)
