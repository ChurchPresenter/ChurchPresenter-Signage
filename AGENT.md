# ChurchPresenter Signage — Agent Notes

The lobby signage player: an always-on Raspberry Pi or mini PC that keeps a TV showing announcements
after hours, with ChurchPresenter's PC shut down. ChurchPresenter feeds it playlists and a weekly
schedule whenever it runs; the player decides on its own what to show. No live video and no sound:
during a service people switch the TV's HDMI input by hand.

The plan behind it: `~/Desktop/lobby-signage/PLAN.md` (local, not in the repo).

## Modules

| Module | What it is |
|---|---|
| `:core` | The wire format and the rules: manifest, schedule, overrides, messages, and `decide()`. Pure Kotlin/JVM, no I/O beyond hashing |
| `:agent` | The player: local store and safe swap (`PlayerStore`, `ContentFiles`), sync (`ManifestSync`), the once-a-second decision loop (`PlayerEngine`), mpv on the screen (`MpvPlayer`, `MpvScene`, `OsdText`, `MpvIpc`), links (`LinkHealth`; web pages by `WebRenderer` in a headless `Chromium`, handed to mpv as MJPEG by `MjpegServer`, each page's picture asked for outright after it loads since the screencast sends none for a page that never changes; Chromium restarted by `MemoryWatch` over a limit sized to the machine's RAM, measured as PSS; the GPU on ARM until it fails once, `web-gpu-off`), rotations (`Rotation`: a playlist with a link shown one item at a time by the engine), the machine's health for the status (`SystemHealth`), previews (`PreviewMaker`), updates (`Updater`, `AppFolder`), CEC (`TvPowerPolicy`, `CecCtl`), the connection to ChurchPresenter (`ServerConnection`, `PlayerSession`) |

The agent's logic takes its clock, CEC and content source as parameters; tests use fakes
(`Fixtures.kt`) and never a real mpv; `MpvIpcTest` talks to a Unix socket of its own, `MpvPlayerTest` to a
fake session.

**mpv rule:** the oldest mpv the player meets is 0.35 (Debian 12, Raspberry Pi OS). Send `loadfile` and
other commands with options in mpv's named-argument form, which reads the same on every version, and
put any option whose name changed between versions in `MpvProcessLauncher.versionArgs`, which picks
by what `mpv --list-options` lists.

**CEC rule:** send only when the wanted power changes; never power-on on the first decision after
start; never anything in a Service block. A TV on another HDMI input must never be pulled away.

## Crash reports
`Diagnostics` sends to Sentry, project **church-projector / church-presenter-signage** (DSN in
`agent/src/main/resources/sentry.properties`, committed as ChurchPresenter's is). Sent: uncaught
exceptions, every `Log.error`, "An update was rolled back", and "mpv keeps exiting" (`ExitWatch`:
three exits or failed starts in ten minutes, then quiet for an hour, with mpv's last output lines;
never for an mpv that is not installed, which never tells its version).
`Log.warn` is a breadcrumb. Release builds are `production`; anything else is `development` —
filter on it.
- **Consent is the owner's**: `Manifest.diagnostics` carries ChurchPresenter's analytics setting and
  is asked before each event; a player with no manifest reports.
- **No `Log.error` in a retry loop** — every one is an event. Log a warning and count it, as a
  failed mpv start does.
- **The operator's environment is not a defect** (ChurchPresenter's rule): an unreachable
  ChurchPresenter, a full disk or a missing tool is a warning, not a report.
- Keep a report's title constant and put what varies in tags. No host name is sent, the player id
  only as a hash, and IPv4 addresses are masked in messages and breadcrumbs.
- Source context is uploaded only when the build has `SENTRY_AUTH_TOKEN` (Sentry Gradle plugin).

## Install and release
- `install/install.sh` (Pi and Linux mini PCs: mpv on DRM/KMS with no desktop, one systemd unit in
  a console session on tty1). The player lives in the data folder as `app/<version>/` with a
  `current` link, started by `install/launch.sh` (shipped inside each bundle), which rolls back an
  update that does not become healthy; `AppFolder` and `launch.sh` share the file names. The README's copy-paste commands fetch it from
  `releases/latest/download/`, so renaming it breaks every README link and every update. Windows
  and macOS get the desktop app (not yet built).
- **Releases follow ChurchPresenter's.** `.github/workflows/build.yml` ("Build") is run by hand. Its
  version is ChurchPresenter's scheme, the two-digit year then the commit count split across two
  segments (`v26.0.42`), and the build takes it as `-PsignageVersion`; a local build works it out
  the same way (root `build.gradle.kts`), and `AGENT_VERSION` is generated from it. One bundle per
  platform (installDist + a jlinked runtime), each smoke-started, named as ChurchPresenter's
  installers: `ChurchPresenter-Signage-26.0.42-LINUX-arm64.tar.gz`, `…-LINUX-x64.tar.gz`,
  `…-WINDOWS-x64.zip`; the release is "ChurchPresenter Signage v26.0.42". `install.sh` finds its
  bundle by that name in the release's `SHA256SUMS`.
- `.github/workflows/nightly.yml` runs that same Build (`workflow_call`) at 03:00 UTC when `main` has
  commits the newest release does not, so every merged change ships within a day as a numbered
  release. A night with nothing new builds nothing.
- `install.sh` also installs `/usr/local/sbin/churchpresenter-signage-reset`, the factory reset on
  the device (`--factory-reset`, `PlayerStore.factoryReset`).
- **Updates are signed.** The publish job writes `signage-release.json` and signs it with the repo
  secret `SIGNAGE_SIGNING_KEY` (an Ed25519 private key, PKCS#8 PEM); a release fails without it. The
  public key is `ReleaseSigning.PUBLIC_KEY` in `:core`, and ChurchPresenter's copy of core must
  carry the same one. Changing the key means every installed player must be reinstalled.
- Not yet: the read-only system disk.

## The wire format is a contract
ChurchPresenter keeps its **own copy** of the wire types (as ChurchPresenter-Mobile does for the
companion API); nothing is published as a library. So:
- `docs/PROTOCOL.md` is the contract. Change it in the same commit as the types.
- `core/src/test/resources/fixtures/*.json` are copied verbatim into ChurchPresenter's tests; both
  sides must decode each and encode it back to the same JSON.
- A change an older peer would misread bumps `PROTOCOL_VERSION`. An optional field with a default
  does not: both sides ignore unknown keys.
- `@SerialName` values never change once released.

## Standards
Follow ChurchPresenter's `CODING_STANDARDS.md`: explicit imports, no wildcards, no fully qualified
names in signatures, KDoc on public API.
- Diagnostics through a logger, never `println`.
- **NEVER** add a `Co-Authored-By` trailer or any agent/tool byline to commits or PRs.
- No detekt baselines; fix findings in code. `config/detekt/detekt.yml` is ChurchPresenter's,
  minus its live-safe rules.

## Tests
- `kotlin.test` on JUnit 5. Time is always passed in (`Instant`), never read from the clock in logic.
- **No `Thread.sleep` or `delay` as the wait**, no test over ~1 s.
- Build paths from real temp directories; keep every file a test writes inside a folder it owns.

## Commands
```bash
./gradlew :core:test :agent:test detekt   # both suites and the gate — run detekt last
./gradlew :agent:installDist             # agent/build/install/churchpresenter-signage/bin/…
# run locally, in a window: …/bin/churchpresenter-signage --data /tmp/sig --no-cec --mpv-args "--no-fullscreen"
./gradlew detekt --auto-correct     # fixes formatting findings
```
