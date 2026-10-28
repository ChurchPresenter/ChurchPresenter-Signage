# Signage protocol — version 1

Between ChurchPresenter (server) and a signage player. JSON everywhere, encoded with
`SignageJson` (`:core`): sealed types carry a `"type"` key, unknown keys are ignored, defaults are
written. File hashes are 64 lower-case hex digits of SHA-256.

## Discovery
ChurchPresenter advertises `_churchpresenter._tcp` over mDNS while dev mode and the companion server
are on. A player installed with an address uses that instead.

## Endpoints (ChurchPresenter's companion server)
All answer `403 "dev mode only"` when dev mode is off. All but the WebSocket's pairing need
`Authorization: Bearer <token>`.

| Route | What |
|---|---|
| `GET /api/signage/ws` (WebSocket) | The control channel, below |
| `GET /api/signage/manifest` | This player's `Manifest`; its version in the `ETag` header |
| `GET /api/signage/files/{sha256}` | A file by hash; `404` for one not in this player's manifest |
| `GET /api/signage/agent/{platform}` | The agent build ChurchPresenter offers for `linux_arm64`, `linux_x64` or `windows_x64`, as downloaded from the release |

## The control channel
The player's first message is `hello`. Then:

| From | `type` | Meaning |
|---|---|---|
| player | `hello` | id, agent version, protocol range, manifest version, screen size, platform, pairing code when unpaired, `features` |
| player | `status` | what it shows, last sync, free disk, CEC state, errors, and its health (processor and memory in use, temperature, uptime, each null where unknown); on change and every minute |
| player | `preview` | a JPEG of what the screen shows (base64), its size and when it was taken; answers `preview_request` |
| player | `web_test_result` | for each frame rate tried: the rate asked for, the average that reached the screen, how busy the processor was and its highest temperature (each null where unknown), whether the GPU drew the page; or why the test could not run |
| player | `update_progress` | version, `downloading` (with a percentage), `checking`, `installing`, `restarting`, `failed` or `rolled_back`, and why |
| server | `unsupported` | the player's protocol range excludes ChurchPresenter's; connection closes |
| server | `pairing_pending` | waiting for approval in the Signage tab |
| server | `paired` | token, owner id and name; the player reconnects with the token |
| server | `pairing_rejected` | |
| server | `released` | the owner released or revoked it; it forgets the owner and waits for pairing |
| server | `manifest_changed` | a newer manifest is waiting |
| server | `override` | a manual switch, or `null` to release it |
| server | `identify` | show the screen's name for a few seconds |
| server | `refresh` | restart what draws the screen (mpv) |
| server | `wipe` | delete everything stored (content, manifests, a manual switch) and fetch the manifest again; stays paired |
| server | `tv` | `on`: turn the TV on (`true`) or to standby now over HDMI-CEC, without changing what plays; the schedule's next change sets it again |
| server | `power` | `action`: `restart` or `shut_down` the player's machine. A shut-down screen stays off until its power is turned off and on |
| server | `preview_request` | send a `preview` no wider than `maxWidth`; sent only while someone looks |
| server | `web_test` | show the built-in moving test page at each of `fps` (15 and 30) for `secondsEach` (20) seconds, then go back and answer `web_test_result` |
| server | `update_available` | an agent build: version, protocol range, hash, path, and the signed release index (`release`, `releaseSignature`) |

A player lists what it can do in its hello's `features` (`wipe`, `preview`, `update`, `stream`, `web`, `interactive`, `power`, `tv`, `system_update`,
`web_test`, `rotation`); ChurchPresenter sends `wipe`, `power`, `preview_request`, `update_available` and `web_test` only to
players that list them,
and a `stream` or `web` item only to a player that lists that kind. Both sides ignore message
types they do not know.

## Updates
Every release publishes `signage-release.json` (version, protocol range, and per platform the
bundle's name, size and SHA-256; fixture `fixtures/signage-release-v1.json`) and
`signage-release.json.sig`, its Ed25519 signature in base64, made by the release workflow with the
repo secret `SIGNAGE_SIGNING_KEY`. Both ChurchPresenter and the player carry the public key
(`ReleaseSigning.PUBLIC_KEY`) and trust a build only through a signed index:
- ChurchPresenter reads the latest index, checks its signature, and offers an update to a player
  that is older and whose platform the release has, when the release speaks ChurchPresenter's
  protocol. It downloads the bundle, checks it against the index, serves it at
  `/api/signage/agent/{platform}`, and sends `update_available` with the index text exactly as
  published and its signature.
- The player checks the signature with its own key, that the index names the offered version and
  its platform and speaks its protocol, and that the download hashes to the index's entry. Then it
  unpacks it beside the running version, switches, and restarts; a version that does not become
  healthy within three starts is rolled back, and the player says so with `update_progress`.

## Ownership
A player stores the owner id it was paired with and refuses a manifest with any other `ownerId`.
It accepts a new owner only after `released` or a factory reset. `released` (ChurchPresenter's
Factory reset) deletes its content and forgets the owner; a factory reset on the device itself
(`churchpresenter-signage-reset`) also gives it a new player id.

## Manifest
See `fixtures/manifest-v1.json` in `core/src/test/resources` for a complete example.
- `schedule[].start`/`end` are `"HH:mm"` wall-clock times in `timeZone`; an end at or before the
  start runs past midnight; `days` are the days a block starts on.
- `schedule[].action`: `play` (a playlist, TV on), `tv_off`, `service` (keep playing the default
  playlist, send nothing over CEC).
- A playlist item's `kind` is `image`, `video`, `stream` or `web`. A file (`image`, `video`) is named
  by `sha256`; a link (`stream`, `web`) has `url` instead and an empty `sha256`, and the player
  opens it when it plays, with no download.
  - `stream`: a video or live stream mpv opens itself (a file over http, HLS, RTSP, or a page
    yt-dlp reads, such as YouTube).
  - `web`: a web page drawn off screen by the player's Chromium, at most `maxFps` frames a second
    (15 when it is left out; ChurchPresenter sends 30 for a link set to move smoothly);
    `refreshMs`, when set, reloads it that often.
  - A player older than these kinds cannot decode a manifest that has one, so it never gets one
    (`features` above); a playlist made only of links it cannot play shows its idle card.
  - A link that does not load within 15 s, or a stream that stops answering, is set aside for a
    minute: the screen falls back as for any playlist that cannot play, and never shows an error page.
  - `url`, `refreshMs` and `maxFps` are left out of an item that has none, so a manifest without links
    encodes as it did before them.
  - An **interactive** page (`"interactive": true` on a `web` item) is one people use on a
    touchscreen, such as an event sign-up. A player listing `interactive` shows it in a visible,
    touchable browser instead of mpv. It stays up for as long as its playlist plays, and a rotation
    stops on it. Nothing typed into it is kept: the browser starts from an empty profile, which is
    deleted after `idleResetMs` without a touch (90 s when left out; ChurchPresenter allows 15 s to
    30 min), when the page goes off screen, and on a restart. Only the page's own site and
    `allowedHosts` (each with its subdomains; `"*"` for any site) open, checked where the page itself goes:
    what it embeds always loads. While one is on screen its `status` says `"interactive": true`, and
    its preview is the page as shown, typing included. ChurchPresenter sends an interactive item only to
    players listing `interactive`. `interactive`, `idleResetMs` and `allowedHosts` are left out
    when unset.
- `screen.kioskPinHash`, when set, is what the screen's switch gesture (a finger held in the
  top-left corner for 5 s) asks for before it switches between the playlist and an interactive page:
  `pbkdf2:<iterations>:<salt hex>:<hash hex>` of a 4- to 8-digit PIN (`KioskPin` in `:core`). It is
  set, changed and removed in ChurchPresenter only. The gesture's switch is stored on the player as an
  override until the schedule next changes, as a manual switch is. `screen.kioskGesture: false` turns
  the gesture off; `screen.kioskGesturePlaylistId` is the playlist it switches to (the first with a
  touch page when left out, or when that one has none). Both are left out at their defaults.
- A playlist item's `durationMs` is null for a video that plays to its end, and for a link that
  stays up for as long as its playlist plays.
- A **rotation** is a playlist with a link and other items: a player listing `rotation` shows it one
  item at a time, each for its `durationMs` (a video without one to its end), and skips a link that
  cannot load until it is tried again. ChurchPresenter sends a link that is not alone in its playlist
  only to players listing `rotation`; to others it sends the rotation's files alone.
- `diagnostics` says whether the screen may send crash reports: the owning ChurchPresenter's
  analytics setting. A player with no manifest reports.

## What a screen shows
`decide()` in `:core`, in order: an active override; the block running now; the default playlist.
A playlist that cannot play falls back to the default playlist, then the idle card. Overrides end
`next_change` (the next start or end of any block after they were set), at a `time`, or when
`released`.

## TV power
`decide()` returns ON, OFF or LEAVE. The player sends CEC only when that changes, never repeatedly
and never for LEAVE, so a TV switched to the service input is left alone.
