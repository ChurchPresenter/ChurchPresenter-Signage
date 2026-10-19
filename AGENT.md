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
| `:agent` | The player: local store and safe swap (`PlayerStore`, `ContentFiles`), sync (`ManifestSync`), the once-a-second decision loop (`PlayerEngine`), the kiosk page and its local server, CEC (`TvPowerPolicy`, `CecCtl`), the connection to ChurchPresenter (`ServerConnection`, `PlayerSession`) |

The agent's logic takes its clock, CEC and content source as parameters; tests use fakes
(`Fixtures.kt`) and never a real socket except `KioskServerTest`'s in-process Ktor host.

**CEC rule:** send only when the wanted power changes; never power-on on the first decision after
start; never anything in a Service block. A TV on another HDMI input must never be pulled away.

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
# run locally: …/bin/churchpresenter-signage --data /tmp/sig --no-cec --kiosk-port 18090, open http://127.0.0.1:18090
./gradlew detekt --auto-correct     # fixes formatting findings
```
