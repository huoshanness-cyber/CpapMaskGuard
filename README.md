# CpapMaskGuard / 面罩守护

> Open-source · Free · Not a medical device  
> 开源 · 免费 · 非医疗器械

---

## English

**CpapMaskGuard** is an Android app for **CPAP / sleep-apnea users** (especially moderate+ severity) who wear a mask at night. It uses the phone **rear camera + torch** to watch whether the mask is on or off. If the mask detaches, it alarms in about **9 seconds** so you can put it back on.

**Detection:** online primary path is cloud vision (**MiniMax-M3**); offline fallback is an on-device **EfficientNet-Lite0** binary classifier (mask on / off).

**Open-source and free** for global users. **Android is the full monitor.** iOS step 1 is a foreground shell (screen on): install and fill light only. Mask detection is not wired on iOS yet.

### Features

- Built for CPAP / sleep-apnea users who wear a mask overnight
- Rear camera + torch monitoring (bedside stand aimed at the pillow)
- Alarms within ~9 seconds when the mask comes off
- Primary: cloud MiniMax-M3; offline fallback: EfficientNet-Lite0 binary classifier
- Capture reference photos without / with the mask
- One-tap **Start Guard** / **Stop Guard**, status in plain language
- **In-app language option: Chinese / English**
- Optional snore cue as an assist signal
- Open-source, free worldwide. Android monitors the mask. iOS step 1 is the foreground shell only.

**Disclaimer:** Personal assistive tool only — not a medical device or diagnosis tool. Follow your clinician for sleep apnea care.

---

## 中文

**面罩守护（CpapMaskGuard）**面向夜间佩戴呼吸机面罩的 **CPAP / 睡眠呼吸暂停用户**（尤其中度及以上）。用手机**后置摄像头 + 手电筒**监测面罩是否还在脸上；一旦脱落，约 **9 秒内**大声提醒，方便重新戴上。

**判定：**联网时主用云端看图（**MiniMax-M3**）；断网时用本机 **EfficientNet-Lite0** 二分类（戴着 / 脱落）兜底。

**开源、全球免费**。**安卓版是完整监测。** iOS 第 1 步是亮屏前台壳：能安装、能补光，还没接面罩判定。

### 功能

- 为夜间戴呼吸机面罩的睡眠呼吸暂停用户设计
- 后置摄像头 + 手电筒床头监测
- 面罩脱落约 9 秒内报警
- 主路径：云端 MiniMax-M3；离线备用：EfficientNet-Lite0 二分类
- 先拍「没戴 / 戴着」基准照片
- 一键「开始守护 / 停止守护」，状态用人话显示
- **应用内语言可切换：中文 / English**
- 可选鼾声辅助信号
- 开源免费。安卓版会判断面罩；iOS 第 1 步只有亮屏前台壳。

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
5. Status should show mask OK; remove the mask and the alarm should sound in several seconds (~9s in our bedside test).  
   状态应显示面罩正常；摘下面罩后数秒内应报警（床头实测约 9 秒）。

---

## Build / 编译

- Open in Android Studio (Windows / macOS / Linux).
- Copy `local.properties.example` → `local.properties`, set `sdk.dir`.
- Optional: `minimax.api.key` for cloud detection (**never commit** `local.properties`).
- Build a debug APK and install on device.

用 Android Studio 打开工程；复制并填写 `local.properties`；可选写入 MiniMax Key；**不要提交**该文件。

### iOS step 1 / 亮屏前台壳

Project / 工程：`ios/CpapMaskGuard/CpapMaskGuard.xcodeproj`  
Target device / 目标机：iPhone, including iPhone 17 Pro Max. Portrait only.

This build does **not** keep the camera on in the background. Lock the screen or leave the app and the fill light stops. Mask detection, MiniMax calls, and log upload are later steps. There is no separate light-test app.

这一步**不会**在后台一直开相机。锁屏或离开 App，补光就停。面罩判定、MiniMax、日志回传都还没做。没有单独的灯光测试 App。

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

打开后主屏是「还没开始」和「开始守护」。点「设置与本机」展开补光两档：前摄白屏（`UIScreen.brightness`）和后摄自备光（手电筒滑条调用 `setTorchModeOn(level:)`，默认关）。判定还没接。

UI 检查（可选）：

```bash
xcodebuild -project CpapMaskGuard.xcodeproj -scheme CpapMaskGuard \
  -destination 'platform=iOS Simulator,name=iPhone Duo,OS=27.1' \
  -configuration Debug test
```

MiniMax key：把 `ios/CpapMaskGuard/Config/Secrets.xcconfig.example` 复制成同目录 `Secrets.xcconfig`（已 gitignore）。第 1 步不会调用接口。

真机以后再装。Team 已是 `H45MTM24C4`。连上 iPhone 并在 Xcode 里选这台手机 Run 时，Xcode 会向该 Personal Team 自动注册 UDID 并生成描述文件。手机没连上之前没有真机 profile。

English: scheme `CpapMaskGuard`, Automatic signing, team `H45MTM24C4`. Build and `simctl install` / `simctl launch` as above. This machine’s iOS 27.1 runtime only boots **iPhone Duo**. Home screen is one status line plus Start. Settings holds the two fill-light modes. Device install waits until a phone is plugged in.

---

## Privacy / 隐私

- Frames are used for mask detection.
- With cloud mode + key: frames go to the configured vision API.
- Offline / no key: inference stays on device.
- No account system in the app today.

有云端 Key 时画面会发到配置的视觉 API；无 Key 或断网则本机推理；当前无账号体系。

---

## License / 许可证

MIT — see [LICENSE](LICENSE).

---

## Roadmap / 路线图

- Strengthen on-device EfficientNet-Lite0 mask on/off classifier  
- iOS step 1 foreground shell is in `ios/`. Next: on-device monitoring while the screen stays on. Background camera is not planned.
- First-run UX polish  

加强本机 EfficientNet-Lite0；iOS 第 1 步亮屏壳已在 `ios/`，下一步才接前台监测（不做后台常开相机）；优化首次使用体验。