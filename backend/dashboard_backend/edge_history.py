"""Port of Edge's chart history preparation, without importing the trading engine.

The reviewed dataset stays beside the private bot code. Only its two literal
artifact constants are decoded: no module execution, environment or bot imports.
"""
import ast
import base64
import csv
import hashlib
import io
from pathlib import Path
import zlib

from .validation import InvalidData
from .grouping import normalized_equity

MAX_ARTIFACT = 8 * 1024 * 1024


def reviewed_source(edge_path):
    parent = Path(edge_path).parent
    for name in ('edge_engine.py', 'edge_history_offline.csv'):
        path = parent / name
        try:
            info = path.stat(follow_symlinks=False)
        except FileNotFoundError:
            continue
        return path, (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns)
    return None, None


def reviewed_rows(path, read_stable):
    if path is None:
        return []  # Edge's documented seed/live fallback when no artifact exists.
    raw, _ = read_stable(str(path), MAX_ARTIFACT)
    if path.name == 'edge_engine.py':
        wanted = {'_EMBEDDED_OFFLINE_B85', '_EMBEDDED_OFFLINE_SHA256'}
        values = {}
        try:
            tree = ast.parse(raw.decode('utf-8'))
            for node in tree.body:
                if isinstance(node, ast.Assign):
                    for target in node.targets:
                        if isinstance(target, ast.Name) and target.id in wanted:
                            if target.id in values:
                                raise InvalidData()
                            values[target.id] = ast.literal_eval(node.value)
            if not values:
                sibling = path.with_name('edge_history_offline.csv')
                return reviewed_rows(sibling, read_stable) if sibling.is_file() else []
            if set(values) != wanted or any(not isinstance(value, str) for value in values.values()):
                raise InvalidData()
            compressed = base64.b85decode(values['_EMBEDDED_OFFLINE_B85'].encode('ascii'))
            decoder = zlib.decompressobj()
            raw = decoder.decompress(compressed, MAX_ARTIFACT + 1)
            if len(raw) > MAX_ARTIFACT or not decoder.eof or decoder.unused_data:
                raise InvalidData()
            if hashlib.sha256(raw).hexdigest() != values['_EMBEDDED_OFFLINE_SHA256']:
                raise InvalidData()
        except (SyntaxError, ValueError, TypeError, UnicodeError, zlib.error) as error:
            raise InvalidData() from error
    reader = csv.DictReader(io.StringIO(raw.decode('utf-8-sig')), strict=True)
    required = {'source', 'model', 'trade_num', 'side', 'entry_time', 'exit_time',
                'entry_price', 'exit_price', 'pnl_usd', 'total_fees', 'cum_capital'}
    if not reader.fieldnames or not required <= set(reader.fieldnames) or len(reader.fieldnames) != len(set(reader.fieldnames)):
        raise InvalidData()
    rows = []
    for index, row in enumerate(reader):
        if index >= 20000 or None in row or any(value is None for value in row.values()):
            raise InvalidData()
        if row['source'] == 'offline_backtest':
            rows.append(row)
    return rows


def collapse_wallet_legs(rows):
    """Edge collapse_wallet_legs: exact model/entry/exit keys, stable order."""
    groups = {}
    for row in rows:
        key = (row['strategy'], row['openedAt'], row['closedAt'])
        if key not in groups:
            groups[key] = dict(row, _chart=dict(row['_chart']))
            continue
        first = groups[key]
        first['netPnl'] += row['netPnl']
        first['fees'] += row['fees']
        # pandas sum skips missing values. Missing both must still be unusable.
        for name in ('position', 'entry'):
            values = [first['_chart'].get(name), row['_chart'].get(name)]
            first['_chart'][name] = sum(value for value in values if value is not None) if any(
                value is not None for value in values) else None
    return list(groups.values())


def prepare_history(offline, logged, offline_end):
    """Edge _load_edge_history_impl: precedence, overlap cut, stable rebuild."""
    merged, seen = [], set()
    for priority, rows in enumerate((offline, [r for r in logged if r['source'] == 'backtest'],
                                    [r for r in logged if r['source'] == 'live'])):
        if priority < 2 and rows:
            # Exact port of the updated engine: source capital is captured on
            # the COMPLETE simulation, before overlap removal and source merge.
            unique = {}
            for row in rows:
                unique.setdefault((row['accountId'], row['openedAt'], row['closedAt']), row)
            source = sorted(unique.values(), key=lambda row: row['closedAt'])
            starting = next((r['_chart']['cumulative'] - r['netPnl'] for r in source
                             if r['_chart'].get('cumulative') is not None), None)
            capital_by_trade = {}
            for row in collapse_wallet_legs(source):
                key = (row['strategy'], row['openedAt'], row['closedAt'])
                capital_by_trade[key] = starting
                if starting is not None:
                    starting += row['netPnl']
            rows = [dict(row, _chart=dict(row['_chart'], sourceCapital=capital_by_trade[
                (row['strategy'], row['openedAt'], row['closedAt'])])) for row in rows]
        for row in rows:
            key = (row['accountId'], row['openedAt'], row['closedAt'])
            if key in seen:
                continue
            seen.add(key)
            if row['_chart'].get('origin') != 'offline_backtest' and offline_end is not None and row['closedAt'] <= offline_end:
                continue
            merged.append(row)
    merged.sort(key=lambda row: row['closedAt'])
    starting = next((r['_chart']['cumulative'] - r['netPnl'] for r in merged
                     if r['_chart'].get('cumulative') is not None), None)
    output = collapse_wallet_legs(merged)
    if starting is not None and output:
        # The updated engine rebases FRACTIONAL RETURNS across source scales;
        # adding a $1,700 seed's dollars to a $37m offline path erases returns.
        prepared = normalized_equity(output, 0, include_row_equities=True)
        if prepared is None:
            raise InvalidData()
        for row, value in zip(output, prepared['rowEquities']):
            row['_chart']['cumulative'] = starting * value / 10000
    return output
