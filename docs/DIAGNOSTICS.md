# App diagnostics

In Settings, tap **Export diagnostics** to save a timestamped ZIP directly to
Downloads, for example `Trading-Dashboard-diagnostics-2026-11-04_17-19-23-456.zip`
(local time). A confirmation shows the saved filename. Android 8–9 requests storage
permission first; Android 10+ requires none. Failed writes remove partial downloads.
Nothing is uploaded automatically. Attach the ZIP from Files → Downloads with the
issue’s approximate time, screen and steps. **Clear** removes private logs, crash
records and the old cached export; saved Downloads ZIPs remain until you delete them.

The ZIP contains `summary.json`, `events.jsonl` and `README.txt`. It includes app
version, Android SDK, display dimensions/density/font scale, lifecycle events,
screen and chart selections, request durations/status codes, connection changes,
validation failures, candle/MACD counts, forming/cache state and sampled viewport
changes. Chart gestures are sampled at most once per second. Export includes
only the last 24 hours, at most 2,000 events; two rolling private files are limited
to 128 KiB each, with a separate bounded crash record. Older on-disk records may
remain until rotation or Clear, but are excluded from exports.

Logging accepts fixed event/field names and numeric values only. Connections use
an irreversible pseudonym derived from the local connection ID. Error categories
and allowlisted app source filenames/line numbers help locate failures without
exception messages. Keys, server URLs, headers, wallet addresses, instance labels,
notification text, balances and trade history are excluded. The export reader
rejects malformed/unknown records and re-encodes validated events instead of
copying arbitrary file contents. Logs stay in private app storage with
Android backup disabled. Export creates a sanitized private ZIP, then publishes
the complete file to shared Downloads. The exported copy remains there after
uninstalling; the app no longer exposes a diagnostics FileProvider or opens a share sheet.

Logs can show state changes around a glitch, but do not record the screen or
prove device rendering behavior. If a visual issue persists, include a recording
and reproduce it after opening the updated app so its diagnostic events exist.
