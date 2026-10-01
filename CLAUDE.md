# Ruinborn: notes for working on the code

The binding specification is `Ruinborn – Spielkonzept v1.md` (German). Deviations are listed in `docs/DECISIONS.md`
and in section 20 of the concept. If a change deviates from the concept, update the concept first.

## Modules

| Module | Type | Content |
| --- | --- | --- |
| `shared` | Kotlin Multiplatform (`jvm`), `commonMain` only | DTOs, enums, balance data classes, formulas (`Rules`), resources, combat, validation, `Log` facade |
| `client` | Kotlin Multiplatform (`jvm`), `commonMain` only | `ApiClient` (Ktor), `GameSocket`, `GameClient`, `GameState` |
| `core` | Kotlin/JVM 17 | libGDX client: screens, HUD, dialogs, rendering, assets |
| `lwjgl3` | Kotlin/JVM 25 | desktop launcher (Ktor engine CIO) |
| `android` | Android (AGP 9, JVM 17) | Android launcher (Ktor engine OkHttp), manifest, icons. Only included if an Android SDK is found |
| `server` | Kotlin/JVM 25 | Ktor server, single-writer engine, SQLite/Exposed, jobs, admin, simulation |
| `tools` | Kotlin/JVM 25 | `packTextures`, `generateFonts` |

## Conventions

- Packages: `bayern.kickner.ruinborn.shared`, `…server.{api,engine,db,jobs,admin,sim,log}`, `…client.{net,state,screen,ui,render,dialog}`, `…tools`.
- Style: `runCatching` instead of `try/catch`, `.not()` instead of `!`, sealed classes against invalid states.
- Server: expected errors are `ResultOf2<T, GameError>` (KotNexLib). Helpers `ok()`, `fail()`, `ensure(...)?.let { return it }`, `orReturn { return it }`.
- Game logic consists of extension functions on `Ctx` (one engine transaction). Never call engine commands from inside the engine.
- Every game number lives in `shared/balance/balance.json` (embedded as `DefaultBalance.JSON`). No numbers in code.
- Time only via `Clock` (tests: `ManualClock`), randomness only via the injected `Random`.
- `commonMain` contains no `java.*` imports.
- New DTO fields always get a default value (older clients ignore unknown fields).
- Dialogs: after `close()`, `stage` is null. Store it in a variable first if another dialog is opened afterwards.
- All client texts live in `assets/i18n/strings.properties` (German). The test `AssetsTest` checks that every key used exists.
- KDoc, comments and documentation are written in English. The concept and in-game texts stay German.
- Migrations: new file `server/src/main/resources/db/V00N__….sql` and an entry in `db/index.txt`. Never change `V001` once a server runs with it.

## Common commands

```bash
./gradlew build                                         # build and test everything
./gradlew :server:run --args="--config dev/config.json" # local server (gameSpeed 20, devMode)
./gradlew :lwjgl3:run                                   # desktop client
./gradlew :android:installDebug -Pruinborn.androidServerUrl=http://10.0.2.2:8080   # app in the emulator
./gradlew :server:runSim                                # balancing simulation → sim-result.csv
python3 tools/sprites/generate.py && ./gradlew :tools:packTextures   # regenerate and pack sprites
```
