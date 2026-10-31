"""Test-only real ASGI server with synthetic prices; never connects to an exchange."""
import asyncio
import sys
import time
import uvicorn
from dashboard_backend.api import Application
from dashboard_backend.__main__ import WS_KEEPALIVE
from dashboard_backend.balances import Balances
from dashboard_backend.config import load_config
from dashboard_backend.storage import Store
from dashboard_backend.indicators import INTERVAL, HISTORY, macd_models
from dashboard_backend.chart_indicators import extend_chart


class SyntheticPrices:
    def acquire(self, symbols):
        pass

    def release(self, symbols):
        pass

    def wait_ready(self, symbols):
        pass

    def get(self, symbol):
        return 110, int(time.time() * 1000)

    def ticker(self, symbol):
        return {"symbol": symbol, "price": 111, "change24hPct": 2.5, "updatedAt": int(time.time() * 1000) - 1000}


class SyntheticBalances(Balances):
    """Give the flat wallet a demonstrably later sample than the active wallet."""
    async def _fetch(self, instance):
        self.active_sampled = asyncio.Event()
        return await super()._fetch(instance)

    async def _account(self, account):
        if account.id == 'b':
            await self.active_sampled.wait()
            await asyncio.sleep(0.02)
        sample = await super()._account(account)
        if account.id == 'a':
            self.active_sampled.set()
        return sample


class SyntheticIndicators:
    async def chart(self, instance, live=False):
        value = await self.get(instance)
        if live:
            current=value['generatedAt']//INTERVAL*INTERVAL
            value.update(chartWindow=256,candleStatus='ok',candleSampledAt=value['generatedAt'],
                candles=[dict(timestamp=current-i*INTERVAL,open=80000,high=80100,low=79900,close=80000) for i in range(255,0,-1)],
                formingCandle=dict(timestamp=current,open=80000,high=80100,low=79900,close=80050))
            # Finalized price differs from the bot sample; full MACD must still
            # survive this realistic case through the real authenticated HTTP path.
            value['candles'][-5]['close']=80014.01
            return extend_chart(value)
        value.update(candleStatus='ok', candleSampledAt=value['generatedAt'],
            candles=[dict(timestamp=p['timestamp'], open=80000, high=80100, low=79900, close=80050)
                     for p in value['models'][0]['points']])
        return value

    async def get(self, instance):
        now = int(time.time() * 1000)
        target = now // INTERVAL * INTERVAL - INTERVAL
        candles = [(target - (HISTORY - 1 - i) * INTERVAL, 80000.) for i in range(HISTORY)]
        return {'schemaVersion': 2, 'instance': instance.label, 'generatedAt': now,
                'source': 'binance_spot_closed', 'symbol': 'BTCUSDT', 'intervalMinutes': 15,
                'status': 'ok', 'sampledAt': now, 'models': macd_models(candles)}


if __name__ == '__main__':
    config, database, port = sys.argv[1:]
    uvicorn.run(Application(load_config(config), Store(database), prices=SyntheticPrices(),
        balances=SyntheticBalances(query=lambda _: 100.0), indicators=SyntheticIndicators()),
        host='127.0.0.1', port=int(port), access_log=False, log_level='critical',
        proxy_headers=False, limit_concurrency=32, ws='websockets-sansio',
        ws_max_size=1024, ws_max_queue=1, ws_per_message_deflate=False, **WS_KEEPALIVE)
