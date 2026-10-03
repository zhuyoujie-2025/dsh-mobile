# get-mobile-url.ps1 — 输出手机端可用的完整网关地址并复制到剪贴板
# 默认出 lan-gate 约定口 3088(插件/独立网关默认;本机多实例请用 -GatePort 指定)。
# lan-gate 不需要 token(首次访问走审批);若入口是 dsh web/dsh-bridge 认证页,
# 用 -Token 附上 ?token= (token 在 dsh web 启动日志或桥插件二维码页可见)。
# 用法: powershell -NoProfile -ExecutionPolicy Bypass -File get-mobile-url.ps1 [-GatePort 3088] [-Token abc]

param([int]$GatePort = 3088, [string]$Token = '', [string]$Password = '')

# 挑手机能到的真实 LAN IP: 物理网卡(排除 VMware/Tailscale/Mihomo/WSL/蓝牙等虚拟口), 优先默认路由所在接口
$virtual = 'vEthernet|VMware|VirtualBox|Tailscale|Mihomo|Loopback|蓝牙|Bluetooth|WSL|Hyper-V|本地连接'
$defIf = (Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue |
    Sort-Object RouteMetric | Select-Object -First 1 -ExpandProperty InterfaceIndex)
$cands = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -match '^(192\.168\.|10\.|172\.(1[6-9]|2[0-9]|3[01])\.)' -and $_.InterfaceAlias -notmatch $virtual }
$ip = ($cands | Where-Object { $_.InterfaceIndex -eq $defIf } | Select-Object -First 1 -ExpandProperty IPAddress)
if (-not $ip) { $ip = ($cands | Select-Object -First 1 -ExpandProperty IPAddress) }
if (-not $ip) { Write-Host "FAIL: 没找到可用 LAN IP"; exit 1 }

$url = "http://${ip}:$GatePort/" + $(if ($Token) { "?token=$Token" } elseif ($Password) { "?pw=$Password" } else { '' })
Write-Host ""
Write-Host "手机与电脑同一局域网时, 在 App 里输入:"
Write-Host "  $url" -ForegroundColor Cyan
try { Set-Clipboard $url; Write-Host "(已复制到剪贴板)" } catch { Write-Host "(剪贴板不可用, 手动复制上面的地址)" }
Write-Host ""
Write-Host "lan-gate(默认 LAN_GATE_AUTO=first): 第一台设备即配对,地址不带任何参数即可;" -ForegroundColor DarkGray
Write-Host "  若走审批页: 电脑开 http://127.0.0.1:$GatePort/lan-gate/admin 批准;设了 LAN_GATE_PASSWORD 则输密码或用 -Password 生成 ?pw= 票据链接。" -ForegroundColor DarkGray
Write-Host "dsh web 直连/桥 token 失效时: 用 -Token 重新取地址(token 每次启动轮换)。" -ForegroundColor DarkGray
