"""Strict allowlisted API documents; unknown fields never leave storage."""
import json
import math
import re
from datetime import datetime, timezone

PERIODS = ("1D", "1W", "1M", "3M", "1Y", "YTD", "All")
MAX_BYTES = 2 * 1024 * 1024
METRICS = ("realizedPnl", "unrealizedPnl", "equity", "totalReturnPct",
           "winRatePct", "sharpeRatio", "maxDrawdownPct", "profitableDays",
           "observedDays", "closedTrades")


class InvalidData(ValueError):
    def __init__(self):
        super().__init__("Invalid dashboard data.")


def period_start(period, now):
    if period == 'All':
        return 0
    if period == 'YTD':
        year = datetime.fromtimestamp(now / 1000, timezone.utc).year
        return int(datetime(year, 1, 1, tzinfo=timezone.utc).timestamp() * 1000)
    return now - {'1D': 1, '1W': 7, '1M': 30, '3M': 90, '1Y': 365}[period] * 86400000


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r"[a-zA-Z0-9_-]{1,64}", value):
        raise InvalidData()
    if re.search(r"0x[a-fA-F0-9]{40}", value):
        raise InvalidData()
    return value


def text(value, limit=240):
    if (not isinstance(value, str) or not 1 <= len(value) <= limit
            or any(ord(c) < 32 for c in value)
            or re.search(r"0x[a-fA-F0-9]{40}|https?://|/(?:etc|home|opt)/", value)):
        raise InvalidData()
    return value


def number(value, nullable=False):
    if value is None and nullable:
        return None
    if type(value) not in (int, float) or not math.isfinite(value) or abs(value) > 1e20:
        raise InvalidData()
    return value


def integer(value, minimum=0):
    if type(value) is not int or value < minimum or value > 2**63 - 1:
        raise InvalidData()
    return value


def fields(value, required, optional=()):
    if not isinstance(value, dict) or set(value) - set(required) - set(optional) or not set(required) <= set(value):
        raise InvalidData()


def encode(value):
    data = json.dumps(value, separators=(",", ":"), allow_nan=False).encode("utf-8")
    if len(data) > MAX_BYTES:
        raise InvalidData()
    return data


def decode(raw):
    if len(raw) > MAX_BYTES:
        raise InvalidData()
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise InvalidData()
            result[key] = value
        return result
    try:
        return json.loads(raw, object_pairs_hook=unique,
                          parse_constant=lambda _: (_ for _ in ()).throw(InvalidData()))
    except (ValueError, UnicodeError, RecursionError):
        raise InvalidData() from None


def records(value, required, optional, converters, unique="id"):
    if not isinstance(value, list) or len(value) > 20000:
        raise InvalidData()
    output, seen = [], set()
    for row in value:
        fields(row, required, optional)
        result = {key: converters[key](item) for key, item in row.items()}
        if unique is not None:
            marker = result[unique] if isinstance(unique, str) else tuple(result[k] for k in unique)
            if marker in seen:
                raise InvalidData()
            seen.add(marker)
        output.append(result)
    return output


def snapshot(value, allowed_accounts, label):
    required = ("schemaVersion", "instance", "generatedAt", "live", "period",
                "selectedAccount", "currency", "accounts", "metrics")
    arrays = ("curve", "monthlyReturns", "positions", "trades", "strategies")
    fields(value, required, (*arrays, "dataSource", "stateUpdatedAt", "priceUpdatedAt", "pnlBasis", "historyMode"))
    if (type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1
            or type(value["live"]) is not bool or value["period"] not in PERIODS
            or value["currency"] != "USD" or value["instance"] != label):
        raise InvalidData()
    output = dict(value)
    for key in ("stateUpdatedAt", "priceUpdatedAt"):
        if key in output and output[key] is not None:
            integer(output[key], 1)
    mode = output.get("historyMode", "real")
    if mode not in ("real", "edge"):
        raise InvalidData()
    if output.get("dataSource", "unknown") not in ("unknown", "bot_files_binance", "edge_csv"):
        raise InvalidData()
    if (mode == "edge") != (output.get("dataSource") == "edge_csv"):
        raise InvalidData()
    if mode == "edge" and (value["live"] or value.get("positions") or value.get("strategies")
            or value.get("stateUpdatedAt") is not None or value.get("priceUpdatedAt") is not None):
        raise InvalidData()
    if output.get("pnlBasis", "unknown") not in ("unknown", "logged_fees_only"):
        raise InvalidData()
    integer(value["generatedAt"], 1)
    selected = identifier(value["selectedAccount"])
    if selected != "all" and selected not in allowed_accounts:
        raise InvalidData()
    output["accounts"] = records(value["accounts"],
        ("id", "label", "equity", "unrealizedPnl"), (),
        {"id": identifier, "label": text, "equity": lambda n: number(n, True),
         "unrealizedPnl": lambda n: number(n, True)})
    if {a["id"] for a in output["accounts"]} != set(allowed_accounts):
        raise InvalidData()
    fields(value["metrics"], (), METRICS)
    output["metrics"] = {}
    for key in METRICS:
        item = value["metrics"].get(key)
        output["metrics"][key] = (integer(item) if key in METRICS[-3:] and item is not None
                                  else number(item, True))
    output["curve"] = records(value.get("curve", []), ("timestamp", "pnl", "equity"), (),
        {"timestamp": lambda n: integer(n, 1), "pnl": number, "equity": lambda n: number(n, True)}, "timestamp")
    if output["curve"] != sorted(output["curve"], key=lambda r: r["timestamp"]):
        raise InvalidData()
    output["monthlyReturns"] = records(value.get("monthlyReturns", []),
        ("year", "month", "returnPct"), ("pnl",),
        {"year": lambda n: integer(n, 1970), "month": lambda n: integer(n, 1),
         "returnPct": lambda n: number(n, True), "pnl": lambda n: number(n, True)}, ("year", "month"))
    if any(r["month"] > 12 for r in output["monthlyReturns"]):
        raise InvalidData()
    position_fields = ("id", "accountId", "symbol", "side", "size", "entryPrice",
                       "markPrice", "unrealizedPnl", "leverage")
    output["positions"] = records(value.get("positions", []), position_fields, ("stopPrice",),
        {**{k: number for k in position_fields[4:]}, "id": identifier, "accountId": identifier,
         "symbol": text, "side": text, "stopPrice": lambda n: number(n, True),
         "markPrice": lambda n: number(n, True), "unrealizedPnl": lambda n: number(n, True)})
    trade_fields = ("id", "accountId", "strategy", "symbol", "side", "openedAt", "closedAt",
                    "entryPrice", "exitPrice", "netPnl", "fees")
    output["trades"] = records(value.get("trades", []), trade_fields, ("funding", "source"),
        {**{k: text for k in trade_fields[:5]}, "id": identifier, "accountId": identifier,
         "openedAt": lambda n: integer(n, 1) if n is not None else None, "closedAt": lambda n: integer(n, 1),
         **{k: number for k in trade_fields[7:]}, "funding": lambda n: number(n, True), "source": text})
    if any(t.get("source", "live") not in (("live",) if mode == "real" else ("backtest", "live"))
           for t in output["trades"]):
        raise InvalidData()
    for row in (*output["positions"], *output["trades"]):
        if (row["accountId"] not in allowed_accounts
                or selected != "all" and row["accountId"] != selected
                or row["side"] not in ("LONG", "SHORT")):
            raise InvalidData()
    if any(r["size"] <= 0 or r["leverage"] <= 0 or r["entryPrice"] <= 0
           or r["markPrice"] is not None and r["markPrice"] <= 0 for r in output["positions"]):
        raise InvalidData()
    if any(r["openedAt"] is not None and r["openedAt"] > r["closedAt"]
           or r["entryPrice"] <= 0 or r["exitPrice"] <= 0 for r in output["trades"]):
        raise InvalidData()
    strategy_fields = ("id", "name", "status", "signal", "reason", "evaluatedAt")
    output["strategies"] = records(value.get("strategies", []), strategy_fields, (),
        {**{k: text for k in strategy_fields[:-1]}, "id": identifier,
         "evaluatedAt": lambda n: integer(n, 1)})
    encode(output)
    return output
