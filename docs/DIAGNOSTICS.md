# App diagnostics

In Settings, tap **Export diagnostics** and select a destination in Android’s
share sheet. Nothing is uploaded automatically. Send the ZIP with the issue’s
approximate time, screen and steps to reproduce it. **Clear** removes the local
log, crash record and cached export; new app activity starts a fresh log.

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
copying arbitrary file contents. Files stay in the app’s private storage with
Android backup disabled; the FileProvider grants temporary read access only to
the selected ZIP directory through a user-initiated share.

Logs can show state changes around a glitch, but do not record the screen or
prove device rendering behavior. If a visual issue persists, include a recording
and reproduce it after opening the updated app so its diagnostic events exist.
