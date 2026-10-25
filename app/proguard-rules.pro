# 当前 release 未开混淆。若以后打开 minify，保留 CameraX 与前台服务入口。
-keep class androidx.camera.** { *; }
-keep class app.cpapmaskguard.service.MonitorService { *; }
