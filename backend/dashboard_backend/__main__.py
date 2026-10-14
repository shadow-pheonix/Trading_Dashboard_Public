import argparse
import sys

from .api import Application
from .config import load_config
from .storage import Store

WS_KEEPALIVE = {"ws_ping_interval": 60, "ws_ping_timeout": 30}


def main(argv=None):
    parser = argparse.ArgumentParser(description="Independent read-only dashboard backend.")
    parser.add_argument("--config", required=True, help="Dashboard-owned configuration JSON.")
    parser.add_argument("--database", required=True, help="Dashboard-owned SQLite file.")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("init", help="Initialize only the dashboard database.")
    provision = sub.add_parser("key-create", help="Save a new instance key privately; never print it.")
    provision.add_argument("--instance", required=True)
    provision.add_argument("--output", required=True)
    provision.add_argument("--server-url", help="Private HTTPS origin to encode in the connection key.")
    revoke = sub.add_parser("key-revoke", help="Immediately revoke all keys for one instance.")
    revoke.add_argument("--instance", required=True)
    serve = sub.add_parser("serve", help="Serve on loopback behind an HTTPS proxy.")
    serve.add_argument("--port", type=int, default=8080)
    args = parser.parse_args(argv)
    try:
        config, store = load_config(args.config), Store(args.database)
        if args.command == "init":
            store.initialize()
        elif args.command == "key-create":
            config.instance(args.instance)
            store.provision(args.instance, args.output, args.server_url)
        elif args.command == "key-revoke":
            config.instance(args.instance)
            store.revoke(args.instance)
        elif args.command == "serve":
            import uvicorn
            if not 1024 <= args.port <= 65535:
                raise ValueError()
            uvicorn.run(Application(config, store), host="127.0.0.1", port=args.port,
                        workers=1, access_log=False, log_level="critical", proxy_headers=False,
                        limit_concurrency=32, timeout_keep_alive=5, ws="websockets-sansio",
                        ws_max_size=1024, ws_max_queue=1, ws_per_message_deflate=False,
                        **WS_KEEPALIVE)
        return 0
    except KeyboardInterrupt:
        return 0
    except Exception:
        print("Operation failed. Check dashboard configuration, data and permissions privately.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
