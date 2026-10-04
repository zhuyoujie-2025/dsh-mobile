# start-lan-gate.ps1 — 拉起独立 lan-gate 网关 (Windows)
# 手机 App → 0.0.0.0:<Port> → 127.0.0.1:<TargetPort> (本机 dsh web / 桌面内核)
# 幂等: 目标口已监听则直退。状态存 <StateHome>\lan-gate-state.json。
# 会话注入: 网关自动读 <DshHome>\.credentials.yaml 的 browser-session 密钥,
#   给每个转发请求现铸回环会话 cookie——手机端不碰 DSH 的 ?token= 门,
#   打开即看到与桌面端同一实例的已登录界面。凭据缺失时退化为原生 token 流。
# -Auto: first(默认)第一台外部设备自动配对,之后走审批/密码;
#        all 放行所有同 LAN 设备(仅可信网络);off 严格审批。
# -Password: 额外启用密码登录页(供 first 之后的设备自助进入)。
# 用法: powershell -NoProfile -ExecutionPolicy Bypass -File tools/start-lan-gate.ps1 `
#        [-Port 3088] [-TargetPort 3080] [-Auto first|all|off] [-DshHome <实例home>]
param(
    [int]$Port = 3088,
    [int]$TargetPort = 3080,
    [string]$StateHome = "$env:USERPROFILE\.dsh\gates\$Port",
    [string]$DshHome = "",
    [string]$Node = "",
    [string]$Auto = "first",
    [string]$Password = ""
)
$ErrorActionPreference = 'Stop'

# 只认通配监听(0.0.0.0/::)为本网关已占用;127.0.0.1-only 监听多为 WSL loopback
# 转发器越狱段(WSL 内插件 gate),与本机 0.0.0.0 网关可共存,不应误判为已运行
$existing = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
    Where-Object { $_.LocalAddress -eq '0.0.0.0' -or $_.LocalAddress -eq '::' }
if ($existing) {
    Write-Host "port $Port already listening on wildcard (PID $($existing[0].OwningProcess))"
    exit 0
}
if (-not (Get-NetTCPConnection -LocalPort $TargetPort -State Listen -ErrorAction SilentlyContinue)) {
    Write-Host "target 127.0.0.1:$TargetPort is NOT listening; abort (先启动 DSH / 桌面端)"
    exit 1
}

# 会话注入凭据: 依次尝试 -DshHome、~/.dsh、~/dsh-desktop-home(桌面端常见 home)
if (-not $DshHome) {
    foreach ($cand in @("$env:USERPROFILE\.dsh", "$env:USERPROFILE\dsh-desktop-home")) {
        if (Test-Path (Join-Path $cand '.credentials.yaml')) { $DshHome = $cand; break }
    }
}
if ($DshHome -and (Test-Path (Join-Path $DshHome '.credentials.yaml'))) {
    $env:LAN_GATE_CREDENTIALS = Join-Path $DshHome '.credentials.yaml'
    Write-Host "session injection: $env:LAN_GATE_CREDENTIALS"
} else {
    Remove-Item Env:LAN_GATE_CREDENTIALS -ErrorAction SilentlyContinue
    Write-Host "WARN: no .credentials.yaml found — phone will see DSH native token page (use -DshHome)"
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
$env:LAN_GATE_AUTO = $Auto
$env:DSH_HOME = $StateHome
if ($Password) { $env:LAN_GATE_PASSWORD = $Password } else { Remove-Item Env:LAN_GATE_PASSWORD -ErrorAction SilentlyContinue }

$p = Start-Process -WindowStyle Hidden -PassThru -FilePath $Node `
    -ArgumentList "`"$server`"" `
    -WorkingDirectory (Split-Path $server -Parent) `
    -RedirectStandardOutput (Join-Path $StateHome 'gate.out.log') `
    -RedirectStandardError  (Join-Path $StateHome 'gate.err.log')
Start-Sleep -Seconds 2
if ($p.HasExited) { Write-Host "gate exited immediately, code $($p.ExitCode)"; exit 1 }
Write-Host "lan-gate started: PID $($p.Id)  0.0.0.0:$Port -> 127.0.0.1:$TargetPort  auto=$Auto"
try { (Invoke-RestMethod -TimeoutSec 5 "http://127.0.0.1:$Port/lan-gate/status") | ConvertTo-Json -Compress } catch { Write-Host "status check failed: $_" }
