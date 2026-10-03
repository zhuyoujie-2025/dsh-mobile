# start-gate-3090.ps1 — 本机约定: 0.0.0.0:3090 -> 127.0.0.1:3085 (WSL 主本)
# 由 ps-watchdog-mobile-stack.ps1 按此路径调用;实现已抽到 start-lan-gate.ps1。
& "$PSScriptRoot\start-lan-gate.ps1" -Port 3090 -TargetPort 3085
