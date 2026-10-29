# CpapMaskGuard / 面罩守护

> Open-source · Free · Not a medical device  
> 开源 · 免费 · 非医疗器械

---

## English

**CpapMaskGuard** is an Android app for **CPAP / sleep-apnea users** (especially moderate+ severity) who wear a mask at night. It uses the phone **rear camera + torch** to watch whether the mask is on or off. If the mask detaches, it alarms after **two** off frames in a row (about 8–16 seconds at the default interval) so you can put it back on.

**Detection:** the phone skips the cloud only for a clearly black, white, or badly blurred frame. A face is not required (a full-face mask often has no detectable eyes). Uncertain frames are sent. Ten cloud “mask off” answers in a row stop further uploads until you tap “mask is on”, or until the scene clearly changes for three frames (10 resumes per Shanghai **sleep day**, which rolls at noon, not midnight). After the resume quota is used up, Android keeps classifying on device and listening for snore. If there is no key or the cloud request fails, the offline fallback is still the on-device classifier (mask on / off). Two cloud failures in a row prefer that local path for five minutes.

**Open-source and free** for global users. **Android monitors in the background.** **iOS 0.3.9 monitors only in the foreground**: the screen stays on, a still is taken every 5–10 seconds, MiniMax-M3 classifies it, and a siren plays if the mask looks off. Locking the phone or leaving the app stops the camera. iOS cannot keep the camera running in the true background.

### Features

- Built for CPAP / sleep-apnea users who wear a mask overnight
- Rear camera + torch monitoring (bedside stand aimed at the pillow)
- Alarms after two consecutive mask-off frames (about 8–16 seconds)
- Primary: cloud MiniMax-M3; offline fallback: EfficientNet-Lite0 binary classifier
- Capture reference photos without / with the mask
- One-tap **Start Guard** / **Stop Guard**, status in plain language
- **In-app language option: Chinese / English**
- Optional snore cue as an assist signal
- Open-source, free worldwide. Android can keep watching in the background. iOS watches only while the app stays open and the screen stays on.

**Disclaimer:** Personal assistive tool only — not a medical device or diagnosis tool. Follow your clinician for sleep apnea care.

---

## 中文

**面罩守护（CpapMaskGuard）**面向夜间佩戴呼吸机面罩的 **CPAP / 睡眠呼吸暂停用户**（尤其中度及以上）。用手机**后置摄像头 + 手电筒**监测面罩是否还在脸上；一旦脱落，连续两帧都判脱落才大声提醒（默认间隔约 8 秒，大约 8–16 秒）。

**判定：**明确的全黑、全白或严重模糊才不上云；没检出人脸也会上云（全脸面罩经常检不出眼睛），拿不准同样上云（**MiniMax-M3**）。云端连续 10 次判脱落就停止上传。恢复以点「我戴好了」为主；画面明显变化并连续确认后也会自动恢复一次。每个上海睡眠日（中午 12:00 换日，不是午夜）最多 10 次。次数用尽后安卓继续本机判断并听鼾。断网或没有 Key 时用本机分类兜底。云端连续失败 2 次后，5 分钟内优先本机，大约每分钟才探一次。

**开源、全球免费**。**安卓版可以在后台监测。** **iOS 0.3.9 只在前台监测**：屏幕保持亮着，大约每 5–10 秒拍一张，交给 MiniMax-M3 看面罩，脱落就响铃。锁屏或离开 App，相机会停。iOS 做不到真正的后台常开相机。

### 功能

- 为夜间戴呼吸机面罩的睡眠呼吸暂停用户设计
- 后置摄像头 + 手电筒床头监测
- 连续两帧判面罩脱落才报警（大约 8–16 秒）
- 主路径：云端 MiniMax-M3；离线备用：EfficientNet-Lite0 二分类
- 先拍「没戴 / 戴着」基准照片
- 一键「开始守护 / 停止守护」，状态用人话显示
- **应用内语言可切换：中文 / English**
- 可选鼾声辅助信号
- 开源免费。安卓版可以在后台看面罩；iOS 只有亮着屏、停在应用里才会看。

**免责声明：**个人辅助用途，不是医疗器械，不能替代诊疗。请遵医嘱治疗睡眠呼吸暂停。

---

## How to use / 怎么用

1. Put the phone on a stand aimed at the pillow (rear camera).  
   手机架在床头，后摄对准枕头。
2. Choose rear camera + light mode in settings.  
   设置里选后摄和补光方式。
3. Capture several photos **without** the mask, then **with** the mask.  
   先拍几张没戴面罩，再拍戴着面罩。
4. Tap **Start Guard**, lie down with the mask on.  
   点「开始守护」，戴着面罩躺下。
5. Status should show mask OK; remove the mask and the alarm should sound after two consecutive off frames (about one capture interval apart).  
   状态应显示面罩正常；摘下面罩后，连续两帧都判脱落才响（默认约 8 秒一张）。

---

## Build / 编译

- Open in Android Studio (Windows / macOS / Linux).
- Copy `local.properties.example` → `local.properties`, set `sdk.dir`.
- Cloud calls go to the official relay `https://cpap.njxxzf.cn` with no MiniMax key in the repo. `minimax.api.key` in `local.properties` is optional and unused unless the relay is turned off.
- Build a debug APK and install on device.

用 Android Studio 打开工程；复制并填写 `local.properties`。云端走官方中转 `https://cpap.njxxzf.cn`，仓库里不要写 MiniMax Key。**不要提交** `local.properties`。

### iOS foreground monitor / 亮屏前台监测

Project / 工程：`ios/CpapMaskGuard/CpapMaskGuard.xcodeproj`  
Target device / 目标机：iPhone, including iPhone 17 Pro Max. Portrait only. Marketing version **0.4.2** (build 9). 监测和样张从视频流取帧，国行不再响快门声。

iOS **cannot** keep the camera on in the true background. The home screen says so. Lock the screen or switch apps and capture, the siren, and the fill light all stop. There is no「使用前检查本机」and no burn-in page.

iOS **不能**在真正的后台开着相机。主屏写明了这一点。锁屏或切到别的应用，拍照、铃声、补光都会停。没有「使用前检查本机」，也没有烤机页。

#### Start Guard / 开始守护怎么工作

1. Put the phone where the chosen camera can see the pillow. Front mode points the **screen** at the pillow (full white fill, brightness slider). Rear mode points the **rear camera** at the pillow and uses your own lamp; the torch is optional, off by default, and the slider calls `setTorchModeOn(level:)`.
2. Copy `ios/CpapMaskGuard/Config/Secrets.xcconfig.example` to `Secrets.xcconfig` in the same folder (gitignored). Set `MINIMAX_API_KEY` there. The app reads it from Info.plist at runtime. **Never commit a real key.** Without a key, frames are not uploaded and the cloud cannot classify them.
3. Tap **开始守护 / Start guard**. The app asks for the **camera** (required), then the **microphone** (local snore cue only, audio is not uploaded) and **notifications** (mask-off alert). Idle timer is disabled so the screen stays on while you remain in the app.
4. About every **8 seconds** (slider **5–10s**, same range as the Android non-low-end policy) it grabs a JPEG, shrinks the long edge to 640, and POSTs it to `https://api.minimax.cn/v1/chat/completions` as **MiniMax-M3** with thinking **disabled**, image **detail low**, temperature 0.1, and max 64 completion tokens. The prompt matches the Android client: side sleep, only the back of the head or a side of the face, or the headband / corrugated tube still on the face or bedside, is still **MASK_ON**.
5. The home card shows **capture time**, **result** (`面罩正常` / `面罩脱落` / `未检测到人` / `不确定`), and **latency** (judge / capture-to-judge / round). Two consecutive `MASK_OFF` frames start the siren. One `MASK_OFF` does not. `NO_PERSON` does not. The siren loops until you tap **停止铃声**; guarding continues, with a 2 second quiet period. **停止守护** stops the camera, the light, and the sound.
6. If the microphone is allowed, a local RMS / low-frequency / zero-cross heuristic can confirm snoring and alarm on its own (about 6s, or 12s when the camera just said the mask is on). Snore never blocks the camera. If the mic fails, capture continues.
7. The siren uses media playback, so the silent switch does not mute it, but a low side-button volume still does. The app cannot force the hardware volume. Raise it before sleep. Vibration and a notification are the backup.
8. Settings → 本机状态 / 拍几张戴着面罩的样子 / 查看日志 stay on the navigation stack (the 0.3.8 crash fix). Status is read-only plus a health score that **does not** stop the guard. Sample frames (latest 12 JPEG + JSON) live under Application Support `MaskFrames` and are not uploaded as files. The on-screen list is this launch only. The overnight file is `MaskFrames/night-events.jsonl` in that same folder (one JSON line per cycle, including failures and the circuit). It is not uploaded.

1. 把手机摆到能看见枕头的位置。前摄模式让**屏幕**朝向枕头（整屏白、亮度滑条）。后摄模式让**后摄**朝向枕头，默认用你自己的灯；手电筒可选，默认关，滑条走 `setTorchModeOn(level:)`。
2. 把 `ios/CpapMaskGuard/Config/Secrets.xcconfig.example` 复制成同目录 `Secrets.xcconfig`（已 gitignore），写上 `MINIMAX_API_KEY`。运行时从 Info.plist 读取。**不要把真 Key 提交进 git。** 没有 Key 时照片不会上传，云端也不能判定。
3. 点 **开始守护**。会先要**相机**（必须）。然后要**麦克风**（只在本机听鼾，声音不上传）和**通知**（脱落提醒）。守护期间关闭自动锁屏，但你必须留在这个应用里。
4. 默认大约每 **8 秒**一张（滑条 **5–10 秒**，与安卓非低端档相同）。JPEG 长边缩到 640，POST 到 `https://api.minimax.cn/v1/chat/completions`，模型 **MiniMax-M3**，思考关闭，图片 detail 为 low，temperature 0.1，最多 64 个 completion token。提示词与安卓客户端一致：侧睡、只见后脑勺或侧脸、头带或波纹管仍在脸上或床边，仍判 **MASK_ON**。
5. 主屏显示**拍摄时间**、**结果**（面罩正常 / 面罩脱落 / 未检测到人 / 不确定）和**耗时**（判定 / 拍到判定 / 整轮）。连续两帧 `MASK_OFF` 才响铃。单独一帧不响。`NO_PERSON` 不响。铃声要自己点 **停止铃声** 才停，守护继续，并安静约 2 秒。**停止守护**会关掉相机、补光和铃声。
6. 允许麦克风后，本机用响度、低频和过零率听鼾，确认打呼后可以单独响铃（相机没说「戴着」时约 6 秒，刚判成戴着时约 12 秒）。听鼾不挡住拍照。麦克风失败时仍然只看相机。
7. 铃声走媒体播放，静音拨片不会把它关掉，但侧边音量很低就会听不见。应用不能强行把系统音量拉满。睡前请把音量调大。震动和通知是辅助。
8. 设置里的本机状态、拍几张戴着面罩的样子、查看日志仍走导航栈（0.3.8 的闪退修复还在）。本机状态只读，外加一个**不会因此停守护**的健康分。样张最多留 12 张 JPEG 和 JSON，在 Application Support 的 `MaskFrames`，这些文件本身不上传。屏幕上的日志是这一次打开的内存。夜间记录是同一目录里的 `night-events.jsonl`（每一轮一行 JSON，失败和熔断也写），不上传。

Scheme：`CpapMaskGuard`。Bundle id：`app.cpapmaskguard`。签名已写在工程里：**Automatically manage signing**，Team **敏敏 史 (Personal Team)** `H45MTM24C4`。模拟器实际用 ad-hoc 签名，不需要真机 UDID。

这台 Mac 的 iOS 27.1 runtime 目前只能开机 **iPhone Duo**。iPhone 17 Pro Max 的模拟器机型与这个 runtime 不兼容，所以下面的命令用 iPhone Duo。Xcode 里如果以后有别的 iPhone 模拟器，选它 Run 即可。

```bash
cd ios/CpapMaskGuard
xcodebuild -project CpapMaskGuard.xcodeproj -scheme CpapMaskGuard \
  -destination 'platform=iOS Simulator,name=iPhone Duo,OS=27.1' \
  -configuration Debug \
  -derivedDataPath /tmp/CpapMaskGuardSim \
  build

APP=/tmp/CpapMaskGuardSim/Build/Products/Debug-iphonesimulator/CpapMaskGuard.app
UDID=$(xcrun simctl list devices available | awk -F '[()]' '/iPhone Duo/ {print $2; exit}')
xcrun simctl boot "$UDID" || true
xcrun simctl bootstatus "$UDID" -b
xcrun simctl install "$UDID" "$APP"
xcrun simctl launch "$UDID" app.cpapmaskguard
```

产物：`/tmp/CpapMaskGuardSim/Build/Products/Debug-iphonesimulator/CpapMaskGuard.app`。

打开后主屏是「还没开始」和「开始守护」，并写明只能亮屏前台监测。点「设置与本机」可选前摄白屏（`UIScreen.brightness`）或后摄自备光（手电筒滑条 `setTorchModeOn(level:)`，默认关），以及 5–10 秒抓拍间隔。同一折叠区可以打开「本机状态」「拍几张戴着面罩的样子」和「查看日志」。打开样张页本身不会开相机。没有「使用前检查本机」。

UI 检查（可选）：

```bash
xcodebuild -project CpapMaskGuard.xcodeproj -scheme CpapMaskGuard \
  -destination 'platform=iOS Simulator,name=iPhone Duo,OS=27.1' \
  -configuration Debug test
```

MiniMax key：把 `ios/CpapMaskGuard/Config/Secrets.xcconfig.example` 复制成同目录 `Secrets.xcconfig`（已 gitignore），填入 Key 后重新编译安装。Key 只出现在本机 Info.plist 的构建结果里，源码和日志都不写真 Key。

真机：用 Xcode 打开工程，选已连接的 iPhone，Run。签名是 Automatic，Team `H45MTM24C4`。模拟器没有真手电筒，云端判定也需要真人和网络，床头测试请用真机，并保持 App 在前台、屏幕常亮。装上后本机状态应显示 **0.4.2 (9)**。

### Overnight log / 夜间记录怎么拿走

iOS writes `Library/Application Support/MaskFrames/night-events.jsonl` inside the app container. Same folder as the 12 sample JPEGs. The file rolls at about 8MB, dropping lines older than 48 hours first, so one sleep-night stays. Lines include local ISO8601 time, sleep day, vote or error, HTTP status, circuit (`open` / `closed`, fails, open_until), upload on/off, resume used/remaining, and relay `classify_used` / `classify_remaining` / `classify_limit` when the response had `quota`. Circuit skips and network errors are lines even when no JPEG is saved.

When classify remaining is low (about 5% or less) or 0, the home screen shows a Chinese line for **云端识别次数**. That is the relay budget (`quota` on `POST /v1/classify`). It is not 「今晚的恢复次数已用完」, which is still the 10 upload resumes.

Pull from a Mac with the phone unlocked and trusted:

1. Xcode → Window → Devices and Simulators → select the iPhone → Installed Apps → CpapMaskGuard → gear → Download Container.
2. Right-click the saved `.xcappdata` → Show Package Contents → `AppData/Library/Application Support/MaskFrames/night-events.jsonl`.

Or, with a recent Xcode:

```bash
xcrun devicectl device copy from \
  --device <UDID> \
  --domain-type appDataContainer \
  --domain-identifier app.cpapmaskguard \
  --source 'Library/Application Support/MaskFrames/night-events.jsonl' \
  --destination ./night-events.jsonl
```

Android on this branch already keeps a text log at `filesDir/logs/app.log` (about 768KB plus `app.log.1`). It does not write this JSON file. The Android cloud path here is still direct MiniMax; the relay client and its `quota` object are not on this branch, so the classify-quota banner is iOS-only.

iOS 写在应用容器的 `Library/Application Support/MaskFrames/night-events.jsonl`，和最多 12 张样张同一个目录。文件大约到 8MB 才滚动，先丢掉超过 48 小时的行，一整夜会留下。每行有本地 ISO8601 时间、睡眠日、判定或错误、HTTP 状态、熔断（open/closed、失败次数、open_until）、上传开关、恢复次数已用/剩余；中转响应里带了 `quota` 时还有 `classify_used` / `classify_remaining` / `classify_limit`。熔断跳过和网络失败即使没有 JPEG 也会记一行。

云端识别还剩大约 5% 或已经是 0 时，主屏单独写「云端识别次数」。这是中转 `POST /v1/classify` 的 `quota`，不是「今晚的恢复次数已用完」（那仍是每晚 10 次恢复上传）。

从 Mac 拉文件：手机解锁并信任电脑。Xcode → Window → Devices and Simulators → 选中这台 iPhone → 已安装的 App → CpapMaskGuard → 齿轮 → Download Container。右键下载的 `.xcappdata` → 显示包内容 → `AppData/Library/Application Support/MaskFrames/night-events.jsonl`。上面的 `devicectl` 命令也可以。

这条分支上的安卓已经把文字日志写在 `filesDir/logs/app.log`（大约 768KB，另留 `app.log.1`），没有这份 JSON。安卓云端仍直连 MiniMax，中转客户端和 `quota` 不在这条分支上，所以云端识别次数这行只做了 iOS。

English: scheme `CpapMaskGuard`, Automatic signing, team `H45MTM24C4`. Build and `simctl install` / `simctl launch` as above when an iOS Simulator runtime is available. Device test: plug in an iPhone, select it, Run. Keep the app in the foreground. The API key comes only from local `Secrets.xcconfig`.

---

## Privacy / 隐私

- Frames are used for mask detection.
- With cloud mode + key: a frame goes to the configured vision API only after the on-device aim check passes, and only while cloud upload is still enabled.
- Ten cloud mask-off results in a row turn uploads off. The alarm keeps using the last mask-off result while resumes remain. Resume is limited to 10 times per Asia/Shanghai sleep day (noon). After that, the phone stays on the local classifier and snore detector.
- Offline / no key, or a failed cloud call: inference stays on device when a local baseline exists.
- After uploads stop, capture and the aim check continue, and the siren follows the last cloud mask-off result.
- No account system in the app today.

有云端 Key 时，只有明确全黑、全白或严重模糊的画面不会上传；拿不准会上传。连续 10 次云端脱落会停止上传。恢复主要靠「我戴好了」，画面连续明显变化也可以自动恢复一次。上海时间以中午 12:00 划分睡眠日，每晚最多 10 次恢复上传。这 10 次用尽后继续本机听鼾。中转另外还有云端识别次数（响应里的 quota），用完会单独提示，不是这 10 次。无 Key 或云端请求失败时，有本机基准就在本机判断。当前无账号体系。图片默认不捐赠。

---

## License / 许可证

MIT — see [LICENSE](LICENSE).

---

## Roadmap / 路线图

- Strengthen on-device EfficientNet-Lite0 mask on/off classifier  
- iOS foreground monitoring (screen on, MiniMax-M3, siren, local snore) is in `ios/`. Background camera is not planned.
- First-run UX polish  

加强本机 EfficientNet-Lite0；iOS 亮屏前台监测已在 `ios/`（不做后台常开相机）；优化首次使用体验。