#!/usr/bin/env bash
# Provision linux-runner's native Samba with the shares the SMB integration
# tests expect, mirroring the dperson/samba container used locally and the
# native server smb-setup-windows.ps1 provisions on win-runner:
#   - Music   guest-writable  (the anonymous code path)
#   - Private user dapr only  (the authenticated code path)
# on port 445, reachable at 127.0.0.1 and localhost.
#
# Run ONCE as root at runner bring-up, not per job: the forge runner is host
# mode, so a job runs as an unprivileged user on a machine shared with every
# other job and must never apt-get or sudo. See usb-ci docs/device-runners.md,
# which is also the recovery path after a VM revert.
#
# Idempotent -- safe to re-run.
set -euo pipefail

[ "$(id -u)" -eq 0 ] || { echo "run as root" >&2; exit 1; }

apt-get update
apt-get install -y samba smbclient

install -d -m 0777 /srv/smb/music
install -d -m 0770 /srv/smb/private

# The auth user. Password matches the container and Windows fixtures so one set
# of credentials works on every backend.
id -u dapr >/dev/null 2>&1 || useradd --system --home-dir /srv/smb --shell /usr/sbin/nologin dapr
printf 'Secretpass1!\nSecretpass1!\n' | smbpasswd -s -a dapr
smbpasswd -e dapr
chown dapr:dapr /srv/smb/private

# `map to guest = Bad User` is what makes the anonymous path work: jcifs sends a
# username Samba does not know, and this maps it to the guest account rather
# than rejecting it.
cat > /etc/samba/smb.conf <<'CONF'
[global]
   workgroup = WORKGROUP
   server min protocol = SMB2
   map to guest = Bad User
   guest account = nobody
   smb ports = 445
   log level = 1

[Music]
   path = /srv/smb/music
   browseable = yes
   read only = no
   guest ok = yes
   force user = root

[Private]
   path = /srv/smb/private
   browseable = yes
   read only = no
   guest ok = no
   valid users = dapr
   force user = dapr
CONF

testparm -s >/dev/null
systemctl enable smbd
systemctl restart smbd

for _ in $(seq 1 30); do
  if smbclient -L //127.0.0.1 -N >/dev/null 2>&1; then
    echo "SMB server is listening on 445"
    smbclient -L //127.0.0.1 -N 2>/dev/null | grep -E 'Music|Private' || true
    exit 0
  fi
  sleep 1
done
echo "SMB server did not start listening on 445" >&2
exit 1
