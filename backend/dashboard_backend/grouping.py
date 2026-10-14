"""Model projections. A/B matching is mutual and unique, never greedy."""
from bisect import bisect_left, bisect_right
from collections import defaultdict
import hashlib

from .validation import InvalidData, snapshot, fields, number, integer, records

MODELS = ('M1', 'M2', 'M3')
WINDOW = 60000


def wallet(account):
    # Old configurations have unique edge model names. Explicit config always wins.
    if account.wallet:
        return account.wallet
    if account.edge_model:
        return 'B' if account.edge_model.endswith('-B') else 'A'
    return {'a': 'A', 'b': 'B'}.get(account.id, '')


def paired_trades(rows, accounts):
    mapping = {a.id: a for a in accounts}
    buckets = defaultdict(lambda: {'A': [], 'B': []})
    for index, row in enumerate(rows):
        account = mapping[row['accountId']]
        side = wallet(account)
        if row['openedAt'] is not None and side:
            key = (account.strategy, row['symbol'], row['side'], row['source'])
            buckets[key][side].append(index)
    candidates = defaultdict(list)
    for sides in buckets.values():
        ordered = sorted(sides['B'], key=lambda i: rows[i]['openedAt'])
        times = [rows[i]['openedAt'] for i in ordered]
        for a in sides['A']:
            opened = rows[a]['openedAt']
            for b in ordered[bisect_left(times, opened - WINDOW):bisect_right(times, opened + WINDOW)]:
                if abs(rows[a]['closedAt'] - rows[b]['closedAt']) <= WINDOW:
                    # Two candidates are enough to prove ambiguity; bound memory for dense histories.
                    if len(candidates[a]) < 2:
                        candidates[a].append(b)
                    if len(candidates[b]) < 2:
                        candidates[b].append(a)
    result, consumed = [], set()
    for index, row in enumerate(rows):
        if index in consumed:
            continue
        match = candidates[index]
        pair = len(match) == 1 and len(candidates[match[0]]) == 1
        item = dict(row)
        item['accountId'] = item['strategy'] = mapping[row['accountId']].strategy
        if pair:
            other = rows[match[0]]
            consumed.add(match[0])
            item.update(id=hashlib.sha256(':'.join(sorted((row['id'], other['id']))).encode()).hexdigest()[:24],
                        openedAt=min(row['openedAt'], other['openedAt']),
                        closedAt=max(row['closedAt'], other['closedAt']),
                        netPnl=row['netPnl'] + other['netPnl'], fees=row['fees'] + other['fees'],
                        funding=None, _equity=None)
            # There is no size in the legacy CSV contract; never invent a weighted price.
            item['entryPrice'] = (row['entryPrice'] + other['entryPrice']) / 2
            item['exitPrice'] = (row['exitPrice'] + other['exitPrice']) / 2
            if '_chart' in row and '_chart' in other:
                first = min((row, other), key=lambda r: (r['closedAt'], r['id']))
                item['_chart'] = dict(first['_chart'])
                sizes = [r['_chart']['position'] for r in (row, other)]
                item['_chart']['position'] = sum(sizes) if all(s is not None for s in sizes) else None
                cum = first['_chart']['cumulative']
                item['_chart']['starting'] = cum - first['netPnl'] if cum is not None else None
        result.append(item)
    return result


def normalized_equity(rows, start):
    """Port Edge Monitor's capital rebuild and source-aware compounding.

    Pairing/source scope happens first. Missing capital stays unavailable rather
    than becoming a fabricated zero return. No bot imports or account queries.
    The 40/20/40 legacy fallback is the monitor's portfolio return convention,
    not an allocation of real dashboard balances.
    """
    if not rows:
        return None
    starting = None
    for row in rows:
        capital = row.get('_chart', {})
        candidate = capital.get('starting')
        if candidate is None and capital.get('cumulative') is not None:
            candidate = capital['cumulative'] - row['netPnl']
        if candidate is not None:
            starting = candidate
            break
    cumulative = starting
    previous_backtest = None
    growth = 1.0
    peak = 1.0
    max_drawdown_usd = max_drawdown_pct = 0.0
    points = {start: 10000.0} if start and len(rows) < 20000 else {}
    legacy = False
    for row in rows:
        capital = row.get('_chart', {})
        pnl = row['netPnl']
        if cumulative is not None:
            cumulative += pnl
        if row['source'] == 'backtest':
            if cumulative is None:
                return None
            base = previous_backtest if previous_backtest is not None and previous_backtest > 0 else cumulative - pnl
            if base <= 0:
                return None
            fraction = cumulative / base - 1
            previous_backtest = cumulative
        else:
            combined = capital.get('combined')
            if combined is not None and combined > 0:
                base = combined - pnl
                if base <= 0:
                    base = combined
            else:
                position, leverage = capital.get('position'), capital.get('leverage')
                if position is None or position <= 0 or leverage is None or leverage <= 0:
                    return None
                weight = {'M1': 0.4, 'M2': 0.2, 'M3': 0.4}.get(row['strategy'])
                if weight is None:
                    return None
                base = position / leverage / weight
                legacy = True
            fraction = pnl / base
        growth *= 1 + fraction
        try:
            value = number(growth * 10000)
        except InvalidData:
            return None
        if value < 0:
            return None
        # Edge Monitor _equity_and_drawdown_from_returns: untouched starting
        # capital is part of the high-water mark; percentage is relative to peak.
        # Calculate before timestamp coalescing so no intra-timestamp trough is lost.
        peak = max(peak, growth)
        max_drawdown_pct = max(max_drawdown_pct, (1 - growth / peak) * 100)
        max_drawdown_usd = max(max_drawdown_usd, (peak - growth) * 10000)
        points[row['closedAt']] = value
    # Return the closed curve only. The client adds a fresh saved-capital open
    # mark, preserving demand-only balance behaviour and source isolation.
    return {'baseEquity': 10000.0, 'legacyCapitalEstimate': legacy,
        'drawdown': {'usd': max_drawdown_usd, 'pct': max_drawdown_pct},
        'points': [{'timestamp': stamp, 'equity': value} for stamp, value in sorted(points.items())]}


def model_projection(instance, histories, accounts, positions, strategies):
    if any(a.strategy not in MODELS for a in instance.accounts):
        raise InvalidData()
    model_accounts, model_positions, model_strategies = [], [], []
    for model in MODELS:
        configured = [a for a in instance.accounts if a.strategy == model]
        ids = {a.id for a in configured}
        values = [a['unrealizedPnl'] for a in accounts if a['id'] in ids]
        model_accounts.append({'id': model, 'label': model, 'equity': None,
            'unrealizedPnl': sum(values) if all(v is not None for v in values) else None})
        grouped = defaultdict(list)
        for p in positions:
            if p['accountId'] in ids:
                grouped[p['symbol'], p['side']].append(p)
        for (symbol, side), group in grouped.items():
            size = sum(p['size'] for p in group)
            marks = [p['markPrice'] for p in group]
            pnls = [p['unrealizedPnl'] for p in group]
            stops = {p['stopPrice'] for p in group}
            model_positions.append({'id': model + '-' + symbol + '-' + side, 'accountId': model,
                'symbol': symbol, 'side': side, 'size': size,
                'entryPrice': sum(p['entryPrice'] * p['size'] for p in group) / size,
                'markPrice': marks[0] if all(m is not None and m == marks[0] for m in marks) else None,
                'unrealizedPnl': sum(pnls) if all(p is not None for p in pnls) else None,
                'leverage': sum(p['leverage'] * p['size'] for p in group) / size,
                'stopPrice': next(iter(stops)) if len(stops) == 1 else None,
                'stopPrices': sorted(stops) if all(s is not None and s > 0 for s in stops) else []})
        evaluations = [s for s in strategies if s['id'] in ids]
        if evaluations:
            directions = sorted({side for _, side in grouped})
            model_strategies.append({'id': model, 'name': model,
                'status': 'PENDING_PROTECTION' if any(s['status'] == 'PENDING_PROTECTION' for s in evaluations)
                          else 'Open (saved state)' if grouped else 'Flat (saved state)',
                'signal': ' + '.join(directions) if directions else 'FLAT',
                'reason': 'Saved position direction; not a current strategy signal.',
                'evaluatedAt': min(s['evaluatedAt'] for s in evaluations)})
    rows = paired_trades([r for history in histories.values() for r in history], instance.accounts)
    return ({model: [r for r in rows if r['accountId'] == model] for model in MODELS},
            model_accounts, model_positions, model_strategies)


def validate_v2(value, label):
    # Share the strict allowlist and numeric/privacy checks with the immutable v1 contract.
    projected = {k: v for k, v in value.items() if k not in ("openPerformance", "btcTicker", "normalizedEquity")}
    projected['positions'] = [{k: v for k, v in p.items() if k != 'stopPrices'} for p in value.get('positions', [])]
    checked = snapshot(projected, MODELS, label)
    for original, position in zip(value.get('positions', []), checked['positions']):
        stops = original.get('stopPrices', [])
        if (not isinstance(stops, list) or len(stops) > 6
                or any(number(s) <= 0 for s in stops) or stops != sorted(set(stops))
                or stops and position.get('stopPrice') is not None and stops != [position['stopPrice']]):
            raise InvalidData()
        position['stopPrices'] = list(stops)
    checked['schemaVersion'] = 2
    checked['selectedModel'] = checked.pop('selectedAccount')
    equity = value.get('normalizedEquity')
    if equity is not None:
        fields(equity, ('baseEquity', 'legacyCapitalEstimate', 'points'), ('drawdown',))
        if (number(equity['baseEquity']) != 10000 or type(equity['legacyCapitalEstimate']) is not bool
                or checked['metrics']['realizedPnl'] is None):
            raise InvalidData()
        points = records(equity['points'], ('timestamp', 'equity'), (),
            {'timestamp': lambda t: integer(t, 1), 'equity': number}, 'timestamp')
        if not points or any(p['timestamp'] > checked['generatedAt'] or p['equity'] < 0 for p in points):
            raise InvalidData()
        equity = dict(equity, points=sorted(points, key=lambda p: p['timestamp']))
        drawdown = equity.get('drawdown')
        if drawdown is not None:
            fields(drawdown, ('usd', 'pct'))
            usd, pct = number(drawdown['usd']), number(drawdown['pct'])
            if usd < 0 or not 0 <= pct <= 100 or (usd == 0) != (pct == 0):
                raise InvalidData()
            equity['drawdown'] = dict(usd=usd, pct=pct)
    checked['normalizedEquity'] = equity
    performance = value.get('openPerformance')
    ticker = value.get('btcTicker')
    if checked['historyMode'] == 'edge' and (performance is not None or ticker is not None):
        raise InvalidData()
    if performance is not None:
        names = ('investedUsd', 'entryEquityUsd', 'investedReturnPct', 'entryEquityReturnPct')
        fields(performance, names, ('usesIdleBalances', 'idleBalanceSampledAt'))
        uses_idle, idle_time = performance.get('usesIdleBalances', False), performance.get('idleBalanceSampledAt')
        if type(uses_idle) is not bool or (idle_time is not None and
                (not uses_idle or integer(idle_time, 1) > checked['generatedAt'])):
            raise InvalidData()
        performance = {key: number(performance[key], True) for key in names}
        if uses_idle and performance['entryEquityUsd'] is not None and idle_time is None:
            raise InvalidData()
        performance.update(usesIdleBalances=uses_idle, idleBalanceSampledAt=idle_time)
        for denominator, percentage in (('investedUsd', 'investedReturnPct'), ('entryEquityUsd', 'entryEquityReturnPct')):
            capital, percentage_value = performance[denominator], performance[percentage]
            if capital is not None and capital < 0:
                raise InvalidData()
            pnl = checked['metrics']['unrealizedPnl']
            expected = pnl / capital * 100 if capital is not None and capital > 0 and pnl is not None else None
            if (expected is None) != (percentage_value is None) or (expected is not None and abs(expected - percentage_value) > 1e-6):
                raise InvalidData()
    if ticker is not None:
        fields(ticker, ('symbol', 'price', 'change24hPct', 'updatedAt'))
        if (ticker['symbol'] != 'BTCUSDT' or number(ticker['price']) <= 0
                or number(ticker['change24hPct']) < -100
                or not 0 <= checked['generatedAt'] - integer(ticker['updatedAt'], 1) <= 15000):
            raise InvalidData()
        ticker = dict(ticker)
    checked['openPerformance'], checked['btcTicker'] = performance, ticker
    return checked
