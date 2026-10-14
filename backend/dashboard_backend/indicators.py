"""Public spot MACD charts; no bot imports, credentials, balances or idle polling."""
import asyncio
import copy
import json
import time
from urllib.parse import urlencode
from urllib.request import urlopen
from urllib.error import HTTPError

from .validation import InvalidData, integer, number

INTERVAL = 900000
HISTORY = 24999  # Bot's 25k spot buffer, excluding the open partial candle.
DISPLAY = 96
SPANS = (("M1", 300, 900, 300), ("M2", 450, 1350, 450), ("M3", 600, 1800, 600))


def fetch_candles(start, end):
    query = urlencode({"symbol": "BTCUSDT", "interval": "15m", "startTime": start,
                       "endTime": end, "limit": 1000})
    # Both are public spot klines. No keys, headers or bot state enter this request.
    for host in ("api.binance.com", "data-api.binance.vision"):
        try:
            with urlopen(f"https://{host}/api/v3/klines?{query}", timeout=4) as response:
                if response.status != 200:
                    raise InvalidData()
                raw = response.read(1024 * 1024 + 1)
            if len(raw) > 1024 * 1024:
                raise InvalidData()
            rows = json.loads(raw)
            candle_page(rows, start, end // INTERVAL * INTERVAL)
            return rows
        except HTTPError as error:
            # Both hosts share Binance's limits: do not evade throttling/IP bans.
            code = error.code
            error.close()
            if code in (418, 429):
                break
        except (OSError, ValueError, InvalidData):
            pass
    raise InvalidData() from None


def candle_page(rows, start, end):
    if not isinstance(rows, list) or not 1 <= len(rows) <= 1000:
        raise InvalidData()
    output = []
    for row in rows:
        if not isinstance(row, list) or len(row) != 12:
            raise InvalidData()
        stamp = integer(row[0], 1)
        close = number(float(row[4])) if isinstance(row[4], str) else None
        if (stamp % INTERVAL or not start <= stamp <= end or close is None or close <= 0
                or integer(row[6]) != stamp + INTERVAL - 1
                or output and stamp != output[-1][0] + INTERVAL):
            raise InvalidData()
        output.append((stamp, close))
    if output[0][0] != start:
        raise InvalidData()
    return output


def macd_models(candles):
    if len(candles) != HISTORY:
        raise InvalidData()
    models = []
    for model, fast, slow, signal in SPANS:
        # Same seeded, recursive EMA as pandas ewm(span=..., adjust=False).
        ema_fast = ema_slow = candles[0][1]
        ema_signal = 0.0
        af, ass, ag = (2.0 / (span + 1) for span in (fast, slow, signal))
        points = []
        for index, (stamp, close) in enumerate(candles):
            if index:
                ema_fast = (1 - af) * ema_fast + af * close
                ema_slow = (1 - ass) * ema_slow + ass * close
            macd = ema_fast - ema_slow
            if index:
                ema_signal = (1 - ag) * ema_signal + ag * macd
            if index >= HISTORY - DISPLAY:
                points.append({"timestamp": stamp, "macd": number(macd),
                               "signal": number(ema_signal), "histogram": number(macd - ema_signal)})
        models.append({"id": model, "fastPeriod": fast, "slowPeriod": slow,
                       "signalPeriod": signal, "points": points})
    return models


class Indicators:
    def __init__(self, clock=lambda: int(time.time() * 1000), fetch=fetch_candles):
        self.clock, self.fetch = clock, fetch
        self.candles = []
        self.pending_candles = []  # Validated seed progress, never exposed as a partial chart.
        self.models = []
        self.task = None
        self.sampled_at = None
        self.retry_at = 0
        self.failed = False
        self.bot_charts = {}

    async def get(self, instance):
        now = self.clock()
        if getattr(instance, "alerts_path", ""):
            # Import lazily to keep public candle constants in one canonical module.
            from .bot_indicators import bot_chart
            value = await asyncio.to_thread(bot_chart, instance, now)
            key = instance.alerts_path
            if value is not None:
                self.bot_charts[key] = value
                return copy.deepcopy(value)
            retained = self.bot_charts.get(key)
            if retained is not None:
                # Once this instance exports bot charts, never silently replace them
                # with an independently calculated public-market chart after failure.
                if retained["sampledAt"] <= now:
                    value = copy.deepcopy(retained)
                    value.update(instance=instance.label, generatedAt=now, status="stale")
                    return value
                return {"schemaVersion": 2, "instance": instance.label, "generatedAt": now,
                        "source": "bot_spot_closed", "symbol": "BTCUSDT", "intervalMinutes": 15,
                        "status": "failed", "sampledAt": None, "models": []}
        target = now // INTERVAL * INTERVAL - INTERVAL
        fresh = bool(self.candles and self.candles[-1][0] == target)
        if not fresh and (self.task is None or self.task.done()) and now >= self.retry_at:
            self.task = asyncio.create_task(self._refresh(target))
        refreshing = self.task is not None and not self.task.done()
        status = ("ok" if fresh else "stale" if self.models else
                  "loading" if refreshing else "failed" if self.failed else "loading")
        return {"schemaVersion": 2, "instance": instance.label, "generatedAt": now,
                "source": "binance_spot_closed", "symbol": "BTCUSDT", "intervalMinutes": 15,
                "status": status, "sampledAt": self.sampled_at, "models": copy.deepcopy(self.models)}

    async def _refresh(self, target):
        try:
            # Keep only the needed closed window; publish atomically after complete gap checks.
            earliest = target - (HISTORY - 1) * INTERVAL
            previous = self.pending_candles or self.candles
            candles = [c for c in previous if earliest <= c[0] <= target]
            start = candles[-1][0] + INTERVAL if candles else earliest
            while start <= target:
                rows = await asyncio.to_thread(self.fetch, start, target + INTERVAL - 1)
                page = candle_page(rows, start, target)
                candles.extend(page)
                self.pending_candles = candles
                start = page[-1][0] + INTERVAL
            models = macd_models(candles)
            self.candles, self.models = candles, models
            self.pending_candles = []
            self.sampled_at = self.clock()
            self.failed = False
            self.retry_at = 0
        except Exception:
            # Do not emit provider responses/exceptions or replace last good data with zeros.
            self.failed = True
            self.retry_at = self.clock() + 30000
