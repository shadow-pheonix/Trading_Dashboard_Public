"""Demand-only portfolio projection. The API authenticates the primary key first."""
import asyncio

from .files import complete_sum
from .validation import InvalidData, encode

MIN_PORTFOLIO_INSTANCE_EQUITY = 1000.0


async def collect_portfolio(config, files, balances, prices, clock):
    limit = asyncio.Semaphore(3)

    async def collect(instance):
        async with limit:
            balance = dashboard = None
            try:
                balance = await balances.get(instance)
            except Exception:
                pass
            try:
                dashboard = await asyncio.to_thread(files.build, instance, 'All', 'all', prices,
                    clock(), 'real', version=2, idle_balances=balances.peek(instance), clock=clock)
                # This route exports Home only; no histories, equity curves or Analytics.
                dashboard = dict(dashboard, trades=[], curve=[], monthlyReturns=[], strategies=[],
                    normalizedEquity=None, edgeDrawdowns=None, realDrawdowns=None,
                    portfolioAvailable=False, metrics=dict(unrealizedPnl=dashboard['metrics']['unrealizedPnl']))
            except Exception:
                dashboard = None
            status = ('ok' if dashboard is not None and balance is not None and balance['status'] == 'ok'
                      and dashboard['live'] else 'unavailable' if dashboard is None and balance is None else 'partial')
            return dict(id=instance.id, label=instance.label, status=status,
                        dashboard=dashboard, balances=balance)

    entries = await asyncio.gather(*(collect(instance) for instance in config.instances))
    # Keep the installed APK's aggregate invariants: every total uses the same
    # funded entries. A failed/missing balance is unknown, not a withdrawal.
    entries = [entry for entry in entries if not (
        entry['balances'] is not None and entry['balances']['status'] == 'ok'
        and entry['balances']['equity'] is not None
        and entry['balances']['equity'] < MIN_PORTFOLIO_INSTANCE_EQUITY)]
    if not entries:
        raise InvalidData()  # No funded portfolio; never manufacture a zero-capital return.
    equity = complete_sum([entry['balances']['equity'] if entry['balances'] else None for entry in entries])
    original = complete_sum([entry['balances']['originalEquity'] if entry['balances'] else None for entry in entries])
    pnl = complete_sum([entry['dashboard']['metrics']['unrealizedPnl'] if entry['dashboard'] else None for entry in entries])
    positions = sum(len(entry['dashboard']['positions']) for entry in entries if entry['dashboard']) if all(
        entry['dashboard'] is not None for entry in entries) else None
    body = dict(schemaVersion=2, generatedAt=clock(), currency='USD',
        status='ok' if all(entry['status'] == 'ok' for entry in entries) else 'partial',
        totals=dict(equity=equity, originalEquity=original, unrealizedPnl=pnl, openPositions=positions,
                    returnPct=(equity / original - 1) * 100 if equity is not None and original is not None and original > 0 else None),
        instances=entries)
    encode(body)
    return body
