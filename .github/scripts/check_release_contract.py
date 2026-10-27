#!/usr/bin/env python3
"""发布契约检查：迁移文件、readiness.sql 与 deploy/versions.env 三方必须一致。

基线（最新 Flyway 版本号、业务表张数）只写在 deploy/versions.env 里，
这里从那边读出来再对照，不在本文件里再抄一份数字 ——
之前 V11/36 同时写死在三个地方，加一条迁移就全体过期。
"""

from __future__ import annotations

import re
from pathlib import Path


def read_baseline() -> tuple[int, int]:
    env = Path("deploy/versions.env").read_text(encoding="utf-8")
    version = re.search(r"^EXPECTED_FLYWAY_VERSION=(\d+)$", env, re.M)
    tables = re.search(r"^EXPECTED_BUSINESS_TABLES=(\d+)$", env, re.M)
    if not version or not tables:
        raise RuntimeError(
            "deploy/versions.env must define EXPECTED_FLYWAY_VERSION and EXPECTED_BUSINESS_TABLES"
        )
    return int(version.group(1)), int(tables.group(1))


def main() -> int:
    expected_version, expected_tables = read_baseline()

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

    if max(versions) != expected_version:
        raise RuntimeError(
            f"versions.env expects Flyway V{expected_version}, migrations reach V{max(versions)}; "
            "新加了迁移请同步更新 deploy/versions.env 与 server/scripts/readiness.sql"
        )
    if table_count != expected_tables:
        raise RuntimeError(
            f"versions.env expects {expected_tables} business tables, migrations create {table_count}; "
            "新建/删除了表请同步更新 deploy/versions.env 与 server/scripts/readiness.sql 的表清单"
        )

    readiness = Path("server/scripts/readiness.sql").read_text(encoding="utf-8")
    if not re.search(rf"version\s*=\s*'{expected_version}'", readiness):
        raise RuntimeError(f"readiness.sql does not require Flyway V{expected_version}")
    if not re.search(rf"@flyway_max_version\s*=\s*{expected_version}\b", readiness):
        raise RuntimeError(f"readiness.sql max version check is not {expected_version}")
    if f"@business_table_count = {expected_tables}" not in readiness:
        raise RuntimeError(f"readiness.sql does not require {expected_tables} business tables")
    named_tables = len(re.findall(r"(?m)^\s*'[a-z0-9_]+',?\s*$", readiness))
    if named_tables != expected_tables:
        raise RuntimeError(
            f"readiness.sql enumerates {named_tables} tables, expected {expected_tables}"
        )

    print(f"Flyway/readiness contract OK: V{expected_version}, {expected_tables} business tables")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
