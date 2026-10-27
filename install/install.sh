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
while [ $# -gt 0 ]; do
  case "$1" in
    --address) address="${2:-}"; address_given=1; shift 2 ;;
    --no-reboot) reboot=0; shift ;;
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

has_package() { apt-cache policy "$1" 2>/dev/null | grep -q 'Candidate: [^(]'; }

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
for extra in yt-dlp chromium; do
  has_package "$extra" && packages="$packages $extra"
done
# shellcheck disable=SC2086
apt-get install -y -q --no-install-recommends $packages
mpv_path="$(command -v mpv || true)"
[ -n "$mpv_path" ] || die "mpv did not install"

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
SupplementaryGroups=video render
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
