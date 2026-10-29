#!/usr/bin/env bash
# Installs the ChurchPresenter signage player on a Raspberry Pi (Raspberry Pi OS Lite, 64-bit) or a
# Linux mini PC (Debian or Ubuntu Server, minimal, no desktop). Run it as root on a fresh system:
#
#   curl -fsSL https://github.com/ChurchPresenter/ChurchPresenter-Signage/releases/latest/download/install.sh | sudo bash
#
# It installs mpv (which draws straight to the screen, with no desktop), the video drivers, the CEC
# tools, the agent with its own Java runtime, and one service that runs the agent and mpv on the
# first screen. Then it reboots to the pairing screen. Run it again to update; the player's pairing
# and content are kept.
#
# Options (after `bash -s --` when piped):  --address host:port   use this ChurchPresenter, skip discovery
#                                           --no-reboot           do not reboot at the end
#                                           --system-only         update the system, not the player
#
# --system-only is what Update in ChurchPresenter's Signage tab runs, through the root-owned
# churchpresenter-signage-setup service: it updates every package (Chromium, mpv and the rest) and
# this machine's settings for the player, and leaves the player, its pairing and its content alone.
set -euo pipefail

REPO="ChurchPresenter/ChurchPresenter-Signage"
BASE_URL="${SIGNAGE_BASE_URL:-https://github.com/$REPO/releases/latest/download}"
PREFIX=/opt/churchpresenter-signage
DATA=/var/lib/churchpresenter-signage
USER_NAME=signage
AGENT_UNIT=churchpresenter-signage
# Versions before mpv drew with a browser in this service.
OLD_KIOSK_UNIT=churchpresenter-signage-kiosk

address=""
address_given=0
reboot=1
system_only=0
while [ $# -gt 0 ]; do
  case "$1" in
    --address) address="${2:-}"; address_given=1; shift 2 ;;
    --no-reboot) reboot=0; shift ;;
    --system-only) system_only=1; reboot=0; address_given=1; shift ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

say() { printf '\n==> %s\n' "$*"; }
die() { printf '\nError: %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run this as root: put sudo before bash, or where there is no sudo, sign in as root (su -) first"
command -v apt-get >/dev/null || die "this installer needs a Debian-based system (Raspberry Pi OS, Debian, Ubuntu)"
command -v systemctl >/dev/null || die "this installer needs systemd"

case "$(uname -m)" in
  aarch64|arm64) platform=linux-arm64 ;;
  x86_64|amd64) platform=linux-x64 ;;
  *) die "unsupported processor $(uname -m); a 64-bit Raspberry Pi OS or x86-64 Linux is needed" ;;
esac

if [ "$address_given" -eq 0 ] && [ -r /dev/tty ]; then
  echo
  echo "ChurchPresenter is found automatically on most networks."
  echo "If the TVs are on a different network from the ChurchPresenter PC, enter its address"
  echo "(for example 192.168.1.20:8765). Otherwise just press Enter."
  read -r -p "ChurchPresenter address: " address < /dev/tty || address=""
fi

# Not grep -q: it stops reading at the match, apt-cache dies of SIGPIPE, and pipefail fails the check.
has_package() { apt-cache policy "$1" 2>/dev/null | grep 'Candidate: [^(]' >/dev/null; }

say "Installing mpv, Chromium, the video drivers and the CEC tools"
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
packages="mpv v4l-utils curl ca-certificates fonts-dejavu-core"
# Hardware video decoding on Intel and AMD graphics (VA-API); the Pi's decoder needs nothing extra.
if [ "$platform" = linux-x64 ]; then
  for driver in mesa-va-drivers intel-media-va-driver; do
    has_package "$driver" && packages="$packages $driver"
  done
fi
# Links: yt-dlp lets mpv open YouTube and similar pages as streams; Chromium draws web pages. Only
# Chromium's own package: where apt offers it only as a snap (Ubuntu), no web pages, unless a
# Chromium or Chrome is already installed.
# cage shows an interactive page (an event sign-up on a touchscreen) in a visible Chromium,
# libinput-tools reads the corner gesture, wlr-randr turns that page for a rotated screen.
# polkitd lets the player restart and shut down the machine when ChurchPresenter asks (rule below).
for extra in yt-dlp chromium cage libinput-tools wlr-randr polkitd; do
  has_package "$extra" && packages="$packages $extra"
done
# shellcheck disable=SC2086
apt-get install -y -q --no-install-recommends $packages
mpv_path="$(command -v mpv || true)"
[ -n "$mpv_path" ] || die "mpv did not install"

say "Setting Chromium never to keep what people type"
# An interactive page runs in a private window whose profile is deleted after each person; this
# policy also keeps Chromium from offering to save a password, an address or a card, from signing
# in, and from opening its own pages, downloads or printing. DevTools stays allowed: the agent drives
# Chromium through it.
policy='{
  "PasswordManagerEnabled": false,
  "AutofillAddressEnabled": false,
  "AutofillCreditCardEnabled": false,
  "BrowserSignin": 0,
  "SyncDisabled": true,
  "SavingBrowserHistoryDisabled": true,
  "SearchSuggestEnabled": false,
  "TranslateEnabled": false,
  "DownloadRestrictions": 3,
  "PrintingEnabled": false,
  "URLBlocklist": ["file://*", "chrome://*", "chrome-extension://*", "view-source:*"]
}'
for dir in /etc/chromium/policies/managed /etc/chromium-browser/policies/managed /etc/opt/chrome/policies/managed; do
  install -d -m 0755 "$dir"
  printf '%s\n' "$policy" > "$dir/churchpresenter-kiosk.json"
done

say "Letting the player restart and shut down this machine"
# Only these two, only for the player's user, never anything else as root: ChurchPresenter's Signage
# tab asks for them, and the player runs systemctl reboot or poweroff itself.
if [ -d /etc/polkit-1 ]; then
  install -d -m 0755 /etc/polkit-1/rules.d
  cat > /etc/polkit-1/rules.d/50-churchpresenter-signage.rules <<EOF
polkit.addRule(function (action, subject) {
  var power = ["org.freedesktop.login1.reboot", "org.freedesktop.login1.reboot-multiple-sessions",
               "org.freedesktop.login1.power-off", "org.freedesktop.login1.power-off-multiple-sessions"];
  if (subject.user == "$USER_NAME" && power.indexOf(action.id) >= 0) return polkit.Result.YES;
});
EOF
else
  echo "polkit is not installed: restart, shut down and update from ChurchPresenter will not work here"
fi

say "Letting ChurchPresenter's Update update this machine"
# Update in the Signage tab starts churchpresenter-signage-setup.service, which runs as root. The
# player may only start it (the polkit rule below); it can hand it nothing. The service fetches this
# script from the latest release itself, checks it against the release index signed with the key
# below, and runs it with --system-only.
install -d -m 0755 /etc/churchpresenter-signage
cat > /etc/churchpresenter-signage/release-key.pem <<'EOF'
-----BEGIN PUBLIC KEY-----
MCowBQYDK2VwAyEA/e/slJSb6siWZ4/nj/yRhgo80F8ZdTyTxBo9VDWf7aA=
-----END PUBLIC KEY-----
EOF
# Quoted: written as it stands. The release, the data folder and the key are fixed for this player.
cat > /usr/local/sbin/churchpresenter-signage-setup.new <<'EOF'
#!/bin/sh
# Updates this machine for the ChurchPresenter signage player: the latest release's install.sh, run
# with --system-only once it matches the signed release index. Its output is in
# /var/log/churchpresenter-signage-setup.log, which the player reads to say how it went.
set -eu
base="https://github.com/ChurchPresenter/ChurchPresenter-Signage/releases/latest/download"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
exec > /var/log/churchpresenter-signage-setup.log 2>&1
curl -fsSL -o "$work/index.json" "$base/signage-release.json"
curl -fsSL -o "$work/index.sig" "$base/signage-release.json.sig"
base64 -d "$work/index.sig" > "$work/index.bin"
openssl pkeyutl -verify -pubin -inkey /etc/churchpresenter-signage/release-key.pem -rawin \
  -in "$work/index.json" -sigfile "$work/index.bin" >/dev/null || { echo "The release index is not signed"; exit 1; }
hash="$(sed -n '/"setup"/,/}/s/.*"sha256": *"\([0-9a-f]\{64\}\)".*/\1/p' "$work/index.json")"
[ -n "$hash" ] || { echo "The release has no setup script"; exit 1; }
curl -fsSL -o "$work/install.sh" "$base/install.sh"
echo "$hash  $work/install.sh" | sha256sum -c --quiet - || { echo "install.sh does not match the release"; exit 1; }
# exec: this file is replaced while the update runs, and nothing after this line may be read from it.
exec bash "$work/install.sh" --system-only
EOF
# Into place by rename: an update rewrites this file while the old one is still running.
chmod 0755 /usr/local/sbin/churchpresenter-signage-setup.new
mv -f /usr/local/sbin/churchpresenter-signage-setup.new /usr/local/sbin/churchpresenter-signage-setup
cat > /etc/systemd/system/churchpresenter-signage-setup.service <<EOF
[Unit]
Description=Update this machine for the ChurchPresenter signage player
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/churchpresenter-signage-setup
TimeoutStartSec=45min
EOF
if [ -d /etc/polkit-1/rules.d ]; then
  cat > /etc/polkit-1/rules.d/51-churchpresenter-signage-setup.rules <<EOF
polkit.addRule(function (action, subject) {
  if (subject.user == "$USER_NAME" && action.id == "org.freedesktop.systemd1.manage-units" &&
      action.lookup("unit") == "churchpresenter-signage-setup.service" && action.lookup("verb") == "start") {
    return polkit.Result.YES;
  }
});
EOF
fi

say "Counting the updates waiting for this machine, once a day"
# ChurchPresenter shows Update only when something is waiting: this root timer refreshes the package
# lists and writes how many would be upgraded where the player can read it, and nothing else.
cat > /usr/local/sbin/churchpresenter-signage-check.new <<'EOF'
#!/bin/sh
# Counts this machine's packages with an update waiting, for ChurchPresenter's Update button: the
# signage player reads the number and sends it with its status.
set -u
dir=/var/lib/churchpresenter-signage-setup
mkdir -p "$dir"
apt-get update -q >/dev/null 2>&1 || true
count="$(apt-get -s -o Debug::NoLocking=1 upgrade 2>/dev/null | grep -c '^Inst ')" || true
printf '%s\n' "${count:-0}" > "$dir/pending.new" && mv -f "$dir/pending.new" "$dir/pending"
EOF
chmod 0755 /usr/local/sbin/churchpresenter-signage-check.new
mv -f /usr/local/sbin/churchpresenter-signage-check.new /usr/local/sbin/churchpresenter-signage-check
cat > /etc/systemd/system/churchpresenter-signage-check.service <<EOF
[Unit]
Description=Count the updates waiting for this machine, for ChurchPresenter's Signage tab
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
Nice=10
ExecStart=/usr/local/sbin/churchpresenter-signage-check
EOF
cat > /etc/systemd/system/churchpresenter-signage-check.timer <<EOF
[Unit]
Description=Count the updates waiting for this machine once a day

[Timer]
OnBootSec=5min
OnCalendar=daily
RandomizedDelaySec=1h
Persistent=true

[Install]
WantedBy=timers.target
EOF
systemctl daemon-reload
systemctl enable --now churchpresenter-signage-check.timer >/dev/null

if [ "$system_only" -eq 1 ]; then
  # The player's own packages (Chromium, mpv and the rest) were brought up to date above, and failed
  # the update if they could not be. Everything else is best effort: a machine's own pins or held
  # packages must not stop the player's update.
  say "Updating every other package"
  apt-get -y -q -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold upgrade ||
    echo "Warning: some other packages could not be updated; the player's own are up to date"
  # So Update goes away in ChurchPresenter as soon as the player reports again.
  /usr/local/sbin/churchpresenter-signage-check || true
  say "Updated"
  exit 0
fi

say "Downloading the signage player ($platform)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
# Bundles are named as ChurchPresenter's installers: ChurchPresenter-Signage-26.0.42-LINUX-arm64.tar.gz.
# The release's checksum list names this one.
curl -fsSL -o "$work/SHA256SUMS" "$BASE_URL/SHA256SUMS"
case "$platform" in linux-arm64) suffix=LINUX-arm64 ;; *) suffix=LINUX-x64 ;; esac
bundle="$(sed -n "s/^[0-9a-f]\{64\}  \(ChurchPresenter-Signage-.*-$suffix\.tar\.gz\)\$/\1/p" "$work/SHA256SUMS" | head -n 1)"
[ -n "$bundle" ] || die "the latest release has no build for this machine ($suffix)"
curl -fsSL -o "$work/$bundle" "$BASE_URL/$bundle"
(cd "$work" && grep " $bundle\$" SHA256SUMS | sha256sum -c --quiet -) || die "the download does not match its checksum"

say "Installing to $DATA/app"
id "$USER_NAME" >/dev/null 2>&1 || useradd --system --create-home --home-dir "/home/$USER_NAME" --shell /usr/sbin/nologin "$USER_NAME"
for group in video render input; do
  getent group "$group" >/dev/null && usermod -aG "$group" "$USER_NAME"
done
systemctl stop "$OLD_KIOSK_UNIT" "$AGENT_UNIT" 2>/dev/null || true
install -d -o "$USER_NAME" -g "$USER_NAME" -m 0750 "$DATA" "$DATA/app"
# The player lives in the data folder as app/<version>/ with a `current` link, so it can update
# itself without root; launch.sh (from the bundle) starts it and rolls a failed update back.
mkdir -p "$work/unpacked"
tar xzf "$work/$bundle" -C "$work/unpacked"
version="$(cat "$work/unpacked/churchpresenter-signage/VERSION" 2>/dev/null || echo installed)"
rm -rf "${DATA:?}/app/$version"
mv "$work/unpacked/churchpresenter-signage" "$DATA/app/$version"
ln -sfn "$version" "$DATA/app/current"
rm -f "$DATA/app/pending" "$DATA/app/previous" "$DATA/app/rolled-back"
chown -R "$USER_NAME:$USER_NAME" "$DATA/app"
# Earlier versions installed the whole player here; now only the launcher stays outside the data.
rm -rf "$PREFIX"
install -d -m 0755 "$PREFIX"
install -m 0755 "$DATA/app/$version/launch.sh" "$PREFIX/launch.sh"

# For a screen moving to another church, or one whose ChurchPresenter is gone: wipes its content and
# pairing so it shows a new pairing code. ChurchPresenter can do the same from the Signage tab.
cat > /usr/local/sbin/churchpresenter-signage-reset <<EOF
#!/bin/sh
# Factory reset of the ChurchPresenter signage player: deletes its pictures, videos, playlists and
# pairing; it then shows a new pairing code. The installed player and its settings stay.
set -e
[ "\$(id -u)" -eq 0 ] || { echo "Run it with sudo." >&2; exit 1; }
systemctl stop $AGENT_UNIT
runuser -u $USER_NAME -- env JAVA_HOME=$DATA/app/current/runtime \\
  $DATA/app/current/bin/churchpresenter-signage --data $DATA --factory-reset
systemctl start $AGENT_UNIT
echo "Reset. The TV shows a new pairing code; approve it in ChurchPresenter's Signage tab."
EOF
chmod 0755 /usr/local/sbin/churchpresenter-signage-reset

say "Removing the browser kiosk of earlier versions"
systemctl disable --now "$OLD_KIOSK_UNIT" 2>/dev/null || true
rm -f "/etc/systemd/system/$OLD_KIOSK_UNIT.service" /etc/sudoers.d/churchpresenter-signage \
  /etc/pam.d/churchpresenter-signage-kiosk

agent_args="--data $DATA --mpv $mpv_path"
[ -n "$address" ] && agent_args="$agent_args --address $address"
[ -e /dev/cec0 ] || agent_args="$agent_args --no-cec"
# mpv draws straight to the screen (DRM/KMS): on the GPU where the machine has a render device (a
# Pi, Intel, AMD), and plainly where it has none, as in a virtual machine, whose software stand-in
# for a GPU draws broken pictures.
if compgen -G '/dev/dri/renderD*' >/dev/null; then
  mpv_args="--vo=gpu --gpu-context=drm"
else
  mpv_args="--vo=drm"
fi

# The agent runs in a session on the first console, so mpv may take the screen; it logs to the
# journal, not to the screen.
cat > /etc/pam.d/churchpresenter-signage <<'EOF'
auth     required pam_unix.so nullok
account  required pam_unix.so
session  required pam_unix.so
session  required pam_systemd.so
EOF

cat > "/etc/systemd/system/$AGENT_UNIT.service" <<EOF
[Unit]
Description=ChurchPresenter signage player
After=network-online.target systemd-user-sessions.service plymouth-quit-wait.service
Wants=network-online.target
Conflicts=getty@tty1.service

[Service]
User=$USER_NAME
SupplementaryGroups=video render input
PAMName=churchpresenter-signage
TTYPath=/dev/tty1
TTYReset=yes
TTYVHangup=yes
TTYVTDisallocate=yes
StandardInput=tty-fail
StandardOutput=journal
StandardError=journal
SyslogIdentifier=churchpresenter-signage
UtmpIdentifier=tty1
UtmpMode=user
Environment=JAVA_OPTS=-Xmx256m
ExecStart=$PREFIX/launch.sh $DATA/app $agent_args --mpv-args "$mpv_args"
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF

say "Turning off the console login on the first screen and screen blanking"
systemctl disable getty@tty1.service 2>/dev/null || true
# A login on the second screen (Ctrl+Alt+F2), for when the network is down; the player is on the first.
systemctl enable getty@tty2.service 2>/dev/null || true
if [ -f /boot/firmware/cmdline.txt ] && ! grep -q 'consoleblank=0' /boot/firmware/cmdline.txt; then
  sed -i '1 s/$/ consoleblank=0/' /boot/firmware/cmdline.txt
fi

systemctl daemon-reload
systemctl enable "$AGENT_UNIT" >/dev/null

say "Installed"
echo "The TV will show a pairing code. Approve it in ChurchPresenter's Signage tab."
if [ "$reboot" -eq 1 ]; then
  echo "Rebooting in 5 seconds…"
  sleep 5
  reboot
else
  systemctl restart "$AGENT_UNIT"
fi
