$ErrorActionPreference = 'Stop'

# Provision the Windows runner's native SMB server with the shares the SMB
# integration tests expect, mirroring the dperson/samba container used on Linux:
#   - Music   and Private, both read-write and accessible by user dapr
# on port 445, reachable at both 127.0.0.1 and localhost.
#
# The tests authenticate as dapr here (Windows blocks anonymous/guest SMB), so no
# guest configuration is needed — the anonymous path is exercised by the Linux run.

$music   = 'C:\smbshare\music'
$private = 'C:\smbshare\private'
New-Item -ItemType Directory -Force -Path $music, $private | Out-Null

# Auth user for both shares. The password satisfies the local-account complexity
# policy (New-LocalUser rejects a simple one).
$pw = ConvertTo-SecureString 'Secretpass1!' -AsPlainText -Force
if (-not (Get-LocalUser -Name dapr -ErrorAction SilentlyContinue)) {
  New-LocalUser -Name dapr -Password $pw -AccountNeverExpires -PasswordNeverExpires | Out-Null
}

# Create the shares (idempotent) and matching NTFS ACLs, granting dapr access.
if (Get-SmbShare -Name Music   -ErrorAction SilentlyContinue) { Remove-SmbShare -Name Music   -Force }
if (Get-SmbShare -Name Private -ErrorAction SilentlyContinue) { Remove-SmbShare -Name Private -Force }
New-SmbShare -Name Music   -Path $music   -FullAccess 'dapr' | Out-Null
New-SmbShare -Name Private -Path $private -FullAccess 'dapr' | Out-Null
icacls $music   /grant 'dapr:(OI)(CI)F' /T | Out-Null
icacls $private /grant 'dapr:(OI)(CI)F' /T | Out-Null

# Wait for the SMB listener before handing off to the tests.
for ($i = 0; $i -lt 30; $i++) {
  if (Test-NetConnection -ComputerName localhost -Port 445 -InformationLevel Quiet) {
    Write-Host 'SMB server is listening on 445'
    exit 0
  }
  Start-Sleep -Seconds 1
}
Write-Error 'SMB server did not start listening on 445'
exit 1
