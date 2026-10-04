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

App 会扫本网段常见入口端口(3088 lan-gate 插件/独立网关默认口及其顺延段
3089-3108 → 3082 dsh-bridge 官方默认/3083-3084 顺延),命中后把整段地址
(含 `?token=`)存进历史。

**推荐链路 = lan-gate 网关(零操作:不设密码、不用 token、不用审批页)**:

```powershell
# 电脑端任选其一,一条命令完事:
dsh plugin add https://github.com/zhuyoujie-2025/dsh-mobile       # A. 本仓即插件(推荐,默认 0.0.0.0:3088)
dsh plugin add https://github.com/Bernardxu123/dsh-mobile-gate    # A'. 上游独立插件仓(等价替代)
node gate/lan-gate-server.cjs          # B. 本仓独立进程,默认 0.0.0.0:3088 → 127.0.0.1:3080
# 或 Windows 一键: tools\start-lan-gate.ps1(自动定位 .credentials.yaml 启用会话注入)
```

手机端:**装 APK → 打开 → 自动搜索 → 直接进 DSH**,
看到的就是桌面端已登录的同一个实例(账号/会话/余额一致)——不碰任何门。

为什么不用输任何东西:
- **会话注入**:独立网关给每个转发请求(HTTP+WebSocket)现铸上游会话
  cookie——与官方 dsh-bridge 相同的注入算法,手机端永远不出现 401 token 页。
  **凭据自动探测**(网关侧 v1.5.3 起):启动时枚举本机全部 DSH home(Windows
  `~/.dsh`、桌面端 `dsh-desktop-home`、WSL 各发行版 root/home 的 `.dsh`)
  并逐一实射验证,自动选中能过上游的那套——DSH 跑在 WSL 里也零配置。
  仍可用 `LAN_GATE_CREDENTIALS=<路径;路径>` 指定候选(仅作首选,照样验证)。
  ⚠️ DSH 装在 WSL2 里时,装进该实例的插件网关只在 loopback(LAN 够不到)——
  此时用 Windows 侧独立网关即可(`tools\start-lan-gate.ps1 -TargetPort <WSL口>`)。
- **自动配对**(`LAN_GATE_AUTO=first`,默认):第一台接入的外部设备即配对
  (TOFU),之后该设备 cookie 直连;第二台起回到审批页。
  `all`=信任整个局域网(设备换 IP 也永远免操作,仅家用可信 Wi-Fi);
  `off`=恢复严格审批;`LAN_GATE_PASSWORD=<pwd>` 额外开密码登录页。
- 凭据文件不可读时静默退化:手机看到 DSH 原生 401 token 页,老流程仍可用。

**备选链路 = 官方 dsh-bridge 预设插件**(`@wenbin_wb/dsh-bridge`,桌面端内置,
LAN 二维码/Cloudflare 隧道/IM bot)。桥默认 LAN 代理口 **3082**(被占顺延);
它自带二维码 token/访问密码门禁并同样注入回环会话 cookie——在 App 里填
桥控制台给出的带 token 链接即可。

## 手机端使用

1. **装 APK**: 把 `dist/DSHMobile.apk` 传到手机安装(允许未知来源)。
2. **放行防火墙**(仅独立网关需要): 管理员运行 `tools/allow-gate-firewall.ps1`
   (放行 TCP 3088-3108 入站,覆盖 lan-gate 顺延全段;若你的网关用其他口自行放行)。
3. **App 自动搜索** → 直接进 DSH(默认首台设备自动配对+上游会话注入,
   无密码/无 token/无审批)。会话 30 天 cookie,主本重启不失效。
   手动输地址可用 `tools/get-mobile-url.ps1` 生成。

## 行为要点(v1.5)

- **发现**: 设置页「自动搜索网关」= 扫 /24 网段 × 端口优先级表;签名认
  lan-gate 页 / `dsh web authentication` 401 / `DeepSeek Harness`/`dsh-bridge` 桥页。
- **连接稳定**: 内置 TCP 转发器(WebView 恒打 `127.0.0.1:<LPORT>`)→ DSH 前端
  判 loopback 受信任,设置走 host 持久化、cookie 不随电脑换 IP 失效。
  主框架加载失败自动重扫网段找回入口,不黑屏。
- **认证**: lan-gate 两道门——设备准入(`LAN_GATE_AUTO`:first 首台自动配对/
  all 全放行/off 审批,显式 deny 优先;`LAN_GATE_PASSWORD` 可选密码登录页,
  同 IP 8 错锁 10 分钟、timingSafeEqual)和上游会话注入(自动铸 DSH 回环
  会话 cookie,手机不碰 `?token=`);dsh web 直连 `?token=` 一次换 30 天
  cookie;dsh-bridge 走桥自有 token/密码门禁。
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
| **3082** | **dsh-bridge LAN 代理官方默认口**(被占顺延 3083/3084) | 官方预设插件 |
| 3089-3108 | lan-gate EADDRINUSE 顺延段(整段被 App 扫口覆盖;3093=APK 订阅源约定) | lan-gate 自择 |

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
- v1.5.1: 端口表补 **3082**(dsh-bridge 官方默认代理口,v1.5 漏列,干净官方
  环境下自动搜索会漏桥);文档修正插件安装命令(git URL)与桥自有门禁模型。
- v1.5.2: 扫口表补全 lan-gate 顺延中段 **3093-3104**——实测插件形态在
  3088-3097 全被占用时落到 3098,v1.5.1 只兜 3105-3108 尾段会漏检;
  防火墙脚本同步放宽到 3088-3108;README 接入本仓即插件
  (`dsh plugin add <本仓>`)。
- **2026-10-04 网关侧修复**(不动 APK):会话注入的密钥取自错误 home
  (Windows 独立网关打 WSL 主本时注入被 401——手机被弹回"填 token"页)。
  现启动时自动枚举全部候选 home 并逐一实射 `GET /` 验证取真钥;
  `start-lan-gate.ps1` 端口预检只认通配监听,不再被 WSL loopback
  转发器的同口占位误判。
