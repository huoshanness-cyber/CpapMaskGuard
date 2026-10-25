import Foundation

struct L10n {
    var language: AppLanguage

    var brand: String { "CpapMaskGuard" }
    var appName: String { language == .zh ? "面罩守护" : "Mask guard" }
    var disclaimer: String {
        language == .zh
            ? "睡眠辅助提醒，不是医疗设备，也不能保证每次都发现面罩脱落。"
            : "A sleep reminder, not a medical device. It cannot promise to catch every mask leak."
    }
    var foregroundLimit: String {
        language == .zh
            ? "这一版只在亮屏、停在前台时工作。锁屏或切到别的应用，补光会停，相机也不会在后台继续开着。"
            : "This version only runs while the screen is on and the app stays open. Locking the phone or leaving the app turns the light off. The camera does not keep running in the background."
    }

    var start: String { language == .zh ? "开始守护" : "Start guard" }
    var stop: String { language == .zh ? "停止守护" : "Stop guard" }
    var settingsClosed: String { language == .zh ? "设置与本机" : "Settings and this phone" }
    var sectionLight: String { language == .zh ? "补光" : "Fill light" }
    var sectionPhone: String { language == .zh ? "本机" : "This phone" }
    var sectionPermissions: String { language == .zh ? "权限" : "Permissions" }
    var languageLabel: String { language == .zh ? "语言" : "Language" }

    func headline(_ phase: GuardPhase, mode: FillLightMode, torchOn: Bool) -> String {
        switch phase {
        case .idle:
            return language == .zh ? "还没开始" : "Not started"
        case .stopped:
            return language == .zh ? "已停止" : "Stopped"
        case .leftForeground:
            return language == .zh ? "已离开前台" : "Left the foreground"
        case .guarding:
            switch mode {
            case .frontScreen:
                return language == .zh ? "白屏补光中" : "White screen is on"
            case .rearAmbient:
                return torchOn
                    ? (language == .zh ? "手电筒开着" : "Torch is on")
                    : (language == .zh ? "请自备小灯" : "Use your own lamp")
            }
        }
    }

    func detail(_ phase: GuardPhase, mode: FillLightMode, torchOn: Bool) -> String {
        switch phase {
        case .idle:
            return language == .zh
                ? "睡前摆好手机，对准枕头，再点开始守护。"
                : "Set the phone toward the pillow, then tap start."
        case .stopped:
            return language == .zh
                ? "补光已关。准备好了再点开始守护。"
                : "The light is off. Tap start when you are ready."
        case .leftForeground:
            return language == .zh
                ? "亮屏监测已停，补光已关。回到这个画面后，请重新点开始守护。"
                : "Monitoring and the light stopped. Come back to this screen and tap start again."
        case .guarding:
            switch mode {
            case .frontScreen:
                return language == .zh
                    ? "屏幕朝向枕头。这一步先不判断面罩，只把白屏灯调好。"
                    : "Point the screen at the pillow. This step does not judge the mask. It only tunes the white screen."
            case .rearAmbient:
                return torchOn
                    ? (language == .zh
                        ? "后摄手电筒用来看强弱。这一步先不判断面罩。别让光直射眼睛。"
                        : "The rear torch is for checking brightness. This step does not judge the mask. Keep it out of the eyes.")
                    : (language == .zh
                        ? "后摄、手电筒关着。请用台灯或夜灯。这一步先不判断面罩。"
                        : "Rear camera, torch off. Use a bedside lamp. This step does not judge the mask.")
            }
        }
    }

    func fillName(_ mode: FillLightMode) -> String {
        switch mode {
        case .frontScreen:
            return language == .zh ? "前摄 + 白屏补光" : "Front camera + white screen"
        case .rearAmbient:
            return language == .zh ? "后摄 + 自备光源" : "Rear camera + your own light"
        }
    }

    func fillHint(_ mode: FillLightMode) -> String {
        switch mode {
        case .frontScreen:
            return language == .zh
                ? "开始守护后整屏变白，并用 UIScreen.brightness 调这台手机的亮度。停止后恢复原来的亮度。手电筒会关掉。"
                : "Start turns the screen white and sets UIScreen.brightness. Stop restores the previous brightness. The torch stays off."
        case .rearAmbient:
            return language == .zh
                ? "默认关手电筒，请自备光源。要验灯光强弱时，打开下面的手电筒，滑条会调用 setTorchModeOn(level:)。"
                : "Torch stays off unless you turn it on below. The slider calls setTorchModeOn(level:) so you can compare strength."
        }
    }

    var brightnessLabel: String {
        language == .zh ? "白屏亮度" : "White-screen brightness"
    }
    var torchSwitch: String {
        language == .zh ? "后摄手电筒（验强弱，默认关）" : "Rear torch (test level, off by default)"
    }
    var torchLevelLabel: String {
        language == .zh ? "手电筒档位" : "Torch level"
    }
    var torchHint: String {
        language == .zh
            ? "滑条范围 5%–100%。过夜请保持偏低并插电。模拟器没有真手电筒，请用 iPhone 真机看。"
            : "Range is 5%–100%. Keep it low overnight and plug the phone in. The Simulator has no real torch; use an iPhone."
    }
    var keyStatusReady: String {
        language == .zh
            ? "云端 Key：本机已配置（不显示，这一步不调用）"
            : "Cloud key: set on this Mac (hidden, not used in this step)"
    }
    var keyStatusEmpty: String {
        language == .zh
            ? "云端 Key：还没配。复制 Secrets.xcconfig.example 为 Secrets.xcconfig 即可，别提交。"
            : "Cloud key: not set. Copy Secrets.xcconfig.example to Secrets.xcconfig. Do not commit it."
    }

    var healthTitle: String { language == .zh ? "本机状态" : "Phone status" }
    var calibrateTitle: String { language == .zh ? "拍几张戴着面罩的样子" : "Sample photos with the mask" }
    var logsTitle: String { language == .zh ? "查看日志" : "View logs" }

    var healthComingSoon: String {
        language == .zh
            ? "即将上线：监测健康分。这一页现在不打分，也不会因此停下守护。"
            : "Coming later: a monitoring score. This page does not score the phone and does not stop the guard."
    }
    var baselineComingSoon: String {
        language == .zh
            ? "即将上线。这一版不会打开相机拍戴着面罩的样张，锁屏或切到别的应用时也不会在后台开摄像头。回到主屏点「开始守护」，目前只切换补光。"
            : "Coming later. This version does not open the camera to take mask photos, and it does not keep the camera on in the background. Start on the home screen only changes the fill light for now."
    }
    var logsComingSoon: String {
        language == .zh
            ? "即将上线：面罩判定的监测日志。下面只记这一次打开应用时的操作。只在内存里，关掉应用就没了，不会写文件，也不会上传。"
            : "Coming later: mask-detection logs. Below is only what happened during this launch. It stays in memory, is not written to a file, and is not uploaded."
    }
    var logsEmpty: String { language == .zh ? "还没有日志。" : "No log lines yet." }

    var rowPhase: String { language == .zh ? "守护" : "Guard" }
    var rowFill: String { language == .zh ? "补光" : "Fill light" }
    var rowTorch: String { language == .zh ? "手电筒" : "Torch" }
    var rowCamera: String { language == .zh ? "相机权限" : "Camera access" }
    var rowDevice: String { language == .zh ? "机型" : "Device" }
    var rowBattery: String { language == .zh ? "电量" : "Battery" }
    var cameraAllowed: String { language == .zh ? "已允许" : "Allowed" }
    var cameraBlocked: String { language == .zh ? "未允许" : "Not allowed" }
    var cameraNotAsked: String { language == .zh ? "还没问过" : "Not asked yet" }
    var valueUnknown: String { language == .zh ? "未知" : "Unknown" }
    var torchOffNow: String { language == .zh ? "关着" : "Off" }
    var batteryUnknown: String { language == .zh ? "读不到" : "Unavailable" }

    func batteryPercent(_ value: Int) -> String { "\(value)%" }

    func pair(_ label: String, _ value: String) -> String {
        let shown = value.trimmingCharacters(in: .whitespacesAndNewlines)
        let safe = shown.isEmpty ? valueUnknown : shown
        return language == .zh ? "\(label)：\(safe)" : "\(label): \(safe)"
    }

    var logOpened: String { language == .zh ? "打开了面罩守护" : "Opened mask guard" }
    var logStarted: String { language == .zh ? "开始守护" : "Started guard" }
    var logStopped: String { language == .zh ? "停止守护" : "Stopped guard" }
    var logLeft: String { language == .zh ? "离开前台，补光已停" : "Left the foreground. Light off." }
    var logCameraDenied: String { language == .zh ? "没有相机权限，没有开始" : "Camera access is off. Did not start." }
    var logTorchOff: String { language == .zh ? "手电筒已关" : "Torch off" }

    func logFill(_ mode: FillLightMode) -> String {
        language == .zh ? "补光改为\(fillName(mode))" : "Fill light set to \(fillName(mode))"
    }

    func logTorchOn(_ status: String) -> String {
        let shown = status.trimmingCharacters(in: .whitespacesAndNewlines)
        let safe = shown.isEmpty ? torchOffNow : shown
        return language == .zh ? "手电筒：\(safe)" : "Torch: \(safe)"
    }

    func logMic(_ ok: Bool) -> String {
        ok
            ? (language == .zh ? "麦克风权限：已允许（不录音）" : "Microphone: allowed (not recording)")
            : (language == .zh ? "麦克风权限：未允许" : "Microphone: not allowed")
    }

    func logNotify(_ ok: Bool) -> String {
        ok
            ? (language == .zh ? "通知权限：已允许（不报警）" : "Notifications: allowed (no alarm)")
            : (language == .zh ? "通知权限：未允许" : "Notifications: not allowed")
    }

    func logNotifyFailed(_ message: String) -> String {
        let shown = message.trimmingCharacters(in: .whitespacesAndNewlines)
        let safe = shown.isEmpty ? valueUnknown : shown
        return language == .zh ? "通知权限申请失败：\(safe)" : "Notification request failed: \(safe)"
    }

    var micButton: String { language == .zh ? "申请麦克风（占位）" : "Ask for microphone (placeholder)" }
    var notifyButton: String { language == .zh ? "申请通知（占位）" : "Ask for notifications (placeholder)" }
    var openSettings: String { language == .zh ? "去系统设置打开权限" : "Open Settings" }
    var micGranted: String { language == .zh ? "麦克风：已允许。这一步不录音。" : "Microphone: allowed. This step does not record." }
    var micDenied: String { language == .zh ? "麦克风：未允许。听鼾以后再说，不影响现在看灯。" : "Microphone: not allowed. Later snore cue only. The light still works." }
    var notifyGranted: String { language == .zh ? "通知：已允许。这一步不会报警。" : "Notifications: allowed. This step does not alarm." }
    var notifyDenied: String { language == .zh ? "通知：未允许。这一步本来也不会报警。" : "Notifications: not allowed. This step would not alarm anyway." }
    var cameraDenied: String {
        language == .zh
            ? "还没有相机权限。请在系统设置里允许，再点开始守护或打开手电筒。"
            : "Camera access is off. Allow it in Settings, then start or turn the torch on."
    }
    var versionFooter: String {
        language == .zh
            ? "iOS 0.3.8 · 第 1 步亮屏前台 · MIT"
            : "iOS 0.3.8 · step 1, screen on · MIT"
    }
}
