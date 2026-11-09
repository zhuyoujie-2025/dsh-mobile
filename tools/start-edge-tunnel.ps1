# start-edge-tunnel.ps1 — cloudflared quick tunnel → edge gate 127.0.0.1:3095。
# 后台拉起 cloudflared 并留守监控；从 stderr 日志解析 trycloudflare URL，
# 写 stateDir\tunnel.json，再调用 publish-rendezvous.ps1。
# 2026-11-09：该脚本不再把地址提交到 GitHub。它只在本机打印地址；只有设了
# DSH_RENDEZVOUS_URL + DSH_RENDEZVOUS_TOKEN 时才 PUT 到你自己的私有 https 端点。
# URL 每次隧道重建都会变。手机靠你粘贴的地址，或那个私有指针，而不是公开仓库。
# 本脚本常驻到 cloudflared 退出（退出后由 mobile-stack watchdog 重新拉起）。

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
    $j = [ordered]@{ v = 1; url = $u; ts = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds();
                target = 'dsh-3085'; via = 'cloudflare-quick-tunnel' } | ConvertTo-Json -Compress
    # 必须无 BOM:PS5.1 Set-Content -Encoding utf8 会写 EF BB BF,
    # org.json/多数 JSON 解析器见到 BOM 直接报错,App 拉指针会三通道全灭
    [IO.File]::WriteAllText($stateFile, $j)
    try {
      & powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden `
        -File $publisher *>> (Join-Path $stateDir 'publish.log')
    } catch {}
  }
}
