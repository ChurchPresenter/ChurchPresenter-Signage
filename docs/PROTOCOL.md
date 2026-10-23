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
| player | `status` | what it shows, last sync, free disk, CEC state, errors; on change and every minute |
| player | `preview` | a JPEG of what the screen shows (base64), its size and when it was taken; answers `preview_request` |
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
| server | `preview_request` | send a `preview` no wider than `maxWidth`; sent only while someone looks |
| server | `update_available` | an agent build: version, protocol range, hash, path, and the signed release index (`release`, `releaseSignature`) |

A player lists what it can do in its hello's `features` (`wipe`, `preview`, `update`); ChurchPresenter
sends `wipe`, `preview_request` and `update_available` only to players that list them. Both sides ignore message
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
- A playlist item's `durationMs` is null for a video that plays to its end.
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
