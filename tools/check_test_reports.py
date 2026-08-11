#!/usr/bin/env python3
"""Reject incomplete or silently skipped mandatory Runtime test reports."""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
MANDATORY_INTEGRATION_TESTS = (
    "com.follarce.app.RuntimeCrashRecoveryIT",
    "com.follarce.app.RuntimeSignalIT",
    "com.follarce.application.FclSystemFunctionsIT",
    "com.follarce.exporter.PostgresLogicalExportIT",
    "com.follarce.persistence.postgres.repository.ClaimCommitFenceIT",
    "com.follarce.persistence.postgres.repository.JdbcAuditRetentionIT",
    "com.follarce.persistence.postgres.repository.JdbcProcessEffectIT",
    "com.follarce.persistence.postgres.repository.JdbcProductionHardeningIT",
    "com.follarce.persistence.postgres.repository.JdbcTerminalCommandHistoryIT",
    "com.follarce.persistence.postgres.repository.PostgresBackupRestoreIT",
    "com.follarce.persistence.postgres.repository.PostgresWalCrashIT",
    "com.follarce.vfs.AdminVfsServiceIT",
)


def report_counts(path: Path) -> tuple[int, int, int, int]:
    root = ElementTree.parse(path).getroot()
    return tuple(int(root.attrib.get(name, "0"))
                 for name in ("tests", "failures", "errors", "skipped"))


def main() -> int:
    failures: list[str] = []
    unit_reports = sorted((ROOT / "target" / "surefire-reports").glob("TEST-*.xml"))
    if not unit_reports:
        failures.append("no Runtime unit-test reports were produced")
    elif sum(report_counts(path)[0] for path in unit_reports) < 1:
        failures.append("Runtime unit-test reports contain no tests")

    integration_directory = ROOT / "target" / "failsafe-reports"
    for class_name in MANDATORY_INTEGRATION_TESTS:
        report = integration_directory / f"TEST-{class_name}.xml"
        if not report.is_file():
            failures.append(f"missing mandatory integration report: {class_name}")
            continue
        tests, failed, errors, skipped = report_counts(report)
        if tests < 1 or failed or errors or skipped:
            failures.append(
                f"invalid mandatory integration report {class_name}: "
                f"tests={tests} failures={failed} errors={errors} skipped={skipped}"
            )

    if failures:
        for failure in failures:
            print(f"test-report error: {failure}", file=sys.stderr)
        return 1
    print(f"Verified unit reports and {len(MANDATORY_INTEGRATION_TESTS)} mandatory integration suites")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
