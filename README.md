# ChurchPresenter Signage

Keeps a lobby TV showing announcements after hours, with ChurchPresenter's PC switched off.

A Raspberry Pi (or a mini PC) sits behind the TV. Whenever ChurchPresenter runs, it sends the screen
its playlists — pictures, videos and presentation slides from ChurchPresenter's signage folder — and
a weekly schedule. The screen keeps playing them on its own, turns the TV on and off on time over
HDMI-CEC, and picks up changes the next time ChurchPresenter starts. During a service, switch the
TV's input to ChurchPresenter or your video mixer as usual.

> **Status:** in development. In ChurchPresenter it is the **Signage** tab: show it from the tab bar's
> menu.

## Install

Each command downloads the latest release, checks it, and sets the screen up to start by itself. At
the end the TV shows a pairing code: approve it in ChurchPresenter's **Signage** tab. Run the same
command again to repair a screen; pairing and content are kept. After that, **Update** on the
screen's card in ChurchPresenter keeps it current (see [Updating](#updating)).

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

From ChurchPresenter, **Factory reset** in the screen's settings in the Signage tab does the same.

### TV on and off

On a Raspberry Pi the screen turns the TV on and off over HDMI-CEC. Turn CEC on in the TV's settings
— it has a brand name there: Anynet+ (Samsung), SimpLink (LG), Bravia Sync (Sony), CEC (most
others). Most mini PCs have no CEC; use the TV's own on/off timer instead.

A schedule time set to **TV off** puts the TV in standby; the next time that plays something turns it
back on. Where the TV answers, the screen's settings also have **TV on** and **TV off**, which act at
once without changing what plays.

## Kiosk mode: touch pages

A screen with a touchscreen can double as a kiosk: an event sign-up, check-in or visitor card that
people fill in themselves, and the after-hours playlist the rest of the time.

**Set it up**
1. In the Signage tab, add the form's address with **Add → Link…**, and turn on **People use it on
   the touchscreen**.
2. Choose how long it waits without a touch before it starts over for the next person (90 seconds
   unless changed), and whether it may open any site (see below).
3. Make that link play: pick it under **Plays**, put it in a schedule time (Sunday 9:00 to 12:00,
   say), or hold the screen's corner (below).

**What people see.** The page fills the screen, with no address bar, tabs or menus. An on-screen
keyboard comes up for text fields (numbers for phone fields, `@` and `.com` for email ones), and a
keyboard button in the bottom-right corner brings it up for fields that do not. Ten seconds before
it starts over, a strip says so; a tap keeps going.

**Nothing anyone types is kept.** The browser starts on an empty, private profile held in memory,
which is deleted after the page has gone untouched for its time, when the page goes off screen, and
on a restart. Chromium is set never to offer to save a password, an address or a card, never to
sign in or keep history, and never to write crash dumps. The screen's preview in the Signage tab
does show the page live, typing included, so you can see it works.

**Where the page may go.** By default, anywhere: leave **It may open any site** on when you are not
sure what the form needs. Turned off, the page can go only to its own site and the ones you list
(each with its subdomains); anything else shows a short notice and goes back. What the page shows
inside itself, such as a payment field, always loads. Payment that opens in a separate pop-up
window, such as PayPal's buttons, is not supported yet.

**Switching on the screen itself.** Holding the top-left corner of the screen for 5 seconds
switches between the playlist and the touch page, until the schedule next changes. In the screen's
settings you can turn that off, choose which touch page it opens, and set a PIN it asks for first
(4 to 8 digits; forgotten, set a new one there; five wrong ones lock it for a minute).

**What it needs**
- A touchscreen the system sees as one (most USB and HDMI touch monitors, and the Raspberry Pi
  Touch Display). Without one the page still shows, but nobody can use it.
- Chromium from the system's own packages: a screen on Ubuntu, whose Chromium is a snap, has no
  touch pages.
- The install from this release or later. A screen installed earlier gets it by running its install
  command once more.

**If it does not come up**
- The link shows the screen's idle card or the playlist instead: the screen's player is older than
  touch pages (update it from the Signage tab) or its Chromium is missing.
- The corner does nothing: the gesture is turned off in the screen's settings, no playlist has a
  touch page, or `libinput-tools` is missing (run the install command again).
- The player's log says what happened. On the screen:
  `journalctl _PID=$(systemctl show -p MainPID --value churchpresenter-signage)`.

## Updating

**Update** on the screen's card in ChurchPresenter updates everything on it: first the system
(Chromium, mpv and every other package, and the screen's settings for the player), then the player
when a newer one is out. The system update runs as root from the release's own `install.sh`, which
the screen downloads itself and checks against the signed release before it runs it; the player
can only ask for it to start.

A screen installed before this needs its install command run once more to get touch pages, restart
and shut down from ChurchPresenter, and this kind of update. Everything after that comes through
**Update**.

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
