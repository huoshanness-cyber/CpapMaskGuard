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
    var sectionPlaceholders: String { language == .zh ? "接下来再接" : "Wired in a later step" }
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

    var burnInTitle: String { language == .zh ? "使用前检查本机" : "Phone check before use" }
    var healthTitle: String { language == .zh ? "本机状态" : "Phone status" }
    var calibrateTitle: String { language == .zh ? "拍几张戴着面罩的样子" : "Sample photos with the mask" }
    var logsTitle: String { language == .zh ? "查看日志" : "View logs" }

    var burnInBody: String {
        language == .zh
            ? "占位。安卓版有约 5 分钟的使用前检查。iOS 这一步还没接，不会开长时压测。"
            : "Placeholder. Android has a short pre-use check. iOS does not run it in this step."
    }
    var healthBody: String {
        language == .zh
            ? "占位。监测真的跑起来之后，才会有本机状态。现在没有分数。"
            : "Placeholder. A status score arrives only after real monitoring exists. There is no score now."
    }
    var calibrateBody: String {
        language == .zh
            ? "占位。下一步才会拍「没戴 / 戴着」样张。现在点开始守护，只切换补光。"
            : "Placeholder. Sample photos come in the next step. Start only changes the fill light for now."
    }
    var logsBody: String {
        language == .zh
            ? "占位。这一步不写监测日志，也不会把记录传出去。"
            : "Placeholder. This step does not write a monitoring log and does not upload anything."
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
            ? "iOS 0.3.7 · 第 1 步亮屏前台 · MIT"
            : "iOS 0.3.7 · step 1, screen on · MIT"
    }
}
