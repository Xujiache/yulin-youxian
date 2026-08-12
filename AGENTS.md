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

### Rider app (`rider-android`) — build & unit tests (headless)

- Install the Android SDK once (baked into the snapshot): command-line tools + `platforms;android-36`,
  `build-tools;36.0.0`, `platform-tools`. Set `sdk.dir` in `rider-android/local.properties`
  (git-ignored) and copy `gradle.properties.example` → `gradle.properties`.
- Build/test (matches CI, needs JDK 17 + `ANDROID_HOME`): `./gradlew --no-daemon clean testDebugUnitTest assembleDebug`.
  Produces `app/build/outputs/apk/debug/app-debug.apk`; unit tests live in `app` + `core:*` modules.
- Mirror gotcha (important): `settings.gradle.kts` puts Aliyun mirrors first, and the Aliyun mirror
  intermittently returns HTTP 502 for some artifacts (seen with the KSP plugin marker
  `com.google.devtools.ksp:...:2.3.11`). A 502 from the first repo makes Gradle abort resolution even
  though Maven Central / Google / Gradle Plugin Portal have the artifact. Fix without editing the repo:
  a `~/.gradle/init.gradle` (baked into the snapshot) reorders `pluginManagement`/`dependencyResolutionManagement`
  to prefer `gradlePluginPortal()`/`mavenCentral()`/`google()` and keep Aliyun only as fallback. If Android
  builds start failing with "plugin ... was not found", verify that init script still exists.
- Debug variant `BASE_URL` defaults to `http://10.0.2.2:8080` (emulator loopback → host backend) and the
  `src/debug` network-security-config permits cleartext, so a debug APK talks to a local backend with no rebuild.
- Emulator GUI run: the Android emulator (even with KVM usable) does not finish booting in this Firecracker
  micro-VM (device stays `offline`); nested-virt limitation, not a code issue. Validate rider↔backend behavior
  via the `/api/rider/**` endpoints + unit tests instead of a live emulator.

### Cross-end delivery flow (order → dispatch → deliver), all against MySQL

WeChat customer login needs real mini-app credentials (`jscode2session`, no dev bypass), so place orders
via the seeded demo order instead (enable `STOREFRONT_SEED_DEMO_DATA=true`). Path that works headless:
`POST /api/admin/orders/{id}/accept` → `POST /api/admin/delivery/orders/{id}/pick-ready` (creates a
`delivery_task`, PENDING) → `POST /api/admin/delivery/tasks/{taskId}/assign` with `{"riderId":..,"force":true}`
(manual dispatch sets `task.rider_id`; the auto-dispatch loop won't assign a probation rider / stale-location /
far task) → rider `accept` → `waves/{id}/pickup` → `depart` → `arrive` → upload a photo via
`POST /api/rider/evidences` → `deliver` with `evidenceIds` (delivery config requires photo proof).
Note: changing a rider password revokes the current rider token (re-login), and admin login is rate-limited
(`ADMIN_LOGIN_RATE_LIMIT_MAX_ATTEMPTS`, default 5 / 15 min) — cache one token instead of re-logging in.
