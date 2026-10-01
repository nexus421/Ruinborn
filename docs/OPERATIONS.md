# Operations

Based on concept section 16. The server runs as a fat JAR with systemd in an Incus container (Debian 13) behind
Caddy, without Docker.

## Building

```bash
./gradlew build                   # build and test all modules
./gradlew :server:buildFatJar     # → server/build/libs/ruinborn-server.jar
./gradlew :lwjgl3:installDist     # desktop client → lwjgl3/build/install/ruinborn/
./gradlew :android:assembleRelease -Pruinborn.androidServerUrl=https://ruinborn.example.com
                                  # → android/build/outputs/apk/release/android-release.apk
```

## Android app

- Server URL: `ruinborn.androidServerUrl` (otherwise `ruinborn.serverUrl`) is written into `BuildConfig.SERVER_URL` at
  build time. Release builds use HTTPS only. Cleartext HTTP is allowed in the debug build only (emulator
  `http://10.0.2.2:8080`).
- Create the signing key once (concept section 16):
  `keytool -genkeypair -v -keystore ~/.android/ruinborn.jks -alias ruinborn -keyalg RSA -keysize 4096 -validity 10000`
  and add `ruinborn.storeFile`, `ruinborn.storePassword`, `ruinborn.keyAlias` and `ruinborn.keyPassword` to
  `~/.gradle/gradle.properties`. Without these entries the build produces `android-release-unsigned.apk`, which cannot
  be installed.
- New version: raise `ruinborn.versionCode`/`ruinborn.versionName` in `gradle.properties`, build the APK, copy it to
  `apkPath` and adjust `latestClientVersion` (hint) or `minClientVersion` (forced update) in `config.json`.
  The server serves the file at `GET /download/ruinborn.apk`.
- Without an Android SDK, or with `-Pruinborn.skipAndroid`, the module is skipped. Server and desktop still build.

## Configuration (`config.json`)

| Key | Meaning | Default |
| --- | --- | --- |
| `bindHost`, `port` | address the server listens on | `127.0.0.1`, `8080` |
| `publicUrl` | public URL (used for `apkUrl`) | `http://localhost:8080` |
| `dbPath` | SQLite file | required |
| `balancePath` | balance file. If it is missing, the default file is written there | required |
| `backupDir`, `backupKeep` | backup directory, number of backups kept | required, `14` |
| `apkPath` | current app | required |
| `inviteCode` | invite code for registration | required |
| `admins` | user names with the admin role | `[]` |
| `maxPlayers` | maximum number of accounts | `50` |
| `gameSpeed` | divides all durations, multiplies rates (protection, reset and jobs excluded) | `1.0` |
| `timezone`, `dailyResetTime` | day boundary | `Europe/Berlin`, `04:00` |
| `backupTime`, `cleanupTime` | daily jobs | `03:30`, `03:45` |
| `minClientVersion`, `latestClientVersion` | version check (426 or hint) | `1`, `1` |
| `logLevel` | `DEBUG`, `INFO`, `WARN`, `ERROR` | `INFO` |
| `devMode` | enables `/give`, `/res`, `/finish` | `false` |
| `passwordIterations` | iterations of password hashing. **Do not change after the first start** | `100000` |

The configuration is fully validated at startup. If it contains errors, the server does not start. The same applies to
`balance.json` (all keys present, values valid). Changes require a restart.

## Setup in an Incus container

As described in the concept: create the container, install Corretto 25, create the user `ruinborn` and the directories
`/opt/ruinborn`, `/etc/ruinborn`, `/var/lib/ruinborn/{backups,apk}`.

systemd unit `/etc/systemd/system/ruinborn.service`. Compared to the concept it adds `--enable-native-access` because
sqlite-jdbc loads a native library (Java 25 prints a warning otherwise):

```ini
[Unit]
Description=Ruinborn Game Server
After=network-online.target
Wants=network-online.target

[Service]
User=ruinborn
ExecStart=/usr/bin/java --enable-native-access=ALL-UNNAMED -Xmx512m -jar /opt/ruinborn/ruinborn-server.jar --config /etc/ruinborn/config.json
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload && systemctl enable --now ruinborn
journalctl -u ruinborn -f
```

Caddy (`reverse_proxy` forwards WebSockets automatically, `encode gzip` compresses the map request):

```
ruinborn.example.com {
    encode gzip
    reverse_proxy <host-ip>:8080
}
```

On shutdown (SIGTERM) the server stops accepting requests, the engine finishes the current message and then all
connections are closed.

## Admin commands (in chat, starting with `/`)

| Command | Effect |
| --- | --- |
| `/ban <name> <duration> [reason]` | blocks login, ends all sessions, the base gets a shield for the duration |
| `/unban <name>` | lifts the ban |
| `/mute <name> <duration>`, `/unmute <name>` | chat ban |
| `/del <message-id>` | deletes a chat message (also by tapping the message) |
| `/rename <old> <new>` | renames a player |
| `/password <name> <new-password>` | sets a new password and ends all sessions. The audit log stores no password |
| `/shield <name> <duration>` | gives a base a shield |
| `/announce <text>` | announcement as report, banner and system message |
| `/reports`, `/resolve <id>` | show or resolve open chat reports |
| `/give <name> <item-id> <count>`, `/res <name> <f> <w> <s>`, `/finish <name>` | only with `devMode=true` |

Durations: `30m`, `12h`, `7d` or `perm`. Every admin action is written to the audit log (`audit_log`).

## Backups

- Daily at `backupTime` via `VACUUM INTO` to `backupDir/ruinborn-YYYY-MM-DD.db` (on a separate connection outside
  the engine). The newest `backupKeep` backups are kept.
- Restore: `systemctl stop ruinborn`, copy the backup to `ruinborn.db`, delete `ruinborn.db-wal` and
  `ruinborn.db-shm`, `systemctl start ruinborn`. The server processes overdue events at startup.

## Updates

- Server: copy the new JAR to `/opt/ruinborn/` and run `systemctl restart ruinborn`. Migrations run automatically.
- New world, with the service stopped:
  `java --enable-native-access=ALL-UNNAMED -jar ruinborn-server.jar --config /etc/ruinborn/config.json --new-world`.
  A backup is created first, then the input `NEUE WELT` is required. All game progress and sessions are deleted,
  accounts are kept. Every player gets the starting state at the next login.
- Monitoring: `GET /api/health` returns `ok`.

## Local development

- `dev/config.json`: `bindHost` `0.0.0.0`, paths under `dev/data/`, `balancePath` `dev/balance.json`,
  invite code `dev`, admin `admin`, `gameSpeed` 20, `logLevel` `DEBUG`, `devMode` on.
- The desktop client uses `http://localhost:8080` (`ruinborn.serverUrl` in `gradle.properties`, can be overridden with
  `-Pruinborn.serverUrl=…` or at runtime with `--server`). The Android emulator uses
  `-Pruinborn.androidServerUrl=http://10.0.2.2:8080`.
- Desktop launch options: `--server URL`, `--profile NAME` (own token, for several clients), `--login NAME:PASSWORD`,
  `--register INVITE_CODE` (with `--login`), `--screen map`, `--dialog NAME` (e.g. `research`, `alliance`,
  `tile:24:79`, `march:28:89:GATHER`, `lastreport`), `--screenshot FILE.png` with `--shot-delay SECONDS`, `--size 540x960`.
  Via Gradle: `./gradlew :lwjgl3:run -Pruinborn.args="--profile a"`.
- Several clients at the same time (one profile per window):

  ```bash
  ./gradlew :lwjgl3:installDist
  cd assets && ../lwjgl3/build/install/ruinborn/bin/ruinborn --profile anna
  cd assets && ../lwjgl3/build/install/ruinborn/bin/ruinborn --profile bert --login bert:secret123 --register dev
  ```

- Rate limits: `X-Forwarded-For` is only accepted from connections from loopback or private networks (Caddy).
  If another proxy sits in front, it must also be in such a network.
