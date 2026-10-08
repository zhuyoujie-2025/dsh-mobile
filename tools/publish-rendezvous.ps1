# publish-rendezvous.ps1 — 发布隧道指针,手机在任何网络都能拿到当前入口地址。
# 2026-10-08：双通道发布——
#   1) repo 的 `rendezvous` 分支文件(raw.githubusercontent.com / cdn.jsdelivr.net 都能拉,
#      中国大陆直连稳定);
#   2) `rendezvous` prerelease 资产(prerelease 不会劫持 releases/latest 更新通道,
#      objects.githubusercontent.com 国内直连差,只作兜底)。
# 指针只含 URL 不含口令——公开可读无损(edge gate 准入靠 LAN_GATE_PASSWORD,
# 口令只存在手机与 password.txt)。幂等:内容未变不重复推送。
# 注:gh 的 stderr 走 cmd /c ... 2>nul —— PS5.1 下原生命令 stderr 包成
# NativeCommandError,配合 ErrorActionPreference=Stop 会误杀脚本。

$ErrorActionPreference = 'Stop'
$stateDir  = "$env:USERPROFILE\.dsh\gates\edge-3095"
$stateFile = Join-Path $stateDir 'tunnel.json'
$marker    = Join-Path $stateDir 'published.sha'
$asset     = Join-Path $stateDir 'rendezvous.json'
$repo      = 'zhuyoujie-2025/dsh-mobile'
$gh        = 'C:\Program Files\GitHub CLI\gh.exe'
$api       = 'https://api.github.com'

if (-not (Test-Path $stateFile)) { exit 0 }
# 归一化:剥 UTF-8 BOM 再落盘/上传——org.json 见 BOM 会解析失败,手机拉指针全灭
$clean = [IO.File]::ReadAllText($stateFile).TrimStart([char]0xFEFF)
[IO.File]::WriteAllText($asset, $clean)
$sha = (Get-FileHash $asset -Algorithm SHA256).Hash
if ((Test-Path $marker) -and ((Get-Content $marker -Raw).Trim() -eq $sha)) { exit 0 }

$token = (& $gh auth token) ; $token = $token.Trim()
$hdr   = @{ Authorization = "Bearer $token"; Accept = 'application/vnd.github+json';
            'X-GitHub-Api-Version' = '2022-11-28' }
$ok = $true

try {
  # --- 通道1:rendezvous 分支文件 ---
  try { $mainRef = Invoke-RestMethod -Headers $hdr "$api/repos/$repo/git/ref/heads/main" }
  catch { $mainRef = $null }
  if ($mainRef) {
    try { Invoke-RestMethod -Headers $hdr "$api/repos/$repo/git/ref/heads/rendezvous" | Out-Null }
    catch {
      Invoke-RestMethod -Headers $hdr -Method Post "$api/repos/$repo/git/refs" `
        -Body (@{ ref = 'refs/heads/rendezvous'; sha = $mainRef.object.sha } | ConvertTo-Json) | Out-Null
    }
    $fileSha = $null
    try { $fileSha = (Invoke-RestMethod -Headers $hdr "$api/repos/$repo/contents/rendezvous.json?ref=rendezvous").sha } catch {}
    $b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($asset))
    $body = @{ message = 'rendezvous: update tunnel pointer'; content = $b64; branch = 'rendezvous' }
    if ($fileSha) { $body.sha = $fileSha }
    Invoke-RestMethod -Headers $hdr -Method Put "$api/repos/$repo/contents/rendezvous.json" `
      -Body ($body | ConvertTo-Json) | Out-Null
    # jsDelivr 有 CDN 缓存,主动 purge 让新指针立即可读
    try { Invoke-RestMethod "https://purge.jsdelivr.net/gh/$repo@rendezvous/rendezvous.json" -TimeoutSec 10 | Out-Null } catch {}
  }

  # --- 通道2:rendezvous prerelease 资产(国内直连差的兜底通道) ---
  cmd /c "`"$gh`" release view rendezvous -R $repo >nul 2>nul"
  if ($LASTEXITCODE -ne 0) {
    cmd /c "`"$gh`" release create rendezvous --prerelease -R $repo --title `"Mobile Rendezvous`" --notes `"machine-maintained tunnel pointer; not a product release`" >nul 2>nul"
  }
  cmd /c "`"$gh`" release upload rendezvous `"$asset`" --clobber -R $repo >nul 2>nul"
  if ($LASTEXITCODE -ne 0) { $ok = $false }
} catch { $ok = $false }

if ($ok) { Set-Content $marker $sha -Encoding ascii }
