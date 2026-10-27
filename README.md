# ChurchPresenter Signage

Keeps a lobby TV showing announcements after hours, with ChurchPresenter's PC switched off.

A Raspberry Pi (or a mini PC) sits behind the TV. Whenever ChurchPresenter runs, it sends the screen
its playlists — pictures, videos and presentation slides from ChurchPresenter's signage folder — and
a weekly schedule. The screen keeps playing them on its own, turns the TV on and off on time over
HDMI-CEC, and picks up changes the next time ChurchPresenter starts. During a service, switch the
TV's input to ChurchPresenter or your video mixer as usual.

> **Status:** in development. In ChurchPresenter it is behind dev mode.

## Install

Each command downloads the latest release, checks it, and sets the screen up to start by itself. At
the end the TV shows a pairing code: approve it in ChurchPresenter's **Signage** tab. Run the same
command again later to update; pairing and content are kept.

### Raspberry Pi

Flash **Raspberry Pi OS Lite (64-bit)** with Raspberry Pi Imager (set up Wi-Fi and SSH there if you
want them), start the Pi, sign in, and run:

```bash
curl -fsSL https://github.com/ChurchPresenter/ChurchPresenter-Signage/releases/latest/download/install.sh | sudo bash
```

A Raspberry Pi 5 (4 GB) is the reference; a Pi 4 is fine for 1080p pictures. An SSD lasts longer
than an SD card in a screen that runs all day. Chromium, which draws web-page links, is the
largest part of the install; leave a few GB free.

### Mini PC — Linux

On a fresh **Debian** or **Ubuntu Server**, minimal, with no desktop installed. Ubuntu has Chromium
only as a snap, which the install does not use, so a screen on Ubuntu plays pictures, videos and
streams but not web pages:

```bash
curl -fsSL https://github.com/ChurchPresenter/ChurchPresenter-Signage/releases/latest/download/install.sh | sudo bash
```

**No `curl`?** Use `wget` instead:

```bash
wget -qO- https://github.com/ChurchPresenter/ChurchPresenter-Signage/releases/latest/download/install.sh | sudo bash
```

**No `sudo`?** (Debian when a root password was set during install.) Sign in as root first with
`su -`, then run either command without `sudo`.

### When ChurchPresenter is on another network

The screen finds ChurchPresenter by itself on the same network. If the TVs and the ChurchPresenter
PC are on separate networks, the installer asks for ChurchPresenter's address (for example
`192.168.1.20:8765`); press Enter to skip. To give it without being asked:

```bash
curl -fsSL https://github.com/ChurchPresenter/ChurchPresenter-Signage/releases/latest/download/install.sh | sudo bash -s -- --address 192.168.1.20:8765
```

### Factory reset

To move a screen to another church, or when the ChurchPresenter that owns it is gone, run this on
the screen itself. It deletes its pictures, videos and pairing, and the TV shows a new pairing code:

```bash
sudo churchpresenter-signage-reset
```

From ChurchPresenter, **Factory reset** on the screen's card in the Signage tab does the same.

### TV on and off

On a Raspberry Pi the screen turns the TV on and off over HDMI-CEC. Turn CEC on in the TV's settings
— it has a brand name there: Anynet+ (Samsung), SimpLink (LG), Bravia Sync (Sony), CEC (most
others). Most mini PCs have no CEC; use the TV's own on/off timer instead.

## Building

```bash
./gradlew :core:test :agent:test detekt   # tests and static analysis
./gradlew :agent:installDist              # agent/build/install/churchpresenter-signage
```

Releases are made as ChurchPresenter's are, by the **Build** workflow: every night by itself when
`main` has changed since the last release, or by hand from **Actions → Build → Run workflow**. The version is
worked out from the year and the commit count (`v26.0.42`), or typed in. The release, "ChurchPresenter
Signage v26.0.42", has a bundle per platform (the player and its own Java runtime), `install.sh`,
`SHA256SUMS` and the signed `signage-release.json` that ChurchPresenter updates screens from.

## Licence

GPL-3.0, as ChurchPresenter.
