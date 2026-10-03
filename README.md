# DSH Mobile — DSH (DeepSeek Harness) 手机壳

English: [README.en.md](README.en.md)

纯 Android Framework 的轻量 WebView APK(~60KB),把手机浏览器里的 DSH Web UI 装进独立 App。
**通用设计**:不写死任何机器地址——手机扫局域网自动发现 DSH 入口,更新源走本仓库
GitHub Releases(公网兜底)或电脑端同网段订阅源。

```
手机 App → [lan-gate 网关 | dsh-bridge | dsh web 直连] → 电脑上的 DSH Web UI
```

## 一分钟接入(通用路径)

| 角色 | 做什么 |
|---|---|
| 电脑端 | 装官方 DeepSeek Harness 桌面版,或跑 `dsh web`(任一口) |
| 手机端 | 装 `DSHMobile.apk`,与电脑连同一 Wi-Fi,App 设置页点「自动搜索网关」 |

App 会扫本网段常见入口端口(3088 lan-gate 插件/独立网关默认口 → 3083/3084
dsh-bridge → 3089-3094 自管区间),命中后把整段地址(含 `?token=`)存进历史。

**推荐链路 = lan-gate 插件**(免 token、带设备审批页、限流防刷):

```powershell
# 电脑端任选其一:
dsh plugin add dsh-mobile-gate          # A. 官方桥/内核内嵌,默认 0.0.0.0:3088
node gate/lan-gate-server.cjs           # B. 独立进程,默认 0.0.0.0:3088 → 127.0.0.1:3080
```

然后电脑跑 `powershell -File tools/get-mobile-url.ps1`(自动挑真实 LAN IP 并复制
`http://<ip>:3088/`),在 App 输入 → 手机停在「等待批准」→ 电脑浏览器开
`http://127.0.0.1:3088/lan-gate/admin` 批准(选「手机」)→ 自动进 DSH。

**备选链路 = 官方 dsh-bridge 预设插件**(桌面端内置,LAN 二维码/Cloudflare
隧道/IM bot)。它默认暴露认证页——App 探测得到但需 `?token=`:
`tools/get-mobile-url.ps1 -GatePort <桥端口> -Token <启动日志里的 token>`。

## 手机端使用

1. **装 APK**: 把 `dist/DSHMobile.apk` 传到手机安装(允许未知来源)。
2. **放行防火墙**(仅独立网关需要): 管理员运行 `tools/allow-gate-firewall.ps1`
   (放行 TCP 3088-3093 入站;若你的网关用其他口自行放行)。
3. **取地址**: `powershell -File tools/get-mobile-url.ps1` → 复制 `http://<电脑IP>:3088/`。
4. **App 输入 → 批准设备** → 进 DSH。会话 30 天 cookie,主本重启不失效。

## 行为要点(v1.5)

- **发现**: 设置页「自动搜索网关」= 扫 /24 网段 × 端口优先级表;签名认
  lan-gate 页 / `dsh web authentication` 401 / `DeepSeek Harness`/`dsh-bridge` 桥页。
- **连接稳定**: 内置 TCP 转发器(WebView 恒打 `127.0.0.1:<LPORT>`)→ DSH 前端
  判 loopback 受信任,设置走 host 持久化、cookie 不随电脑换 IP 失效。
  主框架加载失败自动重扫网段找回入口,不黑屏。
- **认证**: lan-gate 免 token(审批制);dsh web / dsh-bridge 的 `?token=` 一次
  换 30 天 cookie,401 自动用存的地址重签。
- **更新**: App 启动先查同网段 `http://<入口主机>:3093/dshmobile/version.json`
  (可选,`tools/start-apk-feed-3093.ps1` 拉起);不通再查本仓库
  `releases/latest/download/version.json` → 发现新 versionCode 弹窗 +
  DownloadManager 下载 + 拉起系统安装器。
- **隐私**: 除两处更新源外无任何网络出口;入口地址只存本机 SharedPreferences。

## 端口约定(建议,全部可改)

| 端口 | 用途 | 谁起的 |
|---|---|---|
| 3080 | dsh web / 桌面内核(官方默认) | DSH 本体 |
| **3088** | **lan-gate 默认口**(插件或独立 `gate/lan-gate-server.cjs`) | 本项目/插件 |
| 3083-3084 | dsh-bridge LAN 代理常见口 | 官方预设插件 |
| 3089-3094 | 自管区间:网关顺延、多实例、APK 订阅源 3093 | 本机约定 |
| 3105-3108 | lan-gate EADDRINUSE 顺延尾段 | lan-gate 自择 |

## 重新构建

```bash
# 一次: 下载 SDK-mini(android.jar API35 + build-tools r34 + r8.jar)
bash tools/fetch-android-tools.sh        # 或 ANDROID_SDK_MINI=... bash ...
py -3 tools/make-icon.py                 # 图标有改动时
bash build.sh                            # 产物 dist/DSHMobile.apk + dist/version.json
```

`build.sh` 无外部依赖路径——SDK 位置走 `ANDROID_SDK_MINI`(默认 `$HOME/android-sdk-mini`),
JDK 8+ 走 `JAVA_HOME`/`PATH`/常见 `/opt/jdk25`。签名默认用项目内调试 keystore
(`dshmobile.keystore`,口令 `dshmobile`);发布版请用私有 release key 重签或换
`DSHMOBILE_KEYSTORE`/`DSHMOBILE_KS_PASS`。

## 文件

- `app/AndroidManifest.xml` + `app/src/.../MainActivity.java` — 全部源码(单 Activity, 无 androidx)
- `build.sh` — 构建脚本(aapt2 compile/link → javac → d8 → zipalign → apksigner)
- `gate/lan-gate-server.cjs` — 独立网关(零依赖,`node` 直接跑;与 `dsh-mobile-gate` 插件同源)
- `tools/` — fetch-android-tools.sh / make-icon.py / start-lan-gate.ps1 /
  start-apk-feed-3093.ps1 / allow-gate-firewall.ps1 / get-mobile-url.ps1
- `docs/ARCHITECTURE.md` — 连接架构与协议细节(给想接其他端的人)

## 事故记录(历史)

- **2026-10-02 手机黑屏**:lan-gate 把上游 gzip 响应当 UTF-8 解码注入(已修:
  转发强制 `accept-encoding: identity`,回写丢 `content-length/transfer-encoding/
  content-encoding`);叠加插件 `settingsScope` 缺席致 web boot 卡死(已修容错)。
  App v1.2 起加载失败回设置页带原因、渲染崩溃自愈。
- v1.3: `Theme.DeviceDefault.DayNight` + `FORCE_DARK_OFF`,网页"跟随系统"真跟随;
  返回键改 `moveTaskToBack` 保会话。
- v1.4: 内置 loopback 转发器;补 `REQUEST_INSTALL_PACKAGES` 自动拉起安装器。
- v1.5: 开源通用化——探测签名覆盖官方 bridge/内核页、端口表去本机化、
  更新源公网兜底、setup 文案与迁移逻辑去机主特定值。
