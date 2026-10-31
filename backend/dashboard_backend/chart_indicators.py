"""Display-only EMA continuation from captured MACD; never changes bot evaluation."""
from statistics import median

from .indicators import INTERVAL, SPANS
from .validation import InvalidData, number


def captured_states(model, candles):
    """Recover the bot's EMA states without replacing its captured input closes.

    A live minute sample can differ from the finalized public kline. MACD deltas
    recover those effective closes up to one shared price offset. A consensus of
    matching public closes anchors that offset, rather than trusting the last bar.
    """
    points = model['points']
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
    effective_closes = {}
    for previous, current in reversed(list(zip(points, points[1:]))):
        if current['timestamp'] - previous['timestamp'] != INTERVAL:
            raise InvalidData()
        if abs(current['signal'] - ((1-ag)*previous['signal'] + ag*current['macd'])) > 0.00001:
            raise InvalidData()
        delta = (current['macd'] - (1-af)*previous['macd'])/(af-ass)
        slow_previous = slow_value - ass*delta
        effective_closes[current['timestamp']] = slow_previous + delta
        slow_value = slow_previous
        states[previous['timestamp']] = (slow_value + previous['macd'], slow_value, previous['signal'])
    offset = median(close - prices[t] for t, close in effective_closes.items())
    matches = sum(abs(close - offset - prices[t]) <= 0.0001 for t, close in effective_closes.items())
    if matches*2 <= len(effective_closes):  # A strict matching majority makes the median anchor identifiable.
        raise InvalidData()
    states = {t: (number(f-offset), number(s-offset), number(sg)) for t, (f, s, sg) in states.items()}
    effective_closes = {t: number(close-offset) for t, close in effective_closes.items()}
    if any(close <= 0 for close in effective_closes.values()):
        raise InvalidData()
    return states, effective_closes


def extend_model(model, candles, forming):
    states, effective_closes = captured_states(model, candles)
    by_time = {p['timestamp']: p for p in model['points']}
    prices = {c['timestamp']: c['close'] for c in candles}
    _, fast, slow, signal = next(s for s in SPANS if s[0] == model['id'])
    af, ass, ag = (2.0/(span+1) for span in (fast, slow, signal))
    cursor = min(states)
    fast_value, slow_value, signal_value = states[cursor]
    while cursor > candles[0]['timestamp']:
        m = fast_value-slow_value
        signal_value = (signal_value-ag*m)/(1-ag)
        fast_value = (fast_value-af*prices[cursor])/(1-af)
        slow_value = (slow_value-ass*prices[cursor])/(1-ass)
        cursor -= INTERVAL
        states[cursor] = (fast_value, slow_value, signal_value)
    stamp = model['points'][-1]['timestamp']
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
            output.append(dict(saved))  # Preserve every captured point byte-for-byte.
        else:
            output.append(dict(timestamp=timestamp, macd=m, signal=sg, histogram=number(m-sg)))
    projected = []
    if forming is not None:
        f, s, sg = states[candles[-1]['timestamp']]
        f, s = (1-af)*f+af*forming['close'], (1-ass)*s+ass*forming['close']
        m = number(f-s); sg = number((1-ag)*sg+ag*m)
        projected = [dict(timestamp=forming['timestamp'], macd=m, signal=sg, histogram=number(m-sg))]
    return dict(model, points=output), dict(model, points=projected), effective_closes


def extend_chart(value):
    value.update(chartModels=[], formingModels=[], chartMacdStatus='loading')
    if not value.get('candles') or not value.get('models'):
        if value.get('status') == 'failed' or value.get('candleStatus') == 'failed':
            value['chartMacdStatus'] = 'failed'
        return value
    try:
        if [m['id'] for m in value['models']] != [s[0] for s in SPANS]:
            raise InvalidData()
        pairs = [extend_model(m, value['candles'], value.get('formingCandle')) for m in value['models']]
        # All three spans must independently recover the same bot input prices.
        baseline = pairs[0][2]
        if any(set(p[2]) != set(baseline) or any(abs(p[2][t]-close) > 0.0001
                for t, close in baseline.items()) for p in pairs[1:]):
            raise InvalidData()
        value['chartModels'] = [p[0] for p in pairs]
        value['formingModels'] = [p[1] for p in pairs] if value.get('formingCandle') else []
        value['chartMacdStatus'] = 'ok' if value['candleStatus'] == 'ok' else 'stale'
    except (InvalidData, KeyError, ValueError, TypeError, StopIteration, IndexError, OverflowError):
        value['chartMacdStatus'] = 'failed'
    return value
