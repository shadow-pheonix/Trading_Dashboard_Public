"""Read one bounded bot-produced chart from its existing instance outbox, read-only."""
from pathlib import Path
import sqlite3

from .indicators import INTERVAL, DISPLAY, SPANS
from .validation import InvalidData, decode, fields, integer, number


def bot_chart(instance, now):
    path = Path(instance.alerts_path)
    try:
        if not path.is_file() or path.is_symlink() or path.stat().st_size > 128 * 1024 * 1024:
            return None
        db = sqlite3.connect(path.as_uri() + '?mode=ro', uri=True, timeout=0.25)
        try:
            db.execute('PRAGMA query_only=ON')
            row = db.execute('SELECT last_candle,sampled_at,payload FROM macd_chart WHERE slot=1').fetchone()
        finally:
            db.close()
        if row is None:
            return None
        last, sampled, raw = row
        integer(last, 1); integer(sampled, 1)
        if not isinstance(raw, str) or len(raw.encode()) > 65536 or sampled > now:
            raise InvalidData()
        value = decode(raw.encode('utf-8'))
        fields(value, ('schemaVersion', 'source', 'symbol', 'intervalMinutes', 'sampledAt', 'models'))
        for key in ('schemaVersion', 'intervalMinutes', 'sampledAt'):
            integer(value[key], 1)
        if (value['schemaVersion'] != 2 or value['source'] != 'bot_spot_closed'
                or value['symbol'] != 'BTCUSDT' or value['intervalMinutes'] != 15
                or value['sampledAt'] != sampled or not isinstance(value['models'], list)
                or len(value['models']) != 3):
            raise InvalidData()
        for model, (key, fast, slow, signal) in zip(value['models'], SPANS):
            fields(model, ('id', 'fastPeriod', 'slowPeriod', 'signalPeriod', 'points'))
            for field in ('fastPeriod', 'slowPeriod', 'signalPeriod'):
                integer(model[field], 1)
            if ((model['id'], model['fastPeriod'], model['slowPeriod'], model['signalPeriod']) != (key, fast, slow, signal)
                    or not isinstance(model['points'], list) or len(model['points']) != DISPLAY):
                raise InvalidData()
            previous = None
            for point in model['points']:
                fields(point, ('timestamp', 'macd', 'signal', 'histogram'))
                stamp = integer(point['timestamp'], 1)
                m, sg, hist = (number(point[k]) for k in ('macd', 'signal', 'histogram'))
                if (stamp % INTERVAL or stamp + INTERVAL > sampled
                        or previous is not None and stamp != previous + INTERVAL
                        or abs(hist - (m - sg)) >= 0.000001):
                    raise InvalidData()
                previous = stamp
            if previous != last:
                raise InvalidData()
        value.update(instance=instance.label, generatedAt=now,
                     status='ok' if last == now // INTERVAL * INTERVAL - INTERVAL else 'stale')
        return value
    except (OSError, sqlite3.Error, InvalidData, ValueError, TypeError):
        return None  # Never expose paths, raw database payloads or exceptions.
