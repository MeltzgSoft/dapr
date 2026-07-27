#!/usr/bin/env bash
set -euo pipefail

# Provision the macOS runner's built-in SMB server with the shares the SMB
# integration tests expect, mirroring the dperson/samba container used on Linux:
#   - Music:   guest-accessible, read-write
#   - Private: user dapr / secretpass, read-write
# on port 445, reachable at both 127.0.0.1 and localhost.
#
# NOTE: macOS guest SMB access and the `sharing` protocol-mask flags are
# version-sensitive; treat this as a first cut that may need iteration against
# the actual GitHub runner image.

MUSIC="/tmp/smbshare/music"
PRIVATE="/tmp/smbshare/private"
sudo mkdir -p "$MUSIC" "$PRIVATE"
sudo chmod 0777 "$MUSIC"

# Auth user for the Private share.
if ! id dapr >/dev/null 2>&1; then
  sudo sysadminctl -addUser dapr -password secretpass
fi
sudo chown -R dapr:staff "$PRIVATE"
sudo chmod 0700 "$PRIVATE"

# Allow guest SMB access (disabled by default on macOS).
sudo defaults write \
  /Library/Preferences/SystemConfiguration/com.apple.smb.server \
  AllowGuestAccess -bool YES

# Register the shares. `-s` / `-g` are 3-bit protocol masks in AFP,FTP,SMB order:
#   -s 001 => share enabled for SMB only
#   -g 001 => guest allowed over SMB only  (000 => no guest, i.e. auth required)
sudo sharing -a "$MUSIC"   -S Music   -s 001 -g 001
sudo sharing -a "$PRIVATE" -S Private -s 001 -g 000

# Restart smbd so it picks up the new shares and guest setting.
sudo launchctl kickstart -k system/com.apple.smbd \
  || sudo launchctl load -w /System/Library/LaunchDaemons/com.apple.smbd.plist

# Wait for the listener before handing off to the tests.
for _ in $(seq 1 30); do
  if nc -z localhost 445; then
    echo "smbd is listening on 445"
    exit 0
  fi
  sleep 1
done
echo "smbd did not start listening on 445" >&2
exit 1
