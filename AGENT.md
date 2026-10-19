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
| `:agent` (next) | The player: sync, local store, kiosk server, CEC, updates |

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
./gradlew :core:test :core:detekt   # the core's suite and gate — run detekt last
./gradlew detekt --auto-correct     # fixes formatting findings
```
