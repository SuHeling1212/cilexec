import importlib.util
import json
import sqlite3
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from contextlib import contextmanager
from pathlib import Path
from unittest import mock


SPEC = importlib.util.spec_from_file_location(
    "cilexec_market_server", Path(__file__).with_name("server.py")
)
assert SPEC is not None and SPEC.loader is not None
server_module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(server_module)


class MarketServerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        self.repository = root / "repository"
        self.catalog = root / "catalog.json"
        package_directory = self.repository / "packages" / "demo" / "tool" / "1.0.0"
        package_directory.mkdir(parents=True)
        self.database = package_directory / "tool.db"
        with sqlite3.connect(self.database) as connection:
            connection.execute(
                "CREATE TABLE package_metadata(metadata_key TEXT PRIMARY KEY, metadata_value TEXT NOT NULL)"
            )
            connection.execute(
                "CREATE TABLE package_dependency(dependency_namespace TEXT, dependency_name TEXT, version_constraint TEXT, optional INTEGER)"
            )
            connection.executemany(
                "INSERT INTO package_metadata VALUES (?, ?)",
                [
                    ("namespace", "demo"),
                    ("name", "tool"),
                    ("version", "1.0.0"),
                    ("package_kind", "application"),
                ],
            )
        self.catalog.write_text(
            json.dumps({"demo/tool/1.0.0": {"summary": "test tool"}}),
            encoding="utf-8",
        )

    @contextmanager
    def configured_repository(self):
        with mock.patch.object(server_module, "REPOSITORY", self.repository), \
                mock.patch.object(server_module, "CATALOG_METADATA", self.catalog):
            yield

    def test_catalog_uses_exact_coordinate_path_and_file_hash(self) -> None:
        with self.configured_repository():
            catalog = server_module.package_catalog()
        self.assertEqual(1, len(catalog))
        record, path = catalog[0]
        self.assertEqual("demo/tool/1.0.0", record["coordinate"])
        self.assertEqual(server_module.sha256_file(path), record["sha256"])
        self.assertEqual("/market/v1/" + record["sha256"], record["download"])

    def test_symlinked_package_is_rejected(self) -> None:
        link = self.database.with_name("linked.db")
        try:
            link.symlink_to(self.database)
        except (NotImplementedError, OSError):
            self.skipTest("symbolic links are unavailable")
        with self.configured_repository():
            with self.assertRaisesRegex(ValueError, "non-symlink"):
                server_module.package_record(link, {})

    def test_http_index_range_and_mutation_guard(self) -> None:
        with self.configured_repository():
            market = server_module.AccessControlledMarketServer(("127.0.0.1", 0), [])
            thread = threading.Thread(target=market.serve_forever, daemon=True)
            thread.start()
            self.addCleanup(thread.join, 2)
            self.addCleanup(market.server_close)
            self.addCleanup(market.shutdown)
            origin = f"http://127.0.0.1:{market.server_address[1]}"

            with urllib.request.urlopen(origin + "/market/v1/index.json", timeout=2) as response:
                index = json.load(response)
                self.assertEqual("nosniff", response.headers["X-Content-Type-Options"])
            package_id = index["packages"][0]["sha256"]

            request = urllib.request.Request(
                origin + "/market/v1/" + package_id,
                headers={"Range": "bytes=0-9"},
            )
            with urllib.request.urlopen(request, timeout=2) as response:
                self.assertEqual(206, response.status)
                self.assertEqual(10, len(response.read()))

            with self.database.open("ab") as destination:
                destination.write(b"changed")
            with self.assertRaises(urllib.error.HTTPError) as failure:
                urllib.request.urlopen(origin + "/market/v1/" + package_id, timeout=2)
            self.assertEqual(409, failure.exception.code)


if __name__ == "__main__":
    unittest.main()
