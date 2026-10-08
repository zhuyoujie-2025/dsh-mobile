# start-edge-gate-3095.ps1 — 公网隧道专用 edge lan-gate。
# 2026-10-08：只绑 127.0.0.1:3095 → DSH 主本 127.0.0.1:3085，配合本机 cloudflared
# quick tunnel 提供"任意网络可达"的手机入口。EDGE 模式关回环/LAN 直通、
# 按 cf-connecting-ip 记设备身份、对外不暴露 admin/status/action；
# 准入只有两条路：?pw= 票据 / 密码登录页（LAN_GATE_PASSWORD 取自 password.txt）。
# 由 ps-watchdog-mobile-stack.ps1 拉起；不要直接绑 0.0.0.0——LAN 设备请走 3088/3090。

$ErrorActionPreference = 'Stop'
$stateDir = "$env:USERPROFILE\.dsh\gates\edge-3095"
New-Item -ItemType Directory -Force -Path $stateDir | Out-Null
$pwFile = Join-Path $stateDir 'password.txt'
if (-not (Test-Path $pwFile)) {
  # 首启生成 32 字节 url-safe 口令；只落盘，不进日志/命令行
  $bytes = New-Object byte[] 24
  [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
  [Convert]::ToBase64String($bytes).Replace('+','-').Replace('/','_').TrimEnd('=') | Set-Content $pwFile -Encoding ascii -NoNewline
}
$env:LAN_GATE_EDGE         = '1'
$env:LAN_GATE_PORT         = '3095'
$env:LAN_GATE_HOST         = '127.0.0.1'
$env:LAN_GATE_TARGET_PORT  = '3085'
$env:LAN_GATE_AUTO         = 'off'
$env:DSH_HOME              = $stateDir
$env:LAN_GATE_PASSWORD     = (Get-Content $pwFile -Raw).Trim()

$node = 'C:\nvm4w\nodejs\node.exe'
$gate = 'C:\Users\36436\Desktop\dsh-mobile-apk\gate\lan-gate-server.cjs'
& $node $gate *>> (Join-Path $stateDir 'edge-gate.log')
