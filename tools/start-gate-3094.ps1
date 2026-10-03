# start-gate-3094.ps1 — 本机约定: 0.0.0.0:3094 -> 127.0.0.1:3081 (Windows 副本)
# 由 ps-watchdog-mobile-stack.ps1 按此路径调用;实现已抽到 start-lan-gate.ps1。
& "$PSScriptRoot\start-lan-gate.ps1" -Port 3094 -TargetPort 3081
