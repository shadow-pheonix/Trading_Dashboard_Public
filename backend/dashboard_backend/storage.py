"""Dashboard-owned credential hashes. Viewers open SQLite read-only."""
import hashlib
import secrets
import sqlite3
from contextlib import closing
from pathlib import Path

from .validation import identifier
from .connection_keys import connection_key

SCHEMA = """
CREATE TABLE IF NOT EXISTS dashboard_keys (
 digest TEXT PRIMARY KEY, instance_id TEXT NOT NULL, revoked INTEGER NOT NULL DEFAULT 0);
"""


class Store:
    def __init__(self, path):
        self.path = Path(path).resolve()

    def connect(self, readonly=False):
        db = (sqlite3.connect(self.path.as_uri() + "?mode=ro", uri=True, timeout=2)
              if readonly else sqlite3.connect(self.path, timeout=2))
        if readonly:
            db.execute("PRAGMA query_only=ON")
        return db

    def initialize(self):
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        with closing(self.connect()) as db, db:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript(SCHEMA)
        self.path.chmod(0o600)

    def provision(self, instance_id, output, server_url=None):
        identifier(instance_id)
        token = secrets.token_urlsafe(32)
        saved = connection_key(server_url, token) if server_url is not None else token
        # Exclusive creation prevents overwriting the owner's only copy of a key.
        target = Path(output)
        import os
        fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            with os.fdopen(fd, "w") as file:
                file.write(saved + "\n")
                file.flush()
                os.fsync(file.fileno())
            with closing(self.connect()) as db, db:
                db.execute("INSERT INTO dashboard_keys(digest, instance_id) VALUES (?, ?)",
                           (hashlib.sha256(token.encode()).hexdigest(), instance_id))
        except BaseException:
            target.unlink(missing_ok=True)
            raise

    def authenticate(self, token):
        if not isinstance(token, str) or not 32 <= len(token) <= 256 or any(not 33 <= ord(c) <= 126 for c in token):
            return None
        with closing(self.connect(True)) as db:
            row = db.execute("SELECT instance_id FROM dashboard_keys WHERE digest=? AND revoked=0",
                             (hashlib.sha256(token.encode()).hexdigest(),)).fetchone()
            return row[0] if row else None

    def revoke(self, instance_id):
        with closing(self.connect()) as db, db:
            db.execute("UPDATE dashboard_keys SET revoked=1 WHERE instance_id=?", (instance_id,))
