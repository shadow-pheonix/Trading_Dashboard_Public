# Dashboard backend on the existing VPS

The backend reads existing trade-state JSON and live trade-history CSV files,
without importing bot code or changing bot files. V2 adds demand-only public
perpetual equity queries; [the v2 contract](../docs/API-v2.md) owns balance scope,
sampling, failure status and pairing. Existing servers use the
[human-run update and rollback](../docs/UPDATE.md).
Native bot events use a separate authenticated read-only outbox feed; the
[alert contract and human deployment](../docs/ALERTS.md) define retention,
Android background behavior and the required notification-only bot change.
An authenticated Real money viewer activates one shared public Binance Futures
WebSocket. V2 adds the BTC ticker to the saved-position mark streams on that same
socket; ticker prices never replace marks in open P&L. The
[live return contract](../docs/API-v2.md#live-btc-and-open-returns) defines the two
capital denominators and missing-history behavior. The Android app receives a
complete dashboard over `/v2/live` (older APKs retain `/v1/live`), then
updates every three seconds while visible. When the last viewer disconnects,
file reads stop and the Binance socket closes. The service remains listening;
there is no background collector, scheduled history import or idle price feed.
A connection attempt already in progress can take up to eight seconds to finish.

The app shows connected only after receiving a valid dashboard. The price badge
says **Market live**, not that the trading bot is alive. State files have no
heartbeat: their saved timestamp is shown separately. Binance prices estimate
open P&L from saved size/entry; they do not prove the Hyperliquid position still
exists. Closed P&L comes directly from `pnl_usd`, which already subtracts logged
fees. Funding and cash-flow-adjusted percentage returns,
Sharpe and recorded-equity percentage drawdown stay unavailable. Monthly cells show logged dollar P&L.
Single-account historical equity points use `exit_equity` only when recorded.
V2 also provides [Edge Monitor reference equity](../docs/API-v2.md#edge-monitor-equity-and-chart-inspection)
and its drawdown calculation using source-aware compounded trade returns. Real money uses regular trade CSVs;
Backtest uses its edge CSV. No balance queries or background collection are added.

## Trade source selection

Analytics and Trades offer **Real money** and **Backtest**.
Real money reads regular `hl_trades_*.csv` files only. Backtest combines both
`backtest` and `live` rows in `edge_trades_combined.csv` for the configured models;
P&L, win rate, monthly dollar results and cumulative P&L include both sources.
Trade details identify each row's source. Backtest responses have no current
positions or prices; their badge says Historical data. The Android foreground
session retains its separate real stream across tabs and requests filtered data
over HTTP; see [app connection behavior](../README.md#app-behavior). Model and period
filters apply to both modes in v2. Home always shows real data. See
[the mode and grouping contract](../docs/API-v2.md).

## First installation by the server administrator

Use a separate checkout of this repository on the **existing VPS**. The supplied
installer creates only dashboard-owned code, data, user and units. It does not
start services, install system packages, change bot permissions, edit bot code
or restart a bot. Python 3.12+ with venv, systemd and the existing read-only
`hlmacdbot_readers` group must already exist. That group must be able to read the
configured JSON/CSV files, including future atomic replacements; do not make
private environment files readable. Confirm this permission arrangement before
starting the service.

1. After reviewing and merging the change, clone
   [Trading_Dashboard_Public](https://github.com/shadow-pheonix/Trading_Dashboard_Public) into its
   own directory and select the exact reviewed commit. From that checkout:

   ```bash
   git rev-parse HEAD
   sudo bash backend/deploy/install.sh EXACT_REVIEWED_40_CHARACTER_COMMIT_SHA
   ```

   Replace the last block with the SHA printed by Git. Installation is deliberately
   first-time only; an existing or partial installation stops rather than overwriting.

2. Edit only the dashboard configuration, privately:

   ```bash
   sudoedit /var/lib/trading-dashboard/config.json
   ```

   Each instance has `id`, `label`, and up to six `accounts`. Each account entry
   needs `id`, `label`, `strategy`, `statePath`, `historyPath`; `symbol` defaults to
   `BTCUSDT`. Account IDs are opaque, unique within the instance and cannot be `all`.
   Add a separate selector for every model/sub-account; never reuse a source path.
   `pollSeconds` is the update interval **while connected** (minimum 3), not an idle poll.

   Main production directory: `/home/hlmacdbot_user/HLTRADINGBOT`;
   Vinay: `/home/hlmacdbot_user/HLTRADINGBOTVINAY`;
   Madhu: `/home/hlmacdbot_user/HLTRADINGBOTMADHU`.
   Wallet A/M1 uses `hl_state_m1.json` and `hl_trades_m1.csv`; M2/M3 change the model
   suffix. Wallet B/M1 uses `hl_state_b_m1.json` and `hl_trades_b_m1.csv`.
   Choose the actual files present in each instance. `historyPath` must name the
   regular real-trade CSV. Optional instance `edgePath` enables Backtest and must
   name its separate edge CSV. Every account then requires a unique `edgeModel`:
   M1/M2/M3 use `M1-Fast`/`M2-Med`/`M3-Slow`; wallet B adds `-B`. Rows for
   unconfigured models are excluded, including old wallet B rows in a three-model
   instance. V2 balances additionally use private `publicAddress` and `wallet` (A/B) fields
   per account, imported once by the human-run setup; no exchange key or bot
   environment access is used by the API. The approved human-run root setup imports
   public addresses and each instance's `ORIGINAL_EQUITY` from the three env files;
   see [private setup](../docs/UPDATE.md) and the [balance return contract](../docs/API-v2.md#demand-only-perpetual-balances).

3. Initialize the dashboard's independent credential database and create the
   once-generated instance key:

   ```bash
   sudo /usr/local/sbin/trading-dashboard-admin init
   sudo /usr/local/sbin/trading-dashboard-admin key-create main main HTTPS_ORIGIN
   ```

   Repeat the second command for each configured instance ID. Each key is stored
   privately at `/var/lib/trading-dashboard/keys/INSTANCE-access-key.txt`, mode 600.
   Read it privately to enter it into the app. Nothing prints the key, and SQLite
   stores only the inner token's SHA-256 hash. Supply the private HTTPS origin
   for the [encoded connection key](../docs/API-v2.md#private-connection-keys).
   Existing key files are never overwritten; the formatter can wrap an existing
   raw key into a new private file without revoking it.

4. Review the installed dashboard units, then start **only the dashboard service**:

   ```bash
   sudo systemctl enable --now trading-dashboard-api.service
   sudo systemctl status trading-dashboard-api.service
   ```

   The API listens on `127.0.0.1:18080`. Its separate user has read-only directory
   binds for the three bot roots, so atomic state replacements remain visible.
   `ProtectHome=tmpfs` hides other home directories. Private environment locations
   and dashboard key files are masked. It can write only its own data directory.
   The shared slice caps CPU at 15% of one core, memory at 256 MiB (192 MiB soft
   threshold), and tasks at 64, with low CPU/IO priority. The bot's resources and
   existing bot service files are untouched. The optional
   [coder-management migration](../docs/UPDATE.md#coder-managed-dashboard-after-the-one-time-migration)
   adds dashboard-only drop-ins and a narrow control helper, retaining this
   isolated runtime user and resource limits. Cgroup limits reduce interference;
   sharing a server still shares hardware and network capacity.

5. Keep port 18080 private and use the [exact Caddy endpoint allowlist](deploy/Caddyfile)
   on the existing dashboard HTTPS proxy. Preserve Authorization, block redirects,
   and avoid header/key logging. Validate the proxy before the human reload.
   Replace the template's `dashboard.example.invalid` with the private hostname
   in the installed proxy configuration. The app receives that origin privately
   through its connection key; no deployment address belongs in Git or the APK.

6. Enter only the full dashboard key in Android Settings. The server supplies
   the instance name. Opening/resuming opens a foreground stream and requests
   balances once. Tabs and Analytics/Trades period, model and source changes
   retain the foreground stream; Home
   stays real. Backgrounding closes the stream and outstanding HTTP reads.
   A one-time HTTPS GET validates each new key. Failed streams retry with bounded
   backoff while visible; balance failures require reopening or manual refresh.

## Revocation and limits

```bash
sudo /usr/local/sbin/trading-dashboard-admin key-revoke main
sudo /usr/local/sbin/trading-dashboard-admin key-create main replacement HTTPS_ORIGIN
```

Revocation closes existing streams on the next update and rejects future requests.
Enter the replacement key privately from `replacement-access-key.txt` in the app.
It does not change APK signing or other instances' keys. No public key-management
or write/trading endpoint exists. Limits are 16 concurrent streams, 32 HTTP
requests in flight, 600 requests/minute overall, 120 per direct peer/minute and
12 authentication failures per peer/minute. Forwarded IP headers are ignored;
behind the local proxy, peer limits are shared, so also limit clients at the proxy.

Readers open only configured JSON/CSV files with read-only flags, rejecting final
symlinks, changing reads, duplicate JSON keys and incomplete CSV appends. Missing
CSV means unknown history; a valid empty CSV proves zero closed trades. CSV source
or model mismatches fail. Invalid/missing state closes the stream; the app retains
its last dashboard marked disconnected and retries. A Binance outage leaves saved
positions/history available with unknown mark/open P&L and `live=false`.

Each CSV is bounded at 8 MiB/20,000 rows; parsed history cache holds at most 20,000
rows across paths. Responses are bounded at 2 MiB and reject oversized history
whole rather than silently omitting trades. Larger archives need a future paginated
API. Cached history is rebuilt only when the file changes and only while viewed.
The backend cannot independently prove bot-state completeness or funding accounting.

## Verification

```bash
python3 -W error::ResourceWarning -m unittest discover -s tests -v
python3 -m compileall -q dashboard_backend tests
bash -n deploy/install.sh deploy/trading-dashboard-admin
python3 tests/verify_units.py
python3 -m pip install -r requirements.txt
python3 -m tests.smoke_http
```

[Backend Actions](https://github.com/shadow-pheonix/Trading_Dashboard_Public/actions/workflows/backend.yml)
checks Python 3.12/3.14 and actual HTTP/WebSocket behavior with synthetic files and
prices. No test contacts a live exchange or bot. The installer and systemd syntax
are checked without installing/starting anything. CI does not publish private
configuration, databases, runtime files or keys. Deployment, real read permissions,
Binance reachability, proxy/TLS and long-running behavior still need server checks.

Source: [Binance mark-price stream](https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/ws-streams/market#mark-price-stream),
[systemd isolation](https://www.freedesktop.org/software/systemd/man/latest/systemd.exec.html),
[systemd resource limits](https://www.freedesktop.org/software/systemd/man/latest/systemd.resource-control.html).
