import asyncio
import copy
import math
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock

from dashboard_backend.candles import LiveCandleSeries
from dashboard_backend.chart_indicators import extend_chart
from dashboard_backend.indicators import Indicators, INTERVAL, SPANS, LIVE_CHART_QUERY, chart_time
import test_backend as fixtures

NOW = 1800000000000
CURRENT = NOW // INTERVAL * INTERVAL


def rows(start):
    return [[start+i*INTERVAL,'80000','80100','79900','80050','1',
             start+(i+1)*INTERVAL-1,'1',1,'1','1','0'] for i in range(256)]


def reference():
    # Independent full forward EMA reference, including its final partial sample.
    closes = [80000 + 300*math.sin(i/23) + i/20 for i in range(3000)]
    stamps = [CURRENT-(len(closes)-1-i)*INTERVAL for i in range(len(closes))]
    models = []
    for key, fast, slow, sig in SPANS:
        f = s = closes[0]; signal = 0.; points = []
        for i,(stamp,close) in enumerate(zip(stamps,closes)):
            if i:
                f += (close-f)*2/(fast+1); s += (close-s)*2/(slow+1)
            m = f-s
            if i: signal += (m-signal)*2/(sig+1)
            points.append(dict(timestamp=stamp,macd=m,signal=signal,histogram=m-signal))
        models.append(dict(id=key,fastPeriod=fast,slowPeriod=slow,signalPeriod=sig,points=points))
    candles = [dict(timestamp=t,open=c,high=c+1,low=c-1,close=c) for t,c in zip(stamps[-256:],closes[-256:])]
    value = dict(models=[dict(m,points=copy.deepcopy(m['points'][-97:-1])) for m in models],
                 candles=candles[:-1],formingCandle=candles[-1],candleStatus='ok',status='ok')
    return value,models


class LiveChartsCase(unittest.IsolatedAsyncioTestCase):
    async def test_shared_256_window_updates_partial_without_idle_polling(self):
        now,calls=[NOW],[]
        def fetch(start,end):calls.append((start,end));return rows(start)
        service=LiveCandleSeries(lambda:now[0],fetch)
        self.assertEqual(calls,[])
        service.get();task=service.task;service.get();self.assertIs(task,service.task)
        await task
        value=service.get()
        self.assertEqual(len(value['candles']),255)
        self.assertEqual(value['formingCandle']['timestamp'],CURRENT)
        self.assertEqual(calls,[(CURRENT-255*INTERVAL,CURRENT+INTERVAL-1)])
        value['formingCandle']['close']=1
        self.assertEqual(service.get()['formingCandle']['close'],80050)
        now[0]+=5000;await asyncio.sleep(0);self.assertEqual(len(calls),1)
        self.assertIsNone(service.get()['formingCandle']);await service.task
        self.assertEqual(len(calls),2)
        now[0]+=INTERVAL;service.get();await service.task
        self.assertEqual(service.get()['candles'][-1]['timestamp'],CURRENT)

    async def test_failure_rollover_and_clock_rollback_never_finalize_partial_samples(self):
        now=[NOW];service=LiveCandleSeries(lambda:now[0],lambda start,end:rows(start))
        service.get();await service.task
        closed=service.get()['candles']
        now[0]=CURRENT+INTERVAL
        service.fetch=lambda *_:[]
        self.assertIsNone(service.get()['formingCandle']);await service.task
        value=service.get();self.assertEqual(value['candles'],closed)
        self.assertEqual(value['candleStatus'],'stale');self.assertIsNone(value['formingCandle'])
        task=service.task;now[0]+=5000;service.get();self.assertIs(task,service.task)
        now[0]=NOW-1
        self.assertEqual(service.get()['candles'],[])

    async def test_slow_response_crossing_boundary_is_not_published(self):
        now=[CURRENT+INTERVAL-1]
        def fetch(start,end):now[0]+=2;return rows(start)
        service=LiveCandleSeries(lambda:now[0],fetch)
        service.get();await service.task
        self.assertEqual(service.get()['candles'],[])
        self.assertIsNone(service.get()['formingCandle'])

    async def test_chart_awaits_shared_live_refresh_and_cancellation_does_not_cancel_it(self):
        gate=asyncio.Event()
        service=Indicators(lambda:NOW,candle_fetch=lambda start,end:rows(start))
        service.get=AsyncMock(return_value=dict(status='loading',models=[]))
        original=service.live_candles._refresh
        async def paused(current):await gate.wait();await original(current)
        service.live_candles._refresh=paused
        request=asyncio.create_task(service.chart(SimpleNamespace(label='Main'),live=True))
        await asyncio.sleep(0);await asyncio.sleep(0)
        task=service.live_candles.task;request.cancel()
        with self.assertRaises(asyncio.CancelledError):await request
        self.assertFalse(task.cancelled());gate.set();await task
        value=await service.chart(SimpleNamespace(label='Main'),live=True)
        self.assertEqual(len(value['candles']),255)
        self.assertEqual(value['chartMacdStatus'],'loading')
        self.assertIsNotNone(value['formingCandle'])

    def test_extended_and_live_macd_match_full_forward_ema_and_preserve_captured_points(self):
        value,expected=reference();original=copy.deepcopy(value['models'])
        extend_chart(value)
        self.assertEqual(value['chartMacdStatus'],'ok')
        self.assertEqual(value['models'],original)
        for actual,truth in zip(value['chartModels'],expected):
            self.assertEqual(len(actual['points']),255)
            self.assertEqual(actual['points'][-96:],original[expected.index(truth)]['points'])
            for point,correct in zip(actual['points'],truth['points'][-256:-1]):
                for field in ('macd','signal','histogram'):self.assertAlmostEqual(point[field],correct[field],delta=0.00001)
        for actual,truth in zip(value['formingModels'],expected):
            for field in ('macd','signal','histogram'):self.assertAlmostEqual(actual['points'][0][field],truth['points'][-1][field],delta=0.00001)
        # Repeated live-price updates must start from the same closed seed, not compound a new EMA step per tick.
        repeated=copy.deepcopy(value);extend_chart(repeated)
        self.assertEqual(repeated['formingModels'],value['formingModels'])
        changed=copy.deepcopy(value);changed['formingCandle']['close']+=100;extend_chart(changed)
        for first,updated,(_,fast,slow,sig) in zip(value['formingModels'],changed['formingModels'],SPANS):
            delta=(2/(fast+1)-2/(slow+1))*100
            self.assertAlmostEqual(updated['points'][0]['macd']-first['points'][0]['macd'],delta,delta=0.00001)
            self.assertAlmostEqual(updated['points'][0]['signal']-first['points'][0]['signal'],delta*2/(sig+1),delta=0.00001)

    def test_bot_evaluation_delay_bridges_one_closed_bar_and_rejects_mismatched_sources(self):
        value,truth=reference()
        value['models']=[dict(m,points=copy.deepcopy(m['points'][-98:-2])) for m in truth]
        extend_chart(value);self.assertEqual(value['chartMacdStatus'],'ok')
        for m,t in zip(value['chartModels'],truth):self.assertAlmostEqual(m['points'][-1]['macd'],t['points'][-2]['macd'],delta=0.00001)
        value,_=reference();value['candles'][-5]['close']+=100
        extend_chart(value);self.assertEqual(value['chartMacdStatus'],'failed')
        self.assertEqual(value['chartModels'],[]);self.assertEqual(value['formingModels'],[])
        value,_=reference();value['models'][0]['points'][-1]['timestamp']-=2*INTERVAL
        extend_chart(value);self.assertEqual(value['chartMacdStatus'],'failed')

    def test_final_timestamp_recheck_removes_expired_partial_without_corrupting_loading_metadata(self):
        value,_=reference();value.update(generatedAt=NOW,candleSampledAt=NOW)
        extend_chart(value);chart_time(value,CURRENT+INTERVAL)
        self.assertIsNone(value['formingCandle']);self.assertEqual(value['formingModels'],[])
        self.assertEqual(value['chartMacdStatus'],'stale')
        value,_=reference();value.update(models=[],candleSampledAt=NOW,status='loading')
        extend_chart(value);chart_time(value,CURRENT+INTERVAL)
        self.assertEqual(value['chartMacdStatus'],'loading')


class LiveChartApiCase(unittest.TestCase):
    state=fixtures.BackendCase.state
    history=fixtures.BackendCase.history
    request=fixtures.BackendCase.request
    scope=fixtures.BackendCase.scope

    def setUp(self):fixtures.BackendCase.setUp(self)

    def test_new_window_is_authenticated_exact_opt_in_with_legacy_compatibility(self):
        self.app.indicators.chart=AsyncMock(return_value={'status':'loading','models':[]})
        query=LIVE_CHART_QUERY.decode()
        self.assertEqual(self.request('/v2/dashboard',query,token=None)[0],401)
        self.app.indicators.chart.assert_not_called()
        self.assertEqual(self.request('/v2/dashboard',query)[0],200)
        self.app.indicators.chart.assert_awaited_once_with(self.main,live=True)
        self.assertEqual(self.request('/v2/dashboard','view=chart')[0],200)
        self.assertEqual(self.prices.acquisitions,0)
        self.assertEqual(self.app.balances.tasks,{})
        for bad in ('view=chart&history=256','view=chart&history=257&live=1',query+'&mode=real',query+'&live=1'):
            self.assertEqual(self.request('/v2/dashboard',bad)[0],400)
