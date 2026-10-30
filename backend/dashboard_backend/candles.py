"""Demand-only public OHLC preview; never reads wallets or recalculates bot MACD."""
import asyncio
import copy
import time

from .indicators import DISPLAY, INTERVAL, fetch_candles
from .validation import InvalidData, integer, number


def ohlc_page(rows, start, target):
    if not isinstance(rows, list) or len(rows) != DISPLAY:
        raise InvalidData()
    candles = []
    for index, row in enumerate(rows):
        if not isinstance(row, list) or len(row) != 12:
            raise InvalidData()
        stamp = integer(row[0], 1)
        if (stamp != start + index * INTERVAL or stamp % INTERVAL
                or integer(row[6]) != stamp + INTERVAL - 1 or stamp > target):
            raise InvalidData()
        values = [number(float(row[i])) if isinstance(row[i], str) else None for i in (1, 2, 3, 4)]
        if any(value is None or value <= 0 for value in values):
            raise InvalidData()
        opened, high, low, close = values
        if not low <= min(opened, close) <= max(opened, close) <= high:
            raise InvalidData()
        candles.append(dict(timestamp=stamp, open=opened, high=high, low=low, close=close))
    if candles[-1]['timestamp'] != target:
        raise InvalidData()
    return candles


class CandleSeries:
    def __init__(self, clock=lambda: int(time.time() * 1000), fetch=fetch_candles):
        self.clock, self.fetch = clock, fetch
        self.candles, self.sampled_at = [], None
        self.task, self.retry_at = None, 0
        self.failed = False

    def get(self):
        now = self.clock()
        target = now // INTERVAL * INTERVAL - INTERVAL
        fresh = bool(self.candles and self.candles[-1]['timestamp'] == target)
        if not fresh and (self.task is None or self.task.done()) and now >= self.retry_at:
            self.task = asyncio.create_task(self._refresh(target))
        refreshing = self.task is not None and not self.task.done()
        if self.sampled_at is not None and self.sampled_at > now:
            return dict(candles=[], candleStatus='loading' if refreshing else 'failed', candleSampledAt=None)
        status = ('ok' if fresh else 'stale' if self.candles else
                  'loading' if refreshing else 'failed' if self.failed else 'loading')
        return dict(candles=copy.deepcopy(self.candles), candleStatus=status,
                    candleSampledAt=self.sampled_at)

    async def _refresh(self, target):
        try:
            start = target - (DISPLAY - 1) * INTERVAL
            rows = await asyncio.to_thread(self.fetch, start, target + INTERVAL - 1)
            value = ohlc_page(rows, start, target)
            self.candles, self.sampled_at = value, self.clock()
            self.retry_at, self.failed = 0, False
        except Exception:
            self.failed = True
            self.retry_at = self.clock() + 30000
            # Keep the last complete window. No provider response/exception output.
