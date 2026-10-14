"""Dashboard-owned config only. No environment or bot config discovery."""
from dataclasses import dataclass, field
from pathlib import Path
import re

from .validation import InvalidData, decode, fields, identifier, integer, text, number


@dataclass(frozen=True)
class Account:
    id: str
    label: str
    state_path: str = field(repr=False)
    history_path: str = field(default="", repr=False)
    strategy: str = "M1"
    symbol: str = "BTCUSDT"
    edge_model: str = ""
    wallet: str = ""
    public_address: str = field(default="", repr=False)


@dataclass(frozen=True)
class Instance:
    id: str
    label: str
    accounts: tuple[Account, ...]
    edge_path: str = field(default="", repr=False)
    original_equity: float | None = None
    alerts_path: str = field(default="", repr=False)


@dataclass(frozen=True)
class Config:
    instances: tuple[Instance, ...]
    poll_seconds: int = 3

    def instance(self, instance_id):
        for item in self.instances:
            if item.id == instance_id:
                return item
        raise InvalidData()


def load_config(path):
    raw = Path(path).read_bytes()
    value = decode(raw)
    fields(value, ("instances",), ("pollSeconds",))
    interval = integer(value.get("pollSeconds", 3), 3)
    if interval > 3600 or not isinstance(value["instances"], list) or not 1 <= len(value["instances"]) <= 20:
        raise InvalidData()
    instances, names, sources, addresses = [], set(), set(), set()
    for entry in value["instances"]:
        fields(entry, ("id", "label", "accounts"), ("edgePath", "originalEquity", "alertsPath"))
        original_equity = number(entry.get("originalEquity"), True)
        if original_equity is not None and original_equity <= 0:
            raise InvalidData()
        instance_id = identifier(entry["id"])
        if instance_id in names:
            raise InvalidData()
        names.add(instance_id)
        if not isinstance(entry["accounts"], list) or not 1 <= len(entry["accounts"]) <= 6:
            raise InvalidData()
        accounts, ids, edge_models = [], set(), set()
        for item in entry["accounts"]:
            fields(item, ("id", "label", "statePath", "historyPath", "strategy"),
                   ("symbol", "edgeModel", "wallet", "publicAddress"))
            account_id = identifier(item["id"])
            symbol = item.get("symbol", "BTCUSDT")
            if (account_id == "all" or account_id in ids or not isinstance(symbol, str)
                    or not re.fullmatch(r"[A-Z0-9]{1,20}USDT", symbol)):
                raise InvalidData()
            for key, suffix in (("statePath", ".json"), ("historyPath", ".csv")):
                path = item[key]
                if (not isinstance(path, str) or not Path(path).is_absolute()
                        or Path(path).suffix != suffix or len(path) > 1024
                        or ".." in Path(path).parts or any(ord(c) < 32 for c in path)
                        or any("backtest" in part.lower() for part in Path(path).parts)
                        or Path(path).name.startswith(".") or path in sources):
                    raise InvalidData()
                sources.add(path)
            ids.add(account_id)
            edge_model = item.get("edgeModel", "")
            if edge_model:
                edge_model = identifier(edge_model)
                if edge_model in edge_models:
                    raise InvalidData()
                edge_models.add(edge_model)
            elif edge_model != "":
                raise InvalidData()
            wallet = item.get("wallet", "")
            address = item.get("publicAddress", "")
            if wallet not in ("", "A", "B") or not isinstance(address, str):
                raise InvalidData()
            if address:
                if (not re.fullmatch(r"0x[a-fA-F0-9]{40}", address)
                        or int(address[2:], 16) == 0 or address.lower() in addresses
                        or wallet not in ("A", "B") or item["strategy"] not in ("M1", "M2", "M3")):
                    raise InvalidData()
                addresses.add(address.lower())
            accounts.append(Account(account_id, text(item["label"]), item["statePath"],
                                    item["historyPath"], text(item["strategy"], 64), symbol, edge_model,
                                    wallet, address))
        slots = [(a.strategy, a.wallet) for a in accounts if a.wallet]
        if len(slots) != len(set(slots)):
            raise InvalidData()
        edge_path = entry.get("edgePath", "")
        if edge_path:
            if (not isinstance(edge_path, str) or not Path(edge_path).is_absolute()
                    or Path(edge_path).suffix != ".csv" or len(edge_path) > 1024
                    or ".." in Path(edge_path).parts or any(ord(c) < 32 for c in edge_path)
                    or Path(edge_path).name.startswith(".") or edge_path in sources
                    or len(edge_models) != len(accounts)):
                raise InvalidData()
            sources.add(edge_path)
        elif edge_path != "":
            raise InvalidData()
        alerts_path = entry.get("alertsPath", "")
        if alerts_path:
            if (not isinstance(alerts_path, str) or not Path(alerts_path).is_absolute()
                    or Path(alerts_path).name != "dashboard_alerts.sqlite"
                    or ".." in Path(alerts_path).parts or len(alerts_path) > 1024
                    or any(ord(c) < 32 for c in alerts_path) or alerts_path in sources
                    or any("backtest" in part.lower() for part in Path(alerts_path).parts)):
                raise InvalidData()
            sources.add(alerts_path)
        elif alerts_path != "":
            raise InvalidData()
        instances.append(Instance(instance_id, text(entry["label"]), tuple(accounts), edge_path, original_equity, alerts_path))
    return Config(tuple(instances), interval)
