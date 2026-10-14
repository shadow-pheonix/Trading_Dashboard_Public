"""One public Binance price stream, present only while authenticated viewers exist."""
import threading
import time
import json
from urllib.request import urlopen

from .validation import InvalidData, decode, integer, number


def decimal(value):
    try:
        if not isinstance(value, str) or len(value) > 64:
            raise InvalidData()
        return number(float(value))
    except ValueError:
        raise InvalidData() from None


def fetch_btc_ticker():
    # Fixed public endpoint: one symbol, no account data or credentials.
    with urlopen("https://fapi.binance.com/fapi/v1/ticker/24hr?symbol=BTCUSDT", timeout=2) as response:
        raw = response.read(4097)
        if len(raw) > 4096:
            raise InvalidData()
        return decode(raw)


class BinancePrices:
    def __init__(self, clock=lambda: int(time.time() * 1000), connector=None, ticker_fetcher=fetch_btc_ticker):
        self.clock, self.connector = clock, connector
        self.ticker_fetcher = ticker_fetcher
        self.seed_thread = None
        self.seed_not_before = 0
        self.ticker_generation = 0
        self.lock = threading.RLock()
        self.condition = threading.Condition(self.lock)
        self.references = {}
        self.prices = {}
        self.tickers = {}
        self.thread = None
        self.socket = None
        self.stop_event = threading.Event()
        self.connected = False

    def acquire(self, symbols):
        with self.lock:
            previous = set(self.references)
            for symbol in symbols:
                self.references[symbol] = self.references.get(symbol, 0) + 1
            if self.thread is None or not self.thread.is_alive():
                self.stop_event.clear()
                self.thread = threading.Thread(target=self._run, name="dashboard-prices", daemon=True)
                self.thread.start()
            else:
                # Reconnect once when the active symbol set changes.
                if set(self.references) != previous and self.socket is not None:
                    try:
                        self.socket.close()
                    except Exception:
                        pass
            self.stop_event.clear()
            if ("BTCUSDT" in self.references and self.ticker("BTCUSDT") is None
                    and (self.seed_thread is None or not self.seed_thread.is_alive())
                    and time.monotonic() >= self.seed_not_before):
                self.seed_not_before = time.monotonic() + 10
                self.seed_thread = threading.Thread(target=self._seed_ticker,
                    args=(self.ticker_generation,), name="dashboard-btc-startup", daemon=True)
                self.seed_thread.start()
            self.condition.notify_all()

    def release(self, symbols):
        with self.lock:
            for symbol in symbols:
                count = self.references.get(symbol, 0) - 1
                if count <= 0:
                    self.references.pop(symbol, None)
                else:
                    self.references[symbol] = count
            if "BTCUSDT" in symbols and "BTCUSDT" not in self.references:
                self.ticker_generation += 1
            if not self.references:
                self.stop_event.set()
                self.connected = False
                if self.socket is not None:
                    try:
                        self.socket.close()
                    except Exception:
                        pass
                self.condition.notify_all()

    def _seed_ticker(self, generation):
        try:
            row = self.ticker_fetcher()
            if not isinstance(row, dict) or row.get("symbol") != "BTCUSDT":
                raise InvalidData()
            with self.lock:
                if generation != self.ticker_generation or "BTCUSDT" not in self.references:
                    return
                # Reuse stream validation and monotonic ordering; never seed marks.
                self.accept(json.dumps({"e": "24hrTicker", "s": "BTCUSDT",
                    "E": row.get("closeTime"), "c": row.get("lastPrice"),
                    "P": row.get("priceChangePercent")}))
        except Exception:
            # Startup failure leaves the live stream intact; no exception payload output.
            pass

    def accept(self, message):
        value = decode(message)
        row = value.get("data", value) if isinstance(value, dict) else {}
        with self.lock:
            if (not isinstance(row, dict) or row.get("e") not in ("markPriceUpdate", "24hrTicker")
                    or row.get("s") not in self.references or row.get("st", 1) != 1):
                raise InvalidData()
            observed = integer(row.get("E"), 1)
            ticker = row["e"] == "24hrTicker"
            price = decimal(row.get("c") if ticker else row.get("p"))
            if price <= 0 or not 0 <= self.clock() - observed <= 15000:
                raise InvalidData()
            if ticker:
                change = decimal(row.get("P"))
                if change < -100:
                    raise InvalidData()
            cache = self.tickers if ticker else self.prices
            previous = cache.get(row["s"])
            if previous is not None and observed < previous[1]:
                return
            cache[row["s"]] = (price, observed, change) if ticker else (price, observed)
            self.condition.notify_all()

    def ticker(self, symbol):
        with self.lock:
            value = self.tickers.get(symbol)
            if (symbol not in self.references or value is None
                    or not 0 <= self.clock() - value[1] <= 15000):
                return None
            return {"symbol": symbol, "price": value[0], "updatedAt": value[1], "change24hPct": value[2]}

    def get(self, symbol):
        with self.lock:
            value = self.prices.get(symbol)
            if not self.connected or value is None or not 0 <= self.clock() - value[1] <= 15000:
                return None
            return value

    def wait_ready(self, symbols, timeout=3):
        deadline = time.monotonic() + timeout
        with self.condition:
            while any(self.get(symbol) is None for symbol in symbols):
                remaining = deadline - time.monotonic()
                if remaining <= 0 or self.stop_event.is_set():
                    break
                self.condition.wait(remaining)

    def _run(self):
        retry = 1
        while True:
            with self.condition:
                while not self.references:
                    self.condition.wait()
            try:
                if self.connector is None:
                    from websockets.sync.client import connect
                    connector = connect
                else:
                    connector = self.connector
                with self.lock:
                    symbols = sorted(self.references)
                if not symbols:
                    continue
                streams = "/".join(s.lower() + "@markPrice@1s" for s in symbols)
                if "BTCUSDT" in symbols:
                    streams += "/btcusdt@ticker"
                # Public fixed endpoint. No wallet address, API key or trading subscription.
                url = "wss://fstream.binance.com/market/stream?streams=" + streams
                with connector(url, open_timeout=8, close_timeout=1, max_size=65536,
                               max_queue=8, compression=None) as socket:
                    with self.lock:
                        if self.stop_event.is_set():
                            continue
                        self.socket, self.connected = socket, True
                    while not self.stop_event.is_set():
                        self.accept(socket.recv(timeout=20))
                        retry = 1
            except Exception:
                # Never print provider exceptions, headers or message bodies.
                pass
            finally:
                with self.lock:
                    self.socket, self.connected = None, False
                    self.condition.notify_all()
            self.stop_event.wait(retry)
            retry = min(30, retry * 2)
