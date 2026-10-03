# DSH Mobile — 连接架构

本文档给「想让手机/平板/其他端接 DSH」的人看:协议事实、端口约定、探测签名、
以及把本 App 思路迁移到别的客户端(如 iOS、桌面 PWA)时需要复刻的点。

## 1. 拓扑

```
┌─────────┐   LAN/Wi-Fi    ┌──────────────────────────┐
│ Android │ ─────────────→ │ 入口层(三选一)           │
│  App    │  http://PC-IP  │  A. lan-gate 网关         │
│ (WebView│  :<入口端口>    │  B. dsh-bridge 代理       │
│  +转发器)│                │  C. dsh web 直接绑 0.0.0.0│
└─────────┘                └──────────┬───────────────┘
                                      │ 127.0.0.1 转发
                              ┌───────▼────────┐
                              │ DSH Web UI     │
                              │ 127.0.0.1:3080 │
                              │ (或任意端口)    │
                              └────────────────┘
```

DSH Web UI 默认只绑 loopback。手机要进来,必须有人在 0.0.0.0 上转发——
这就是「入口层」的唯一职责。三种入口 App 都能认:

| 入口 | 默认口 | 认证 | 签名(App 探测) | 备注 |
|---|---|---|---|---|
| lan-gate(上游插件 `dsh plugin add https://github.com/Bernardxu123/dsh-mobile-gate`,或本仓 `gate/lan-gate-server.cjs`) | 3088(顺延+1..+20) | 设备准入 `LAN_GATE_AUTO`(first 首台自动配对/all/off)+ 上游会话注入;可选 `LAN_GATE_PASSWORD` 登录页 | 页含 `lan-gate`/`/?t=` | **推荐**:审批页 `/lan-gate/admin`,限流 120 req/min/IP;注入让手机永不碰 `?token=` |
| dsh-bridge(`@wenbin_wb/dsh-bridge`,桌面版官方预设插件) | **3082**(被占顺延 3083/3084) | 桥自有门禁:二维码 256-bit token / 访问密码;转发时自动注入回环会话 cookie | 页含 `DeepSeek Harness`/`dsh-bridge` | 手机用桥控制台给的带 token 链接;另有 Cloudflare 隧道、IM bot |
| dsh web 直接绑 0.0.0.0 | 3080 或自定义 | `?token=` → cookie | 401 页含 `dsh web authentication` | 无网关功能,直连内核 |

## 2. 认证模型

- **lan-gate**:两道独立的门,本仓独立版都让手机可以零操作穿过——
  a) **设备准入**(`LAN_GATE_AUTO`,默认 `first`):第一台接入的外部设备
  自动配对放行(TOFU,写入 decisions 并发 `lg_token` cookie,之后同设备
  直连);第二台起回等待/登录页。`all`=同 LAN 设备全部自动放行(可信
  家用网络,设备 DHCP 换 IP 也免操作);`off`=恢复严格审批——手机挂起在
  「等待批准」页,电脑端 `http://127.0.0.1:<口>/lan-gate/admin`(loopback
  免鉴权)批准。显式 deny 的 IP 任何模式都不放行。
  b) **共享口令**(`LAN_GATE_PASSWORD` 非空,可选叠加):未知设备的页面变
  登录页,输对密码即批准(`POST /lan-gate/login`,timingSafeEqual,
  同 IP 8 错锁 10 分钟);入口地址带 `?pw=<密码>` 可票据直通。
  c) **上游会话注入**(凭据可读即自动,独立版特性):网关读
  `<DSH_HOME>/.credentials.yaml`(或 `LAN_GATE_CREDENTIALS` 指定)里
  `client-connection/browser-session` 的签名密钥,按 DSH 官方格式
  (`dsh-auth-<b64url sha256(authority)>` = `v1.<b64 payload>.<b64 HMAC-SHA256>`,
  算法同 @wenbin_wb/dsh-bridge 的 dsh-native-cookie)给**每个转发请求
  (HTTP 与 WebSocket 握手)**现铸回环会话 cookie——手机端因此永不触碰
  DSH 原生 `?token=` 门,直接呈现桌面端已登录的实例会话(同一账号)。
  凭据缺失时静默退化:手机落到原生 401 token 页,经典流程仍可用。
  注意:注入只对「已过设备准入的转发流量」生效,不削弱准入层本身。
- **token 系**(dsh web 直连):入口地址形如 `http://IP:PORT/?token=XXXX`。
  token 是一次性引导凭证——拿到后立刻换 30 天会话 cookie,**cookie 跨服务端
  重启仍有效**;token 本身每次 `dsh web` 启动轮换。App 收到 401 会自动用保存的
  `?token=` 地址重签一次,只有 token 也失效(服务端重启 + 旧 cookie 过期)才回设置页。
- **dsh-bridge 自有门禁**:桥插件带独立的二维码 256-bit token 与可选访问密码,
  并在转发时自动注入合法的 DSH 回环会话 cookie——**经桥访问的手机完全不接触
  DSH 原生 `?token=`**。App 端只要把桥控制台展示的带 token 链接填进去即可;
  桥 token 可由管理员在控制台一键轮换。

## 3. App 侧三个非显而易见的做法

1. **loopback 转发器**。WebView 不直连 `http://PC-IP:PORT`,而是 App 起一个
   本地 TCP 转发(`127.0.0.1:3090+` → 入口),WebView 打 `http://127.0.0.1:<L>`。
   原因:DSH 前端 `isLoopbackHostname(location.hostname)` 判真后设置走 host
   持久化(否则 memory 模式每次重置);cookie/localStorage 固定在 127.0.0.1 域,
   电脑换 IP/换入口不影响会话。
2. **断线自愈**。主框架 `onReceivedError` → 后台重扫网段(同发现逻辑),
   命中即改指转发器重载原页;扫不到或连续失败才回设置页。
   `onRenderProcessGone` 自动重载一次。
3. **双通道更新**。启动时先查 `http://<入口主机>:3093/dshmobile/version.json`
   (同网段可选源,适合内测频道),不通再查 GitHub
   `releases/latest/download/version.json`。version.json 格式:
   `{"versionCode":N,"versionName":"x.y","size":B,"sha256":"...","url":"DSHMobile.apk"}`。
   `url` 相对订阅源 base 解析。

## 4. 发现协议(复刻清单)

```
对每个候选 host ∈ 本网段 /24, port ∈ [已存口, 3088, 3082, 3083, 3084, 3089..3094, 3105..3108]:
    TCP connect (700ms) → 发 "GET / HTTP/1.0" (1.2s 读窗) →
    响应头 3KB 内含任一签名即命中:
        "lan-gate" | "/?t=" | "dsh web authentication" |
        "DeepSeek Harness" | "dsh-bridge" | "DSH"
按端口优先级择优返回第一个命中。
```

注意 3088 顺延:lan-gate `EADDRINUSE` 会 +1 重试至 +20,所以自管区间要预留。

## 5. 给其他端的迁移点

- iOS:`WKWebView` 同样需要 loopback 转发——可以用 `NWListener` 做本地口,
  或直接 `http://PC-IP` 但接受 memory 设置(不推荐)。
- 桌面/浏览器:直接开 `http://PC-IP:<入口>` 即可,没有持久化坑;
  lan-gate 审批流程对任何客户端一致。
- 订阅源:`apk-feed-server.cjs` 只是把 `dist/` 目录以 `/dshmobile/` 前缀 HTTP
  暴露(带 `Cache-Control: no-store`),任何静态文件服务器都能替代。

## 6. 安全边界

- lan-gate 转发一律打到 `127.0.0.1:<目标>`——它只解决「0.0.0.0 监听」,
  不解决「谁可以连」。审批+限流是它的防线;别把网关口暴露到公网。
  `LAN_GATE_PASSWORD` 只在环境变量里(不写状态文件/日志),强度自担——
  它是 LAN 场景便利项,公网场景请走桥隧道而非密码+端口映射。
- `?token=` 等价于登录态,明文走 HTTP——只在可信 LAN 用。要出公网走
  dsh-bridge 的 Cloudflare 隧道(自带 TLS)而不是端口映射。
- `lan-gate-state.json` 存审批列表与 adminKey,位于 `DSH_HOME`(默认 `~/.dsh`,
  本项目 tools 脚本设成 `~/.dsh/gates/<口>/`);泄露=网关管理权,勿同步勿外传。
