#!/usr/bin/env python3
"""Small host-side HTTP package market for immutable CilExec package databases."""

from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import re
import sqlite3
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit


MARKET_DIR = Path(__file__).resolve().parent
REPOSITORY = MARKET_DIR / "repository"
CATALOG_METADATA = MARKET_DIR / "catalog.json"
MAX_PACKAGE_BYTES = 64 * 1024 * 1024
MAX_CATALOG_BYTES = 1024 * 1024
MAX_CONCURRENT_REQUESTS = 16
REQUEST_TIMEOUT_SECONDS = 15
SAFE_COORDINATE = re.compile(
    r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}/"
    r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}/"
    r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}"
)
LOOPBACK_NETWORKS = (
    ipaddress.ip_network("127.0.0.0/8"),
    ipaddress.ip_network("::1/128"),
)


def published_metadata() -> dict[str, dict[str, object]]:
    """The explicit publication list; files present on disk are not automatically public."""
    if not CATALOG_METADATA.is_file():
        return {}
    if CATALOG_METADATA.is_symlink() or CATALOG_METADATA.stat().st_size > MAX_CATALOG_BYTES:
        raise ValueError("market/catalog.json must be a regular file no larger than 1 MiB")
    document = json.loads(CATALOG_METADATA.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        raise ValueError("market/catalog.json must contain an object")
    publications: dict[str, dict[str, object]] = {}
    for coordinate, value in document.items():
        if not isinstance(coordinate, str) or SAFE_COORDINATE.fullmatch(coordinate) is None:
            raise ValueError(f"Invalid coordinate in market/catalog.json: {coordinate!r}")
        if not isinstance(value, dict):
            raise ValueError(f"Publication metadata for {coordinate} must be an object")
        publications[coordinate] = value
    return publications


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def package_record(database: Path, publications: dict[str, dict[str, object]]) -> dict[str, object]:
    repository = REPOSITORY.resolve()
    if database.is_symlink() or not database.is_file():
        raise ValueError(f"Published package must be a regular non-symlink file: {database}")
    resolved = database.resolve(strict=True)
    if repository not in resolved.parents:
        raise ValueError(f"Published package escapes the repository: {database}")
    size = resolved.stat().st_size
    if size <= 0 or size > MAX_PACKAGE_BYTES:
        raise ValueError(f"Package size is outside 1..{MAX_PACKAGE_BYTES} bytes: {database}")
    with sqlite3.connect(f"{resolved.as_uri()}?mode=ro", uri=True) as connection:
        connection.execute("PRAGMA query_only=ON")
        metadata = dict(connection.execute(
            "SELECT metadata_key, metadata_value FROM package_metadata"
        ))
        dependencies = [
            {
                "namespace": namespace,
                "name": name,
                "version": version,
                "optional": bool(optional),
            }
            for namespace, name, version, optional in connection.execute(
                "SELECT dependency_namespace,dependency_name,version_constraint,optional "
                "FROM package_dependency ORDER BY dependency_namespace,dependency_name"
            )
        ]
    required = ("namespace", "name", "version")
    if any(not isinstance(metadata.get(key), str)
           or SAFE_COORDINATE.fullmatch("x/x/" + metadata[key]) is None
           for key in required):
        raise ValueError(f"Package has invalid identity metadata: {database}")
    coordinate = "/".join(metadata[key] for key in required)
    if SAFE_COORDINATE.fullmatch(coordinate) is None:
        raise ValueError(f"Package has unsafe coordinate {coordinate!r}: {database}")
    relative_parts = resolved.relative_to(repository).parts
    expected_parts = ("packages", metadata["namespace"], metadata["name"],
                      metadata["version"], f"{metadata['name']}.db")
    if relative_parts != expected_parts:
        raise ValueError(
            f"Package path does not match its immutable coordinate: {database}"
        )
    package_id = sha256_file(resolved)
    record = {
        "namespace": metadata["namespace"],
        "name": metadata["name"],
        "version": metadata["version"],
        "kind": metadata.get("package_kind", "legacy"),
        "coordinate": coordinate,
        "download": f"/market/v1/{package_id}",
        "sha256": package_id,
        "bytes": size,
        "mediaType": "application/vnd.sqlite3",
        "dependencies": dependencies,
    }
    publication = publications.get(str(record["coordinate"]), {})
    for key in ("summary", "description", "tags"):
        if key in publication:
            record[key] = publication[key]
    return record


def version_key(value: str) -> tuple[tuple[int, object], ...]:
    """Natural, deterministic ordering for ordinary dotted package versions."""
    return tuple((0, int(part)) if part.isdigit() else (1, part.lower())
                 for part in re.split(r"([0-9]+)", value) if part)


def package_catalog() -> list[tuple[dict[str, object], Path]]:
    publications = published_metadata()
    catalog = []
    for path in sorted(REPOSITORY.rglob("*.db")):
        record = package_record(path, publications)
        if record["kind"] != "legacy" and record["coordinate"] in publications:
            catalog.append((record, path.resolve(strict=True)))
    latest: dict[tuple[str, str], dict[str, object]] = {}
    for record, _ in catalog:
        key = str(record["namespace"]), str(record["name"])
        current = latest.get(key)
        if current is None or version_key(str(record["version"])) > version_key(
                str(current["version"])):
            latest[key] = record
    for record, _ in catalog:
        record["latest"] = latest[(str(record["namespace"]),
                                   str(record["name"]))] is record
    return catalog


def index_document(catalog: list[tuple[dict[str, object], Path]] | None = None) -> bytes:
    packages = [record for record, _ in (catalog if catalog is not None else package_catalog())]
    return json.dumps(
        {"apiVersion": "cilexec.market/v1", "packages": packages},
        ensure_ascii=False,
        indent=2,
    ).encode("utf-8") + b"\n"


class MarketHandler(BaseHTTPRequestHandler):
    server_version = "CilExecMarket/1"
    sys_version = ""

    def setup(self) -> None:
        super().setup()
        self.connection.settimeout(REQUEST_TIMEOUT_SECONDS)

    def end_headers(self) -> None:
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", "default-src 'none'")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Frame-Options", "DENY")
        super().end_headers()

    def log_message(self, message_format: str, *args: object) -> None:
        message = message_format % args
        safe = re.sub(r"[\x00-\x1f\x7f-\x9f]", "?", message)
        print(f"{self.client_address[0]} - {safe}", flush=True)

    def do_GET(self) -> None:  # noqa: N802 - HTTP handler API
        requested_path = urlsplit(self.path).path
        if requested_path == "/market/v1/index.json":
            payload = self.server.index_payload
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            try:
                self.wfile.write(payload)
            except (BrokenPipeError, ConnectionResetError):
                pass
            return
        package_request = re.fullmatch(r"/market/v1/([0-9a-f]{64})", requested_path)
        if package_request:
            package_id = package_request.group(1)
            database = self.server.packages_by_id.get(package_id)
            if database is None:
                self.send_error(404, "Unknown package ID")
                return
            self.send_package(database, package_id)
            return
        self.send_error(404, "Unknown market resource")

    def send_package(self, database: Path, package_id: str) -> None:
        """Serve one immutable package with the byte ranges used by network.download."""
        if database.is_symlink() or not database.is_file() or sha256_file(database) != package_id:
            self.send_error(409, "Published package changed after market startup")
            return
        size = database.stat().st_size
        start = 0
        end = size - 1
        partial = False
        requested_range = self.headers.get("Range")
        etag = f'"{package_id}"'
        if_range = self.headers.get("If-Range")
        if requested_range and (if_range is None or if_range == etag):
            match = re.fullmatch(r"bytes=([0-9]{1,20})-([0-9]{0,20})",
                                 requested_range.strip())
            if match is None:
                self.send_error(400, "Only one explicit byte range is supported")
                return
            start = int(match.group(1))
            if start >= size:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("ETag", etag)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if match.group(2):
                end = min(int(match.group(2)), size - 1)
            if end < start:
                self.send_error(400, "Invalid byte range")
                return
            partial = True

        length = end - start + 1
        self.send_response(206 if partial else 200)
        self.send_header("Content-Type", "application/vnd.sqlite3")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("ETag", etag)
        self.send_header("Content-Length", str(length))
        if partial:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        with database.open("rb") as source:
            source.seek(start)
            remaining = length
            while remaining:
                chunk = source.read(min(1024 * 1024, remaining))
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    return
                remaining -= len(chunk)


class AccessControlledMarketServer(ThreadingHTTPServer):
    """Rejects clients before an HTTP handler or file access is created."""

    daemon_threads = True
    request_queue_size = 32

    def __init__(self, address: tuple[str, int], allowed_cidrs: list[str]) -> None:
        self.allowed_networks = LOOPBACK_NETWORKS + tuple(
            ipaddress.ip_network(value, strict=True) for value in allowed_cidrs
        )
        catalog = package_catalog()
        self.index_payload = index_document(catalog)
        self.packages_by_id = {
            str(record["sha256"]): path for record, path in catalog
        }
        self._request_slots = threading.BoundedSemaphore(MAX_CONCURRENT_REQUESTS)
        super().__init__(address, MarketHandler)

    def process_request(self, request: object, client_address: tuple[str, int]) -> None:
        if not self._request_slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self._request_slots.release()
            raise

    def process_request_thread(self, request: object,
                               client_address: tuple[str, int]) -> None:
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._request_slots.release()

    def verify_request(self, request: object, client_address: tuple[str, int]) -> bool:
        address = ipaddress.ip_address(client_address[0].split("%", 1)[0])
        if isinstance(address, ipaddress.IPv6Address) and address.ipv4_mapped:
            address = address.ipv4_mapped
        return any(address.version == network.version and address in network
                   for network in self.allowed_networks)


def main() -> None:
    def port_number(value: str) -> int:
        try:
            port = int(value, 10)
        except ValueError as error:
            raise argparse.ArgumentTypeError("port must be an integer") from error
        if not 1 <= port <= 65535:
            raise argparse.ArgumentTypeError("port must be from 1 to 65535")
        return port

    parser = argparse.ArgumentParser(description="Run the CilExec local package market")
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=port_number, default=8787)
    parser.add_argument(
        "--allow-cidr",
        action="append",
        default=[],
        metavar="CIDR",
        help="additional client network allowed to read packages (repeatable)",
    )
    arguments = parser.parse_args()
    try:
        server = AccessControlledMarketServer(
            (arguments.bind, arguments.port), arguments.allow_cidr
        )
    except (OSError, ValueError, sqlite3.Error, KeyError) as error:
        parser.exit(
            1,
            f"Cannot start CilExec market on {arguments.bind}:{arguments.port}: "
            f"{getattr(error, 'strerror', None) or error}\n",
        )
    print("CilExec market: http://127.0.0.1:"
          f"{arguments.port}/market/v1/index.json", flush=True)
    print("Container URL: http://host.docker.internal:"
          f"{arguments.port}/market/v1/index.json", flush=True)
    print("Allowed clients: " + ", ".join(
        str(network) for network in server.allowed_networks
    ), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
