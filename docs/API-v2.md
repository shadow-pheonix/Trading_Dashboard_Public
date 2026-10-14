# Model dashboard API v2

V2 uses the same per-instance Bearer authentication, privacy allowlist, GET-only
HTTP interface, response limits, stream revocation and price lifecycle as
[v1](API.md). V1 responses and account selectors remain available to older APKs.
Neither version grants trading or bot service control.

## Private connection keys

New connections use `TD1.` followed by unpadded base64url-encoded UTF-8 JSON
containing `url` (an HTTPS origin) and `key` (the existing raw access token).
The app extracts both, validates them before connecting, and saves them only in
Android Keystore-encrypted connection records. The source and APK contain no
deployment address. HTTP, query strings, fragments, embedded credentials,
paths and malformed keys are rejected with errors that disclose neither value.
API, streaming, balance, MACD and alert requests use the saved connection address
and send only the inner token as Bearer authentication. The connection code is
never a query parameter or authorization header. Updates download directly from
public GitHub Releases without using saved server addresses or credentials. Existing saved
connections retain their addresses and tokens; new raw-token-only entries require
an encoded connection key. Encoding hides the address from public source/APKs,
not from a person holding the key. The [private key formatter](../scripts/encode-connection-key.py)
wraps existing tokens without rotating them, printing them, or overwriting files.

## Endpoints and model projection

`GET /v2/dashboard` and authenticated WebSocket `/v2/live` accept `period`, `model`
and `mode`. Defaults are `1M`, `all`, `real`; model values are `all`, `M1`, `M2`,
`M3`. Other account/instance selectors are rejected. The key alone determines
instance scope. `/healthz` never activates prices, files or balance queries.

Snapshots have `schemaVersion=2` and `selectedModel` in place of `selectedAccount`.
Their accounts array contains M1, M2 and M3, and position/trade `accountId` means
model ID. Strategies are also grouped by model. Other field types follow v1.
V2 strategy `signal` describes saved position direction: `LONG`, `SHORT`,
`LONG + SHORT`, or `FLAT`; it is not a current crossover evaluation. The Strategies
page uses the [MACD chart view](#macd-strategy-charts) instead of repeating Home's
position details. Bot evaluations remain in memory, with no new bot export or bot changes.
Open positions combine size and P&L only within the same model, symbol and
direction. Entry price and leverage are weighted by size. Different directions
remain separate; differing saved stops are listed individually. Missing mark prices keep
P&L unavailable; saved flat models have zero open P&L. A model with no configured
accounts has no positions, zero open P&L, and unavailable balance.
Home shows entry notional (`size × size-weighted entryPrice`) as combined USD
size and replaces the mark cell with saved stop prices. Position details retain
BTC size, mark and leverage and add USD size. The optional v2 `stopPrices` array
contains sorted distinct positive stops only when every grouped wallet has one.
A shared `stopPrice` remains null when they differ, preserving older clients;
Home displays the midpoint if the spread (highest minus lowest) is at most
0.1% of that midpoint, labelled Stop loss (midpoint); larger differences stay
separate. Position details always retain exact saved stops. Missing stops leave
both the shared value and the list unavailable. This is presentation only:
saved stops and V1 position fields are unchanged.

`real` continues using regular real-trade CSVs and saved state plus Binance
prices. `edge` combines both backtest and live rows from the edge CSV and never
activates saved-state or price reads. Home always requests `real`, `All`, `all`;
Analytics and Trades have independent source, period and model selections.

## MACD strategy charts

Authenticated `GET /v2/dashboard?view=macd` returns a separate indicator document;
it accepts only that exact query. No new proxy path is required.
The response contains `schemaVersion=2`, `instance`, `generatedAt`,
`source` (`bot_spot_closed` or `binance_spot_closed`), `symbol=BTCUSDT`, `intervalMinutes=15`, `status`,
`sampledAt`, and `models`. Each model has `id`, `fastPeriod`, `slowPeriod`,
`signalPeriod` and 96 ordered `points` of `timestamp`, `macd`, `signal`, `histogram`.
Point timestamps are candle opening times; only fully closed candles are included.

When the bot's export is available, `bot_spot_closed` copies its existing MACD
and signal series from evaluation, without calculating EMA or fetching candles
in the dashboard. The API reads one `macd_chart` row from the instance's existing
alert outbox with SQLite read-only/query-only access. The bot captures 96 closed
points per model, collapses identical Wallet B models, and writes one atomic
replaceable document (maximum 64 KiB) on existing bounded notification workers.
It saves chart values, not credentials, wallet identities or trade state. No
archive accumulates; after 1,000 alternating replacements the synthetic SQLite
fixture remained under 256 KiB, including test alerts. The existing alert
retention remains separate. Older worker completions cannot replace newer data.
Stale, missing or corrupt exports retain the last bot chart visibly stale; once
an instance supplies an export in the API process, an outage does not switch it
to independent EMA. A dashboard restart reads the saved export again.

Before the export's human bot activation and first successful evaluation,
`binance_spot_closed` remains the explicitly labelled public-market calculation.
It is not a captured bot evaluation or trade signal. It uses [Binance's public spot klines](https://github.com/binance/binance-spot-api-docs/blob/master/faqs/market_data_only.md),
the bot's 300/900/300, 450/1350/450 and 600/1800/600 spans, recursively seeded
EMA (`adjust=False`), and its 24,999 closed-candle window. MACD is fast EMA minus
slow EMA; signal is the EMA of MACD; histogram is MACD minus signal.
Gaps, partial candles and nonfinite values reject the whole update. The chart
does not imply the bot evaluated that candle or passed its entry gates.

For the public-market source, the backend shares one lazy in-memory candle cache
across authenticated instances;
initial pagination is followed by incremental fetching only on requests for a
new closed candle. Public spot requests use `api.binance.com` with
`data-api.binance.vision` as a validated fallback; each attempt has a four-second
socket timeout and a one-MiB response limit. Throttling/IP bans do not switch
hosts. Validated pages survive a failed seed in memory, so retry resumes at the
missing page; no partial chart is published. An active seed retry is loading,
not a completed failure, and clears the app's previous failure message. There
is no timer, idle collection, balance query or bot file write. Simultaneous requests share the seed/update task. Status is `loading`,
`ok`, `failed`, or `stale`; failures retain the last complete chart visibly stale,
with a 30-second retry cooldown. The app requests this view only in foreground
Strategies, retries loading every two seconds, checks ready data every 30 seconds,
and cancels its requests on tab exit/background. Portfolio WebSockets are unchanged.
The phone saves the last validated complete chart per connection in private app
storage and shows it before the next request. Saved charts are labelled while
refreshing; expired candles remain visibly stale. Loading or failed responses do
not erase a complete chart. Credential changes invalidate the cache, forgetting
an instance deletes it, and no raw dashboard key is serialized into chart files.
First use without a saved chart still requires the server's initial calculation.

## Live BTC and open returns

Real snapshots add optional `btcTicker` and `openPerformance` objects; missing
objects remain compatible with older v2 servers. Edge snapshots return both as
null. V1 responses retain their existing shape and calculations.

`btcTicker` contains `symbol=BTCUSDT`, `price`, `change24hPct` and `updatedAt`.
Price is the last Binance USD-M Futures trade price; the percentage is its
rolling 24-hour change, from the public
[`btcusdt@ticker` stream](https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/ws-streams/market#individual-symbol-ticker-streams).
V2 real viewers subscribe even when their configured trades use another symbol.
The ticker shares the existing viewer-only socket, is independently validated
and expires after 15 seconds; no active viewer means no exposed ticker. A shared,
bounded [public REST startup sample](https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api/market-data#ticker24hr-price-change-statistics)
provides price/change while the socket connects. It runs on viewer acquisition,
shares in-flight requests, reuses fresh samples and has a ten-second failure
cooldown; there is no recurring REST polling. It never seeds mark prices.
V2 stamps real snapshots after reading their prices, so a ticker received during
CSV processing is not discarded merely for arriving after request start.
The phone retains the newest validated ticker through empty snapshots and caches
public price/change/timestamp for up to one day, writing at most every 15 seconds.
Cached/offline quotes show Last price until the connection and 15-second freshness
checks pass; this cache never changes portfolio or Analytics snapshots.
Mark prices still calculate open P&L. No idle collection or balance polling is added.

`openPerformance` has four nullable numeric fields: `investedUsd`,
`entryEquityUsd`, `investedReturnPct`, and `entryEquityReturnPct`.
Optional `usesIdleBalances` and `idleBalanceSampledAt` identify a mixed capital
basis and the oldest included idle sample; old servers omit these fields.
Invested USD is the sum of saved `pos_size_quote / leverage` for active wallets
within the selected model scope, calculated before merging A/B positions. If
saved notional is absent, known saved size times entry price supplies notional.
Opposing positions add capital separately. Missing or invalid required capital
makes that denominator unavailable; no leveraged notional is used as investment.

The all-wallet capital denominator includes every configured wallet in the
instance, regardless of the selected model. Active wallets use positive saved
`entry_capital` with a known entry time, retained in trade state until closing.
Flat wallets use their latest successful current perpetual equity sample from
the existing demand-only balance request. Zero idle equity is valid. Per-wallet
samples stay private; model totals cannot identify which wallet is active.
An active wallet's current equity never replaces missing entry capital.

A flat wallet's balance fetch must begin at or after its saved flat state was
written; an older sample could contain its previous position's open P&L. A trade
closing after the sample therefore needs manual refresh or instance reopening.
Missing/failed idle samples or missing active entry capital make the whole
denominator unavailable. A later failed idle request replaces the earlier
sample rather than silently reusing it. A failed current query for an active
wallet does not invalidate its saved entry capital. Streams only read existing
samples, even after the ten-second request-sharing cache expires; no balance
queries or polling are added. Balance cards retain explicit stale/failure labels.
The app shows the displayed snapshot's update time once beside Read-only portfolio;
sample timestamps remain in the API, independently of live prices.

Each return is combined open P&L divided by its complete positive denominator,
times 100. Missing P&L or a zero/unavailable denominator produces a null return.
Home requests all models. Its [Prism overview](../README.md#app-behavior) combines
BTC, instance balance equity and open P&L without changing these data sources.
The headline values share a fitted size; the BTC 24-hour change and open returns
use half that size. The overview ends after the two percentage labels. Active
positions and balance details follow. Analytics retains its blue P&L gradient
and metric cards stretch to a common height per row. Analytics and Trades place
their heading above the period bar, with source/model selectors side by side;
Trades has no search bar.

## Closed-trade pairing and accounting

Within an instance, a Wallet A row pairs with Wallet B only if model, symbol,
direction and source match, both opening times are known, and the absolute
opening and closing differences are each at most 60,000 milliseconds. Each row
must have exactly one candidate in the original set. Ambiguous matches remain
separate, without greedy matching or pairing after removing other candidates.
Backtest and live rows never pair with each other. Explicit account `wallet`
mapping wins; legacy configs can derive wallet from their unique edge model
suffix (`-B`), or exact `a`/`b` IDs. Unknown wallet mappings remain unpaired.

Paired opening time is the earliest, closing time the latest. Period filtering
happens after pairing, including v2 monthly results. Paired net P&L and fees are
summed once; `pnl_usd` already includes fees. The pair counts as one closed trade,
and wins only if combined net P&L is positive. Curves and monthly dollar P&L use
these same rows. Paired entry/exit prices are the arithmetic mean because legacy
closed CSVs provide no size for weighting; they do not imply a known combined
fill price. Recorded historical combined equity, funding and percentage analytics remain
unavailable. V1 retains its original separate-row calculations.

## Edge Monitor equity and chart inspection

The optional v2 `normalizedEquity` object contains `baseEquity=10000`,
`legacyCapitalEstimate`, optional `drawdown` with `usd` and `pct`, and `points` with UTC-millisecond `timestamp` and
`equity`. This is the Edge Monitor's **$10k reference equity** calculation,
not recorded account balance history. It is available for either source and
any model selection when the selected, paired trade history is complete and
has sufficient capital metadata. Real money reads regular account trade CSVs;
Backtest reads both backtest and live rows from the configured edge CSV. The
chart never substitutes one source's history for the other.

After unique A/B pairing and period filtering, capital is rebuilt from the
first available raw cumulative capital minus that raw leg's P&L, followed by
the paired P&L sequence. Backtest returns use successive rebuilt backtest
capital. Live returns prefer recorded post-close `combined_equity` minus the
trade's P&L; otherwise they use position quote size divided by leverage and
model weight (M1 40%, M2 20%, M3 40%), matching the monitor's legacy convention.
The curve compounds these fractional returns on $10,000. Regular CSV
`final_capital`/`pos_size` map to edge `cum_capital`/`pos_size_quote`. Legacy
estimates are labelled. Missing required capital leaves the curve unavailable;
it does not create a zero return. YTD begins at January 1 UTC on the $10k base.
This reference curve does not populate cash-flow-adjusted return, Sharpe or
recorded percentage drawdown metrics.

Cumulative P&L and reference equity show a closed-trade line and a separate
captured point including active P&L. Raw P&L adds captured saved-position P&L
from Binance marks once. Reference equity applies the current open return
using saved active entry capital plus sampled flat-account idle capital.
This follows the monitor's compounding convention while avoiding its exchange
queries: no bot imports, bot environment access or new balance polling.
Marks already stale at capture or missing capital make that open point unavailable;
historical open P&L is not invented. Backtest never receives real positions.
Recorded equity remains a separate view available only for actual samples.
Daily/monthly bars group closed P&L in UTC. Tap, drag or use previous/next controls
to inspect points; axis ranges include both the closed curve and captured point.
The last closed point retains its actual close timestamp. A dashed estimate
connects it to the captured point at the snapshot timestamp across the elapsed
time axis; no synthetic close is appended at now and no open-P&L history is inferred.

Analytics displays backend `normalizedEquity.drawdown` directly. The backend
uses Edge Monitor's `_equity_and_drawdown_from_returns` definition on the same
source-aware compounded returns as the reference chart. Include untouched
starting equity in the running peak, then take the largest percentage drop below
that peak; expose its positive magnitude as `pct`. `usd` is the largest dollar
drop on the $10k reference curve. These maxima may occur at different troughs.
Compute both before chart timestamps are coalesced so simultaneous closes cannot
hide a trough. The selected source, period and model scope is preserved. Real
money reads regular trade CSVs; Backtest includes edge backtest and live rows.
Original instance equity, current balances and active P&L are not denominators
for this closed-trade metric. Missing capital or incomplete history leaves it
unavailable; older v2 responses without `drawdown` also remain unavailable,
with no Android calculation substituted. This adds no balance requests.

Analytics fetches one HTTP snapshot on tab entry and holds it until a filter
change, manual refresh, instance switch or foreground resume. Stream packets
do not fetch or replace its snapshot, including Real money / All models / All.
The badge says Snapshot and the chart legend says Open at snapshot. Capture-time
freshness uses the server's timestamps, so the phone clock and later UI ticks
cannot drop the captured open point or repeatedly rescale the chart. A mark
arriving within one second after `generatedAt` is accepted as the bounded server
capture race; larger future timestamps remain unavailable. This is a historical
estimate at the displayed timestamp, not a continuously live balance. Home
continues following the real stream; Trades continues refreshing its selected
history on stream updates. Neither navigation policy adds balance requests.

## Demand-only perpetual balances

`GET /v2/balances` accepts no query parameters. After authentication, it queries
only the configured public sub-account addresses for the key's instance using
Hyperliquid `clearinghouseState`, extracting `marginSummary.accountValue` as
[current perpetual account equity](https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint/perpetuals#retrieve-users-perpetuals-account-summary).
It uses no trading keys, bot imports, bot environment access or signing SDK.
Public addresses are private configuration and never appear in API responses,
logs or source. Setup and service changes are [human-run](UPDATE.md).

Main sums six configured accounts; Vinay and Madhu each sum three. HTTP calls
share one in-flight fetch per instance. Results completed less than ten seconds
ago are reused, including failures. A cancelled viewer cannot cancel a shared
fetch. Balance fetching has no timer: the Android app requests it only when
opening/selecting/resuming an instance or on manual refresh. Stream updates,
reconnection retries and Analytics/Trades filters make no balance requests.

The response contains `schemaVersion=2`, `instance`, `generatedAt`, `attemptedAt`,
`sampledAt`, `status`, `equity`, and `models` (M1, M2, M3, each with `id`, `equity`,
`sampledAt`, `status`, and optional `walletAllocations`). Timestamps are UTC Unix milliseconds. `sampledAt` is the
oldest successful sample in a complete sum; it is null when that sum is unavailable.
`attemptedAt` reports the fetch attempt, including failures. Balance timestamps
are independent of Binance prices and the stream's generated time.
Optional instance-total fields `originalEquity`, `pnl` and `returnPct` use the
instance's dashboard-owned `originalEquity`, imported from its `ORIGINAL_EQUITY`
through human-run setup. P&L is complete current equity minus that positive
baseline; return is P&L divided by baseline times 100. Missing baseline or an
incomplete current balance leaves both return fields null. Home displays this
under the instance total as USDT (percentage). No original model allocations
are inferred. This equity difference includes funding and account cash flows.

Statuses are `ok`, `failed`, `unconfigured`, and model-only `not_configured`.
Failure or missing addresses make the affected model and instance total null;
complete other model sums may still be shown. A model absent from configuration
does not invalidate the instance's configured total. Never show a partial sum as
full equity. Failures expose no provider payload. On client/network failure,
the last successful sample may remain visible with an explicit stale label;
its timestamp stays in the API. Returning to the foreground marks old samples stale until
the requested balance response arrives. No background refresh is introduced.

`walletAllocations` contains only configured wallet labels A/B and `pct`: each
model's equity in that wallet divided by that wallet's total equity times 100,
matching Telegram System Health. It reuses the same balance samples; no extra
queries or private account identifiers are added. Incomplete samples, unknown
wallet mappings, or a nonpositive wallet total leave percentages null. Absent
models have an empty list. Home keeps combined model dollars and shows rounded
whole percentages beside them: `$2,005.00 (A: 40%, B: 40%)` for two-wallet instances,
or `$2,005.00 (40%)` for single-wallet instances. Null percentages show `—`;
older backends without this field retain dollar-only rows. Stale labeling covers
both amounts and percentages, and narrow screens can wrap the suffix.
