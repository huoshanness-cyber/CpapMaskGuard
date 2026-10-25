# CpapMaskGuard / 面罩守护

> Open-source · Free · Not a medical device  
> 开源 · 免费 · 非医疗器械

---

## English

**CpapMaskGuard** is an Android app for **CPAP / sleep-apnea users** (especially moderate+ severity) who wear a mask at night. It uses the phone **rear camera + torch** to watch whether the mask is on or off. If the mask detaches, it alarms in about **9 seconds** so you can put it back on.

**Detection:** online primary path is cloud vision (**MiniMax-M3**); offline fallback is an on-device **EfficientNet-Lite0** binary classifier (mask on / off).

**Open-source and free** for global users. **Currently Android.**

### Features

- Built for CPAP / sleep-apnea users who wear a mask overnight
- Rear camera + torch monitoring (bedside stand aimed at the pillow)
- Alarms within ~9 seconds when the mask comes off
- Primary: cloud MiniMax-M3; offline fallback: EfficientNet-Lite0 binary classifier
- Capture reference photos without / with the mask
- One-tap **Start Guard** / **Stop Guard**, status in plain language
- **In-app language option: Chinese / English**
- Optional snore cue as an assist signal
- Open-source, free worldwide; Android version now

**Disclaimer:** Personal assistive tool only — not a medical device or diagnosis tool. Follow your clinician for sleep apnea care.

---

## 中文

**面罩守护（CpapMaskGuard）**面向夜间佩戴呼吸机面罩的 **CPAP / 睡眠呼吸暂停用户**（尤其中度及以上）。用手机**后置摄像头 + 手电筒**监测面罩是否还在脸上；一旦脱落，约 **9 秒内**大声提醒，方便重新戴上。

**判定：**联网时主用云端看图（**MiniMax-M3**）；断网时用本机 **EfficientNet-Lite0** 二分类（戴着 / 脱落）兜底。

**开源、全球免费**。**目前为安卓版。**

### 功能

- 为夜间戴呼吸机面罩的睡眠呼吸暂停用户设计
- 后置摄像头 + 手电筒床头监测
- 面罩脱落约 9 秒内报警
- 主路径：云端 MiniMax-M3；离线备用：EfficientNet-Lite0 二分类
- 先拍「没戴 / 戴着」基准照片
- 一键「开始守护 / 停止守护」，状态用人话显示
- **应用内语言可切换：中文 / English**
- 可选鼾声辅助信号
- 开源免费；当前为安卓版

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
- iOS exploration (background camera limits apply)  
- First-run UX polish  

加强本机 EfficientNet-Lite0；探索 iOS（后台相机受限）；优化首次使用体验。