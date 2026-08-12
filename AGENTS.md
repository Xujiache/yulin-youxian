# AGENTS.md

## Cursor Cloud specific instructions

This is a multi-product monorepo for 禹邻优鲜 (a single-store fresh-grocery same-city
delivery platform). The current branch focus is `feature/rider-delivery`.

### Services and how to run them (headless Linux VM)

Two services run and are testable end-to-end in the cloud VM; the other three
require GUI / OS-specific tooling and cannot run here.

| Service | Path | Runnable headless | Notes |
| --- | --- | --- | --- |
| Backend API (`fresh-delivery-server`) | `server` | Yes | Spring Boot 4.1 / Java 17 / Maven. Needs MySQL. |
| Admin web (`art-lnb-master`) | `art-lnb-master` | Yes | Vue 3 + Vite. Proxies `/api` → `:8080`. |
| WeChat mini program | `client-wechat`, `packages/yulin-youxian-miniprogram-source` | No | Needs WeChat DevTools GUI. Only its `node --test` suite runs headless. |
| Rider app (Android) | `rider-android` | No | Needs Android SDK (not installed) + device/emulator. `./gradlew` unusable without SDK. |
| Print agent | `print-agent` | No | Windows-only C#/.NET Framework + DPAPI + PowerShell. |

### Backend (`server`)

- Use JDK 17, not the VM default 21: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`
  (the `pom.xml` targets Java 17, matching CI).
- Standard commands live in `server/README.md`: tests `./mvnw -B -ntp clean test`, run
  `./mvnw spring-boot:run`. Serves `/api/wx/**` (mini program) and `/api/admin/**` (admin) on `:8080`.
- MySQL is REQUIRED even though the README says "no MySQL needed". On this branch Flyway
  runs 11 migrations (rider/delivery relational tables) against MySQL at boot regardless of
  `PERSISTENCE_MODE`. MySQL 8 is installed in the snapshot but is not started automatically —
  start it each session with `sudo service mysql start`. DB `yulin_fresh`, user
  `yulin_fresh` / password `yulin_fresh_pw` on `127.0.0.1:3306` already exist in the snapshot.
- The app does NOT auto-load `.env`; export env vars before running. Minimum to boot:
  `MYSQL_USERNAME`, `MYSQL_PASSWORD`, `ADMIN_USERNAME`, `ADMIN_PASSWORD`,
  `DELIVERY_STORE_LAT`, `DELIVERY_STORE_LNG`. Optional `STOREFRONT_SEED_DEMO_DATA=true`
  seeds demo categories/products. See `server/.env.example` for the full list.
- `ADMIN_PASSWORD` has a strength gate: ≥14 chars, ≥3 of {upper, lower, digit, symbol},
  and must not contain the username — otherwise startup fails with
  "生产环境管理员密码强度不足". (A working local combo used during setup was
  `ADMIN_USERNAME=admin` / `ADMIN_PASSWORD=Str0ngDeliveryPass2026`.)

### Timezone gotcha (important)

The backend business timezone is `Asia/Shanghai` (`application.yml` `jackson.time-zone`).
Several backend tests (e.g. `MatrixCacheServiceTests`, `RoutingWithoutAmapKeyTests`) mix
`LocalDateTime.now()` with expiry / time-window math and FAIL under a UTC JVM. The VM
system timezone is set to `Asia/Shanghai` in the snapshot, which makes the full suite (512
tests) pass. If you ever see those two tests fail, confirm the timezone and, if needed, run
with `TZ=Asia/Shanghai` (or re-apply `sudo ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime`).

### Admin web (`art-lnb-master`)

- Use `npm` (matches CI + `package-lock.json`), even though the README mentions pnpm.
  Standard scripts: `npm run dev` (Vite dev server on port 3006, proxies `/api` → `:8080`),
  `npm run build` (`vue-tsc --noEmit && vite build`), `npm run lint`.
- Auto-import generation gotcha: `npm run build` and `npm run lint` depend on generated files
  that are gitignored and only produced when Vite runs: `src/types/import/auto-imports.d.ts`,
  `src/types/import/components.d.ts`, and `.auto-import.json`. On a fresh checkout these do
  not exist, so a first `npm run build` fails with `Cannot find name 'ref'/'computed'/...`
  and lint fails with `.auto-import.json ... no such file`. Fix: run Vite once first
  (`npx vite build` or `npm run dev`) to generate them, then `npm run build` / `npm run lint`
  work. A dev session (`npm run dev`) generates them automatically.
- `npm run lint` currently reports pre-existing `prettier/prettier` style errors in repo
  source files. This is not an environment problem; CI's admin job only gates on `npm run build`.
- Admin login uses the BACKEND admin credentials (`ADMIN_USERNAME`/`ADMIN_PASSWORD` via
  `POST /api/admin/auth/login`), NOT the upstream template's `Super`/`123456`.

### Quick end-to-end check

With MySQL up and the backend running, start the admin dev server and log in at
`http://localhost:3006/` with the configured admin credentials. A good core-feature smoke
test is creating a rider under 配送任务 → 骑手管理 (`/#/fresh/delivery/riders`); it writes a
row to the MySQL `rider` table.
