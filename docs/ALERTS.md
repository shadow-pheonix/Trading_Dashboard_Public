# Native bot alerts

Alerts use the bot's actual messages and recorded evaluation outcomes. They do
not compare positions, guess fills, depend on Telegram, use ntfy, or import bot
credentials into the dashboard. Telegram stays active during verification.

The reviewed notification change writes scrubbed text, time, a random event ID
and an increasing sequence to `dashboard_alerts.sqlite` before Telegram sending
or queueing. Temporary messages, command replies, direct sends, risk edits and
photo captions are included. Continuous pinned portfolio updates are excluded.
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

The authenticated socket polls only the outbox and key revocation every three
seconds. New events and changed feed status are sent on the next read; unchanged
feeds send no empty acknowledgement or application heartbeat. The server alone
sends a WebSocket protocol ping every 60 seconds, with a 30-second pong timeout;
the app replies automatically. It never requests prices, balances,
trade state, or Analytics snapshots. HTTP and WebSocket v1 behavior is retained.
The dashboard service has a read-only mount of the bot runtime directories and
no access to bot environments. `alertsPath` is private dashboard configuration,
derived during human setup from each instance's configured state directory.

## Android

Allow notifications when prompted. Settings → Bot alerts controls background
delivery and shows connection status for every saved key. Open alerts and notification
taps open a dedicated page with instance filters, date groups and separate event
cards. Fields are labelled, test metadata stays under Full message, and the full
original message remains selectable. Back returns to the previous tab; opening
alerts does not change its filters or fetch balances. Alert history retains
up to 1,000 events per saved connection in private app storage; backups are
disabled. Initial history loads silently. New events are posted with stable IDs
before saving the cursor, so reconnection and a process interruption replay
idempotently. Events missed during a disconnected period remain replayable while
retained on the server. An expired-history warning remains visible.

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
The grouped summary stays silent on its own low-importance channel.
Expanded notifications retain the full text. Lock-screen
public content hides trading details, and taps open the app's authenticated
alert history. No notification can place, close or modify a trade.

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

Backend tests cover authentication, instance scope, no saved-state inference,
no price/balance requests, read-only replay, paging, missing/corrupt feeds,
expired/reset cursors, revocation and human reader setup with synthetic files.
The loopback HTTP/WebSocket smoke checks exercise actual event replay over the
real ASGI server. Idle-stream regressions prove no empty feed traffic while new
events still deliver on the next read. Android tests cover presentation, channel
migration, source-ID deduplication, replay, bootstrap, gaps, feed validation,
authenticated URLs, offline suspension, network handover, retry cancellation,
bounded jitter, healthy recovery and boot/update eligibility.
Actions runs unit tests, lint and builds with the existing permanent signature.
These offline checks do not prove live fills, exchange rejections, device
background reliability, real VPS permissions or long-running behavior.
