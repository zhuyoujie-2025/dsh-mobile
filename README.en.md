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

The app scans common entry ports on your subnet (3088 lan-gate default →
3082 dsh-bridge official default / 3083-3084 bumped → 3089-3094 community
range) and stores the full address (including `?token=`) in history.

**Recommended path = lan-gate gateway** (no token, device approval, rate limit):

```powershell
dsh plugin add https://github.com/Bernardxu123/dsh-mobile-gate   # plugin (upstream repo, 0.0.0.0:3088)
node gate/lan-gate-server.cjs           # standalone from this repo, 0.0.0.0:3088 -> 127.0.0.1:3080
```

Then on the PC run `powershell -File tools/get-mobile-url.ps1` to copy
`http://<lan-ip>:3088/`; enter it in the app → phone shows "waiting for approval"
→ open `http://127.0.0.1:3088/lan-gate/admin` on the PC and approve → DSH loads.

**Fallback = official `dsh-bridge` preset plugin** (`@wenbin_wb/dsh-bridge`,
bundled with the desktop app: LAN QR / Cloudflare tunnel / IM bots). Its LAN
proxy defaults to **3082** (bumps when occupied). The bridge has its own QR
token / password gate and injects the loopback session cookie itself — **the
phone does NOT need DSH's native `?token=`**; paste the token link shown in the
bridge console into the app.

## Behavior highlights (v1.5)

- **Discovery**: signature scan covers lan-gate page / `dsh web authentication`
  401 / `DeepSeek Harness`/`dsh-bridge` bridge pages.
- **Stable connection**: built-in loopback TCP forwarder → DSH frontend trusts
  host-persisted settings; cookies survive IP changes. Main-frame failure
  triggers a subnet rescan — no black screen.
- **Auth**: lan-gate is approval-based (no token); dsh web / dsh-bridge need a
  one-time `?token=` that mints a 30-day cookie; 401 auto re-auths.
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
| 3089-3094 | community range: gate overflow, multi-instance, APK feed on 3093 | local convention |
| 3105-3108 | lan-gate EADDRINUSE tail | lan-gate |

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
