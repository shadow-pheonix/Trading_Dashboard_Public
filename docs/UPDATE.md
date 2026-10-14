# Dashboard update and rollback

This upgrades only the dashboard API and its existing HTTPS proxy. Bot code,
services, runtime files, permissions and environments remain untouched. Dashboard
login keys and the credential database are preserved; APK signing stays in Actions.

## Coder-managed dashboard after the one-time migration

The owner explicitly approved dashboard-only AI management on 2026-10-09.
The root repository's `SECURITY_BOUNDARY.md` `[DASHBOARD-MANAGEMENT]` is the
permission boundary. Initial root setup remains human-run. It migrates reviewed
backend releases, their Python environments and existing APKs to
`/home/coder/HLTRADINGBOT/.dashboard-runtime`, owned by `coder`. The API continues
as the isolated `trading-dashboard` account. Its existing private config, login
database and key files stay in place; migration neither imports bot env files
nor changes bot code, state, permissions or services.

The human runs the prepared `dashboard_v2_update/manage-dashboard-setup.py`
launcher once. It loads checksum-pinned committed migration code, backs up the
legacy backend and parent-directory ACLs, and grants the API account
traversal-only access to the two coder workspace parents. Namespace binds expose
only dashboard code/assets; bot and secret masks remain in place. It installs
root-owned startup verification and `trading-dashboard-control`, with sudoers
limited to check, status, restart-api and restart-https. Candidate imports run
as `trading-dashboard`, with output suppressed. Arbitrary commands, service names
and bot activation are rejected. The proxy must already use a non-root account.
An unsuccessful migration restores its ACLs, public assets and dashboard units.
Existing APKs move rather than being copied; no extra APK backup is retained.
The proxy keeps `ProtectHome=yes`: its public APK directory is mounted read-only
at the existing `/opt/trading-dashboard/app-updates` path, with no symlink into
the hidden coder home. Failure output names the failed dashboard step and exit
code; it never prints private configuration or response bodies.

When running the repository migration script directly, pass the reviewed commit
and private HTTPS origin; the public source contains no deployment hostname.

Then Codex can run, as `coder`, from the clean published dashboard checkout:

```bash
python3 backend/deploy/manage.py deploy EXACT_REVIEWED_COMMIT
python3 backend/deploy/manage.py rollback
sudo -n /usr/local/sbin/trading-dashboard-control status
bash scripts/host-app-update.sh v0.1.VERSION
```

Deployment builds a reviewed immutable release, verifies startup as the service
user, switches the current link atomically, and restarts only the dashboard API.
Failed readiness restores the prior code and restarts the API. Healthy repeat
deployments avoid restarts; a lock excludes simultaneous deployments. Rollback
changes code, never the current login database. Publication uses coder-owned
assets, checks the permanent certificate and existing metadata/checksum guards,
and retains the newest three versions. No bot restart, env read or guessed alert
is needed: the API reads the bot's existing durable alert outboxes. Private
balance configuration remains unchanged. Future configuration or proxy changes
outside these permissions still need a separately reviewed human setup.

The legacy root update instructions below apply before migration. After migration,
use the coder commands above instead of root `update.sh`/`rollback.sh` or the
combined bot/dashboard deployment command. The human's regular bot command remains
`sudo /usr/local/sbin/hlmacdbot-activate`.

## Prepare the exact reviewed release

Use a separate Git checkout of `shadow-pheonix/Trading_Dashboard` on the VPS,
within the development workspace. Select the released APK's commit from
`build-info.txt`; do not install unreviewed working files. Review
[update.sh](../backend/deploy/update.sh), [rollback.sh](../backend/deploy/rollback.sh),
[private setup](../backend/deploy/configure-balances.py),
[service-user preflight](../backend/deploy/check-startup.py) and
[proxy allowlist](../backend/deploy/Caddyfile). The existing installation must have
unchanged requirements, its dashboard credential database, and the Caddy HTTPS
proxy. The scripts refuse another layout.

The human-run root setup imports the 12 public sub-account addresses from fixed
instance files: `/etc/hlmacdbot/hlmacdbot.env`, `hlmacdbot-vinay.env` and
`hlmacdbot-madhu.env` in the same directory. Main uses `HL_M1_SUB_WALLET`,
`HL_M2_SUB_WALLET`, `HL_M3_SUB_WALLET` and their `HL_B_` counterparts; Vinay and
Madhu use the three Wallet A fields. Extra Wallet B fields in either instance
are ignored, preserving the confirmed six/three/three dashboard scope.
Setup also imports each instance's positive literal `ORIGINAL_EQUITY` into its
private dashboard configuration. Missing values leave the original-equity
return unavailable; malformed or duplicate values fail before service changes.

No address entry is required. Only these addresses and original equity are parsed; unrelated
fields are ignored and no shell expansion or execution occurs. Files must be
regular, root-owned, mode `0600`, and not symlinks. Missing, malformed, duplicate,
zero or conflicting addresses fail before any service stops; a saved address
does not mask a missing env value. The candidate private configuration is
validated before installation. No values are printed. The API service retains
its existing prohibition on access to `/etc/hlmacdbot`; the importer is transient,
root-owned and never installed in the service directory. The AI must never run
it against real env files.

The owner explicitly approved this human-run public-address import exception
on 2026-10-08 after being asked whether to permit it under
`SECURITY_BOUNDARY.md` `[SECRET-NOREAD]`. This approval permits neither AI env
access nor importing trading keys, and adds no AI privileged command.
The owner subsequently confirmed using each instance's env `ORIGINAL_EQUITY`
for the balance return; that value is handled by the same human-run importer.

## Run the upgrade

From that clean reviewed dashboard checkout, a human administrator runs:

```bash
git rev-parse HEAD
sudo bash backend/deploy/update.sh EXACT_REVIEWED_40_CHARACTER_COMMIT_SHA
```

The script checks the commit, stages only named committed backend modules,
imports public addresses privately, and validates configuration and the proxy before
stopping anything. Both staged code directories are explicitly `0755`, so the
private setup umask cannot make the installed backend root-only. Before any
service stops, the script runs candidate CLI/runtime imports, configuration
validation and a read-only database schema check under `trading-dashboard`.
This check makes no bot reads, price connections or balance queries. The work
directory is root-owned `0710` with the dashboard group allowed to traverse it;
the candidate config is root-owned `0640` for that group, while the env importer
remains root-only `0600`. Installed config remains dashboard-owned `0600`.

It creates a root-only backup under
`/var/backups/trading-dashboard/v2-XXXXXXXX`: old code, configuration, proxy,
installed commit, consistent SQLite backup and dashboard key files. Save the
printed backup path. The backup contains private data; keep it root-only.

It then stops only `trading-dashboard-api.service` and
`trading-dashboard-https.service`, replaces dashboard code/configuration and the
proxy allowlist, and starts those two services. It does not reinstall dependencies,
modify service units, recreate login keys or change the credential database.
A failed upgrade invokes dashboard rollback. The CLI health check proves process
readiness only; authenticated and exchange checks below remain necessary.

## Verify after the human upgrade

The latest update requires the dashboard backend for Edge Monitor drawdown,
YTD and reference equity. See the [chart contract](API-v2.md#edge-monitor-equity-and-chart-inspection).
Verify YTD excludes prior-year closes after pairing and starts at January 1 on
the $10k reference scale. Real money must use regular trade CSVs, and Backtest
must keep its edge history. Both chart views include an open-P&L point captured
with fresh marks and sufficient capital. This adds no balance polling or bot changes.

Install the released APK as an update, retaining its data. Confirm that existing
saved keys connect with their original stored addresses, the form is hidden, and
Add another key accepts an [encoded connection key](API-v2.md#private-connection-keys).
Home must show the Prism overview: BTC with bracketed 24-hour change beside instance
equity, combined open P&L and two smaller return percentages below, then M1/M2/M3 active trades and the instance total
and M1/M2/M3 balances with [wallet allocation percentages](API-v2.md#demand-only-perpetual-balances).
Compare the percentages with Telegram System Health for both single- and two-wallet instances.
Opposing A/B positions must show both directions. Check
each instance, the single update time beside Read-only portfolio, and that failed balances are unavailable or
explicitly stale. Compare balances privately with current perpetual account
equity for the configured addresses.

Check that invested return uses the sum of each active wallet's saved notional
divided by its leverage. All-wallet capital combines active wallets' saved entry
equity and flat wallets' sampled current equity. Confirm sample timestamps privately in the API;
refresh after a newly closed trade. Missing entry capital or a failed idle
sample must leave the percentage unavailable. BTC price should match the P&L
number's size, and the bracketed change should be half that size. Check the dot
before the BTC dollar symbol: green on a higher accepted quote, red on a lower
one, unchanged on equal quotes; neutral before comparison or while stale/offline.
Older or missing quotes and navigation must not reset movement. Equity uses the
same balance sample as Account balances, retaining unavailable/stale states.
An older v2 server can still connect but lacks these optional fields; update the
dashboard backend to enable them. Check Prism's three matching, prominent headings
and absence of an estimate badge in Light and Dark
settings, including narrow screens and larger system text. Model names lead active trade cards; USD size, open
P&L, entry/stop prices and position details stay available. Balance rows must
show each model once, retaining stale/unavailable labels.
Check USD position size and both BTC/USD sizes in position details. Different
wallet stop prices within 0.1% of their midpoint must show that midpoint on
Home, with exact prices in details; larger differences stay separate, and a
missing wallet stop leaves the combined value unavailable. Reload Home, switch
instances and restart the app: a saved BTC quote should appear as Last price during refresh,
then become Live only with a healthy connection and a fresh timestamp. Empty
responses must not clear it. Check aligned Analytics metric rows and its blue
P&L gradient in both themes and with large text.
Compare the instance balance's USDT (percentage) return against its original
equity setting; no model baseline is invented. Strategies must show MACD and
signal EMA lines with histogram bars for M1(300), M2(450) and M3(600), using the
[closed-candle chart contract](API-v2.md#macd-strategy-charts). Check tap/drag
inspection, loading/stale states and both themes; Home retains position details.
After human activation of the bot export and its first complete evaluation,
Strategies must say Bot-calculated spot candles; compare values with the bot's
MACD State report. Export storage replaces one bounded row in the existing
outbox and reuses its permissions; no additional root setup is needed. Restart
reads the saved export again. Export-read failure retains a stale bot chart,
rather than substituting independent EMA values once a bot chart has been
received in the API process.
Reopen Strategies after a successful load, including after closing the app: saved
charts should appear before refresh completes. Forgetting an instance must clear
its chart cache. Test the dedicated Alerts page, instance filters, Full message,
notification focus and Back navigation. Settings help and Analytics calculation
notes expand on demand; all monetary values stay visible.
Confirm routine no-cross evaluations are absent from notifications/history,
while blocked actual signals, errors and trade alerts remain visible.
Use the [background alert controls](ALERTS.md#android) to hide only the connection
indicator and enable floating alerts. Verify background delivery after ordinary
recent-app dismissal, screen-off, network handover, reboot/unlock and APK updates;
Force stop requires reopening. Check that disabling alerts stops the receiver.

Confirm Analytics and Trades headings above the period bar, source/model filters
beside each other, no Trades search bar, paired counts, and
Analytics' Max drawdown display as USD (percentage), with unavailable
values when trade capital is missing. Both values must come from the backend's
Edge Monitor compounded-equity calculation; USD uses the $10k reference scale
and percentage uses its running peak, including model and Backtest selections. Confirm
that switching them leaves Home real. Background the app and confirm no recurring
Hyperliquid balance requests. Navigate through every tab and change filters:
the connection must stay open. Analytics must hold its snapshot through repeated
live updates and time passing, including the All models / All view. Re-entering
Analytics, changing a filter or manual refresh must load a new snapshot; Home
and Trades must continue updating. Inspect the final dashed chart segment:
the last close and current snapshot must occupy their own time positions, with
the elapsed time visible horizontally. Test the Fingerprint unlock switch across
closing/reopening: disabled opens directly; enabled requires Android authentication
after closing, while tabs/background/resume preserve an unlocked session.
Confirm background/close releases it and
resuming reconnects once. Check monthly P&L's neutral tiles and colored text in
Dark mode. Retain an older APK to check v1 login and streaming.
For failures, use dashboard service status and private diagnostics; do not print
login headers, public addresses or provider payloads. Offline tests use mocks
and do not prove live exchange reachability, actual address mapping, phone UI,
TLS or long-running behavior.

## Roll back only dashboard services

Use the backup path printed by the upgrade:

```bash
sudo bash backend/deploy/rollback.sh /var/backups/trading-dashboard/v2-XXXXXXXX
```

Rollback restores the previous dashboard code/config/proxy and installed commit,
and restarts only the two dashboard services. It preserves the current credential
database and keys, including any keys added after upgrade. The backed-up database
and keys are for separate disaster recovery; rollback does not overwrite them.
The new APK requires v2; after a backend rollback, older APKs remain usable and the
new APK will show unavailable data until v2 is restored. Never clear app storage
or revoke login keys to address a version mismatch.

## In-app updates from public GitHub releases

The app requests the public repository's `releases/latest/download/latest.json`
and downloads the versioned APK from `releases/download/vVERSION/APK_NAME`.
GitHub authentication, dashboard access keys and private server addresses are
never used for update requests. Manifest redirects are limited to HTTPS on
`github.com`, `release-assets.githubusercontent.com` and `objects.githubusercontent.com`,
with a bounded number of hops. APKs still require the declared checksum, size,
application ID, newer version code and the installed app's signing certificate.
Automatic checks, retries, Later and Android install permission behavior remain
as described below. Publishing a verified GitHub Release makes it available to
the app without copying APKs to the VPS. Install the GitHub-downloading version
once from its Release asset when upgrading an older VPS-downloading APK; saved
connections remain intact.

## Legacy: host signed APK updates on the VPS

The following procedure remains for older APKs that fetch updates from the VPS.
Their HTTPS server serves APKs and a manifest at
`/app-updates/latest.json`. Dashboard API authentication remains unchanged.
The app checks on opening and foreground return, then every minute while unlocked
and visible. Failed checks/downloads retry automatically after 30 seconds; manual
checks share any in-flight request. Recurring checks stop in the background.
Manifest requests require cache revalidation. It automatically downloads newer
versions through Android DownloadManager (including in the background), and verifies size,
SHA-256, application ID, version code and the installed signing certificate.
Only a verified private copy is shared with Android's installer. The user chooses
**Install now** or **Later**; Later suppresses reminders for the current opening
and reminds again after closing/restarting. Settings also offers Check for updates
and Install. Android may ask the user to allow this app to install updates.

After the dashboard upgrade above installs the updated proxy allowlist, run as
your normal GitHub-authenticated user for each released version:

```bash
cd /home/coder/HLTRADINGBOT/Trading_Dashboard_Public
# If GitHub CLI is not installed, a human can install it: sudo apt-get install gh
# Authenticate once as this user: gh auth login
bash scripts/host-app-update.sh v0.1.VERSION
```

The script downloads only the five named release assets from GitHub using the
invoking user's authentication, then requests sudo for the public-file publisher.
It can also run directly from your human administrator root shell after `gh auth login`
in that shell; it does not inherit another user's credentials.
No GitHub token is stored in the APK, web directory or dashboard configuration.
The publisher copies only the APK and latest.json to the root-owned
`/opt/trading-dashboard/app-updates`. It validates release checksums/metadata,
refuses downgrades and certificate changes, publishes the manifest atomically,
and keeps **the latest three APK versions**, removing older versioned dashboard
APKs after publication. Scratch downloads are removed on exit. It uses no bot
files, trading keys, dashboard login keys or service control. Re-running the
same unchanged release is safe. GitHub Actions publishes release assets; the
publisher must run for each release to offer that release through the app. After
migration, Codex runs it as `coder` without sudo; the legacy root publisher is for
unmigrated installations.

Alternatively, download the APK, SHA256SUMS.txt, signature.txt, build-info.txt
and latest.json assets into one directory using your browser, then run:

```bash
sudo python3 backend/deploy/publish-app-update.py /path/to/downloaded-release-directory
```

Check the public manifest from another device, confirm its version/hash, and
test a newer APK on a phone: automatic background download, Later, rotation,
background/resume, close/restart reminder, Install now, denial/grant of Android
install permission, installer cancellation, and preservation of connections/theme/
fingerprint settings after installation. Android unit tests simulate the update
lifecycle and failures; they do not prove device-specific installer behavior.
