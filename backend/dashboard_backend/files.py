"""Read existing bot files without importing its code, locking it or writing its files."""
import csv
from collections import OrderedDict
from datetime import datetime, timezone
import hashlib
import io
import os
from pathlib import Path
import stat
import threading

from .market import decimal
from .validation import InvalidData, decode, number, snapshot, period_start
from .grouping import model_projection, validate_v2, normalized_equity

MAX_HISTORY_BYTES = 8 * 1024 * 1024


def positive_optional(value):
    # Missing/malformed optional capital telemetry must not hide the saved position.
    try:
        value = number(value)
        return value if value > 0 else None
    except InvalidData:
        return None


def chart_number(row, *names):
    for name in names:
        if row.get(name):
            try:
                return decimal(row[name])
            except InvalidData:
                return None
    return None


def complete_sum(values):
    return sum(values) if all(value is not None for value in values) else None


def open_performance(pnl, capital, uses_idle=False, idle_sampled_at=None):
    invested, equity = capital
    return {"investedUsd": invested, "entryEquityUsd": equity,
            "investedReturnPct": pnl / invested * 100 if pnl is not None and invested is not None and invested > 0 else None,
            "entryEquityReturnPct": pnl / equity * 100 if pnl is not None and equity is not None and equity > 0 else None,
            "usesIdleBalances": uses_idle, "idleBalanceSampledAt": idle_sampled_at if equity is not None else None}


def timestamp(value):
    try:
        if not isinstance(value, str) or len(value) > 64:
            raise InvalidData()
        dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
        # Bot CSV's naive timestamps are explicitly written in UTC.
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        result = int(dt.timestamp() * 1000)
        if result <= 0:
            raise InvalidData()
        return result
    except (ValueError, OverflowError):
        raise InvalidData() from None


def entry_timestamp(value):
    # Manual adoption exports this exact sentinel; its actual opening time is unavailable.
    return None if value == "unknown" else timestamp(value)


def read_stable(path, limit):
    # Only caller-configured JSON/CSV. Reject symlink files and never inspect environment files.
    path = Path(path)
    if path.suffix not in (".json", ".csv") or path.name.startswith("."):
        raise InvalidData()
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, "rb") as file:
        before = os.fstat(file.fileno())
        if not stat.S_ISREG(before.st_mode) or before.st_size > limit:
            raise InvalidData()
        raw = file.read(limit + 1)
        after = os.fstat(file.fileno())
    signature = lambda s: (s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns)
    if len(raw) > limit or signature(before) != signature(after):
        raise InvalidData()
    return raw, before


class BotFiles:
    def __init__(self):
        self.lock = threading.RLock()
        self.history_cache = OrderedDict()

    def cache(self, key, marker, rows):
        self.history_cache.pop(key, None)
        while self.history_cache and sum(len(v[1]) for v in self.history_cache.values()) + len(rows) > 20000:
            self.history_cache.popitem(last=False)
        self.history_cache[key] = marker, rows

    @staticmethod
    def trade_row(account, row, source="live"):
        if None in row or any(v is None for v in row.values()):
            raise InvalidData()
        opened, closed = entry_timestamp(row["entry_time"]), timestamp(row["exit_time"])
        identity = hashlib.sha256((source + ":" + account.id + ":" + row["trade_num"] + ":"
            + str(opened) + ":" + str(closed)).encode()).hexdigest()[:24]
        if row["side"] not in ("LONG", "SHORT") or opened is not None and opened > closed:
            raise InvalidData()
        return {"id": identity, "accountId": account.id, "strategy": account.strategy,
            "symbol": account.symbol, "side": row["side"], "openedAt": opened, "closedAt": closed,
            "entryPrice": decimal(row["entry_price"]), "exitPrice": decimal(row["exit_price"]),
            "netPnl": decimal(row["pnl_usd"]), "fees": decimal(row["total_fees"]), "funding": None,
            "source": source,
            "_equity": decimal(row["exit_equity"]) if row.get("exit_equity") else None,
            "_chart": {'cumulative': chart_number(row, 'cum_capital', 'final_capital'),
                'position': chart_number(row, 'pos_size_quote', 'pos_size'),
                'leverage': chart_number(row, 'leverage'),
                'combined': chart_number(row, 'combined_equity')}}

    def edge_history(self, instance):
        if not instance.edge_path or any(not a.edge_model for a in instance.accounts):
            raise InvalidData()
        mapping = {a.edge_model: a for a in instance.accounts}
        if len(mapping) != len(instance.accounts):
            raise InvalidData()
        info = os.stat(instance.edge_path, follow_symlinks=False)
        marker = (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns)
        key = ("edge", instance.edge_path, tuple((a.id, a.edge_model) for a in instance.accounts))
        cached = self.history_cache.get(key)
        if cached and cached[0] == marker:
            self.history_cache.move_to_end(key)
            return cached[1], info.st_mtime_ns // 1000000
        raw, info = read_stable(instance.edge_path, MAX_HISTORY_BYTES)
        if not raw.endswith(b"\n"):
            raise InvalidData()
        reader = csv.DictReader(io.StringIO(raw.decode("utf-8-sig")), strict=True)
        required = {"source", "model", "trade_num", "side", "entry_time", "exit_time",
                    "entry_price", "exit_price", "pnl_usd", "total_fees"}
        if (not reader.fieldnames or not required <= set(reader.fieldnames)
                or len(reader.fieldnames) != len(set(reader.fieldnames))):
            raise InvalidData()
        output, seen = [], set()
        for index, row in enumerate(reader):
            if index >= 20000 or None in row or any(v is None for v in row.values()):
                raise InvalidData()
            if row["source"] not in ("backtest", "live"):
                raise InvalidData()
            account = mapping.get(row["model"])
            if account is None:
                continue  # Other accounts in this shared log are outside the configured scope.
            trade = self.trade_row(account, row, row["source"])
            if trade["id"] in seen:
                raise InvalidData()
            seen.add(trade["id"])
            output.append(trade)
        output.sort(key=lambda t: (t["closedAt"], t["id"]))
        marker = (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns)
        self.cache(key, marker, output)
        return output, info.st_mtime_ns // 1000000

    def history(self, account):
        try:
            info = os.stat(account.history_path, follow_symlinks=False)
        except FileNotFoundError:
            return [], False  # A missing log does not prove zero closed trades.
        marker = (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns)
        cached = self.history_cache.get(account.history_path)
        if cached and cached[0] == marker:
            self.history_cache.move_to_end(account.history_path)
            return cached[1], True
        raw, info = read_stable(account.history_path, MAX_HISTORY_BYTES)
        if raw and not raw.endswith(b"\n"):
            raise InvalidData()  # An in-progress append must never become a fake complete row.
        reader = csv.DictReader(io.StringIO(raw.decode("utf-8-sig")), strict=True)
        required = {"trade_num", "side", "entry_time", "exit_time", "entry_price", "exit_price", "pnl_usd", "total_fees"}
        if not reader.fieldnames or not required <= set(reader.fieldnames) or len(reader.fieldnames) != len(set(reader.fieldnames)):
            raise InvalidData()
        output, seen = [], set()
        for row in reader:
            if None in row or any(v is None for v in row.values()) or len(output) >= 20000:
                raise InvalidData()
            if row.get("source", "live") != "live" or row.get("model", account.strategy) != account.strategy:
                raise InvalidData()  # Never blend seeded/offline backtests or other strategy records.
            trade = self.trade_row(account, row)
            if trade["id"] in seen:
                raise InvalidData()
            seen.add(trade["id"])
            output.append(trade)
        output.sort(key=lambda t: (t["closedAt"], t["id"]))
        marker = (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns)
        self.cache(account.history_path, marker, output)
        return output, True

    def build(self, instance, period, selected, prices, now, mode="real", version=1, idle_balances=None, clock=None):
        with self.lock:
            if mode == "edge":
                rows, updated = self.edge_history(instance)
                if updated <= 0 or updated > now:
                    raise InvalidData()
                histories = {a.id: [r for r in rows if r["accountId"] == a.id] for a in instance.accounts}
                accounts = [{"id": a.id, "label": a.label, "equity": None, "unrealizedPnl": 0.0}
                            for a in instance.accounts]
                return self.summarize(instance, period, selected, histories, accounts, [], [],
                                      True, [], [], now, mode, version)
            if mode != "real":
                raise InvalidData()
            accounts, positions, histories, state_times, price_times, strategies = [], [], {}, [], [], []
            invested, entry_equities = [], []
            uses_idle, idle_times = False, []
            complete = True
            for account in instance.accounts:
                raw, info = read_stable(account.state_path, 1024 * 1024)
                value = decode(raw)
                if not isinstance(value, dict) or "trade" not in value:
                    raise InvalidData()
                updated = info.st_mtime_ns // 1000000
                if updated <= 0 or updated > now:
                    raise InvalidData()
                history, available = self.history(account)
                histories[account.id] = history
                chosen = selected == "all" or selected == (account.strategy if version == 2 else account.id)
                if chosen:
                    complete = complete and available
                    state_times.append(updated)
                trade = value["trade"]
                entry_equity = None
                pnl, status = 0.0, "Flat (saved state)"
                price = prices.get(account.symbol)
                if price and chosen:
                    price_times.append(price[1])
                if trade is not None:
                    if not isinstance(trade, dict) or trade.get("side") not in ("LONG", "SHORT"):
                        raise InvalidData()
                    size, entry = number(trade.get("sz")), number(trade.get("entry_price"))
                    opened = entry_timestamp(trade.get("entry_time"))
                    if size <= 0 or entry <= 0 or opened is not None and opened > now:
                        raise InvalidData()
                    if opened is not None and any(t["openedAt"] == opened and t["side"] == trade["side"] for t in history):
                        raise InvalidData()  # State/CSV crossed a close-write boundary; retry next update.
                    mark = price[0] if price else None
                    pnl = (mark - entry) * size * (1 if trade["side"] == "LONG" else -1) if mark is not None else None
                    status = trade.get("status") if trade.get("status") in ("ACTIVE", "PENDING_PROTECTION") else "Open (saved state)"
                    if chosen:
                        if version == 2:
                            notional = positive_optional(trade.get("pos_size_quote", size * entry))
                            leverage = positive_optional(trade.get("leverage"))
                            invested.append(positive_optional(notional / leverage) if notional is not None and leverage is not None else None)
                        positions.append({"id": hashlib.sha256((account.id + ":" + str(opened)).encode()).hexdigest()[:24],
                            "accountId": account.id, "symbol": account.symbol, "side": trade["side"],
                            "size": size, "entryPrice": entry, "markPrice": mark, "unrealizedPnl": pnl,
                            "leverage": number(trade.get("leverage")),
                            "stopPrice": number(trade.get("sl")) if trade.get("sl") else None})
                    if version == 2 and opened is not None:
                        entry_equity = positive_optional(trade.get("entry_capital"))
                elif version == 2:
                    uses_idle = True
                    sample = (idle_balances or {}).get('accounts', {}).get(account.id)
                    attempted = (idle_balances or {}).get('attemptedAt')
                    # A balance fetched before this saved flat state could still include
                    # that wallet's prior position. Require a new demand-only refresh.
                    if sample and sample['status'] == 'ok' and updated <= attempted <= sample['sampledAt'] <= now:
                        idle_equity = number(sample['equity'])
                        if idle_equity >= 0:
                            entry_equity = idle_equity
                            idle_times.append(sample['sampledAt'])
                # Active wallets use saved entry capital; flat wallets use sampled idle
                # equity. Never replace a missing active entry with a current balance.
                entry_equities.append(entry_equity)
                accounts.append({"id": account.id, "label": account.label, "equity": None, "unrealizedPnl": pnl})
                if chosen:
                    strategies.append({"id": account.id, "name": account.strategy, "status": status,
                        "signal": "UNAVAILABLE", "reason": "Saved position status; current strategy signals are not exported.",
                        "evaluatedAt": updated})
            btc_ticker = prices.ticker("BTCUSDT") if version == 2 else None
            if version == 2 and clock is not None:
                now = max(now, clock())
            if btc_ticker is not None and btc_ticker['updatedAt'] > now:
                btc_ticker = None  # An event arriving after this snapshot belongs to the next update.
            return self.summarize(instance, period, selected, histories, accounts, positions, strategies,
                                  complete, state_times, price_times, now, mode, version,
                                  capital=(complete_sum(invested), complete_sum(entry_equities)),
                                  btc_ticker=btc_ticker, uses_idle=uses_idle,
                                  idle_sampled_at=min(idle_times) if idle_times else None)

    @staticmethod
    def summarize(instance, period, selected, histories, accounts, positions, strategies,
                  complete, state_times, price_times, now, mode, version=1, capital=(None, None), btc_ticker=None,
                  uses_idle=False, idle_sampled_at=None):
        chosen_count = sum(selected == "all" or selected == (a.strategy if version == 2 else a.id)
                           for a in instance.accounts)
        if version == 2:
            histories, accounts, positions, strategies = model_projection(
                instance, histories, accounts, positions, strategies)
        start = period_start(period, now)
        selected_rows = [r for aid, rows in histories.items() if selected == "all" or aid == selected for r in rows]
        if any(r["closedAt"] > now for r in selected_rows):
            raise InvalidData()
        trades = sorted((r for r in selected_rows if r["closedAt"] >= start), key=lambda r: (r["closedAt"], r["id"]))
        chosen_accounts = [a for a in accounts if selected == "all" or a["id"] == selected]
        upnl = sum(a["unrealizedPnl"] for a in chosen_accounts) if all(a["unrealizedPnl"] is not None for a in chosen_accounts) else None
        curve, running = [], 0.0
        for row in trades:
            running += row["netPnl"]
            equity = row["_equity"] if selected != "all" and version == 1 else None
            if curve and curve[-1]["timestamp"] == row["closedAt"]:
                curve[-1].update(pnl=running, equity=equity)
            else:
                curve.append({"timestamp": row["closedAt"], "pnl": running, "equity": equity})
        monthly = {}
        for row in (trades if version == 2 else selected_rows):
            dt = datetime.fromtimestamp(row["closedAt"] / 1000, timezone.utc)
            key = dt.year, dt.month
            monthly[key] = monthly.get(key, 0) + row["netPnl"]
        result = {"schemaVersion": 1, "instance": instance.label, "generatedAt": now,
            "live": mode == "real" and chosen_count > 0 and len(price_times) == chosen_count, "period": period, "selectedAccount": selected,
            "currency": "USD", "dataSource": "bot_files_binance" if mode == "real" else "edge_csv",
            "historyMode": mode, "pnlBasis": "logged_fees_only",
            "stateUpdatedAt": min(state_times) if state_times else None, "priceUpdatedAt": min(price_times) if price_times else None,
            "accounts": accounts, "positions": positions, "strategies": strategies,
            "metrics": {"equity": None, "unrealizedPnl": upnl,
                "realizedPnl": running if complete else None, "closedTrades": len(trades) if complete else None,
                "winRatePct": 100 * sum(r["netPnl"] > 0 for r in trades) / len(trades) if complete and trades else None},
            "curve": curve if complete else [],
            "monthlyReturns": [{"year": y, "month": m, "returnPct": None, "pnl": pnl}
                for (y, m), pnl in sorted(monthly.items())] if complete else [],
            "trades": [{k: v for k, v in t.items() if not k.startswith('_')} for t in trades]}
        if version == 2:
            result['normalizedEquity'] = normalized_equity(trades, start) if complete else None
            result["openPerformance"] = open_performance(upnl, capital, uses_idle, idle_sampled_at) if mode == "real" else None
            result["btcTicker"] = btc_ticker if mode == "real" else None
            return validate_v2(result, instance.label)
        return snapshot(result, [a.id for a in instance.accounts], instance.label)
