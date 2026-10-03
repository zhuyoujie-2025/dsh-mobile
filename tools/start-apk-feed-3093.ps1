# start-apk-feed-3093.ps1 — 拉起 APK 更新订阅源 0.0.0.0:3093 -> 项目 dist/
# App 端用 http://<电脑IP>:3093/dshmobile/version.json 检查更新 (公网 GitHub Releases 兜底)
# 用法: powershell -NoProfile -ExecutionPolicy Bypass -File start-apk-feed-3093.ps1 [-Port 3093]
param([int]$Port = 3093)
$ErrorActionPreference = 'Stop'

$existing = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($existing) { Write-Host "port $Port already listening (PID $($existing[0].OwningProcess))"; exit 0 }

$Home2 = "$env:USERPROFILE\.dsh\gates\$Port"
New-Item -ItemType Directory -Force $Home2 | Out-Null

$env:APK_FEED_PORT = "$Port"
$node = (Get-Command node -ErrorAction SilentlyContinue).Source
# 可选回退:看门狗/计划任务等无 PATH 场景下常见的本机 node 安装位,不存在即跳过
if (-not $node -and (Test-Path 'C:\nvm4w\nodejs\node.exe')) { $node = 'C:\nvm4w\nodejs\node.exe' }
if (-not $node) { Write-Host "node not found in PATH"; exit 1 }
$server = Join-Path $PSScriptRoot 'apk-feed-server.cjs'

$p = Start-Process -WindowStyle Hidden -PassThru -FilePath $node `
    -ArgumentList "`"$server`"" `
    -WorkingDirectory $PSScriptRoot `
    -RedirectStandardOutput (Join-Path $Home2 'feed.out.log') `
    -RedirectStandardError  (Join-Path $Home2 'feed.err.log')
Start-Sleep -Seconds 1
if ($p.HasExited) { Write-Host "feed exited immediately, code $($p.ExitCode)"; exit 1 }
Write-Host "apk-feed started: PID $($p.Id)  0.0.0.0:$Port -> dist/"
try { (Invoke-RestMethod -TimeoutSec 5 "http://127.0.0.1:$Port/dshmobile/version.json") | ConvertTo-Json -Compress } catch { Write-Host "version.json not reachable yet: $_" }
