"""Display-only EMA continuation from captured MACD; never changes bot evaluation."""
from .indicators import INTERVAL, SPANS
from .validation import InvalidData, number


def extend_model(model, candles, forming):
    points = model['points']
    by_time = {p['timestamp']: p for p in points}
    prices = {c['timestamp']: c['close'] for c in candles}
    anchor = points[-1]
    stamp = anchor['timestamp']
    if len(points) < 2 or stamp not in prices or stamp < candles[-1]['timestamp'] - INTERVAL:
        raise InvalidData()
    _, fast, slow, signal = next(s for s in SPANS if s[0] == model['id'])
    af, ass, ag = (2.0/(span+1) for span in (fast, slow, signal))
    # Two consecutive MACD values and their known close uniquely recover both EMAs.
    # M(t)=(1-af)*M(t-1)+(af-as)*(close(t)-slow(t-1)).
    slow_previous = prices[stamp] - (anchor['macd'] - (1-af)*points[-2]['macd'])/(af-ass)
    slow_value = (1-ass)*slow_previous + ass*prices[stamp]
    fast_value, signal_value = slow_value + anchor['macd'], anchor['signal']
    states = {stamp: (fast_value, slow_value, signal_value)}
    cursor = stamp
    while cursor > candles[0]['timestamp']:
        m = fast_value-slow_value
        signal_value = (signal_value-ag*m)/(1-ag)
        fast_value = (fast_value-af*prices[cursor])/(1-af)
        slow_value = (slow_value-ass*prices[cursor])/(1-ass)
        cursor -= INTERVAL
        states[cursor] = (fast_value, slow_value, signal_value)
    fast_value, slow_value, signal_value = states[stamp]
    for candle in candles:
        if candle['timestamp'] <= stamp:
            continue
        close = candle['close']
        fast_value = (1-af)*fast_value + af*close
        slow_value = (1-ass)*slow_value + ass*close
        signal_value = (1-ag)*signal_value + ag*(fast_value-slow_value)
        states[candle['timestamp']] = (fast_value, slow_value, signal_value)
    output = []
    for candle in candles:
        timestamp = candle['timestamp']
        f, s, sg = states[timestamp]
        m = number(f-s); sg = number(sg)
        saved = by_time.get(timestamp)
        if saved is not None:
            if abs(m-saved['macd']) > 0.00001 or abs(sg-saved['signal']) > 0.00001:
                raise InvalidData()  # Mismatching spot closes must not produce a false continuous chart.
            output.append(dict(saved))  # Preserve every captured point byte-for-byte.
        else:
            output.append(dict(timestamp=timestamp, macd=m, signal=sg, histogram=number(m-sg)))
    projected = []
    if forming is not None:
        f, s, sg = states[candles[-1]['timestamp']]
        f, s = (1-af)*f+af*forming['close'], (1-ass)*s+ass*forming['close']
        m = number(f-s); sg = number((1-ag)*sg+ag*m)
        projected = [dict(timestamp=forming['timestamp'], macd=m, signal=sg, histogram=number(m-sg))]
    return dict(model, points=output), dict(model, points=projected)


def extend_chart(value):
    value.update(chartModels=[], formingModels=[], chartMacdStatus='loading')
    if not value.get('candles') or not value.get('models'):
        if value.get('status') == 'failed' or value.get('candleStatus') == 'failed':
            value['chartMacdStatus'] = 'failed'
        return value
    try:
        pairs = [extend_model(m, value['candles'], value.get('formingCandle')) for m in value['models']]
        value['chartModels'] = [p[0] for p in pairs]
        value['formingModels'] = [p[1] for p in pairs] if value.get('formingCandle') else []
        value['chartMacdStatus'] = 'ok' if value['candleStatus'] == 'ok' else 'stale'
    except (InvalidData, KeyError, ValueError, TypeError, StopIteration, IndexError, OverflowError):
        value['chartMacdStatus'] = 'failed'
    return value
