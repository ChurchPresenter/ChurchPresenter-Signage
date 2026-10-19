#!/usr/bin/env bash
# Installs the ChurchPresenter signage player on a Raspberry Pi (Raspberry Pi OS Lite, 64-bit) or a
# Linux mini PC (Debian or Ubuntu Server, minimal). Run it as root on a fresh system:
#
#   curl -fsSL https://github.com/ChurchPresenter/ChurchPresenter-Signage/releases/latest/download/install.sh | sudo bash
#
# It installs Chromium, the cage kiosk compositor and the CEC tools, the agent with its own Java
# runtime, and two services: the agent, and the kiosk browser on the first screen. Then it reboots to
# the pairing screen. Run it again to update; the player's pairing and content are kept.
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
KIOSK_UNIT=churchpresenter-signage-kiosk
KIOSK_PORT=8090

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

[ "$(id -u)" -eq 0 ] || die "run this as root (put sudo before bash)"
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

say "Installing Chromium, cage and the CEC tools"
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q --no-install-recommends cage v4l-utils curl ca-certificates fonts-dejavu-core
if apt-cache show chromium >/dev/null 2>&1; then
  apt-get install -y -q --no-install-recommends chromium
elif apt-cache show chromium-browser >/dev/null 2>&1; then
  apt-get install -y -q --no-install-recommends chromium-browser
fi
browser="$(command -v chromium || command -v chromium-browser || true)"
[ -n "$browser" ] || die "no Chromium package found; on Ubuntu the snap version cannot run in kiosk mode — use Debian or Raspberry Pi OS"
if [ -L "$browser" ] && readlink -f "$browser" | grep -q snap; then
  die "Chromium here is a snap, which cannot run under cage; use Debian or Raspberry Pi OS"
fi

say "Downloading the signage player ($platform)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
bundle="churchpresenter-signage-$platform.tar.gz"
curl -fsSL -o "$work/$bundle" "$BASE_URL/$bundle"
curl -fsSL -o "$work/SHA256SUMS" "$BASE_URL/SHA256SUMS"
(cd "$work" && grep " $bundle\$" SHA256SUMS | sha256sum -c --quiet -) || die "the download does not match its checksum"

say "Installing to $PREFIX"
id "$USER_NAME" >/dev/null 2>&1 || useradd --system --create-home --home-dir "/home/$USER_NAME" --shell /usr/sbin/nologin "$USER_NAME"
for group in video render input; do
  getent group "$group" >/dev/null && usermod -aG "$group" "$USER_NAME"
done
systemctl stop "$KIOSK_UNIT" "$AGENT_UNIT" 2>/dev/null || true
rm -rf "$PREFIX.new"
mkdir -p "$PREFIX.new"
tar xzf "$work/$bundle" -C "$PREFIX.new" --strip-components=1
rm -rf "$PREFIX.old"
[ -d "$PREFIX" ] && mv "$PREFIX" "$PREFIX.old"
mv "$PREFIX.new" "$PREFIX"
install -d -o "$USER_NAME" -g "$USER_NAME" -m 0750 "$DATA"

# The agent restarts the kiosk browser when the page stops answering.
cat > /etc/sudoers.d/churchpresenter-signage <<EOF
$USER_NAME ALL=(root) NOPASSWD: /usr/bin/systemctl restart $KIOSK_UNIT
EOF
chmod 0440 /etc/sudoers.d/churchpresenter-signage

agent_args="--data $DATA --kiosk-port $KIOSK_PORT"
[ -n "$address" ] && agent_args="$agent_args --address $address"
[ -e /dev/cec0 ] || agent_args="$agent_args --no-cec"

cat > "/etc/systemd/system/$AGENT_UNIT.service" <<EOF
[Unit]
Description=ChurchPresenter signage player
After=network-online.target
Wants=network-online.target

[Service]
User=$USER_NAME
SupplementaryGroups=video
Environment=JAVA_HOME=$PREFIX/runtime
Environment=JAVA_OPTS=-Xmx256m
ExecStart=$PREFIX/bin/churchpresenter-signage $agent_args --browser-restart "sudo -n /usr/bin/systemctl restart $KIOSK_UNIT"
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF

cat > /etc/pam.d/churchpresenter-signage-kiosk <<'EOF'
auth     required pam_unix.so nullok
account  required pam_unix.so
session  required pam_unix.so
session  required pam_systemd.so
EOF

cat > "/etc/systemd/system/$KIOSK_UNIT.service" <<EOF
[Unit]
Description=ChurchPresenter signage kiosk browser
After=$AGENT_UNIT.service systemd-user-sessions.service plymouth-quit-wait.service
Wants=$AGENT_UNIT.service
Conflicts=getty@tty1.service

[Service]
User=$USER_NAME
PAMName=churchpresenter-signage-kiosk
TTYPath=/dev/tty1
TTYReset=yes
TTYVHangup=yes
TTYVTDisallocate=yes
StandardInput=tty-fail
UtmpIdentifier=tty1
UtmpMode=user
ExecStart=/usr/bin/cage -- $browser --kiosk --ozone-platform=wayland --noerrdialogs --disable-infobars \\
  --no-first-run --disable-session-crashed-bubble --disable-features=Translate \\
  --autoplay-policy=no-user-gesture-required --check-for-update-interval=31536000 \\
  --user-data-dir=/home/$USER_NAME/.signage-browser http://127.0.0.1:$KIOSK_PORT
Restart=always
RestartSec=2

[Install]
WantedBy=graphical.target
EOF

say "Turning off the console login on the first screen and screen blanking"
systemctl disable getty@tty1.service 2>/dev/null || true
systemctl set-default graphical.target >/dev/null
if [ -f /boot/firmware/cmdline.txt ] && ! grep -q 'consoleblank=0' /boot/firmware/cmdline.txt; then
  sed -i '1 s/$/ consoleblank=0/' /boot/firmware/cmdline.txt
fi

systemctl daemon-reload
systemctl enable "$AGENT_UNIT" "$KIOSK_UNIT" >/dev/null
rm -rf "$PREFIX.old"

say "Installed"
echo "The TV will show a pairing code. Approve it in ChurchPresenter's Signage tab."
if [ "$reboot" -eq 1 ]; then
  echo "Rebooting in 5 seconds…"
  sleep 5
  reboot
else
  systemctl restart "$AGENT_UNIT" "$KIOSK_UNIT"
fi
