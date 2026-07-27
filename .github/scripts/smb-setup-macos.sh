#!/usr/bin/env bash
set -euo pipefail

# Provision the macOS runner's built-in SMB server with the shares the SMB
# integration tests expect, mirroring the dperson/samba container used on Linux:
#   - Music   and Private, both read-write and accessible by user dapr
# on port 445, reachable at both 127.0.0.1 and localhost.
#
# The tests authenticate as dapr here (native guest SMB isn't available on the
# macOS runner), so no guest configuration is needed — the anonymous path is
# exercised by the Linux run.

MUSIC="/Users/Shared/smbshare/music"
PRIVATE="/Users/Shared/smbshare/private"
sudo mkdir -p "$MUSIC" "$PRIVATE"

# Auth user for both shares.
if ! id dapr >/dev/null 2>&1; then
  sudo sysadminctl -addUser dapr -password 'Secretpass1!'
fi
# A sysadminctl-created account often can't authenticate over SMB until its
# password is re-set through dscl, which (re)generates the SMB-compatible
# password hash. Without this, smbd returns "unknown user name or bad password".
sudo dscl . -passwd /Users/dapr 'Secretpass1!'
# If SMB access is gated by the com.apple.access_smb group, add dapr to it.
if sudo dscl . -read /Groups/com.apple.access_smb >/dev/null 2>&1; then
  sudo dscl . -append /Groups/com.apple.access_smb GroupMembership dapr || true
fi
sudo chown -R dapr:staff "$MUSIC" "$PRIVATE"
sudo chmod -R 0700 "$MUSIC" "$PRIVATE"

# Register both directories as SMB share points. `-s 001` enables the share for
# SMB only (the 3-bit mask is AFP,FTP,SMB); no `-g`, so access requires auth.
sudo sharing -a "$MUSIC"   -S Music   -s 001
sudo sharing -a "$PRIVATE" -S Private -s 001

# Restart smbd so it picks up the new shares.
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
