// lan-gate.mjs — Cordis plugin entry for the standalone lan-gate server.
// Spawns ./lan-gate-server.cjs as an isolated child via the subprocess service.
//
// Zero-touch extras vs upstream dsh-mobile-gate:
//  - forwards LAN_GATE_AUTO / LAN_GATE_PASSWORD / LAN_GATE_CREDENTIALS from
//    plugin config (or env) into the child
//  - the child inherits DSH_HOME, so upstream session injection
//    (minted dsh-auth-* cookie) works with zero setup whenever the host
//    instance's .credentials.yaml is readable
//
// Entry shape mirrors upstream: name + inject + apply(ctx), server stays a
// child process — never import the server into the DSH process.
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

export const name = 'dsh-mobile'
export const inject = ['subprocess']

const here = dirname(fileURLToPath(import.meta.url))
const serverFile = join(here, 'lan-gate-server.cjs')

// 转发目标默认跟随本内核实际 --port(官方桌面端端口是动态的,固化 env 会指错实例)。
// 优先级: config.targetPort > 本进程 --port argv > LAN_GATE_TARGET_PORT > 3080
// 监听口: config.listenPort > LAN_GATE_PORT > 3088(EADDRINUSE 顺延 +1..+20)。
function envNumber(key) {
  const n = Number(process.env[key])
  return Number.isFinite(n) && n > 0 ? n : undefined
}

function ownKernelPort() {
  const argv = process.argv || []
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    if (a === '--port' || a === '-p') {
      const n = Number(argv[i + 1])
      if (Number.isFinite(n) && n > 0) return n
    }
    const m = /^--port=(\d+)$/.exec(a)
    if (m) return Number(m[1])
  }
  return undefined
}

export function apply(ctx) {
  const timer = ctx.get('timer')
  // ctx.config requires a 'config' inject declaration; use the non-throwing
  // ctx.get() so the plugin still loads when no config service is provided.
  let cfg = {}
  try {
    const c = ctx.get('config')
    cfg = (c && typeof c === 'object') ? c : {}
  } catch (e) { /* config not injected */ }
  const targetPort = cfg.targetPort ?? ownKernelPort() ?? envNumber('LAN_GATE_TARGET_PORT')
  const listenPort = cfg.listenPort ?? envNumber('LAN_GATE_PORT')
  const listenHost = cfg.listenHost ?? process.env.LAN_GATE_HOST
  const autoMode = cfg.auto ?? process.env.LAN_GATE_AUTO
  const password = cfg.password ?? process.env.LAN_GATE_PASSWORD
  const credentials = cfg.credentials ?? process.env.LAN_GATE_CREDENTIALS
  if (targetPort !== undefined) process.env.LAN_GATE_TARGET_PORT = String(targetPort)
  if (listenPort !== undefined) process.env.LAN_GATE_PORT = String(listenPort)
  if (listenHost !== undefined) process.env.LAN_GATE_HOST = String(listenHost)
  if (autoMode !== undefined) process.env.LAN_GATE_AUTO = String(autoMode)
  if (password !== undefined) process.env.LAN_GATE_PASSWORD = String(password)
  if (credentials !== undefined) process.env.LAN_GATE_CREDENTIALS = String(credentials)
  let handle = null

  const start = async () => {
    try {
      const nodePath = await ctx.subprocess.resolveExecutable('node')
      handle = ctx.subprocess.spawn({
        argv: [nodePath, serverFile],
        cwd: here,
        stdio: {
          stdin: 'ignore',
          stdout: { maxBytes: 131072 },
          stderr: { maxBytes: 131072 },
        },
        graceMs: 3000,
      })
      handle.done.then((outcome) => {
        console.log(`[dsh-mobile] gate exited code=${outcome.exitCode} signal=${outcome.signal}`)
      }).catch((err) => {
        console.error(`[dsh-mobile] spawn failed: ${String(err && err.message || err)}`)
      })
      if (timer) {
        timer.timeout(() => {
          const r = handle && handle.collected && handle.collected.stdout
          if (r) {
            const read = r.readFrom(0)
            if (read && read.text) console.log(`[dsh-mobile] ${read.text.trim()}`)
          }
          const e = handle && handle.collected && handle.collected.stderr
          if (e) {
            const eread = e.readFrom(0)
            if (eread && eread.text) console.error(`[dsh-mobile] stderr: ${eread.text.trim()}`)
          }
        }, 1500)
      }
    } catch (err) {
      console.error(`[dsh-mobile] ${String(err && err.message || err)}`)
    }
  }

  start()

  ctx.effect(() => {
    return () => {
      if (handle) {
        try { handle.terminate() } catch (e) { /* ignore */ }
      }
    }
  })
}
