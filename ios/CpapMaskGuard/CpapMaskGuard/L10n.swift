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
            ? "iOS 不能在锁屏或后台开着相机。只有亮着屏、并且停在这个应用里，才会拍照、看面罩、响铃。锁屏或切到别的应用，相机会停，铃声也会停。"
            : "iOS cannot keep the camera on when the screen is locked or the app is in the background. Photos, mask checks, and the alarm run only while this screen stays open and awake. Leaving the app or locking the phone stops the camera and the alarm."
    }

    var start: String { language == .zh ? "开始守护" : "Start guard" }
    var stop: String { language == .zh ? "停止守护" : "Stop guard" }
    var stopSound: String { language == .zh ? "停止铃声" : "Stop the sound" }
    var settingsClosed: String { language == .zh ? "设置与本机" : "Settings and this phone" }
    var sectionLight: String { language == .zh ? "补光" : "Fill light" }
    var sectionPhone: String { language == .zh ? "本机" : "This phone" }
    var sectionPermissions: String { language == .zh ? "权限" : "Permissions" }
    var languageLabel: String { language == .zh ? "语言" : "Language" }
    var intervalLabel: String { language == .zh ? "抓拍间隔" : "Capture interval" }
    var lastCaptureTitle: String { language == .zh ? "最近一张" : "Latest frame" }
    var capturedLabel: String { language == .zh ? "拍摄时间" : "Captured" }
    var resultWord: String { language == .zh ? "结果" : "Result" }
    var takingFirst: String { language == .zh ? "正在拍第一张…" : "Taking the first photo…" }

    func headline(
        phase: GuardPhase,
        alarming: Bool,
        failures: Int,
        vote: FrameVote?,
        unjudged: Bool,
        aimMissed: Bool = false
    ) -> String {
        switch phase {
        case .idle:
            return language == .zh ? "还没开始" : "Not started"
        case .stopped:
            return language == .zh ? "已停止" : "Stopped"
        case .leftForeground:
            return language == .zh ? "已离开前台" : "Left the foreground"
        case .guarding:
            if alarming {
                return language == .zh ? "可能脱落·请戴回" : "Mask may be off"
            }
            if failures >= 5 {
                return language == .zh ? "请先摆好手机" : "Aim the phone at the pillow"
            }
            if aimMissed {
                return language == .zh ? "正在确认面罩" : "Checking the mask"
            }
            if unjudged {
                return language == .zh ? "正在确认面罩" : "Checking the mask"
            }
            switch vote {
            case .maskOn:
                return language == .zh ? "已守护·面罩正常" : "Guard on · mask looks on"
            case .noPerson:
                return language == .zh ? "请先摆好手机" : "Aim the phone at the pillow"
            case .maskOff, .uncertain, nil:
                return language == .zh ? "正在确认面罩" : "Checking the mask"
            }
        }
    }

    func detail(
        phase: GuardPhase,
        mode: FillLightMode,
        torchOn: Bool,
        interval: Int,
        alarming: Bool,
        reason: String?,
        snore: String?
    ) -> String {
        switch phase {
        case .idle:
            return language == .zh
                ? "睡前摆好手机，对准枕头，再点开始守护。请保持亮屏并停在这个画面。"
                : "Set the phone toward the pillow, then tap start. Keep the screen on and stay in this app."
        case .stopped:
            return language == .zh
                ? "相机和补光已关。准备好了再点开始守护。"
                : "The camera and the light are off. Tap start when you are ready."
        case .leftForeground:
            return language == .zh
                ? "亮屏监测已停，相机和铃声已关。回到这个画面后，请重新点开始守护。"
                : "Monitoring stopped. The camera and the alarm are off. Come back and tap start again."
        case .guarding:
            let light = lightDetail(mode: mode, torchOn: torchOn, interval: interval)
            let because = reason ?? takingFirst
            let snoreBit = (snore?.isEmpty == false) ? " \(snore ?? "")" : ""
            if alarming {
                return language == .zh
                    ? "铃声要你自己停。把面罩戴回去，再点停止铃声。守护还在拍照。\(because)\(snoreBit)"
                    : "The sound keeps going until you stop it. Put the mask back on, then stop the sound. Photos continue. \(because)\(snoreBit)"
            }
            return "\(light) \(because)\(snoreBit)"
        }
    }

    private func lightDetail(mode: FillLightMode, torchOn: Bool, interval: Int) -> String {
        switch mode {
        case .frontScreen:
            return language == .zh
                ? "前摄 · 白屏补光 · 每 \(interval) 秒一张。屏幕请朝向枕头。"
                : "Front camera · white screen · every \(interval)s. Point the screen at the pillow."
        case .rearAmbient:
            if torchOn {
                return language == .zh
                    ? "后摄 · 手电筒开着 · 每 \(interval) 秒一张。别让光直射眼睛。"
                    : "Rear camera · torch on · every \(interval)s. Keep it out of the eyes."
            }
            return language == .zh
                ? "后摄 · 请自备小灯 · 每 \(interval) 秒一张。"
                : "Rear camera · use your own lamp · every \(interval)s."
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
                ? "开始守护后整屏变白，并用 UIScreen.brightness 调这台手机的亮度。停止或离开前台后恢复原来的亮度。手电筒会关掉。前摄按间隔拍照。"
                : "Start turns the screen white and sets UIScreen.brightness. Stop, or leaving the app, restores the previous brightness. The torch stays off. The front camera takes a photo on the interval."
        case .rearAmbient:
            return language == .zh
                ? "默认关手电筒，请自备光源。要补光或验强弱时，打开下面的手电筒，滑条会调用 setTorchModeOn(level:)。守护时后摄按间隔拍照。"
                : "Torch stays off unless you turn it on below. The slider calls setTorchModeOn(level:). While guarding, the rear camera takes a photo on the interval."
        }
    }

    var brightnessLabel: String {
        language == .zh ? "白屏亮度" : "White-screen brightness"
    }
    var torchSwitch: String {
        language == .zh ? "后摄手电筒（可调强弱，默认关）" : "Rear torch (adjustable, off by default)"
    }
    var torchLevelLabel: String {
        language == .zh ? "手电筒档位" : "Torch level"
    }
    var torchHint: String {
        language == .zh
            ? "过夜建议 2%。滑条最低约 1%。这台手机如果只能开关，会自动开到全亮，不会把开关关掉。模拟器没有真手电筒。"
            : "2% is the overnight suggestion. The slider goes down to about 1%. Phones that can only switch the light on or off turn it fully on. The Simulator has no torch."
    }
    func settleHeadline(_ seconds: Int) -> String {
        language == .zh
            ? "请戴罩躺好（还剩 \(seconds)s）"
            : "Lie down with the mask on (\(seconds)s left)"
    }
    var settleDetail: String {
        language == .zh
            ? "这一分钟内，坐在床边整理管路也不会因为没戴面罩响铃。"
            : "For this minute, sitting up to fix the tubing will not ring."
    }
    var settleDone: String {
        language == .zh ? "躺好时间到了，开始看面罩有没有脱落。" : "The lie-down minute is over. Mask-off alarms are on."
    }
    var maskSeenWhileSettling: String {
        language == .zh
            ? "已经连续看到戴着面罩。这一分钟结束前仍然不响铃。"
            : "The mask has been seen. The alarm stays off until the minute ends."
    }
    var aimAtPillow: String { language == .zh ? "对准枕头" : "Aim at the pillow" }
    var aimPreviewButton: String { language == .zh ? "先看床头" : "Look at the pillow" }
    var relayFallback: String {
        language == .zh ? "官方中转这一张没连上，改用本机 Key。" : "The relay missed this frame. Trying the on-device key."
    }
    var relayStatus: String {
        language == .zh
            ? "云端：官方中转 cpap.njxxzf.cn，安装包不含 MiniMax Key。"
            : "Cloud: official relay at cpap.njxxzf.cn. No MiniMax key in the app."
    }
    var keyStatusReady: String {
        language == .zh
            ? "云端 Key：已从 Secrets.xcconfig 注入，这里不显示。守护时照片会发给 MiniMax，不会把 Key 写进日志。"
            : "Cloud key: injected from Secrets.xcconfig and hidden here. Guard sends stills to MiniMax and does not write the key into the log."
    }
    var keyStatusEmpty: String {
        language == .zh
            ? "云端 Key：还没配。复制 Secrets.xcconfig.example 为 Secrets.xcconfig 后重新编译。没有 Key 时照片不会上传。"
            : "Cloud key: not set. Copy Secrets.xcconfig.example to Secrets.xcconfig and rebuild. Without a key, photos are not uploaded."
    }

    func uploadNote(hasKey: Bool) -> String {
        if hasKey {
            return language == .zh
                ? "全黑、全白或糊成一片才不上云。拿不准会照常上传判定。连续 10 次脱落会停下上传。声音只在本机判断。"
                : "Only a black, white, or badly blurred frame stays off the cloud. An uncertain frame is still sent. Ten mask-off results in a row stop uploads. Audio stays on the phone."
        }
        return language == .zh
            ? "还没有 MiniMax Key，照片不会上传，云端也无法判定。听鼾仍可在确认打呼后响铃。"
            : "No MiniMax key, so photos are not uploaded and the cloud cannot judge them. A confirmed snore can still alarm."
    }

    var healthTitle: String { language == .zh ? "本机状态" : "Phone status" }
    var calibrateTitle: String { language == .zh ? "拍几张戴着面罩的样子" : "Sample photos with the mask" }
    var logsTitle: String { language == .zh ? "查看日志" : "View logs" }

    var healthComingSoon: String {
        language == .zh
            ? "监测健康分只作参考，不会因此停下守护。iOS 读不到电池温度时，这一项按未知计入。"
            : "The monitoring score is only a hint. It does not stop the guard. When battery temperature is unavailable it counts as unknown."
    }
    var baselineComingSoon: String {
        language == .zh
            ? "打开这一页不会打开相机。守护时拍下的画面留在本机（最近 12 张和结果），不上传这些文件。要单独拍一张，点下面的按钮，只在前台拍。锁屏后不会在后台开摄像头。"
            : "Opening this page does not open the camera. Frames from a guard stay on this phone (the latest 12, plus results) and those files are not uploaded. The button below takes one foreground photo. The camera does not keep running in the background."
    }
    var logsComingSoon: String {
        language == .zh
            ? "下面是这一次打开应用的操作和每一轮判定。只在内存里，关掉应用就没了，也不会上传。"
            : "Below is what happened during this launch, including each judgment. It stays in memory, disappears when the app closes, and is not uploaded."
    }
    var logsEmpty: String { language == .zh ? "还没有日志。" : "No log lines yet." }
    var samplesEmpty: String { language == .zh ? "还没有样张。" : "No sample frames yet." }
    var captureSample: String { language == .zh ? "现在拍一张样张" : "Take one sample photo" }
    var refreshSamples: String { language == .zh ? "刷新样张" : "Refresh samples" }
    var sampleWhileGuarding: String {
        language == .zh
            ? "守护进行中。样张会自动留下，点刷新即可。打开这一页不会再开一路相机。"
            : "Guard is running. Frames are saved automatically. Tap refresh. Opening this page does not start another camera."
    }
    var sampleUnjudgedReason: String {
        language == .zh ? "样张（未上云或尚未判定）" : "Sample photo, not judged"
    }

    var rowPhase: String { language == .zh ? "守护" : "Guard" }
    var rowFill: String { language == .zh ? "补光" : "Fill light" }
    var rowTorch: String { language == .zh ? "手电筒" : "Torch" }
    var rowCamera: String { language == .zh ? "相机权限" : "Camera access" }
    var rowDevice: String { language == .zh ? "机型" : "Device" }
    var rowBattery: String { language == .zh ? "电量" : "Battery" }
    var rowHealth: String { language == .zh ? "监测健康分" : "Monitor score" }
    var rowInterval: String { language == .zh ? "抓拍间隔" : "Interval" }
    var rowLastResult: String { language == .zh ? "最近结果" : "Latest result" }
    var rowCaptureTime: String { language == .zh ? "拍摄时间" : "Captured" }
    var rowLatency: String { language == .zh ? "判定耗时" : "Judge time" }
    var rowSnore: String { language == .zh ? "听鼾" : "Snore" }
    var rowKey: String { language == .zh ? "云端 Key" : "Cloud key" }
    var rowVersion: String { language == .zh ? "版本" : "Version" }
    var healthPending: String { language == .zh ? "还没有样本" : "No sample yet" }
    var keyYes: String { language == .zh ? "已配置" : "Set" }
    var relayShort: String { language == .zh ? "官方中转" : "Official relay" }
    var keyNo: String { language == .zh ? "未配置" : "Not set" }
    var cameraAllowed: String { language == .zh ? "已允许" : "Allowed" }
    var cameraBlocked: String { language == .zh ? "未允许" : "Not allowed" }
    var cameraNotAsked: String { language == .zh ? "还没问过" : "Not asked yet" }
    var valueUnknown: String { language == .zh ? "未知" : "Unknown" }
    var torchOffNow: String { language == .zh ? "关着" : "Off" }
    var batteryUnknown: String { language == .zh ? "读不到" : "Unavailable" }
    var unjudged: String { language == .zh ? "未判定" : "Not judged" }
    var noKeyReason: String {
        language == .zh ? "没有云端 Key，这一张没有上传。" : "No cloud key. This frame was not uploaded."
    }
    var waitingFrame: String {
        language == .zh ? "等待下一张照片" : "Waiting for the next photo"
    }

    func batteryPercent(_ value: Int) -> String { "\(value)%" }

    func pair(_ label: String, _ value: String) -> String {
        let shown = value.trimmingCharacters(in: .whitespacesAndNewlines)
        let safe = shown.isEmpty ? valueUnknown : shown
        return language == .zh ? "\(label)：\(safe)" : "\(label): \(safe)"
    }

    func resultLabel(_ vote: FrameVote, alarmed: Bool) -> String {
        if alarmed { return language == .zh ? "面罩脱落" : "Mask off" }
        switch vote {
        case .maskOn:
            return language == .zh ? "面罩正常" : "Mask on"
        case .maskOff:
            return language == .zh ? "面罩脱落" : "Mask off"
        case .noPerson:
            return language == .zh ? "未检测到人" : "No person"
        case .uncertain:
            return language == .zh ? "不确定" : "Uncertain"
        }
    }

    func cloudReason(_ vote: FrameVote, raw: String) -> String {
        let snippet = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let safe = snippet.isEmpty ? valueUnknown : snippet
        switch vote {
        case .maskOn:
            return language == .zh ? "云端：戴着面罩" : "Cloud: mask on"
        case .maskOff:
            return language == .zh ? "云端：面罩脱落/未戴" : "Cloud: mask off"
        case .noPerson:
            return language == .zh ? "云端：无人或过暗" : "Cloud: no person or too dark"
        case .uncertain:
            return language == .zh ? "云端：看不清（\(safe)）" : "Cloud: unclear (\(safe))"
        }
    }

    func latencyLine(_ readout: CaptureReadout) -> String {
        language == .zh
            ? "判定 \(readout.inferMs)ms · 拍到判定 \(readout.captureToJudgeMs)ms · 整轮 \(readout.roundMs)ms"
            : "Judge \(readout.inferMs) ms · Capture to judge \(readout.captureToJudgeMs) ms · Round \(readout.roundMs) ms"
    }

    func snoreLine(_ snap: SnoreSnapshot) -> String {
        let windows = max(snap.windows, 1)
        switch snap.state {
        case .off:
            return language == .zh ? "听鼾未开" : "Snore cue off"
        case .denied:
            return language == .zh ? "无麦克风权限，只看相机" : "No microphone permission. Camera only."
        case .unavailable:
            return language == .zh ? "麦克风不可用，只看相机" : "Microphone unavailable. Camera only."
        case .listening:
            return language == .zh
                ? "听声中 · 疑似窗 \(snap.positives)/\(windows)"
                : "Listening · snore-like \(snap.positives)/\(windows)"
        case .confirmed:
            return language == .zh
                ? "鼾声确认 \(snap.confirmedForMs / 1000)s · 窗 \(snap.positives)/\(windows)"
                : "Snore confirmed \(snap.confirmedForMs / 1000)s · windows \(snap.positives)/\(windows)"
        }
    }

    func snorePending(_ elapsed: Int, _ need: Int, base: String) -> String {
        language == .zh ? "\(base) · 打呼中 \(elapsed)s/\(need)s" : "\(base) · snore \(elapsed)s/\(need)s"
    }

    func decisionLine(_ decision: AlarmDecision, fallback: String) -> String {
        switch decision {
        case .camera(let assisted):
            if assisted {
                return language == .zh ? "\(fallback) · 同时有鼾声" : "\(fallback) · snore too"
            }
            return fallback
        case .snore(let seconds, let vote):
            let voteText = vote?.rawValue ?? (language == .zh ? "无相机票" : "no camera vote")
            return language == .zh
                ? "音频确认打呼 \(seconds)s（面罩可能未就位）· 相机:\(voteText)"
                : "Snore confirmed \(seconds)s. Mask may be off. Camera: \(voteText)"
        case .pendingSnore, .quiet:
            return fallback
        }
    }

    func failureMessage(_ error: Error) -> String {
        if let error = error as? CaptureError {
            return captureMessage(error)
        }
        if let error = error as? MiniMaxError {
            return cloudMessage(error)
        }
        if let error = error as? RelayError {
            let text = error.errorDescription ?? valueUnknown
            return language == .zh ? "这一张没问成：\(text)" : "This frame was not classified: \(text)"
        }
        let text = error.localizedDescription.trimmingCharacters(in: .whitespacesAndNewlines)
        let safe = text.isEmpty ? valueUnknown : text
        return language == .zh ? "这一轮没拍成：\(safe)" : "This round failed: \(safe)"
    }

    private func captureMessage(_ error: CaptureError) -> String {
        switch error {
        case .noCamera:
            return language == .zh ? "没有可用的相机。请用 iPhone 真机，并允许相机。" : "No camera is available. Use an iPhone and allow the camera."
        case .notRunning:
            return language == .zh ? "相机没有跑起来。请留在前台再试。" : "The camera did not start. Stay in the foreground and try again."
        case .timeout:
            return language == .zh ? "这一张拍太久，下一轮再试。" : "That photo took too long. The next round will try again."
        case .empty:
            return language == .zh ? "这一张是空的。" : "That frame was empty."
        case .stopped:
            return language == .zh ? "拍摄已停。" : "Capture stopped."
        case .failed(let message):
            let safe = message.trimmingCharacters(in: .whitespacesAndNewlines)
            let shown = safe.isEmpty ? valueUnknown : safe
            return language == .zh ? "这一轮没拍成：\(shown)" : "This round failed: \(shown)"
        }
    }

    private func cloudMessage(_ error: MiniMaxError) -> String {
        switch error {
        case .missingKey:
            return noKeyReason
        case .emptyImage:
            return language == .zh ? "照片是空的，没有上传。" : "The photo was empty and was not uploaded."
        case .http(let code, let snippet):
            return language == .zh ? "云端 HTTP \(code)：\(snippet)" : "Cloud HTTP \(code): \(snippet)"
        case .api(let snippet):
            return language == .zh ? "云端返回：\(snippet)" : "Cloud response: \(snippet)"
        case .emptyContent:
            return language == .zh ? "云端没有返回判定。" : "The cloud returned no label."
        case .badJSON:
            return language == .zh ? "云端响应无法解析。" : "The cloud response could not be read."
        case .transport(let snippet):
            return language == .zh ? "网络失败：\(snippet)" : "Network failed: \(snippet)"
        }
    }

    var logOpened: String { language == .zh ? "打开了面罩守护" : "Opened mask guard" }
    var logStarted: String { language == .zh ? "开始守护" : "Started guard" }
    var logStopped: String { language == .zh ? "停止守护" : "Stopped guard" }
    var logLeft: String { language == .zh ? "离开前台，相机和铃声已停" : "Left the foreground. Camera and alarm off." }
    var logLeftLight: String { language == .zh ? "离开前台，补光已停" : "Left the foreground. Light off." }
    var logCameraDenied: String { language == .zh ? "没有相机权限，没有开始" : "Camera access is off. Did not start." }
    var logTorchOff: String { language == .zh ? "手电筒已关" : "Torch off" }
    var logAlarmStopped: String { language == .zh ? "停止铃声，守护还在继续" : "Sound stopped. Guard continues." }
    var logAlarmSilent: String {
        language == .zh ? "铃声没有播出来。请把侧边音量调大，并允许通知。" : "The siren did not play. Raise the side volume and allow notifications."
    }
    var logManyFailures: String {
        language == .zh ? "连续多轮没拍成。请看手机是否对准枕头。" : "Several rounds failed. Check that the phone faces the pillow."
    }

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
            ? (language == .zh ? "麦克风：已允许（本机听鼾，不上传）" : "Microphone: allowed (on-device snore, not uploaded)")
            : (language == .zh ? "麦克风：未允许，只看相机" : "Microphone: not allowed. Camera only.")
    }

    func logNotify(_ ok: Bool) -> String {
        ok
            ? (language == .zh ? "通知：已允许（脱落时提醒）" : "Notifications: allowed (mask-off alert)")
            : (language == .zh ? "通知：未允许。应用内铃声仍会响。" : "Notifications: not allowed. The in-app siren can still play.")
    }

    func logNotifyFailed(_ message: String) -> String {
        let shown = message.trimmingCharacters(in: .whitespacesAndNewlines)
        let safe = shown.isEmpty ? valueUnknown : shown
        return language == .zh ? "通知权限申请失败：\(safe)" : "Notification request failed: \(safe)"
    }

    func logRound(time: String, result: String, inferMs: Int, captureToJudgeMs: Int, roundMs: Int) -> String {
        language == .zh
            ? "\(time)  \(result)  判定\(inferMs)ms  拍到判定\(captureToJudgeMs)ms  整轮\(roundMs)ms"
            : "\(time)  \(result)  judge \(inferMs) ms  capture-to-judge \(captureToJudgeMs) ms  round \(roundMs) ms"
    }

    func logAlarm(_ summary: String) -> String {
        let shown = summary.trimmingCharacters(in: .whitespacesAndNewlines)
        let safe = shown.isEmpty ? (language == .zh ? "可能脱落" : "Mask may be off") : shown
        return language == .zh ? "响铃：\(safe)" : "Alarm: \(safe)"
    }

    func logSample(_ result: String) -> String {
        language == .zh ? "已拍样张：\(result)" : "Sample saved: \(result)"
    }

    func volumeLow(_ percent: Int) -> String {
        language == .zh
            ? "媒体音量大约 \(percent)%。请把侧边音量调大，否则可能叫不醒。应用不能强行改系统音量。静音拨片不会关掉这次铃声。"
            : "Media volume is about \(percent)%. Raise the side buttons or the alarm may not wake you. The app cannot force the system volume. The silent switch does not mute this siren."
    }

    var notifyAlarmTitle: String { language == .zh ? "面罩可能脱落" : "Mask may be off" }
    var notifyAlarmBody: String {
        language == .zh
            ? "请戴回面罩。铃声要你自己停。锁屏后不会继续拍照。"
            : "Put the mask back on. Stop the sound yourself. Locking the phone stops photos."
    }
    var notifyLeftTitle: String { language == .zh ? "面罩守护已停" : "Mask guard stopped" }
    var notifyLeftBody: String {
        language == .zh
            ? "离开前台或锁屏后相机会关掉。回到应用再点开始守护。"
            : "The camera stops when you leave the app or lock the phone. Come back and tap start."
    }

    var micButton: String { language == .zh ? "申请麦克风（本机听鼾）" : "Allow microphone (on-device snore)" }
    var notifyButton: String { language == .zh ? "申请通知（脱落时提醒）" : "Allow notifications (mask-off alert)" }
    var openSettings: String { language == .zh ? "去系统设置打开权限" : "Open Settings" }
    var micGranted: String {
        language == .zh ? "麦克风：已允许。听鼾只在本机算，不上传声音。" : "Microphone: allowed. Snore stays on this phone and is not uploaded."
    }
    var micDenied: String {
        language == .zh ? "麦克风：未允许。听鼾不会开，相机监测不受影响。" : "Microphone: not allowed. Snore stays off. The camera can still guard."
    }
    var notifyGranted: String {
        language == .zh ? "通知：已允许。面罩可能脱落时会通知你。锁屏后不会继续监测。" : "Notifications: allowed. A mask-off alert can be posted. Locking the phone stops monitoring."
    }
    var notifyDenied: String {
        language == .zh ? "通知：未允许。人在这个画面里时，应用内铃声仍会响。" : "Notifications: not allowed. The in-app siren still plays while this screen is open."
    }
    var cameraDenied: String {
        language == .zh
            ? "还没有相机权限。请在系统设置里允许，再点开始守护。"
            : "Camera access is off. Allow it in Settings, then start."
    }
    var aimSoftResult: String { language == .zh ? "镜头可能没对准" : "Aim looks off" }
    var aimSoftHint: String {
        language == .zh
            ? "镜头可能没对准。这一张没有上传，守护还在。"
            : "The camera may not be aimed. This photo was not uploaded. Guard is still on."
    }
    var pausedResult: String { language == .zh ? "已暂停上传" : "Uploads paused" }
    var pausedReason: String {
        language == .zh
            ? "连续看到面罩脱落，已停止上传。守护还在，铃声仍按上次结果。戴好了就点「我戴好了」。"
            : "Uploads stopped after repeated mask-off results. Guard and the alarm continue from the last result. Tap when the mask is back on."
    }
    var pausedAlarm: String {
        language == .zh ? "已暂停上传，仍按上次脱落提醒" : "Uploads paused. Still using the last mask-off result."
    }
    var resumeUpload: String { language == .zh ? "我戴好了" : "Mask is on" }
    func resumeRemaining(_ count: Int) -> String {
        language == .zh ? "今晚还可恢复上传 \(count) 次（中午重置）" : "\(count) upload resumes left tonight (resets at noon)"
    }
    var resumeExhausted: String {
        language == .zh
            ? "今晚的恢复次数已用完。继续本机听鼾兜底，不再上传画面。"
            : "No upload resumes left tonight. Snore detection continues on this phone, without sending photos."
    }
    func resumeAccepted(_ count: Int) -> String {
        language == .zh ? "已恢复上传，今晚还可恢复 \(count) 次" : "Uploads resumed. \(count) resumes left tonight."
    }
    var localOnlyResult: String { language == .zh ? "本机兜底" : "On-device fallback" }
    var guardInterrupted: String { language == .zh ? "守护已中断，点此恢复" : "Guard interrupted. Tap to resume." }
    var guardFailedTitle: String { language == .zh ? "守护失效" : "Guard stalled" }
    var guardStalled: String {
        language == .zh ? "超过 3 个抓拍间隔没有新的判定" : "No new result for more than 3 capture intervals."
    }
    var logResumedAfterInactive: String {
        language == .zh ? "回到前台，继续监测" : "Back in the foreground. Monitoring again."
    }
    var cloudProbe: String { language == .zh ? "云端熔断中，试探一次" : "Cloud circuit open. Probing once." }
    var circuitLocal: String {
        language == .zh ? "云端连续失败，这几分钟先不上传" : "Cloud failed twice. Skipping uploads for a few minutes."
    }
    func telemetry(prompt: Int, completion: Int) -> String {
        "telemetry kind=round prompt=\(prompt) completion=\(completion)"
    }
    var donationPending: String {
        language == .zh
            ? "图片捐赠已打开，但还没有上传地址，这一帧只记了指标"
            : "Image donation is on, but there is no upload URL. This frame logged metrics only."
    }
    func autoResumed(_ count: Int) -> String {
        language == .zh ? "画面变化已确认，自动恢复上传一次。今晚还可恢复 \(count) 次" : "The scene changed, so uploads resumed once. \(count) resumes left tonight."
    }
    func lastJudgment(_ seconds: Int) -> String {
        language == .zh ? "上次判定 \(seconds) 秒前" : "Last result \(seconds)s ago"
    }
    var resumeAlready: String {
        language == .zh ? "上传本来就是开着的" : "Uploads are already on"
    }
    func uploadStopped(_ streak: Int, _ remaining: Int) -> String {
        language == .zh
            ? "连续 \(streak) 次看到面罩脱落，已停止上传。铃声继续。今晚还可恢复上传 \(remaining) 次"
            : "\(streak) mask-off results in a row. Uploads stopped. The alarm continues. \(remaining) resumes left tonight."
    }

    /// Marketing version and build from the bundle, for example `0.4.1 (6)`.
    var appVersionLabel: String {
        let info = Bundle.main.infoDictionary ?? [:]
        let version = (info["CFBundleShortVersionString"] as? String ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let build = (info["CFBundleVersion"] as? String ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let short = version.isEmpty ? "0.4.2" : version
        guard !build.isEmpty else { return short }
        return "\(short) (\(build))"
    }

    var versionFooter: String {
        language == .zh
            ? "iOS \(appVersionLabel) · 亮屏前台监测 · MIT"
            : "iOS \(appVersionLabel) · foreground monitor · MIT"
    }
}
