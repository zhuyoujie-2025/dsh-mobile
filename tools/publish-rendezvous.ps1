# publish-rendezvous.ps1 — 把当前隧道地址交给机主,不再公开发布。
# 2026-11-09: 以前会把 tunnel URL 提交到公开仓库的 rendezvous 分支,并上传同名
# prerelease。这两处任何人都能读到,而且一天几十个自动提交。现在默认只留在本机:
#   %USERPROFILE%\.dsh\gates\edge-3095\tunnel.json
# 脚本打印地址、复制不含密码的 dshmobile:// 深链。需要手机在隧道轮换后自动跟上时,
# 设两个环境变量,把指针 PUT 到你自己的私有 https 端点(必须带令牌):
#   DSH_RENDEZVOUS_URL    只接受 https,且拒绝 GitHub / jsDelivr 等公开主机
#   DSH_RENDEZVOUS_TOKEN  Authorization: Bearer 的值,不会写进 URL
# 公开的旧 rendezvous 分支和 prerelease 请自己删。本脚本不再推它们,也不改写历史。

$ErrorActionPreference = 'Stop'
$stateDir  = "$env:USERPROFILE\.dsh\gates\edge-3095"
$stateFile = Join-Path $stateDir 'tunnel.json'
$marker    = Join-Path $stateDir 'published.sha'
$asset     = Join-Path $stateDir 'rendezvous.json'

if (-not (Test-Path $stateFile)) { exit 0 }

# 归一化:剥 UTF-8 BOM。只写本机副本,不上传。
$clean = [IO.File]::ReadAllText($stateFile).TrimStart([char]0xFEFF)
[IO.File]::WriteAllText($asset, $clean)
try { $url = ([string]($clean | ConvertFrom-Json).url).Trim() } catch { $url = '' }
if ($url -notmatch '^https://') {
  Write-Warning "tunnel.json has no https url; nothing to share."
  exit 0
}

$deep = 'dshmobile://connect?url=' + [Uri]::EscapeDataString($url)
Write-Host ""
Write-Host "Tunnel URL (local only, not published):" -ForegroundColor Cyan
Write-Host "  $url"
Write-Host "Phone: paste that https address, or open this password-free link:"
Write-Host "  $deep"
Write-Host "Do not put the gate password in the URL. The phone logs in with POST /lan-gate/login."
try { Set-Clipboard $url; Write-Host "(https URL copied to the clipboard)" } catch {}
Write-Host ""
Write-Host "Public GitHub rendezvous publishing is disabled (no branch commit, no prerelease upload)." -ForegroundColor Yellow
Write-Host "Rotate the leaked tunnel by restarting cloudflared, then delete the old rendezvous branch and prerelease yourself."

$dest = [string]$env:DSH_RENDEZVOUS_URL
$token = [string]$env:DSH_RENDEZVOUS_TOKEN
if ([string]::IsNullOrWhiteSpace($dest)) { exit 0 }
$token = $token.Trim()
if ([string]::IsNullOrWhiteSpace($token)) {
  Write-Warning "DSH_RENDEZVOUS_URL is set but DSH_RENDEZVOUS_TOKEN is empty; refusing to publish without authentication."
  exit 0
}

try { $u = [Uri]$dest.Trim() } catch { Write-Warning "DSH_RENDEZVOUS_URL is not a URL; not publishing."; exit 0 }
if ($u.Scheme -ne 'https') { Write-Warning "DSH_RENDEZVOUS_URL must be https; not publishing."; exit 0 }
$blocked = @('github.com','githubusercontent.com','jsdelivr.net','gitlab.com','bitbucket.org','gitee.com')
foreach ($b in $blocked) {
  if ($u.Host -eq $b -or $u.Host.EndsWith('.' + $b)) {
    Write-Warning "Refusing to publish the tunnel URL to public host $($u.Host)."
    exit 0
  }
}

$sha = (Get-FileHash $asset -Algorithm SHA256).Hash
if ((Test-Path $marker) -and ((Get-Content $marker -Raw).Trim() -eq $sha)) {
  Write-Host "Private pointer unchanged; not PUT again."
  exit 0
}

try {
  Invoke-RestMethod -Method Put -Uri $u.AbsoluteUri -Headers @{ Authorization = "Bearer $token" } `
    -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($clean)) | Out-Null
  Set-Content $marker $sha -Encoding ascii
  Write-Host "PUT tunnel pointer to private endpoint $($u.Host) (URL not printed again)."
} catch {
  Write-Warning "Private rendezvous PUT failed: $($_.Exception.Message)"
}
