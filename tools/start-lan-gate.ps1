# start-lan-gate.ps1 — 拉起独立 lan-gate 网关 (Windows)
# 手机 App → 0.0.0.0:<Port> → 127.0.0.1:<TargetPort> (本机 dsh web / 桌面内核)
# 幂等: 目标口已监听则直退。状态存 <StateHome>\lan-gate-state.json。
# -Password: 设访问密码后,手机首访输同一密码即进(免电脑端审批);不传则保留审批页。
# 用法: powershell -NoProfile -ExecutionPolicy Bypass -File tools/start-lan-gate.ps1 [-Port 3088] [-TargetPort 3080] [-Password <访问密码>]
param(
    [int]$Port = 3088,
    [int]$TargetPort = 3080,
    [string]$StateHome = "$env:USERPROFILE\.dsh\gates\$Port",
    [string]$Node = "",
    [string]$Password = ""
)
$ErrorActionPreference = 'Stop'

$existing = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "port $Port already listening (PID $($existing[0].OwningProcess))"
    exit 0
}
if (-not (Get-NetTCPConnection -LocalPort $TargetPort -State Listen -ErrorAction SilentlyContinue)) {
    Write-Host "target 127.0.0.1:$TargetPort is NOT listening; abort (先启动 DSH / 桌面端)"
    exit 1
}

if (-not $Node) {
    $Node = (Get-Command node -ErrorAction SilentlyContinue).Source
    # 可选回退:无 PATH 场景下常见的本机 node 安装位,不存在即跳过
    if (-not $Node -and (Test-Path 'C:\nvm4w\nodejs\node.exe')) { $Node = 'C:\nvm4w\nodejs\node.exe' }
    if (-not $Node) { Write-Host "node not found in PATH"; exit 1 }
}
$server = Join-Path $PSScriptRoot '..\gate\lan-gate-server.cjs'

New-Item -ItemType Directory -Force $StateHome | Out-Null
$env:LAN_GATE_PORT = "$Port"
$env:LAN_GATE_HOST = '0.0.0.0'
$env:LAN_GATE_TARGET_PORT = "$TargetPort"
$env:DSH_HOME = $StateHome
if ($Password) { $env:LAN_GATE_PASSWORD = $Password } else { Remove-Item Env:LAN_GATE_PASSWORD -ErrorAction SilentlyContinue }

$p = Start-Process -WindowStyle Hidden -PassThru -FilePath $Node `
    -ArgumentList "`"$server`"" `
    -WorkingDirectory (Split-Path $server -Parent) `
    -RedirectStandardOutput (Join-Path $StateHome 'gate.out.log') `
    -RedirectStandardError  (Join-Path $StateHome 'gate.err.log')
Start-Sleep -Seconds 2
if ($p.HasExited) { Write-Host "gate exited immediately, code $($p.ExitCode)"; exit 1 }
Write-Host "lan-gate started: PID $($p.Id)  0.0.0.0:$Port -> 127.0.0.1:$TargetPort"
try { (Invoke-RestMethod -TimeoutSec 5 "http://127.0.0.1:$Port/lan-gate/status") | ConvertTo-Json -Compress } catch { Write-Host "status check failed: $_" }
