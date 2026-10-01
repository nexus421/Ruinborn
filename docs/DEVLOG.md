# Implementation log Ruinborn v1

Chronological log of all steps. Each entry states what was done, why, and how it was verified. Fundamental decisions and
deviations from the concept are collected in [DECISIONS.md](DECISIONS.md), the plan is in
[superpowers/plans/2026-09-30-ruinborn-v1.md](superpowers/plans/2026-09-30-ruinborn-v1.md).

## 0. Initial inventory (30.09.2026)

- Found: `Ruinborn – Spielkonzept v1.md` (1,227 lines, 19 sections) and a supplied asset package (ready-made libGDX atlas with 292 frames on a 2:1 iso grid of 128 × 64, Kotlin loading code, Python generator, raw images, preview).
- Backed up the original concept before implementation started. All later changes are listed in section 20 of the concept.
- Recalculated all examples in the concept (HQ 10/20, warehouse 20, farm 20, research 10, zombie 20, battle example in section 7): all correct. HQ 20 takes 17,543.04 s → rounded up 17,544 s = 4 h 52 min 24 s as in the concept.
- Environment: Corretto 25.0.4 installed, Gradle caches present, access to Maven Central and `maven.kickner.bayern` works. Noto Sans is installed locally.
- Python dependencies of the sprite generator (Pillow 12.3, NumPy 2.5, SciPy 1.18) installed in a venv in the scratch directory (the system Python has no `ensurepip`, therefore `get-pip.py` from PyPA).

## 1. Version research (30.09.2026)

Sources: `maven-metadata.xml` from Maven Central, Gradle Plugin Portal, `services.gradle.org`, `maven.kickner.bayern`, source code of gdx-liftoff (master).

| Component | Concept (25.09.) | Used | Note |
| --- | --- | --- | --- |
| Gradle | 9.7.1 | **9.8.0** | current since 24.09.2026, used by gdx-liftoff 1.14.2.3 |
| Kotlin | 2.4.20 | 2.4.20 | 2.4.21 only RC, 2.5.0 only beta |
| Ktor | 3.6.0 | 3.6.0 | server, client and Gradle plugin |
| Exposed | 1.x | **1.5.0** | latest 1.x |
| sqlite-jdbc | 3.53.4.0 | 3.53.4.0 | |
| kotlinx.serialization | none | 1.11.0 | 1.12.0 only RC |
| kotlinx.coroutines | none | 1.11.0 | |
| libGDX | 1.14.2 | 1.14.2 | |
| KTX | "as in gdx-liftoff" | **1.14.2-rc2**, group `io.github.quillraven.libktx` | KTX is now maintained by Quillraven under a new Maven group. rc2 is newer than the rc1 in gdx-liftoff |
| LWJGL | none | 3.4.3 | as in gdx-liftoff (fixes Java 25 warnings) |
| Klogger | 0.1.0 | **0.2.0** | latest version in the own repository |
| KotNexLib | 4.3.0 | **4.4.1** | latest version in the own repository |
| slf4j-api | (from Ktor) | 2.0.20 | for the custom SLF4J bridge. 2.1.0 only alpha |
| Foojay resolver | none | 1.0.0 | toolchain provisioning in CI |
| JDK | Corretto 25 | Corretto 25 | toolchain `languageVersion = 25`, `vendor = AMAZON` |

## 2. Plan (task 0)

- Implementation plan created with the skill `superpowers:writing-plans`: `docs/superpowers/plans/2026-09-30-ruinborn-v1.md` (20 tasks in 11 phases). Executed natively in one session because the user delegated all intermediate decisions.

## 3. Phase A: skeleton (task 1)

- `settings.gradle.kts` with the modules `shared`, `client`, `core`, `lwjgl3`, `server`, `tools`. Central repositories (Maven Central + `maven.kickner.bayern/releases`, restricted to the group `bayern.kickner`). Foojay resolver for the toolchain.
- `gradle/libs.versions.toml` with all versions from section 1.
- Gradle wrapper 9.8.0 generated with the locally installed 9.7.1.
- `shared` and `client` are KMP modules (`jvm()` target, code in `commonMain`), the others Kotlin/JVM.
- Bytecode: `shared`, `client`, `core` JVM 17 (`-Xjdk-release=17`, so only JDK 17 APIs are used), `server`, `lwjgl3`, `tools` JVM 25. Toolchain everywhere Corretto 25.
- `core` generates `BuildInfo.kt` from `gradle.properties` (`ruinborn.versionCode`, `ruinborn.versionName`, `ruinborn.serverUrl`).
- Replaced the Gradle 9.8 deprecation `val x by tasks.registering` with `tasks.register(...)`.
- Check: `./gradlew build` → BUILD SUCCESSFUL, no deprecation warnings.

## 4. Phase B: game rules in `shared` (tasks 2 to 6)

- `model/Enums.kt`: all IDs and states from sections 3 to 15. Slots keep the German IDs of the concept (`HQ`, `MAUER`, …, `R1` to `R10`), building types use English names as in the balance example (`FARM`, …).
- `shared/balance/balance.json`: complete default balance with every number from sections 2 to 11. A Gradle task embeds it as `DefaultBalance.JSON` (single source. The server writes it to `balancePath` on first start).
- `balance/Balance.kt` + `BalanceValidator.kt`: one data class per top-level key. Required fields without default values, unknown keys are an error. `validate()` checks the completeness of all maps and the value ranges.
- `rules/Rules.kt`: all formulas (costs, durations, production, capacities, troop tiers, healing, hero experience, zombies, nests, fields, zones, travel times, helps, speed-ups, power). `floorSafe`/`ceilSafe` guard against floating point artifacts.
- `rules/Bonuses.kt`: bonuses per kind, additive. Research, hero, catch-up bonus.
- `rules/Resources.kt`: materialization using formula B(t). **Deviation:** amounts as decimals (see DECISIONS.md E-07).
- `combat/Combat.kt`: combat algorithm, split between hospital and death, loot, split by shares.
- `rules/Validation.kt`, `dto/*` (including sealed `ReportPayload` and `WsEvent` with discriminator `type`), `ApiJson`, `Log` facade.
- Tests (`./gradlew :shared:allTests`): 43 tests, all green. Among them HQ 10/20, farm 20, warehouse 20, research 10, zombie 20, battle example (1,851.85 / 482.14 / 3 rounds / 9 losses), counter bonus, 20 round limit, empty base, hospital order, loot without division by 0, WebSocket format exactly as in the concept.

## 5. Phases C to G: server (tasks 7 to 16)

- Configuration (`ServerConfig`, `dev/config.json`), Klogger setup, SLF4J→Klogger bridge (`META-INF/services`), `Db` with separate write and read connections (pragmas, WAL at startup), custom migrations (`db/index.txt`, `V001__init.sql`, 29 tables + indexes), Exposed tables.
- Engine: one thread, one `Channel`, alarm job. Every command is one transaction, side effects run after the commit. Idempotency via `processed_request`. Overdue events are processed at startup.
- Game logic as `Ctx` extensions: accounts/sessions/hashing (KotNexLib), starting state, `settle`, economy, build/demolish/research/training/healing/cancel/speed-ups/items, daily tasks/achievements/rewards, marches, battles, alliances, rallies/nests/gifts, chat, admin commands with audit log, spawn, daily reset, cleanup, backup, rankings, profile, cosmetics, `--new-world`, balancing simulation.
- HTTP routes for all endpoints from section 15, WebSocket hub, rate limits, `X-Server-Time`, 426 check.
- Trial start: `./gradlew :server:run --args="--config dev/config.json"` → health, version and registration via `curl` successful.

Bugs found and fixed during testing:

1. Exposed switched the read-only SQLite connection to "writable" (sqlite-jdbc forbids that) → `DatabaseConfig.defaultReadOnly` set per database.
2. SQLite reused deleted IDs: the new return-home event got the ID of the deleted arrival event and was removed along with it → `AUTOINCREMENT` (E-14), and the engine now removes first, then adds.
3. The engine loop could die from an exception → every message is guarded individually. Additionally a guard against engine commands from inside the engine (deadlock) and a 60 s timeout for tests.
4. Registration and login shared one rate limit → separated (E-15).

Tests (`./gradlew :server:test`): **55 tests, all green**, among them:

- Starting state exactly as in section 2. Build queues, HQ/wall rule, 50 % cancel refund, speed-ups with excess loss, offline days with a full warehouse, exact production across timer boundaries, research/lab, training, healing, items, daily tasks, beginner protection up to HQ 6.
- Map: spawn amounts per zone, battle example of section 7 end to end (3 rounds, 9 wounded, 400 food), zombie level lock, a lost battle leaves the zombies in place, gathering up to load, recall, **restart catches up on arrival and return home**, **two arrivals in the same millisecond ordered by march ID**, relocation, scouting, PvP with loot, a shield turns a running attack around, recovery shield after 3 defeats, attack on a gathering march, nest only via rally, march size.
- Alliances: founding rules, rights per rank, 12 h join lock, help (−60 s, once per timer, daily task), reinforcement, **rally with 3 participants defeats nest 1**, gifts also for non-participants, joining too late, cancellation, **leaving with a waiting rally march and a stationed reinforcement**.
- System: **daily reset exactly once at 04:00 on both DST change days** (25 h and 23 h interval), catch-up bonus with median, inactivity shield, migration of an empty DB → 29 tables, backup keeps 14, chat limits and admin commands, dev commands only with `devMode`, rankings, new world, simulation.
- HTTP (ktor-server-test-host): public endpoints, registration/login rules without revealing wrong names, login rate limit, 401/426, **duplicate `X-Request-Id` → one execution, same response**, balance with ETag/304, invalid JSON → 400, admin commands via chat including `/password`, WebSocket receives `state_changed`.

## 6. Balancing simulation

- `./gradlew :server:runSim` (option `-Psim.days=N`): real engine, controllable clock, two bots per player type, logins between 8:00 and 22:00. Result `sim-result.csv` (day, type, HQ, power, resources, zombie wins, speed-ups, units) and an evaluation of the target values. 60 days take about one minute.
- Bot behavior made more realistic step by step (upgrade the scarcest resource first, HQ reserve for side buildings, a fifth into the military continuously).
- Result with the concept's starting values: normal HQ 10/15/20 on day 9/17/30, casual 12/25/45, active 7/13/21.
- Parameter study (60 days each): zombie reward growth 1.15 to 1.30, gathering rate 1,000 to 4,000/h, time growth 1.30 to 1.42, drop chance 5 to 25 %, HQ cost growth 1.40 to 1.52. None of the values moves HQ 20 noticeably without pushing HQ 10 out of the target range. The late game is limited by the login rhythm (E-20). The default balance therefore keeps the concept's starting values. Fine tuning follows in M6 with beta data.

## 7. Phase H: assets (task 17)

- Moved the generator of the supplied assets to `tools/sprites/` (paths and Kotlin package adjusted: raw images to
  `assets-raw/sprites/`, catalog to `core/.../render/gfx/SpriteCatalog.kt`).
- Added in the style of the supplied set: buildings `wall`, `factory`, `range`, `rally_point` (animated flag), `alliance_center`
  (animated flags). Map objects `nest` (pulsing), `field_food`, `field_wood`, `field_steel`. Unit `shooter`.
  28 new icons (shield, chest, book, relocation, sword, clock, binoculars, chat, report, alliance, gear, padlock,
  research, hospital, gift, star, trophy, task, map, base, troops, help, power, five hero figures).
  Result: 390 frames. Visual check via contact sheet.
- `:tools:packTextures` (libGDX TexturePacker, settings from `pack.json`) → `assets/atlas/game.atlas` (2 pages ≤ 2048).
- `:tools:generateFonts` (FreeType + BitmapFontWriter in a headless application) → Noto Sans 16/20/28 and bold 20/28,
  each at 1.5x, with umlauts, ß, €, typographic characters. License (OFL) in `assets-raw/fonts/OFL.txt`.

## 8. Phase I: client layer `client` (KMP, task 18)

- `ApiClient` for all endpoints (Ktor client, engine from the launcher), `X-Request-Id` for write commands with one
  retry after a timeout, 10 s timeout, server time offset, balance via ETag, callbacks for 401/426.
- `GameSocket` reconnecting after 1, 2, 5, 10, then every 30 s. After every reconnect `GameClient` reloads state,
  map, balance and chat. `GameState`/`GameStore` immutable, only replaced on the render thread.
- Additional DTO field `PlayerState.gameSpeed` so the client computes costs and durations exactly like the server.
- Tests: 6 unit tests (backoff, server time, map changes, chat merging, store, URL encoding) and
  one end-to-end test in the server module (real client against a real CIO server: registration, loading, building, timer,
  chat over WebSocket).

## 9. Phase J: libGDX client `core` and `lwjgl3` (task 19)

- `RuinbornGame` (KtxGame), Klogger only with `logToCustom` → `Gdx.app.log`, SLF4J bridge in the client as well.
- Skin built entirely in code (ktx style) with rounded nine-patches from pixmaps. Virtual resolution 720 × 1280
  (`ExtendViewport`), desktop window 540 × 960.
- Screens: login (log in/register, version check, update dialog on 426), base (isometric, 20 slots,
  level badges, construction sites with progress and workers, padlocks with HQ level, skin tint), map (zone ground,
  decoration, objects with levels, marches as a line with an animated unit, zoom via mouse wheel/two fingers, center base).
- HUD: resources with production, power, protection and catch-up bonus icon, "next goal", banners (attack with
  countdown, connection lost, announcement, update), timer bar with countdowns (reloads at 0), navigation with counters.
- Dialogs: building (info, upgrade, demolish, build menu), training, research, hospital, heroes, inventory, timer actions
  (speed up, help, cancel), tile info with actions, march (hero, slider per type/tier, maximum,
  combat power, load, travel time), march actions (recall), alliance (search, founding, overview, members with
  rights, requests, help, rallies, gifts), chat (world/alliance, report, admin delete, admin commands),
  reports (list, battle/scout/gathering/system report), tasks & achievements, rankings, profile & cosmetics,
  settings (password, log out), more menu. Confirmation for orders over 1 h and actions against players.
- Texts: 400+ keys in `assets/i18n/strings.properties`.
- Visual check: every screen and every dialog captured via `--screenshot` against the running dev server and
  reviewed. Fixed along the way: double mirrored screenshots, dialogs sized too low, wrapping labels with
  full text width (new `WrapLabel`), headings too long, too many tabs in one row, dialogs built before the
  first game state, missing glyph "⚔".
- Played through (API + client): registration, HQ upgrade, zombie attack with march line and battle report, founding/joining
  an alliance, requesting help, world and alliance chat.
- Test `AssetsTest` (without OpenGL): every sprite of the catalog with frame count and size in the atlas, pages ≤ 2048,
  fonts with umlauts/ß/€, base layout complete and without overlaps, every text key used in code and all
  dynamic keys present.

## 10. Phase K: wrap-up (task 20)

- `README.md`, `CLAUDE.md`, operations guide, decisions document (then E-01 to E-24), concept section 20.
- GitHub Actions workflow `.github/workflows/build.yml` (Corretto 25, `./gradlew build`. Actions in their current
  versions checkout v7, setup-java v6, setup-gradle v6).
- Started the fat JAR `server/build/libs/ruinborn-server.jar` standalone (migration from the JAR, default balance
  written, registration with 100,000 hash iterations in ~0.25 s) and played through `--new-world`.

## 11. Phase L: Android app (01.10.2026)

Added at the user's request (the app is the actual target, E-02 revised).

- Versions: Android Gradle Plugin **9.4.1** (ships its own Kotlin support, no `kotlin-android` plugin),
  `gdx-backend-android` 1.14.2, `ktor-client-okhttp` 3.6.0. compileSdk and targetSdk **37** instead of 36 from gdx-liftoff,
  because OkHttp 5.5 (from Ktor 3.6) requires at least compileSdk 37 (the AAR metadata check failed) (E-25).
- Module `android`: `AndroidLauncher` (immersive mode, no sensors, OkHttp engine, server URL from `BuildConfig`),
  manifest (only `INTERNET`, portrait, `singleTask`), debug manifest with cleartext HTTP, theme without bars
  (display cutout from API 27 in `values-v27`), launcher icons from the HQ sprite, no backup/device transfer
  (only the session token in the preferences), signing from `ruinborn.storeFile` etc., unsigned
  release APK without a key. Assets directly from `assets/`, natives via the task `copyAndroidNatives` for four ABIs.
- The module is only included if an Android SDK is found (`ANDROID_HOME`, `ANDROID_SDK_ROOT`,
  `local.properties`, `~/Android/Sdk`) and `-Pruinborn.skipAndroid` is not set. Desktop and server therefore build
  without an SDK as well, and a failure in the Android module does not affect them (E-26).
- `core`: the back key (Android) or Esc closes the topmost dialog. On the map it leads to the base, in the base
  nothing happens (E-27).
- Lint: initially 1 error (display cutout only from API 27) and 4 warnings. Fixed or disabled with a reason
  (portrait is intentional). Result "No issues found".
- Quirk of the build sandbox (not of the project): the JVM writes a cgroup warning to stdout there,
  which breaks AGP's `JdkImageTransform` ("Could not determine Java version"). Workaround when building:
  `export JAVA_TOOL_OPTIONS="-Xlog:os+container=off"`.
- Emulator test (Pixel profile API 36, 1080 × 2400) against the dev server (`-Pruinborn.androidServerUrl=http://10.0.2.2:8080`):
  login and registration with the on-screen keyboard, base, HQ upgrade with construction site, map, tile info, march with
  slider, battle report, all entries in the more menu, heroes, alliance, chat (send, tap a message, profile,
  "show on map"), log out, back key, app to the background and back.

## 12. Full code review (01.10.2026)

All modules reviewed file by file, then desktop and Android clicked through by hand again. Found and fixed:

| Area | Bug | Fix |
| --- | --- | --- |
| Server `settle` | If the catch-up bonus ended after a finished timer, resources were materialized up to the bonus end first and the timer applied afterwards: a production upgrade counted too late (too few resources). | The bonus end is a breakpoint before every timer and before "now". Test `timerDuringCatchupBonusCountsFromItsEnd` (fails with the old code: 2375 instead of 2406). |
| Server API | `X-Forwarded-For` was trusted from anyone. Whoever reached the server directly could have bypassed the login limit with made-up headers. | The header only counts if the connection comes from loopback/a private network (Caddy) (E-28). |
| Server WebSocket | When a session ended, the connection could close before "session ended" was sent. | Closing is a marker in the outgoing buffer after the last message. |
| Server backup | The backup "before new world" sorted as the newest and permanently took one of the 14 slots. | Rotation only over files `ruinborn-YYYY-MM-DD.db`. Test extended. |
| Server admin | After `/unban` the report "shield ended" still arrived later. | The `SHIELD_END` event is removed when lifting the ban. |
| Client | A `state_changed` that arrived during a running request was lost (stale state until the next event). | Merging via a `CONFLATED` channel: after a running request exactly one more follows. |
| Client | On timeout the registration was retried as well (without `X-Request-Id`). The account had been created, the user saw "name taken". | Only reads and commands with `X-Request-Id` are retried. |
| core (all platforms) | **Crash:** entries in the more menu and "profile" on a chat message closed the dialog and then used its (now empty) stage. | Keep the stage beforehand. `GameDialog.show` also accepts `null` and then does nothing. |
| core Android | Panels and buttons were unmanaged textures and would turn black after a GL context loss. | `PixmapTextureData(managed = true)`, pixmaps are kept until `dispose`. |
| core Android | An unexpected error in a background coroutine would have terminated the process. | A `CoroutineExceptionHandler` logs it. |
| core Android | The on-screen keyboard stayed open over the base after login/registration. | It is closed on screen changes and when a dialog with input focus closes. |
| core Android | After returning from the background the state could be stale (WebSocket dropped silently). | `resume()` reloads state and map. |
| core login | On tall screens the scene was behind the form. | Form at the top (the keyboard does not cover it), scene at a fixed distance from the bottom edge. |
| core | Debug output via `RUINBORN_UI_DEBUG` in the dialog layout. | Removed. |

Checked without findings: engine loop and transactions, event order, idempotency, combat, loot, hospital,
gathering, rallies, leaving an alliance, daily reset and cleanup, migrations, rate limits, admin commands,
validation, formulas in `Rules`.

Result: `./gradlew build` green, **111 tests** (shared 43, client 6, core 5, server 57) also with `--rerun`,
Android lint without findings, debug and unsigned release APK built, `-Pruinborn.skipAndroid` builds without Android.

## 13. Preparation for publication (01.10.2026)

- Translated all KDoc, code comments and documentation (except the concept and in-game texts) to English. A comparison of
  all source files with comments removed showed no code changes apart from the two below.
- `tools/sprites/generate.py`: translated the comment templates for the generated `SpriteCatalog.kt`.
- `tools/sprites/verify/VerifyAssets.kt`: imports now point to the project package instead of the package of the
  original asset package.
- AGP lowered from 9.4.1 to 9.1.0 because IntelliJ IDEA does not support newer versions (E-25). Debug and release APK
  build, lint reports 0 errors. Not yet tested on the emulator with this version.
- `gradle/gradle-daemon-jvm.properties` pins the Gradle daemon to Corretto 25, because Gradle 9.8 does not run on Java 27.
- Removed the archived original concept, the original asset package and its README, and references to third-party
  product names. Added `LICENSE` (MIT).

## Open items (after the v1 scope of this stage)

1. Balancing with beta data (M6). Simulation and findings exist (E-20).
2. Testing on real Android devices (so far only the emulator) and beta with friends (M6).
3. Create the release signing key (concept section 16) and add `ruinborn.storeFile` etc. to `~/.gradle/gradle.properties`.
