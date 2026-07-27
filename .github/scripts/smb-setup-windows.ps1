$ErrorActionPreference = 'Stop'

# Provision the Windows runner's native SMB server with the shares the SMB
# integration tests expect, mirroring the dperson/samba container used on Linux:
#   - Music:   guest-accessible, read-write
#   - Private: user dapr / secretpass, read-write
# on port 445, reachable at both 127.0.0.1 and localhost.
#
# NOTE: Windows blocks anonymous / guest SMB by default. The guest "Music" share
# is the fragile part below and may need iteration against the runner image.

$music   = 'C:\smbshare\music'
$private = 'C:\smbshare\private'
New-Item -ItemType Directory -Force -Path $music, $private | Out-Null

# Auth user for the Private share.
$pw = ConvertTo-SecureString 'secretpass' -AsPlainText -Force
if (-not (Get-LocalUser -Name dapr -ErrorAction SilentlyContinue)) {
  New-LocalUser -Name dapr -Password $pw -AccountNeverExpires -PasswordNeverExpires | Out-Null
}

# Enable the built-in Guest account so the Music share is reachable anonymously.
Enable-LocalUser -Name Guest

# Relax the server so guest / null sessions are accepted.
$params = 'HKLM:\SYSTEM\CurrentControlSet\Services\LanmanServer\Parameters'
Set-ItemProperty -Path $params -Name RestrictNullSessAccess   -Value 0 -Type DWord
Set-ItemProperty -Path $params -Name RequireSecuritySignature -Value 0 -Type DWord

# Remove Guest from "Deny access to this computer from the network", which
# otherwise blocks the anonymous logon regardless of the share ACL.
$cfg = Join-Path $env:RUNNER_TEMP 'sec.inf'
$db  = Join-Path $env:RUNNER_TEMP 'sec.sdb'
secedit /export /cfg $cfg | Out-Null
(Get-Content $cfg) -replace '(SeDenyNetworkLogonRight[^\r\n]*?),?\s*Guest', '$1' |
  Set-Content $cfg
secedit /configure /db $db /cfg $cfg /areas USER_RIGHTS | Out-Null

# Create the shares and matching NTFS ACLs.
if (Get-SmbShare -Name Music -ErrorAction SilentlyContinue) { Remove-SmbShare -Name Music -Force }
if (Get-SmbShare -Name Private -ErrorAction SilentlyContinue) { Remove-SmbShare -Name Private -Force }
New-SmbShare -Name Music   -Path $music   -FullAccess 'Everyone' | Out-Null
New-SmbShare -Name Private -Path $private -FullAccess 'dapr'     | Out-Null
icacls $music   /grant 'Everyone:(OI)(CI)F' /T | Out-Null
icacls $private /grant 'dapr:(OI)(CI)F'     /T | Out-Null

# Restart the server service so the policy changes take effect.
Restart-Service -Force LanmanServer
Start-Sleep -Seconds 3

# Wait for the listener before handing off to the tests.
for ($i = 0; $i -lt 30; $i++) {
  if (Test-NetConnection -ComputerName localhost -Port 445 -InformationLevel Quiet) {
    Write-Host 'SMB server is listening on 445'
    exit 0
  }
  Start-Sleep -Seconds 1
}
Write-Error 'SMB server did not start listening on 445'
exit 1
