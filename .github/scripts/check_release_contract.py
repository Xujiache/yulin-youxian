#!/usr/bin/env python3
"""Assert that release readiness matches the checked-in Flyway schema."""

from __future__ import annotations

import re
from pathlib import Path


def main() -> int:
    migrations = Path("server/src/main/resources/db/migration")
    files = sorted(migrations.glob("V*__*.sql"))
    if not files:
        raise RuntimeError("No Flyway migrations found")

    versions: set[int] = set()
    table_count = 0
    for path in files:
        match = re.match(r"V(\d+)__", path.name)
        if not match:
            raise RuntimeError(f"Unexpected migration name: {path.name}")
        versions.add(int(match.group(1)))
        table_count += len(
            re.findall(
                r"(?im)^\s*CREATE\s+TABLE(?:\s+IF\s+NOT\s+EXISTS)?\s+",
                path.read_text(encoding="utf-8"),
            )
        )

    readiness = Path("server/scripts/readiness.sql").read_text(encoding="utf-8")
    if max(versions) != 11:
        raise RuntimeError(f"Expected latest Flyway V11, got versions {sorted(versions)}")
    if table_count != 36:
        raise RuntimeError(f"Expected 36 business tables, counted {table_count}")
    if not re.search(r"version\s*=\s*'11'", readiness):
        raise RuntimeError("readiness.sql does not require Flyway V11")
    if "@business_table_count = 36" not in readiness:
        raise RuntimeError("readiness.sql does not require 36 business tables")

    print("Flyway/readiness contract OK: V11, 36 business tables")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
