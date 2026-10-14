# Read-only dashboard API v1

[Model API v2 and balance contract](API-v2.md) describes the new APK. This v1
contract remains unchanged for older APKs.

Implemented by the [backend](../backend/README.md). The foreground app opens:

```http
GET /v1/live?period=1M&account=all
Upgrade: websocket
Authorization: Bearer <instance-dashboard-key>
```

Use HTTPS/WSS with a trusted certificate. Keys go only in the Authorization
header, never the URL. Redirects are blocked. A base URL may include a proxy
path prefix. The key identifies its instance and allowed account selectors;
`account=all` means only accounts within that instance. Periods are `1D`, `1W`,
`1M`, `3M`, `1Y`, `YTD`, `All`, using trailing 1/7/30/90/365 days,
January 1 at 00:00 UTC of the current year, or all logged history. The new app
shows 1W, 1M, 3M, 1Y, YTD, All; 1D stays supported for older APKs.

Both endpoints accept `mode=real` (default, **Real money**) or `mode=edge`
(**Backtest**). Real money uses only configured regular trade CSVs and saved
positions. Backtest combines `source=backtest` and `source=live` rows from the
instance's edge CSV, scoped by explicit model-to-account mappings. It uses the
same closed P&L, win rate, monthly P&L and cumulative curve calculations; it
does not merge regular CSVs or activate state reads/Binance. Mode changes replace
the foreground stream. Missing edge configuration/data returns 503.

Authenticate and check scope **before accepting** the stream or activating
sources. Each text message is a complete JSON snapshot matching the requested
period/account. First message follows initial file read and a bounded price wait;
subsequent messages arrive at the configured interval while connected. No phone
messages or trading commands are accepted. Backgrounding the app disconnects it.
The last viewer leaving stops file reads and closes the shared Binance stream.
An in-flight connection handshake can still take its bounded timeout to finish.

A one-time `GET /v1/dashboard` with the same key/query returns the same schema for
new-connection validation. It activates sources only during that request. There
is no periodic HTTP poll. `GET /healthz` reports only process liveness and does
not activate data sources. Other HTTP methods return 405; no public write,
key-management, ingestion, order or bot-control endpoint exists.

HTTP errors: 401 invalid/revoked key, 403 unauthorized account, 400 invalid query,
429 rate limit, 503 unavailable/invalid data. Unauthorized WebSocket handshakes
are refused (HTTP 403); accepted streams close with 1008 for revoked credentials
or unexpected client messages and 1011 for unavailable/invalid data. Never send
raw exceptions or log keys. Key hashes live in independent SQLite; no bot or
exchange credentials are used. Revocation checks run before each stream update.

## Snapshot schema and accounting

JSON uses camelCase, Unix milliseconds in UTC and numeric USD amounts/prices.
Arrays may be empty; unavailable numbers are null. Example shape:

```json
{
  "schemaVersion": 1,
  "instance": "Main",
  "generatedAt": 1791456000000,
  "live": true,
  "period": "1M",
  "selectedAccount": "a",
  "currency": "USD",
  "dataSource": "bot_files_binance",
  "historyMode": "real",
  "pnlBasis": "logged_fees_only",
  "stateUpdatedAt": 1791455900000,
  "priceUpdatedAt": 1791456000000,
  "accounts": [{"id": "a", "label": "Wallet A · M1", "equity": null, "unrealizedPnl": 20}],
  "metrics": {"realizedPnl": 9, "unrealizedPnl": 20, "equity": null, "closedTrades": 1, "winRatePct": 100},
  "curve": [{"timestamp": 1791455999000, "pnl": 9, "equity": 1009}],
  "monthlyReturns": [{"year": 2026, "month": 10, "returnPct": null, "pnl": 9}],
  "positions": [{"id": "position-1", "accountId": "a", "symbol": "BTCUSDT", "side": "LONG", "size": 2, "entryPrice": 100, "markPrice": 110, "unrealizedPnl": 20, "leverage": 3, "stopPrice": 90}],
  "trades": [{"id": "trade-1", "accountId": "a", "strategy": "M1", "symbol": "BTCUSDT", "side": "LONG", "openedAt": 1791455989000, "closedAt": 1791455999000, "entryPrice": 100, "exitPrice": 110, "netPnl": 9, "fees": 1, "funding": null}],
  "strategies": [{"id": "a", "name": "M1", "status": "ACTIVE", "signal": "UNAVAILABLE", "reason": "Saved position status; current strategy signals are not exported.", "evaluatedAt": 1791455900000}]
}
```

Values above are synthetic. All instance account selectors appear in `accounts`;
metrics, curve, positions, trades and strategies match `selectedAccount`.
`historyMode` matches the requested mode. Trades include `source` (`live` in real
mode; `backtest` or `live` in edge mode). Edge snapshots use `dataSource=edge_csv`,
`live=false`, null state/price timestamps and empty positions/strategies. Their
headline is combined closed P&L; they do not claim current account equity.
Opaque IDs are unique within each array. Never return wallet addresses, file paths,
raw bot trade/order IDs, provider payloads or credentials. Snapshots reject
nonfinite numbers, unknown fields, wrong scopes and responses over 2 MiB. Trade
history includes every logged trade in the period within those limits; oversized
history fails instead of silently truncating. Monthly values span available years.

- `generatedAt` is when this dashboard was built. `stateUpdatedAt` is the oldest
  selected state file's modification time; **it is not a heartbeat**. The backend
  does not prove that a saved position is still open on the exchange.
- `priceUpdatedAt` is the oldest selected Binance mark event. `live=true` requires
  fresh available prices for all selected accounts, at most 15 seconds old, and a
  successful saved-file read. The client separately requires a healthy connection,
  a snapshot at most 90 seconds old and Binance price age at most 15 seconds.
  The UI labels this **Market live**, not bot liveness.
- Open P&L estimates `(Binance mark − saved entry) × saved base size × direction`.
  Binance mark differs from Hyperliquid's mark and excludes funding/closing fees.
  Missing prices produce null marks/open P&L rather than fabricated zeros. A saved
  flat position has zero open P&L even if prices are unavailable.
- `realizedPnl`, trade `netPnl`, curve `pnl`, monthly dollar `pnl` and win rate use
  logged `pnl_usd`, already net of recorded entry/exit fees. Do not subtract fees
  twice. `pnlBasis=logged_fees_only` explicitly means funding is unavailable;
  trade `funding=null` does not mean zero. The headline sums selected-period logged
  P&L and current open estimate only when both exist.
- Current `equity` is null. Individual-account curve equity may use recorded
  `exit_equity` at each close, which includes account cash flows. Combined equity
  is null because asynchronously observed account balances cannot be summed into
  a proven historical balance. Never use the bot's synthetic `final_capital`.
- Monthly `returnPct`, `totalReturnPct`, `sharpeRatio`, `maxDrawdownPct`,
  `profitableDays` and `observedDays` are null without the needed accounting data.
  Monthly heatmap displays dollar P&L when percentage returns are unavailable.
- Missing CSV means unknown closed count/realized P&L. A valid empty CSV means zero
  logged closes/P&L, with win rate unavailable. Missing history for any selected
  account prevents aggregate analytics. Current strategy signals are unavailable;
  saved position status and timestamp are displayed without recomputing indicators.

No Hyperliquid calls run through v1. State and CSV data are read-only inputs from
existing bot output, separate from the backend's credential database. This contract
does not authorize changes to bot services or production files. Deployment and
read permissions are reviewed separately by the server administrator.
