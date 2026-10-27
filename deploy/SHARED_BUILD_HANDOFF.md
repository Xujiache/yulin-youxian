# Shared build handoff — resolved

The final integration pass resolved the blockers recorded by A12 in the current
`feature/rider-delivery` working tree:

1. Backend generated keys now read `id` by column name with a single-key
   fallback; the clean isolated suite passes 512/512 tests.
2. Android imports `URI` without the Gradle `java` DSL collision. The clean,
   no-build-cache debug gate passes 12 JVM tests and assembles the debug APK.
3. Non-ASCII Windows checkout paths now mirror unit-test classes to an ASCII
   temporary path, removing the stale `ClassNotFoundException` failure.
4. Production admin credentials remain fail-closed; package manager metadata,
   root dump/token/runtime ignores, signed release verification, and HTTPS URL
   rejection are wired.
5. Flyway V11 and the 36-table readiness contract were validated against H2
   and isolated MySQL 8 databases.

`pnpm-lock.yaml` and `pnpm-workspace.yaml` were intentionally retained; CI uses
Node 22.14.0, npm 10.9.2, and `package-lock.json`. No tracked archive was
deleted. See `deploy/security/tracked-archive-report.md`.
