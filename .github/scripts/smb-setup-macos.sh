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

# Samba's own state (tdbs, logs) lives under RUNNER_TEMP — smbd touches it as
# root. The *share* dirs must instead sit somewhere the mapped unix user (dapr)
# can traverse: RUNNER_TEMP is under /Users/runner (mode 0700), so dapr couldn't
# reach shares there and writes fail with "Access is denied". /private/tmp is
# world-traversable, so the shares go there.
STATE="$RUNNER_TEMP/smb"
SHARE="/private/tmp/dapr-smb"
MUSIC="$SHARE/music"
PRIVATE="$SHARE/private"
mkdir -p "$STATE" "$MUSIC" "$PRIVATE"
chmod -R 0777 "$SHARE"

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
  passdb backend = tdbsam:$STATE/passdb.tdb
  private dir = $STATE
  lock directory = $STATE
  state directory = $STATE
  cache directory = $STATE
  pid directory = $STATE
  log file = $STATE/log.%m
  log level = 2

[Music]
  path = $MUSIC
  read only = no
  valid users = dapr
  force user = dapr
  admin users = dapr
  create mask = 0666
  directory mask = 0777
  delete readonly = yes

[Private]
  path = $PRIVATE
  read only = no
  valid users = dapr
  force user = dapr
  admin users = dapr
  create mask = 0666
  directory mask = 0777
  delete readonly = yes
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
listening=
for _ in $(seq 1 30); do
  if nc -z localhost 445; then listening=1; echo "Samba is listening on 445"; break; fi
  sleep 1
done
if [ -z "$listening" ]; then
  echo "Samba did not start listening on 445" >&2
  cat "$STATE"/log.* 2>/dev/null >&2 || true
  exit 1
fi

# Warm up the share-enumeration RPC (srvsvc). Samba 4.x spawns that helper on
# demand, and the first client connection can race it ("No process is on the
# other end of the pipe"); listing shares here readies it before the tests run.
SMBCLIENT="$PREFIX/bin/smbclient"
for _ in $(seq 1 20); do
  if "$SMBCLIENT" -L //127.0.0.1 -U "dapr%Secretpass1!" >/dev/null 2>&1; then
    echo "share enumeration RPC is ready"
    exit 0
  fi
  sleep 1
done
echo "share enumeration RPC did not become ready" >&2
cat "$STATE"/log.* 2>/dev/null >&2 || true
exit 1
