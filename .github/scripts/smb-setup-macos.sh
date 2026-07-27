#!/usr/bin/env bash
set -euo pipefail

# Provision an SMB server on the macOS runner with the shares the SMB integration
# tests expect (Music and Private, both accessible by user dapr, on port 445).
#
# macOS's *built-in* smbd won't authenticate a scripted local account over NTLM
# (jcifs gets "unknown user name or bad password", because macOS doesn't store an
# NTLM-usable hash for such accounts). So instead we run Samba from Homebrew: its
# own tdbsam passdb holds the NT hash we set via smbpasswd, exactly like the
# dperson/samba container used on Linux. The tests authenticate as dapr (native
# guest SMB isn't available here; the anonymous path is covered by the Linux run).

brew install samba

PREFIX="$(brew --prefix samba)"
# Homebrew renames smbd to avoid clashing with /usr/sbin/smbd; fall back if not.
SMBD="$PREFIX/sbin/samba-dot-org-smbd"
[ -x "$SMBD" ] || SMBD="$(command -v samba-dot-org-smbd || true)"
[ -x "$SMBD" ] || SMBD="$PREFIX/sbin/smbd"
SMBPASSWD="$PREFIX/bin/smbpasswd"

ROOT="$RUNNER_TEMP/smb"
MUSIC="$ROOT/music"
PRIVATE="$ROOT/private"
mkdir -p "$MUSIC" "$PRIVATE"
chmod 0777 "$MUSIC" "$PRIVATE"

# smbpasswd has no --configfile flag, so write smb.conf to Samba's compiled-in
# default path; every tool (smbpasswd, smbd) then reads it without a flag.
CONF="$("$SMBD" -b 2>/dev/null | awk -F': *' '/CONFIGFILE/ {print $2; exit}')"
CONF="${CONF:-$PREFIX/etc/smb.conf}"
sudo mkdir -p "$(dirname "$CONF")"

# Samba maps the SMB login to a unix account, so dapr must exist (its macOS
# password is irrelevant — Samba authenticates against its own passdb below).
if ! id dapr >/dev/null 2>&1; then
  sudo sysadminctl -addUser dapr -password 'Secretpass1!'
fi

# Keep all Samba state under $ROOT so nothing needs pre-existing system paths.
sudo tee "$CONF" >/dev/null <<EOF
[global]
  server min protocol = SMB2
  map to guest = never
  smb ports = 445
  security = user
  passdb backend = tdbsam:$ROOT/passdb.tdb
  private dir = $ROOT
  lock directory = $ROOT
  state directory = $ROOT
  cache directory = $ROOT
  pid directory = $ROOT
  log file = $ROOT/log.%m

[Music]
  path = $MUSIC
  read only = no
  valid users = dapr

[Private]
  path = $PRIVATE
  read only = no
  valid users = dapr
EOF

# Set dapr's Samba (NT-hash) password in the passdb (reads $CONF from its default
# path; smbpasswd has no config-file flag).
printf 'Secretpass1!\nSecretpass1!\n' | sudo "$SMBPASSWD" -a -s dapr

# Free port 445 by stopping macOS's built-in smbd, then start our Samba on it.
sudo launchctl bootout system /System/Library/LaunchDaemons/com.apple.smbd.plist 2>/dev/null \
  || sudo launchctl unload -w /System/Library/LaunchDaemons/com.apple.smbd.plist 2>/dev/null \
  || true
sudo "$SMBD" -D

# Wait for the listener before handing off to the tests.
for _ in $(seq 1 30); do
  if nc -z localhost 445; then
    echo "Samba is listening on 445"
    exit 0
  fi
  sleep 1
done
echo "Samba did not start listening on 445" >&2
cat "$ROOT"/log.* 2>/dev/null >&2 || true
exit 1
