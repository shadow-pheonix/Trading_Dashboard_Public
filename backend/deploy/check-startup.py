#!/usr/bin/env python3
"""Human update preflight under the service identity; no bot reads or network calls."""
from contextlib import closing
import os
from pathlib import Path
import sys


def check_runtime():
    import uvicorn
    from uvicorn.protocols.websockets.websockets_sansio_impl import WebSocketsSansIOProtocol
    if not callable(uvicorn.run) or WebSocketsSansIOProtocol is None:
        raise ValueError()


def verify(backend, config_path, database, runtime_check=check_runtime):
    backend = Path(backend)
    if not all(os.access(path, os.R_OK | os.X_OK)
               for path in (backend, backend / 'dashboard_backend')):
        raise ValueError()
    sys.path.insert(0, str(backend))
    try:
        # The same imports as the installed CLI, including the new v2 modules.
        from dashboard_backend.__main__ import main
        from dashboard_backend.api import Application
        from dashboard_backend.config import load_config
        from dashboard_backend.storage import Store
        runtime_check()
        config, store = load_config(config_path), Store(database)
        with closing(store.connect(readonly=True)) as connection:
            columns = {row[1] for row in connection.execute('PRAGMA table_info(dashboard_keys)')}
            if not {'digest', 'instance_id', 'revoked'} <= columns:
                raise ValueError()
        # Construction creates no price subscriptions or balance queries.
        app = Application(config, store)
        if (not callable(main) or app.viewers != 0 or app.balances.tasks
                or app.prices.thread is not None or app.prices.references):
            raise ValueError()
    finally:
        sys.path.pop(0)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    try:
        if len(argv) != 3:
            raise ValueError()
        verify(*argv)
    except Exception:
        print('Dashboard service-user preflight failed. Check candidate code, configuration, database and dependency permissions privately.', file=sys.stderr)
        return 1
    print('Dashboard startup imports, permissions and configuration verified.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
