# DSH Mobile — Android shell for DSH (DeepSeek Harness)

中文: [README.md](README.md)

A pure Android-framework WebView APK (~60KB) that wraps the DSH Web UI into a
standalone app. **Generic by design**: no machine-specific address is baked in —
the phone auto-discovers DSH entry points on the LAN, and updates come from this
repo's GitHub Releases (public fallback) or an optional same-subnet feed.

```
phone app → [lan-gate | dsh-bridge | direct dsh web] → DSH Web UI on the PC
```

## One-minute hookup

| side | do |
|---|---|
| PC | Install official DeepSeek Harness Desktop, or run `dsh web` on any port |
| phone | Install `DSHMobile.apk`, join the same Wi-Fi, tap "auto-search gateway" in setup |

The app scans common entry ports on your subnet (3088 lan-gate default plus
its full overflow range 3089-3108 → 3082 dsh-bridge official default /
3083-3084 bumped) and stores the full address (including `?token=`) in
history.

**Recommended path = lan-gate gateway** (zero-touch: no password, no token,
no approval page):

```powershell
dsh plugin add https://github.com/zhuyoujie-2025/dsh-mobile       # this repo IS the plugin (recommended)
dsh plugin add https://github.com/Bernardxu123/dsh-mobile-gate    # upstream plugin repo (equivalent)
node gate/lan-gate-server.cjs           # standalone from this repo, 0.0.0.0:3088 -> 127.0.0.1:3080
# or on Windows: tools\start-lan-gate.ps1  (auto-locates .credentials.yaml)
```

Phone: **install APK → open → auto-search → DSH loads**, already logged in as
the same desktop instance session (account/balance included) — no gate at all.

Why nothing needs typing:
- **Session injection**: the standalone gate mints a fresh upstream session
  cookie into every forwarded request (HTTP + WebSocket) — the same algorithm
  the official dsh-bridge uses. **Credential auto-probe** (v1.5.2+): at
  startup the gate enumerates every local DSH home (`~/.dsh`,
  `dsh-desktop-home`, every WSL distro's root/home `.dsh` via `wsl -l -q`)
  and test-fires each secret against the target — the key that survives wins,
  so a WSL-hosted DSH needs zero config. `LAN_GATE_CREDENTIALS=<path;path>`
  still works but is merely the first candidate, not a forced pick.
  ⚠️ When DSH itself runs inside WSL2, the in-WSL plugin gate is reachable
  only via loopback — use the Windows-side standalone gate instead
  (`tools\start-lan-gate.ps1 -TargetPort <wsl-port>`).
- **Auto-pairing** (`LAN_GATE_AUTO=first`, default): the first foreign device
  to connect pairs itself (TOFU); subsequent devices fall back to the approval
  page. `all` trusts the whole LAN (survives DHCP renumbering — trusted home
  Wi-Fi only); `off` restores strict approval; `LAN_GATE_PASSWORD=<pwd>` adds
  a password login page for devices beyond the first.
- Missing credentials degrade silently: the phone sees DSH's native 401 token
  page and the classic flow still works.

**Fallback = official `dsh-bridge` preset plugin** (`@wenbin_wb/dsh-bridge`,
bundled with the desktop app: LAN QR / Cloudflare tunnel / IM bots). Its LAN
proxy defaults to **3082** (bumps when occupied). The bridge has its own QR
token / password gate and also injects the loopback session cookie — paste the
token link shown in the bridge console into the app.

## Behavior highlights (v1.5)

- **Discovery**: signature scan covers lan-gate page / `dsh web authentication`
  401 / `DeepSeek Harness`/`dsh-bridge` bridge pages.
- **Stable connection**: built-in loopback TCP forwarder → DSH frontend trusts
  host-persisted settings; cookies survive IP changes. Main-frame failure
  triggers a subnet rescan — no black screen.
- **Auth**: lan-gate has two gates, both avoidable — device admission
  (`LAN_GATE_AUTO`: `first` auto-pairs the first foreign device / `all` /
  `off` approval-only, explicit deny always wins; optional
  `LAN_GATE_PASSWORD` login page, 8 wrong tries/IP locks 10 min,
  timingSafeEqual) and upstream session injection (mints the DSH loopback
  session cookie itself so the phone never meets `?token=`); `dsh web` direct
  needs a one-time `?token=` minting a 30-day cookie; dsh-bridge uses its own
  token/password gate.
- **Updates**: at boot the app tries `http://<entry-host>:3093/dshmobile/version.json`
  (optional LAN feed), then this repo's `releases/latest/download/version.json`.
  A newer `versionCode` prompts a DownloadManager install.
- **Privacy**: no network calls except the two update channels; entry addresses
  stay in local SharedPreferences.

## Port conventions (all configurable)

| port | purpose | owned by |
|---|---|---|
| 3080 | dsh web / desktop kernel (official default) | DSH |
| **3088** | **lan-gate default** (plugin or standalone `gate/lan-gate-server.cjs`) | this project |
| **3082** | **dsh-bridge LAN proxy official default** (bumps to 3083/3084) | official preset plugin |
| 3089-3108 | lan-gate EADDRINUSE overflow range — all scanned by the app; 3093 = APK feed convention | lan-gate |

## Rebuild

```bash
bash tools/fetch-android-tools.sh        # one-time: SDK-mini (android.jar + build-tools + r8.jar)
py -3 tools/make-icon.py                 # only when the icon changes
bash build.sh                            # -> dist/DSHMobile.apk + dist/version.json
```

`build.sh` has no hard-coded paths: SDK dir via `ANDROID_SDK_MINI` (default
`$HOME/android-sdk-mini`), JDK 8+ via `JAVA_HOME`/`PATH`/fallback `/opt/jdk25`.
Default signing uses the in-repo debug keystore (`dshmobile.keystore`, password
`dshmobile`) — releases should be re-signed with a private key or override
`DSHMOBILE_KEYSTORE`/`DSHMOBILE_KS_PASS`.

## Files

- `app/AndroidManifest.xml` + `app/src/.../MainActivity.java` — all sources (single Activity, no androidx)
- `build.sh` — build script (aapt2 compile/link → javac → d8 → zipalign → apksigner)
- `gate/lan-gate-server.cjs` — standalone gateway (zero deps, `node` directly;
  same code as the `dsh-mobile-gate` plugin)
- `tools/` — fetch-android-tools.sh / make-icon.py / start-lan-gate.ps1 /
  start-apk-feed-3093.ps1 / allow-gate-firewall.ps1 / get-mobile-url.ps1
- `docs/ARCHITECTURE.md` — connection architecture and protocol notes
