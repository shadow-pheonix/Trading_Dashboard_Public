"""Small ASGI read-only interface, served by Uvicorn behind an HTTPS proxy."""
import asyncio
from collections import OrderedDict, deque
import time
from urllib.parse import parse_qs

from .validation import PERIODS, InvalidData, encode, identifier
from .files import BotFiles
from .market import BinancePrices
from .balances import Balances
from .alerts import Alerts
from .indicators import Indicators
from .grouping import MODELS


class Limits:
    """Single-worker, bounded sliding windows. Forwarded IP headers are untrusted."""
    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self.global_requests = deque()
        self.peers = OrderedDict()
        self.failures = OrderedDict()

    def _allow(self, bucket, limit, record=True):
        now = self.clock()
        while bucket and bucket[0] <= now - 60:
            bucket.popleft()
        if len(bucket) >= limit:
            return False
        if record:
            bucket.append(now)
        return True

    def _bucket(self, mapping, peer):
        if peer not in mapping:
            if len(mapping) >= 1024:
                mapping.popitem(last=False)
            mapping[peer] = deque()
        mapping.move_to_end(peer)
        return mapping[peer]

    def allow(self, peer):
        return (self._allow(self.global_requests, 600)
                and self._allow(self._bucket(self.peers, peer), 120)
                and self._allow(self._bucket(self.failures, peer), 12, False))

    def failure(self, peer):
        self._allow(self._bucket(self.failures, peer), 12)


class Application:
    def __init__(self, config, store, clock=lambda: int(time.time() * 1000), limits=None,
                 file_reader=None, prices=None, balances=None, indicators=None):
        self.config, self.store, self.clock = config, store, clock
        self.limits = limits or Limits()
        self.inflight = 0
        self.files = file_reader or BotFiles()
        self.prices = prices or BinancePrices(clock=clock)
        self.viewers = 0
        self.alerts = Alerts()
        self.balances = balances or Balances(clock=clock)
        self.indicators = indicators or Indicators(clock=clock)

    async def __call__(self, scope, receive, send):
        if scope["type"] == "lifespan":
            while True:
                event = await receive()
                if event["type"] == "lifespan.startup":
                    await send({"type": "lifespan.startup.complete"})
                elif event["type"] == "lifespan.shutdown":
                    await send({"type": "lifespan.shutdown.complete"})
                    return
        elif scope["type"] == "websocket":
            await self.stream(scope, receive, send)
        elif scope["type"] == "http":
            if self.inflight >= 32:
                await self.respond(send, 503, {"error": "Dashboard temporarily unavailable."})
                return
            self.inflight += 1
            try:
                status, body = await self.request(scope)
                encode(body)  # Reject oversized/nonfinite data before sending any response bytes.
            except Exception:
                # No raw exceptions, config, paths, credentials or exchange bodies in output/logs.
                status, body = 503, {"error": "Dashboard temporarily unavailable."}
            finally:
                self.inflight -= 1
            await self.respond(send, status, body)

    async def respond(self, send, status, body):
        raw = encode(body)
        headers = [(b"content-type", b"application/json; charset=utf-8"),
                   (b"content-length", str(len(raw)).encode()), (b"cache-control", b"no-store"),
                   (b"x-content-type-options", b"nosniff")]
        if status == 401:
            headers.append((b"www-authenticate", b"Bearer"))
        if status == 405:
            headers.append((b"allow", b"GET"))
        if status == 429:
            headers.append((b"retry-after", b"60"))
        await send({"type": "http.response.start", "status": status, "headers": headers})
        await send({"type": "http.response.body", "body": raw})

    async def subscribe(self, symbols):
        task = asyncio.create_task(asyncio.to_thread(self.prices.acquire, symbols))
        try:
            await asyncio.shield(task)
        except asyncio.CancelledError:
            # A cancelled viewer must not abandon an acquisition still running in a thread.
            await task
            await asyncio.to_thread(self.prices.release, symbols)
            raise

    async def request(self, scope):
        peer = (scope.get("client") or ("unknown",))[0]
        if not self.limits.allow(peer):
            return 429, {"error": "Too many requests. Try again later."}
        if scope["method"] != "GET":
            return 405, {"error": "Read-only service."}
        if scope["path"] == "/healthz":
            return 200, {"status": "ok"}  # Process liveness only, not exchange readiness.
        if scope["path"] not in ("/v1/dashboard", "/v2/dashboard", "/v2/balances", "/v2/alerts"):
            return 404, {"error": "Not found."}
        status, resolved = await self.resolve(scope)
        if status is not None:
            return status, resolved
        instance, token, period, account, mode = resolved
        if scope["path"] == "/v2/dashboard" and scope.get("query_string") == b"view=macd":
            value = await self.indicators.get(instance)
            if await asyncio.to_thread(self.store.authenticate, token) != instance.id:
                return 401, {"error": "Access denied."}
            return 200, value
        if scope["path"] == "/v2/alerts":
            value = await asyncio.to_thread(self.alerts.read, instance, self.clock(), self.alert_cursor(scope))
            if await asyncio.to_thread(self.store.authenticate, token) != instance.id:
                return 401, {"error": "Access denied."}
            return 200, value
        if scope["path"] == "/v2/balances":
            value = await self.balances.get(instance)
            if await asyncio.to_thread(self.store.authenticate, token) != instance.id:
                return 401, {"error": "Access denied."}
            return 200, value
        version = 2 if scope["path"].startswith("/v2/") else 1
        symbols = ({a.symbol for a in instance.accounts if account == "all" or (a.strategy if version == 2 else a.id) == account}
                   if mode == "real" else set())
        if version == 2 and mode == "real":
            symbols.add("BTCUSDT")
        if symbols:
            await self.subscribe(symbols)
        try:
            if symbols:
                await asyncio.to_thread(self.prices.wait_ready, symbols)
            args = (instance, period, account, self.prices, self.clock(), mode)
            options = {"version": 2, "idle_balances": self.balances.peek(instance), "clock": self.clock} if version == 2 else {}
            value = await asyncio.to_thread(self.files.build, *args, **options)
        finally:
            if symbols:
                await asyncio.to_thread(self.prices.release, symbols)
        if value is None:
            return 503, {"error": "Dashboard data not available yet."}
        return 200, value

    async def resolve(self, scope):
        peer = (scope.get("client") or ("unknown",))[0]
        auth = [v for k, v in scope.get("headers", []) if k.lower() == b"authorization"]
        token = None
        if len(auth) == 1 and auth[0].startswith(b"Bearer "):
            try:
                token = auth[0][7:].decode("ascii")
            except UnicodeError:
                pass
        instance_id = await asyncio.to_thread(self.store.authenticate, token)
        if instance_id is None:
            self.limits.failure(peer)
            return 401, {"error": "Access denied."}
        try:
            instance = self.config.instance(instance_id)
        except InvalidData:
            return 401, {"error": "Access denied."}
        try:
            query = scope.get("query_string", b"")
            version = 2 if scope["path"].startswith("/v2/") else 1
            selector = "model" if version == 2 else "account"
            if len(query) > 512:
                raise InvalidData()
            params = parse_qs(query.decode("ascii"), keep_blank_values=True,
                              strict_parsing=True, max_num_fields=3)
            indicator_view = scope["path"] == "/v2/dashboard" and query == b"view=macd"
            allowed = ({"view"} if indicator_view else
                       {"after"} if scope["path"] in ("/v2/alerts", "/v2/alerts/live") else
                       set() if scope["path"] == "/v2/balances" else {"period", selector, "mode"})
            if "after" in allowed:
                self.alert_cursor(scope)
            if set(params) - allowed or any(len(v) != 1 for v in params.values()):
                raise InvalidData()
            period = params.get("period", ["1M"])[0]
            account = identifier(params.get(selector, ["all"])[0])
            mode = params.get("mode", ["real"])[0]
            if period not in PERIODS or mode not in ("real", "edge"):
                raise InvalidData()
        except (ValueError, UnicodeError):
            return 400, {"error": "Invalid period or account."}
        allowed_accounts = set(MODELS) if version == 2 else {a.id for a in instance.accounts}
        if account != "all" and account not in allowed_accounts:
            return 403, {"error": "Access denied."}
        return None, (instance, token, period, account, mode)

    @staticmethod
    def alert_cursor(scope):
        params = parse_qs(scope.get("query_string", b"").decode("ascii"), strict_parsing=True)
        value = params.get("after", ["0"])[0]
        if not value.isascii() or not value.isdigit() or len(value) > 16:
            raise InvalidData()
        cursor = int(value)
        if cursor > 2**53 - 1:
            raise InvalidData()
        return cursor

    async def stream(self, scope, receive, send):
        if scope["path"] not in ("/v1/live", "/v2/live", "/v2/alerts/live") or self.viewers >= 16:
            await send({"type": "websocket.close", "code": 1008})
            return
        peer = (scope.get("client") or ("unknown",))[0]
        if not self.limits.allow(peer):
            await send({"type": "websocket.close", "code": 1008})
            return
        self.viewers += 1
        symbols = None
        receiver = None
        try:
            if (await receive())["type"] != "websocket.connect":
                return
            status, resolved = await self.resolve(scope)
            if status is not None:
                await send({"type": "websocket.close", "code": 1008})
                return
            instance, token, period, account, mode = resolved
            alerts = scope["path"] == "/v2/alerts/live"
            version = 2 if scope["path"] == "/v2/live" else 1
            await send({"type": "websocket.accept"})
            requested_symbols = ({a.symbol for a in instance.accounts if account == "all" or (a.strategy if version == 2 else a.id) == account}
                                 if mode == "real" else set())
            if version == 2 and mode == "real":
                requested_symbols.add("BTCUSDT")
            if alerts:
                requested_symbols = set()
            if requested_symbols:
                await self.subscribe(requested_symbols)
                symbols = requested_symbols
            receiver = asyncio.create_task(receive())
            if symbols:
                await asyncio.to_thread(self.prices.wait_ready, symbols)
            last_raw = None
            cursor = self.alert_cursor(scope) if alerts else 0
            while not receiver.done():
                # Revocation applies to already-open connections on the next update.
                if await asyncio.to_thread(self.store.authenticate, token) != instance.id:
                    await send({"type": "websocket.close", "code": 1008})
                    return
                args = (instance, period, account, self.prices, self.clock(), mode)
                options = {"version": 2, "idle_balances": self.balances.peek(instance), "clock": self.clock} if version == 2 else {}
                value = (await asyncio.to_thread(self.alerts.read, instance, self.clock(), cursor) if alerts
                         else await asyncio.to_thread(self.files.build, *args, **options))
                if value is None:
                    raise InvalidData()
                raw = encode(value).decode("utf-8")
                # Events are delivered immediately; unchanged metadata needs no
                # empty acknowledgement or application heartbeat. Uvicorn sends
                # the single protocol keepalive, including on idle feeds.
                marker = encode({key: item for key, item in value.items()
                                 if key not in ('generatedAt', 'events')}) if alerts else None
                if not alerts or value['events'] or marker != last_raw:
                    await send({"type": "websocket.send", "text": raw})
                    last_raw = marker
                    if alerts and value["status"] == "ready":
                        cursor = value["cursor"]
                await asyncio.wait({receiver}, timeout=0.01 if alerts and value["hasMore"] else self.config.poll_seconds)
            event = receiver.result()
            if event["type"] != "websocket.disconnect":
                await send({"type": "websocket.close", "code": 1008})
        except Exception:
            try:
                await send({"type": "websocket.close", "code": 1011})
            except Exception:
                pass
        finally:
            if receiver is not None:
                receiver.cancel()
                try:
                    await receiver
                except (asyncio.CancelledError, Exception):
                    pass
            if symbols is not None:
                await asyncio.to_thread(self.prices.release, symbols)
            self.viewers -= 1
