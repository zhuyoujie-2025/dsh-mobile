# start-edge-tunnel.ps1 — cloudflared quick tunnel → edge gate 127.0.0.1:3095。
# 2026-10-08：后台拉起 cloudflared 并留守监控；从 stderr 日志解析 trycloudflare URL，
# 写 stateDir\tunnel.json 并调用 publish-rendezvous.ps1 把指针推到 GitHub prerelease。
# URL 每次隧道重建都会变，全靠指针发布保证手机端永远找得到——所以本脚本常驻到
# cloudflared 退出（退出后由 mobile-stack watchdog 重新拉起本脚本）。

$ErrorActionPreference = 'Stop'
$stateDir = "$env:USERPROFILE\.dsh\gates\edge-3095"
New-Item -ItemType Directory -Force -Path $stateDir | Out-Null
$bin       = "$env:USERPROFILE\.dsh\gates\bin\cloudflared.exe"
$errLog    = Join-Path $stateDir 'cloudflared.err.log'
$outLog    = Join-Path $stateDir 'cloudflared.out.log'
$stateFile = Join-Path $stateDir 'tunnel.json'
$publisher = Join-Path $PSScriptRoot 'publish-rendezvous.ps1'

if (-not (Test-Path $bin)) { Write-Error "cloudflared 不在 $bin"; exit 1 }

$p = Start-Process -FilePath $bin -WindowStyle Hidden -PassThru `
  -ArgumentList 'tunnel','--url','http://127.0.0.1:3095','--no-autoupdate' `
  -RedirectStandardError $errLog -RedirectStandardOutput $outLog

while (-not $p.HasExited) {
  Start-Sleep -Seconds 2
  if (-not (Test-Path $errLog)) { continue }
  $m = Select-String -Path $errLog -Pattern 'https://[a-z0-9-]+\.trycloudflare\.com' |
       Select-Object -First 1
  if (-not $m) { continue }
  $u = $m.Matches[0].Value
  $cur = ''
  if (Test-Path $stateFile) {
    try { $cur = (Get-Content $stateFile -Raw | ConvertFrom-Json).url } catch {}
  }
  if ($u -ne $cur) {
    [ordered]@{ v = 1; url = $u; ts = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds();
                target = 'dsh-3085'; via = 'cloudflare-quick-tunnel' } |
      ConvertTo-Json -Compress | Set-Content $stateFile -Encoding utf8
    try {
      & powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden `
        -File $publisher *>> (Join-Path $stateDir 'publish.log')
    } catch {}
  }
}
