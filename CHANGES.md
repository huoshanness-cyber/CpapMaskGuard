# iOS 0.3.8 · 设置三页不再闪退，去掉使用前检查

iPhone 个人包（0.3.7 壳）里，设置里的「本机状态」「拍几张戴着面罩的样子」「查看日志」一点就闪退。崩溃栈是主线程 `EXC_BREAKPOINT` / `SIGTRAP`：`EnvironmentObject.error()` ← `PlaceholderPage.title` ← `PlaceholderPage.body`。这三行和「使用前检查本机」都是 `DisclosureGroup` 里的 `NavigationLink`，而 `GuardModel` 只注入在主屏 `ZStack` 上，推上去的页面拿不到。页面里没有强制拆包，没有去开一个空日志路径，也没有在后台开相机。

- iOS 设置里去掉「使用前检查本机」（没有留下会崩的死按钮）
- 其余三页改由主屏 `NavigationStack` 的 path 打开，`GuardModel` 直接传进页面
- 本机状态：只读显示守护、补光、手电筒、相机权限、机型、电量；健康分写明即将上线
- 拍几张戴着面罩的样子：即将上线，这一版不打开相机
- 查看日志：只记这一次打开应用里的操作，放内存，不写文件、不上传

安卓 0.3.7 说明见下文，安卓的使用前检查没有动。

---

# CpapMaskGuard 0.3.7 · 主屏生活化（参考公开使用习惯，视觉自研）

## 相对 0.3.6
合法合规前提下，按 MaskAlarm 一类产品的**公开使用习惯**重组主界面：一句状态 + 超大「开始/停止守护」；烤机、健康分、预览、分辨率、保活等收入「设置与本机」。**不抄**对方布局像素、图标、英文原句与品牌色。

## 改了什么
- 主屏：最上状态人话（还没开始 / 请先摆好手机 / 正在确认面罩 / 已守护·面罩正常 / 可能脱落·请戴回 / 已停止）+ 超大「开始守护」「停止守护」；默认无半屏预览
- 「设置与本机」折叠区：预览与抓拍日志、本机状态、使用前检查本机、认面罩样张、分辨率/低端、补光、保活跳转、查看日志、技术明细
- 首次引导：说明相机用途后再申请权限；三步清单（允许相机、摆好对准枕头、试拍认面罩）
- 「① 使用前烤机」→「使用前检查本机（约5分钟）」进本机与诊断；健康分→「本机状态」，正常不占主屏，<60 红字提示
- 校准入口→「拍几张戴着面罩的样子」；跳过校准仅 debug 可见
- 保留：云端 M3 主路径、本机备用、听鼾、补光两档、邮件报告、INTERNET/Key 注入
- versionName **0.3.7** / versionCode **19**

## 不动
判定链路与脱落报警目标时延；云端 Key 注入方式。
## 0.3.7+ compatibility
- Keep-alive prompts are generic-first (ignore battery + app details). MIUI autostart/battery deep-links are optional Xiaomi-only helpers, hidden on non-Xiaomi.
- Flashlight remains CameraX enableTorch; notifications NotificationCompat; minSdk 26 / targetSdk 37 unchanged.
