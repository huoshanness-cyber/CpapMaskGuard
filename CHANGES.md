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
