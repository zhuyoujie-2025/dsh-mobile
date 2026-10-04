# allow-gate-firewall.ps1 — 放行 lan-gate 入站端口 (需管理员, 会自动提权)
# 覆盖 3088-3108: 3088=lan-gate 默认口(EADDRINUSE 顺延 +1..+20 整段),
#   含 3093=APK 订阅源;v1.5.2 起与 App 扫口表一致(实测顺延可落到 3095-3104)
# 用法: 右键"使用 PowerShell 运行", 或 powershell -NoProfile -ExecutionPolicy Bypass -File allow-gate-firewall.ps1

if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "need admin, relaunching elevated..."
    Start-Process -Verb RunAs -FilePath "powershell.exe" -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    exit
}

$rule = Get-NetFirewallRule -DisplayName "DSH LAN Gate" -ErrorAction SilentlyContinue
if ($rule) { Remove-NetFirewallRule -DisplayName "DSH LAN Gate" }

New-NetFirewallRule -DisplayName "DSH LAN Gate" `
    -Direction Inbound -Action Allow -Protocol TCP `
    -LocalPort 3088-3108 -Profile Private,Domain `
    -Description "DSH mobile lan-gate+apk-feed ports (3088-3108), created by dsh-mobile-apk/tools" | Out-Null

Write-Host "OK: inbound TCP 3088-3108 allowed (Private/Domain profile)"
Write-Host "NOTE: 若手机走公用网络(Public profile)仍不通, 把 Wi-Fi 网络类别改为专用, 或手动加 -Profile Any"
pause
