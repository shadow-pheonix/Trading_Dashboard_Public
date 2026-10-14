"""Actual bot emissions only. Instance-scoped, read-only durable event replay."""
from pathlib import Path
import re
import sqlite3

from .validation import InvalidData, identifier, integer


class Alerts:
    def read(self, instance, now, after=0):
        value = {"schemaVersion": 1, "generatedAt": now,
                 "instance": {"id": instance.id, "label": instance.label},
                 "status": "unavailable", "events": [], "cursor": after,
                 "oldest": 0, "newest": 0, "gap": False, "hasMore": False}
        if not instance.alerts_path:
            return value
        path = Path(instance.alerts_path)
        try:
            if not path.is_file() or path.is_symlink() or path.stat().st_size > 128 * 1024 * 1024:
                return value
            db = sqlite3.connect(path.as_uri() + '?mode=ro', uri=True, timeout=0.25)
            try:
                db.execute('PRAGMA query_only=ON')
                db.execute('BEGIN')
                oldest, newest = db.execute('SELECT MIN(sequence),MAX(sequence) FROM alerts').fetchone()
                oldest, newest = oldest or 0, newest or 0
                integer(oldest); integer(newest)
                # Bootstrap includes recent history silently in the app; reconnect
                # replays strictly after the durable device cursor.
                start = max(0, newest - 100) if after == 0 else after
                reset = after > newest
                if reset:
                    start = max(0, oldest - 1)
                rows = db.execute('SELECT sequence,id,timestamp,message FROM alerts '
                                  'WHERE sequence>? ORDER BY sequence LIMIT 100', (start,)).fetchall()
                events = []
                for sequence, event_id, timestamp, message in rows:
                    integer(sequence, 1); identifier(event_id); integer(timestamp, 1)
                    if (not isinstance(message, str) or not 1 <= len(message) <= 16000
                            or any(ord(c) < 32 and c not in '\n\t' for c in message)):
                        raise InvalidData()
                    # Defense in depth: public addresses and delivery URLs/paths
                    # aren't needed by native alerts. The bot also scrubs secrets.
                    message = re.sub(r'0x[a-fA-F0-9]{40,64}|https?://[^\s]+|/(?:etc|home|opt)/[^\s]+',
                                     '[private]', message)
                    events.append({"sequence": sequence, "id": event_id,
                                   "timestamp": timestamp, "message": message})
                cursor = events[-1]['sequence'] if events else newest
                value.update(status="ready", events=events, cursor=cursor, oldest=oldest,
                             newest=newest, gap=reset or (after > 0 and after < oldest - 1),
                             hasMore=cursor < newest)
            finally:
                db.close()
        except (OSError, sqlite3.Error, InvalidData):
            # Never disclose paths, database contents, or exception bodies.
            return {**value, "status": "unavailable", "events": [], "cursor": after,
                    "oldest": 0, "newest": 0, "gap": False, "hasMore": False}
        return value
