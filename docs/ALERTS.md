# Native bot alerts

Bot alerts use the bot's actual messages and recorded evaluation outcomes.
[Combined open-P&L milestones](#combined-open-pl-milestones) are explicitly
generated on the phone from validated dashboard samples. Bot alerts do
not compare positions, guess fills, depend on Telegram, use ntfy, or import bot
credentials into the dashboard. Telegram stays active during verification.

The reviewed notification change writes scrubbed text, time, a random event ID
and an increasing sequence to `dashboard_alerts.sqlite` before Telegram sending
or queueing. Automatic messages, direct alerts and risk edits are included.
With the Telegram-menu fix deployed in the bot, user-requested command replies,
confirmation prompts and chart captions stay in Telegram only; existing APKs
need no rebuild. Automatic missed-entry approval alerts remain included.
Continuous pinned portfolio updates are excluded.
The app's existing Analytics charts provide chart rendering; PNG attachments
and Telegram buttons are not copied. Telegram commands remain Telegram commands;
the app does not expose trading controls. Captured evaluation outcomes supply
the actual reasons models did not enter without requiring a Telegram command.

The outbox retains 2,000 events per instance. Local SQLite commits use FULL
durability, and a 250 ms busy timeout. Permission, storage or busy failures log
a fixed warning without leaking payloads and do not interrupt trading or
Telegram. Such failed writes cannot guarantee Android delivery.

## API

`GET /v2/alerts?after=SEQUENCE` and WebSocket `/v2/alerts/live?after=SEQUENCE`
use the existing dashboard bearer key. The key determines the instance; clients
cannot select another instance or a filesystem path. Responses contain
`schemaVersion`, `instance`, `status`, `events`, `cursor`, `oldest`, `newest`,
`gap`, `hasMore` and `generatedAt`. Events contain `sequence`, `id`, `timestamp`
and `message`. A ready response returns at most 100 ordered events. Starting at
zero gives the most recent 100; subsequent batches replay after the saved
cursor. Retention expiry or an outbox reset explicitly sets `gap`. An absent,
unreadable or corrupt outbox returns `status: unavailable`, without advancing
the client's cursor.

The legacy authenticated socket polls only the outbox and key revocation every three
seconds. New events and changed feed status are sent on the next read; unchanged
feeds send no empty acknowledgement or application heartbeat. The server alone
sends a WebSocket protocol ping every 60 seconds, with a 30-second pong timeout;
the app replies automatically. This legacy mode never requests prices, balances,
trade state, or Analytics snapshots. New apps opt into the milestone samples
described below. HTTP and WebSocket v1 behavior is retained.
The dashboard service has a read-only mount of the bot runtime directories and
no access to bot environments. `alertsPath` is private dashboard configuration,
derived during human setup from each instance's configured state directory.

## Combined open-P&L milestones

New Android alert sockets add `X-Dashboard-Pnl: 1`. The authenticated backend
adds optional `openPnl` to event batches: `positionsKey` (opaque SHA-256), `value`
(USD), `activeCount`, `sampledAt` and nullable `priceUpdatedAt`. The key selects
the instance; the total includes every configured model/wallet, independent of
Home’s model filter. It reuses Home’s saved-position validation and public
Binance mark P&L calculation, excluding realized P&L, fees and funding. It is
not an exchange-position verification. No balance or Analytics requests occur.
Only connected opt-in receivers hold a shared public-price subscription; it is
released on disconnect. Missing prices never delay actual bot events. Old apps
retain the outbox-only contract, and old servers ignore the new header while
continuing ordinary bot delivery.

The server samples on the existing three-second alert poll. Missing/corrupt
state or history, a closed-history/state write race, unknown entry time, or any
active wallet’s missing/future/over-15-second-old mark returns `openPnl: null`,
never a partial total. Flat portfolios return zero with no price timestamp.
Only changed totals, position sets or availability generate extra frames;
sample-time changes alone send no heartbeat. Bot outbox failure does not block
an otherwise valid P&L sample. All access remains read-only.

The phone tracks signed $1,000 bands: +1,000, +2,000, −1,000, −2,000, etc.
Each level has an independent 15-minute cooldown. A newly crossed level alerts
immediately; crossing the same level again in either direction stays quiet until
15 minutes after its last alert. Quiet re-crossings do not extend that expiry.
Zero is not a milestone. Eligible crossings send current combined P&L and the
last eligible crossed level with its direction. A sample jumping several levels
produces one message counting only levels eligible to alert. For example,
+$950 → +$2,045.61 reports current +$2,045.61, crossed +$2,000 ↑ (2 levels).
A subsequent +$1,999.99 is quiet during that level’s cooldown; reaching +$3,000
can alert immediately. Expiry alone never generates an alert without a crossing.
Cooldowns are persisted as compact level ranges, so large jumps do not allocate
one record per $1,000 mark.

First observation and a changed position set (entry, addition, trim, reversal
or close) establish silent baselines; a changed stop alone does not reset them.
Every new socket connection establishes a quiet baseline. Missing, future or
over-30-second-late totals invalidate the crossing baseline; recovery is quiet.
Out-of-order totals cannot replace an accepted sample. Cooldowns and the local
event sequence survive reconnects and availability gaps for the same position set.
A continuous connection may have long intervals between changed totals; those
intervals alone do not invalidate its baseline.
The baseline, cooldowns, local sequence, history and pending notifications share
one private ledger commit before posting; a second commit clears pending delivery.
Local milestone retries are allowed only within the same uninterrupted stream,
for the same open position set, while both event and sample are at most 30 seconds old.
Reconnect drops pending local milestone delivery but retains its inbox history.
Durable bot events still replay across disconnects and process restarts. Deterministic
IDs make retries idempotent. Same-band quotes stay in memory; only baseline validity,
position/band changes, cooldowns, crossings or bot delivery changes checkpoint the
ledger, avoiding a whole-inbox rewrite on every tick. Offline crossings are not inferred.
Milestones use the existing floating reports channel, instance bells,
master switch, privacy and inbox behavior. Muted crossings still advance local
history/baseline; unmuting does not replay them. Forgetting clears them.

## Android

Allow notifications when prompted. Settings → Bot alerts controls background
delivery and shows connection status for every saved key. Open alerts and notification
taps open a dedicated page with instance filters, date groups and separate event
cards. Fields are labelled, test metadata stays under Full message, and the full
original message remains selectable. Back returns to the previous tab; opening
alerts does not change its filters or fetch balances. Alert history retains
up to 1,000 events per saved connection in private app storage; backups are
disabled. Initial history loads silently. New events are posted with stable IDs
and a durable pending-delivery record, so reconnection and a process interruption
replay idempotently. Events missed during a disconnected period remain replayable while
retained on the server. An expired-history warning remains visible.

Settings → Connections has a bell between Open Home and Forget for each instance.
Tap it to enable or mute that instance's notifications on this phone; a crossed-out
bell and Muted label show the saved choice. Existing and new connections default
to enabled. The Bot alerts switch remains the master control, and Android
notification permissions and channel settings still apply. Muting keeps the
instance's alert stream, history and replay cursor active, so enabling it again
does not send notifications for events already recorded while muted. Forgetting
a connection removes its notification preference with its local alert history.

Routine `Evaluation outcome` messages with `Stage: no_cross` and a `no MACD cross`
reason are suppressed from Android notifications and Bot alerts history for both
wallets. Their sequences still advance the replay cursor; they cannot block later
events. Existing saved routine rows are hidden immediately and pruned on replay.
Actual signal rejection reasons, errors, failures, entries/exits and risk alerts
are preserved. The bot outbox and Telegram behavior remain unchanged.

Entries/exits, urgent risk, risk updates and reports have separate high-importance
Android channels, eligible for floating banners, sound and vibration. Existing
installations get new floating trade/risk/report channels because Android cannot
raise an existing channel's importance. Explicitly blocked legacy categories
stay blocked; user channel settings and Do Not Disturb remain authoritative.
Shade and floating alerts use native BigTextStyle with no large avatar or
extra sender/category heading. The instance appears in Android's small header;
each alert's own first line becomes its title once, with the remaining message
on expansion. P&L milestones in both notifications and the inbox lead with
`📈 Rose above +$3,000` or `📉 Dropped below +$3,000`, followed by current P&L.
The sign identifies the level, while the verb/icon identifies movement; recovery
can therefore say `📈 Rose above −$3,000`. Multi-level counts remain visible.
Entry previews lead with direction/entry/SL, closed trades with P&L, and skipped
trades with their reason. Original emoji, paragraphs and fields remain available.
Stored messages remain unchanged; old inbox milestones use the clearer directional
heading too. Each instance/category
has one expanding card; urgent risk stays separate from routine reports.
Category channel settings remain authoritative. A new event can alert again; replaying an event already in
the card updates it quietly. Rapid bursts remain buffered until Android
acknowledges the submitted card. The silent grouped summary counts only pending
cards, rather than the whole saved history. Each card retains up to 100 recent
event IDs and displays the latest six messages, latest details first; larger
counts show 100+. Android may truncate expanded text; complete messages remain
in authenticated history. The Open dashboard action uses the same locked route
as tapping the notification and offers no trading action.

Swiping away a card starts its next count fresh; summary counts refresh on
dismissal. Opening the authenticated alert page clears notifications for the
displayed instance (or all instances in the combined view), while Home opening
does not. Muting or forgetting also clears that instance's shade cards; history
is preserved when muting or viewing it. Lock-screen public content hides trading
details. Taps open authenticated history with the complete saved message when
Android limits the expanded text. No trading/reply actions or automatically
suggested link actions are offered. Android/HyperOS, channel settings and Do Not
Disturb determine the final banner appearance, available text width and sound;
the preview mockups do not guarantee identical rendering or floating delivery.

A special-use foreground service maintains the alert connection after leaving
the UI. Settings → Bot alerts → Hide connection indicator opens Android's
channel settings: disable only Background alert connection to hide its quiet
ongoing notification while leaving real alerts enabled. The service still posts
the notification required by Android. Floating notifications opens the app's
Android notification settings; enable floating alerts there on HyperOS.

One shared HTTP client serves the separate authenticated instance sockets.
Validated network callbacks pause sockets and retry timers offline, and reconnect
immediately on recovery or Wi-Fi/mobile handover. Other failures retry with
exponential backoff and jitter, capped at two minutes; ready delivery resets it.
There are no wake locks, recurring balance requests, polling alarms or duplicate
client keepalives. The portfolio connection still stops in the background.

The sticky receiver survives ordinary recent-app dismissal and requests restart
after boot (following device unlock) or an APK update, only with saved connections,
alerts enabled and notification permission. HyperOS autostart and unrestricted
battery settings help normal background operation. Android Settings → Force stop
blocks these restarts until the user opens the app again; autostart cannot override
that state. Phone shutdown, network outages, Doze and manufacturer restrictions
can interrupt delivery; this is not an FCM push service. Verify actual screen-off
delivery and battery use on the device before relying on it instead of Telegram.

## Human deployment and rollback

1. Update the dashboard using the existing backup/rollback updater pinned to the
   reviewed dashboard commit. Login keys and credential storage are preserved.
2. Run the following human-only command to prepare the three empty outboxes and
   grant the existing dashboard reader group access. Existing events are
   preserved; only the dedicated outbox's metadata changes. No env file, trade
   state contents or service is accessed by this helper.

   ```bash
   sudo python3 /home/coder/HLTRADINGBOT/Trading_Dashboard/backend/deploy/configure-alert-access.py /var/lib/trading-dashboard/config.json
   ```

3. Record, review and deploy the bot notification change through the bot
   repository's normal full-manifest release process. The implementation is
   prepared against protected v2.4 in the isolated `native-alerts-bot` branch.
   Follow `INSTRUCTIONS.md` Step 7 and `RUNBOOK.md` staging, pending integrity,
   human activation and healthy-boot packets. Preserve local work. Never copy
   an individual bot program into production or activate the bot from the
   dashboard updater. The three-file manifest, pre-restart exchange snapshot,
   backup and human-selected version remain required.
4. Install the versioned APK, allow notifications and open Alert history.
   Confirm Main, Vinay and Madhu are connected and actual bot startup events
   appear. Test background/screen-off delivery on each intended phone.
5. Keep Telegram enabled until Android delivery has been verified. A controlled
   Telegram outage must still leave actual events in the outbox and app. Do not
   generate a real order merely to test notifications.

Dashboard rollback uses the backup path printed by `deploy/update.sh`; it
preserves the current login database and keys. Bot rollback follows its human
full-manifest activation procedure and the pre-release snapshot. Retain the
outboxes across rollback; an older bot simply stops writing new app events.

## Verification evidence

Offline bot tests cover capture before Telegram sending, Telegram failure,
ephemeral fallback without duplicates, queue saturation, scrubbed payloads,
actual evaluation gates, concurrent writers, retention, permissions, restart
and storage failures. Structural comparison restricts changed functions to
notification handling, diagnostics export and the pinned-panel opt-out.
`edge_engine.py` and `account2.py` are byte-identical to protected v2.4.

Backend tests cover authentication, instance scope, legacy outbox-only behavior,
opt-in P&L/Home parity without balance requests, sample failures, read-only replay,
paging, missing/corrupt feeds,
expired/reset cursors, revocation and human reader setup with synthetic files.
The loopback HTTP/WebSocket smoke checks exercise actual event replay over the
real ASGI server. Idle-stream regressions prove no empty feed traffic while new
events still deliver on the next read. Android tests cover presentation, channel
migration, source-ID deduplication, replay, bootstrap, gaps, feed validation,
authenticated URLs, offline suspension, network handover, retry cancellation,
bounded jitter, healthy recovery, boot/update eligibility, signed milestone
crossings/recovery, cooldown expiry and independent levels, silent baselines,
position-set resets, mute, durable bot replay and fresh-only local milestone delivery.
Actions runs unit tests, lint and builds with the existing permanent signature.
These offline checks do not prove live fills, exchange rejections, device
background reliability, real VPS permissions or long-running behavior.
