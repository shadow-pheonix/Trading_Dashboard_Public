"""Demand-only perpetual equity, no signing keys, trading SDK or background collector."""
import asyncio
import time
import urllib.request

from .grouping import MODELS, wallet
from .market import decimal
from .validation import InvalidData, decode, encode, number


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise InvalidData()


def query_equity(address):
    request = urllib.request.Request('https://api.hyperliquid.xyz/info',
        data=encode({'type': 'clearinghouseState', 'user': address}),
        headers={'Content-Type': 'application/json'}, method='POST')
    with urllib.request.build_opener(NoRedirect()).open(request, timeout=8) as response:
        raw = response.read(262145)
    if len(raw) > 262144:
        raise InvalidData()
    value = decode(raw)
    # marginSummary.accountValue is current perpetual account equity, including open P&L.
    return decimal(value['marginSummary']['accountValue'])


class Balances:
    def __init__(self, query=query_equity, clock=lambda: int(time.time() * 1000), monotonic=time.monotonic):
        self.query, self.clock, self.monotonic = query, clock, monotonic
        self.samples, self.tasks = {}, {}
        self.wallet_samples = {}

    def peek(self, instance):
        # Internal, immutable samples only. Reading this never queries an exchange.
        return self.wallet_samples.get(instance.id)

    async def get(self, instance):
        cached = self.samples.get(instance.id)
        if cached and 0 <= self.monotonic() - cached[0] < 10:
            return cached[1]
        task = self.tasks.get(instance.id)
        if task is None:
            task = asyncio.create_task(self._fetch(instance))
            self.tasks[instance.id] = task
        # A disconnected client does not cancel a request other viewers are sharing.
        return await asyncio.shield(task)

    async def _account(self, account):
        if not account.public_address:
            return None, 'unconfigured', None
        try:
            equity = await asyncio.to_thread(self.query, account.public_address)
            return number(equity), 'ok', self.clock()
        except Exception:
            return None, 'failed', None  # Never expose exception/provider/address payloads.

    async def _fetch(self, instance):
        try:
            attempted = self.clock()
            samples = await asyncio.gather(*(self._account(a) for a in instance.accounts))
            complete = bool(samples) and all(s[1] == 'ok' for s in samples)
            allocation_complete = complete and all(wallet(a) for a in instance.accounts)
            groups = sorted({wallet(a) for a in instance.accounts} - {''})
            # Match Telegram System Health: each wallet is its own allocation pool.
            # A partial sample must never supply a misleading denominator.
            totals = {group: sum(s[0] for a, s in zip(instance.accounts, samples)
                                 if wallet(a) == group) for group in groups} if allocation_complete else {}
            models = []
            for model in MODELS:
                values = [s for a, s in zip(instance.accounts, samples) if a.strategy == model]
                ok = bool(values) and all(s[1] == 'ok' for s in values)
                status = ('ok' if ok else 'not_configured' if not values else
                          'failed' if any(s[1] == 'failed' for s in values) else 'unconfigured')
                allocations = []
                for group in groups:
                    members = [s for a, s in zip(instance.accounts, samples)
                               if a.strategy == model and wallet(a) == group]
                    if members:
                        pct = (sum(s[0] for s in members) / totals[group] * 100
                               if allocation_complete and totals[group] > 0 else None)
                        allocations.append({'wallet': group, 'pct': pct})
                models.append({'id': model, 'equity': sum(s[0] for s in values) if ok else None,
                               'status': status, 'sampledAt': min(s[2] for s in values) if ok else None,
                               'walletAllocations': allocations})
            equity = sum(s[0] for s in samples) if complete else None
            original = instance.original_equity
            pnl = equity - original if equity is not None and original is not None else None
            body = {'schemaVersion': 2, 'instance': instance.label, 'generatedAt': self.clock(),
                    'attemptedAt': attempted, 'sampledAt': min(s[2] for s in samples) if complete else None,
                    'status': 'ok' if complete else 'failed' if any(s[1] == 'failed' for s in samples) else 'unconfigured',
                    'equity': equity, 'originalEquity': original, 'pnl': pnl,
                    'returnPct': pnl / original * 100 if pnl is not None else None,
                    'models': models}
            encode(body)
            self.wallet_samples[instance.id] = {
                'attemptedAt': attempted,
                'accounts': {a.id: {'equity': s[0], 'status': s[1], 'sampledAt': s[2]}
                             for a, s in zip(instance.accounts, samples)}}
            # Failures are also briefly reused to prevent an outage causing a request storm.
            self.samples[instance.id] = self.monotonic(), body
            return body
        finally:
            self.tasks.pop(instance.id, None)
