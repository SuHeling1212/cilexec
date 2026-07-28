#!/usr/bin/env python3
"""Small host-side HTTP package market for immutable CilExec package databases."""

from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit


MARKET_DIR = Path(__file__).resolve().parent
REPOSITORY = MARKET_DIR / "repository"


def package_record(database: Path) -> dict[str, object]:
    with sqlite3.connect(f"file:{database}?mode=ro", uri=True) as connection:
        metadata = dict(connection.execute(
            "SELECT metadata_key, metadata_value FROM package_metadata"
        ))
    relative = database.relative_to(REPOSITORY).as_posix()
    content = database.read_bytes()
    return {
        "namespace": metadata["namespace"],
        "name": metadata["name"],
        "version": metadata["version"],
        "coordinate": "/".join(
            (metadata["namespace"], metadata["name"], metadata["version"])
        ),
        "download": f"/{relative}",
        "sha256": hashlib.sha256(content).hexdigest(),
        "bytes": len(content),
        "mediaType": "application/vnd.sqlite3",
    }


def index_document() -> bytes:
    packages = [package_record(path) for path in sorted(REPOSITORY.rglob("*.db"))]
    return json.dumps(
        {"apiVersion": "cilexec.market/v1", "packages": packages},
        ensure_ascii=False,
        indent=2,
    ).encode("utf-8") + b"\n"


class MarketHandler(SimpleHTTPRequestHandler):
    extensions_map = {**SimpleHTTPRequestHandler.extensions_map,
                      ".db": "application/vnd.sqlite3"}

    def __init__(self, *args: object, **kwargs: object) -> None:
        super().__init__(*args, directory=str(REPOSITORY), **kwargs)

    def do_GET(self) -> None:  # noqa: N802 - HTTP handler API
        if urlsplit(self.path).path in ("/", "/index.json", "/v1/index.json"):
            payload = index_document()
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        super().do_GET()


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the CilExec local package market")
    parser.add_argument("--bind", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8787)
    arguments = parser.parse_args()
    try:
        server = ThreadingHTTPServer((arguments.bind, arguments.port), MarketHandler)
    except OSError as error:
        parser.exit(
            1,
            f"Cannot start CilExec market on {arguments.bind}:{arguments.port}: "
            f"{error.strerror or error}\n",
        )
    print(f"CilExec market: http://127.0.0.1:{arguments.port}/v1/index.json", flush=True)
    print("Container URL: http://host.docker.internal:"
          f"{arguments.port}/v1/index.json", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
