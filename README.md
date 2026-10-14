# Trading Dashboard for Android

A read-only companion for the trading bot: saved positions, strategy status,
performance charts, monthly returns and closed-trade history. The UI
offers saved Light, Dark and device-controlled appearance across every screen,
with readable profit/loss colors and bottom navigation. Kotlin + Jetpack Compose; Android 8.0 or later.

## App behavior

- One-field connection using a private key that includes the server address;
  see [private connection keys](docs/API-v2.md#private-connection-keys).
- Existing encrypted connections keep their saved addresses, keys and IDs.
- Saved instances remain visible; Add another key opens the hidden entry form.
- Home's Prism overview puts BTC price beside instance equity, with real combined
  open P&L below; all three headings share a prominent style without an estimate
  badge. Active trades and Account balances follow. The BTC dot tracks
  consecutive price moves (green up, red down), retaining its color on equal
  quotes and appearing neutral before comparison or when stale/offline.
  The bracketed change is over 24 hours. Open P&L includes returns on invested
  USD before leverage and all-wallet capital: saved entry equity for active
  wallets plus sampled current equity for flat wallets. Missing values remain
  unavailable. See the [calculation contract](docs/API-v2.md#live-btc-and-open-returns).
- M1/M2/M3 combine Wallet A/B while keeping opposing directions visible.
- Account balances adds each model's share within its wallet beside the dollar
  amount; see [wallet allocation percentages](docs/API-v2.md#demand-only-perpetual-balances).
- Strategies shows each model's MACD line, signal EMA and histogram with tap/drag inspection.
  See the [closed-candle indicator contract](docs/API-v2.md#macd-strategy-charts).
- Analytics holds realized P&L, monthly results and statistics from combined closed trades:
  profit factor, average/best/worst trade, win/loss/breakeven counts, logged fees
  and Max drawdown as USD (percentage), supplied by the backend's Edge Monitor
  calculation. USD uses its $10k reference scale; % uses running peak equity.
  See the [drawdown contract](docs/API-v2.md#edge-monitor-equity-and-chart-inspection).
- Charts offer cumulative, daily and monthly P&L plus the Edge Monitor's
  $10k reference equity curve for either history source and model selection.
  Cumulative and reference-equity views show a captured point including active P&L
  for Real money. Tap, drag or use previous/next controls to inspect points.
  The [chart contract](docs/API-v2.md#edge-monitor-equity-and-chart-inspection)
  defines capital requirements and legacy estimates. Recorded equity is offered
  only if actual samples exist. Cash-flow-adjusted return, percentage equity
  drawdown and Sharpe require actual capital history.
- Tap an active trade to see saved weighted leverage and a shared recorded stop.
  These details do not verify live exchange orders.
- Settings saves Light, Dark or Use device setting without changing login keys.
- Settings can enable or disable [Android fingerprint or screen-lock authentication](#app-unlock); existing installs keep it enabled.
- Source, period and All models/M1/M2/M3 filters appear in Analytics and Trades.
  Their shared timeframe bar is 1W, 1M, 3M, 1Y, YTD, All; YTD starts January 1 UTC.
- One foreground live connection per selected instance persists across tabs,
  filters and manual refresh. Filtered Analytics/Trades data uses separate HTTP
  reads. Analytics holds one snapshot per tab entry; filters, manual refresh,
  instance changes and foreground resume reload it. Home and Trades keep their
  live updates. See the [snapshot policy](docs/API-v2.md#edge-monitor-equity-and-chart-inspection).
  Backgrounding/closing disconnects the session, and resuming reconnects.
  Perpetual equity stays demand-only on opening/resume/manual refresh.
- Monthly results use neutral tiles with mint/rose text and a small accent bar,
  including Dark mode, rather than filled profit/loss blocks.
- HTTPS/WSS header authentication, blocked redirects and encrypted local keys.
- Read only: no trading, withdrawal, order or bot service control endpoints.

The [independent backend](backend/README.md) preserves the [v1 API](docs/API.md)
for older APKs and implements the [v2 grouping and balance contract](docs/API-v2.md).
After a one-time human permission setup, `coder` can manage backend releases,
dashboard restarts and signed APK hosting. Bot activation remains human-only.
See the [dashboard-only update and rollback procedure](docs/UPDATE.md).

## App unlock

With Fingerprint unlock enabled in Settings, the app requires Android's system
authentication before displaying portfolio data
or opening dashboard network connections. It uses enrolled strong biometrics
(including fingerprint) or the phone's existing PIN, pattern or password. No
fingerprint data or separate app PIN is stored. A phone without secure screen
lock stays locked and offers a button to open Android security settings; cancelling
authentication keeps the app locked with an Unlock button to retry.

Unlock exists only in process memory. Tabs, rotation and background/resume keep
the current session unlocked. Back closes the app and resets unlock; removing its
task or Android ending its process also requires authentication on reopening.
The setting is saved independently of connection keys. Disabling it allows opening
without authentication; enabling it in an unlocked session locks on the next close/open.
Enabling requires an existing secure Android screen lock. Dashboard connections
still pause while backgrounded. Saved connection keys and
the backend API are unchanged.

Android 11 and later use the combined biometric/credential system prompt.
Android 8–10 use a fingerprint prompt with a **Use screen lock** fallback, or
screen-lock confirmation directly when strong biometrics are unavailable, following
[Android's supported authenticators](https://developer.android.com/identity/sign-in/biometric-auth).
The lock screen follows the saved app appearance.

Unit tests cover cancellation, rejected fingerprints, stale callbacks, rotation,
background/resume, closing, task removal, process restart, the saved enable/disable setting, Android version routing
and zero dashboard streams while locked. Before relying on the lock on a phone,
verify fingerprint success, screen-lock fallback, cancellation/retry, rotation,
background/resume and closing/reopening on that device. System biometric UI and
hardware require device testing; JVM tests do not exercise the fingerprint sensor.

## GitHub Actions builds and updates

[Android build](../../actions/workflows/android.yml) compiles the APK and runs unit
tests and Android lint on main pushes/PRs. Verification also signs two disposable
test builds and checks that both retain the same certificate. Those test APKs
are never published. On `main`, it also builds and publishes a
signed installable APK once permanent signing is configured. Open
[Releases](https://github.com/shadow-pheonix/Trading_Dashboard_Public/releases), choose the
latest version and download `Trading-Dashboard-VERSION.apk` under **Assets**.
Install that APK directly on your Android phone. Each release is tagged
`v0.1.<version code>` and includes the APK, checksum, signing-certificate details
and build information. Actions artifacts provide a temporary secondary download;
see [Actions storage](#actions-storage) for retention.
The app automatically detects newer VPS-hosted releases on opening/foreground
return and every minute while visible, retries failures, downloads the APK, and
offers Install now or Later. Later reminds again after restarting. Settings includes
update controls. Scheduling and three-version APK retention are documented in
[the human-run update guide](docs/UPDATE.md#host-signed-apk-updates-on-the-vps).

Releases are created only after the permanent APK signature passes verification;
PR/test APKs are never released. No additional GitHub secret is needed for publishing.

Signing uses one persistent PKCS12 keystore from GitHub Actions secrets. The
workflow checks the certificate fingerprint before building and checks the APK's
signature afterward. It fails if signing secrets are missing or inconsistent;
it never substitutes a newly generated runner/debug key.

The application ID stays `com.shadowpheonix.tradingdashboard`. The version code
is this workflow's run number plus the repository variable `APP_VERSION_OFFSET`
(default zero); `versionName` is `0.1.<version code>`.
Download a newer release to update the app while retaining saved connections.
A workflow rerun has the same version code as its original run; trigger a fresh
workflow run when a new version code is needed. Keep this workflow file/history
and signing certificate: changing the app ID/key or resetting version numbering
can prevent normal updates. Development debug builds use a separate `.debug` ID
and are not distributed by CI.

### Actions storage

Signed APK artifacts expire after seven days. The
[storage cleanup workflow](.github/workflows/artifact-storage.yml) runs after
successful main Android builds and daily, keeping the newest three APK artifact
versions. Older copies are deleted only after their matching commit tag and
published release contain the APK, checksum, certificate and build information.
Unpublished copies are preserved until their normal expiry. Verification reports
retain their existing 14-day expiry; workflow runs, logs, caches, GitHub Releases
and the VPS updater are untouched. Cleanup changes take effect immediately for
eligible existing copies; shorter upload retention applies to new artifacts.

### One-time signing setup

The GitHub connector used to create this app can write files and inspect Actions,
but cannot create repository Actions secrets. On a trusted computer with Java 17,
Python 3 and authenticated GitHub CLI, clone this repository and run:

```bash
bash scripts/setup-signing.sh
```

The script generates the key once, stores it under ignored `.signing/`, uploads
three encrypted repository secrets, and triggers a build. Re-running reuses the
existing key. When moving to a new public repository, reuse the private checkout's
signing directory with `DASHBOARD_SIGNING_DIR` and select the destination with
`GITHUB_REPOSITORY`. Set `APP_VERSION_OFFSET` at least as high as the last published
version code before the first build. Back up `.signing/` securely before deleting or moving the checkout.
To configure an existing backup manually, use:

| Repository secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 PKCS12 keystore; alias `dashboard` |
| `ANDROID_KEYSTORE_PASSWORD` | Same store/key password |
| `ANDROID_SIGNING_CERT_SHA256` | Lowercase SHA-256 of the signing certificate DER |

Signing credentials and dashboard login keys are separate. Never put either in
source code, issue comments, workflow logs or build artifacts.

## Local development

Install Android SDK 35, Java 17 and Gradle 8.11.1. GitHub Actions installs Gradle
explicitly; there is no unchecked downloaded wrapper JAR in this first version.

```bash
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Open the repository in Android Studio with Gradle 8.11.1 and JDK 17 configured.
The unit tests cover HTTPS/authentication boundaries, GET-only requests,
model/period scoping, connection migration and form visibility, stale-data labeling and missing metric handling.
