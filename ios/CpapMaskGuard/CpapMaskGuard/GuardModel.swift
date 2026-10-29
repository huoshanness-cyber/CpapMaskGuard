import AudioToolbox
import AVFoundation
import Combine
import os
import UIKit
import UserNotifications

struct CaptureReadout: Equatable {
    var capturedAt: Date
    var vote: FrameVote?
    var raw: String
    var alarmed: Bool
    var unjudged: Bool
    var inferMs: Int
    var captureToJudgeMs: Int
    var roundMs: Int
    var aimMissed: Bool = false
    var cloudPaused: Bool = false
    var localOnly: Bool = false
    /// Relay classify budget is empty. Not the upload-resume counter.
    var gatewayLimited: Bool = false
}

@MainActor
final class GuardModel: ObservableObject {
    @Published var language: AppLanguage
    @Published var fillMode: FillLightMode
    @Published var screenBrightness: Double
    @Published var torchEnabled = false
    @Published var torchLevel: Double
    @Published var intervalSeconds: Int
    @Published var phase: GuardPhase = .idle
    @Published var alarming = false
    @Published var torchHardwareOn = false
    @Published var torchStatus = "已关闭"
    @Published var torchError: String?
    @Published var permissionNote: String?
    @Published var roundNote: String?
    @Published var volumeWarning: String?
    @Published var sampleNote: String?
    @Published var micNote: String?
    @Published var notificationNote: String?
    @Published var busy = false
    @Published var whiteScreenActive = false
    @Published var lastReadout: CaptureReadout?
    @Published var lastThumbnail: UIImage?
    @Published var snoreHint = ""
    @Published var health: HealthSnapshot?
    @Published private(set) var logs: [SessionLogLine] = []
    @Published var uploadEnabled = true
    @Published var resumeRemaining = CloudUploadPolicy.dailyResumeLimit
    /// Last classify budget from the relay. Nil until a response includes `quota`.
    @Published private(set) var gatewayQuota: GatewayQuota?
    @Published var judgmentAgeSeconds = -1
    @Published var watchdogStale = false
    /// True while the post-start minute still forbids a mask-off alarm.
    @Published var settling = false
    @Published var settleSecondsLeft = 0
    /// Camera finder is on, before Start or after Stop.
    @Published var aimPreviewOn = false

    private let torch = RearTorch()
    private let cloudMemory = CloudUploadMemory()
    private let capture = FrameCapture()
    private let night = NightAudio()
    private let debouncer = Debouncer()
    private let logger = Logger(subsystem: "app.cpapmaskguard", category: "monitor")
    private var savedBrightness: CGFloat?
    private var transition = 0
    private var torchTicket = 0
    private var alarmGeneration = 0
    private var ignoreUntil: Date?
    private var consecutiveFailures = 0
    private var cameraLive = false
    private var monitorTask: Task<Void, Never>?
    private var guardHealth = GuardHealthState()
    private var sceneWatch = SceneWatch()
    private var lastJudgmentAt: Date?
    private var guardStartedAt: Date?
    private var maskOnStreak = 0
    private var alarmArmed = false
    private var settleTimer: Timer?
    private var lastGatewayProbe = Date.distantPast
    private var cycleQuotaProbe = false

    init() {
        let defaults = UserDefaults.standard
        language = AppLanguage(rawValue: defaults.string(forKey: Key.language) ?? "") ?? .zh
        fillMode = FillLightMode(rawValue: defaults.string(forKey: Key.fillMode) ?? "") ?? .frontScreen
        let brightness = defaults.object(forKey: Key.brightness) as? Double ?? 0.85
        screenBrightness = min(max(brightness, 0.05), 1)
        let level = defaults.object(forKey: Key.torchLevel) as? Double ?? TorchLevel.suggested
        torchLevel = TorchLevel.clamp(level)
        let storedInterval = defaults.object(forKey: Key.interval) as? Int ?? 8
        intervalSeconds = min(max(storedInterval, 5), 10)
        logs = [SessionLogLine(id: UUID(), date: Date(), text: L10n(language: language).logOpened)]
        snoreHint = L10n(language: language).snoreLine(.off)
        let stored = cloudMemory.load(now: Date())
        uploadEnabled = stored.uploadEnabled
        resumeRemaining = CloudUploadPolicy.remaining(stored)
        gatewayQuota = GatewayQuotaStore.load()
        capture.setSessionHandler { [weak self] raw in
            Task { @MainActor in
                self?.noteCameraSession(raw)
            }
        }
        writeEvent(kind: "app", note: l10n.logOpened, vote: nil, error: nil, httpStatus: nil, quotaProbe: false, sleepDay: nil)
        NotificationCenter.default.addObserver(
            forName: UIApplication.willTerminateNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.record(self?.l10n.appWillStop ?? "应用将退出", kind: "app")
            }
        }
    }

    func record(_ text: String, kind: String = "lifecycle") {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        appendMemory(trimmed)
        writeEvent(kind: kind, note: trimmed, vote: nil, error: nil, httpStatus: nil, quotaProbe: false, sleepDay: nil)
    }

    private func appendMemory(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        logs.append(SessionLogLine(id: UUID(), date: Date(), text: trimmed))
        if logs.count > 200 {
            logs.removeFirst(logs.count - 200)
        }
        logger.info("\(trimmed, privacy: .public)")
    }

    var l10n: L10n { L10n(language: language) }

    /// Banner when the relay classify budget is low or empty. Separate from resume copy.
    var gatewayQuotaLine: String? {
        if gatewayExhaustedNow() {
            if let quota = quotaForToday() {
                return l10n.gatewayQuotaExhausted(quota)
            }
            return l10n.gatewayQuotaExhaustedUnknown
        }
        if let quota = quotaForToday(), GatewayQuotaPolicy.isLow(quota) {
            return l10n.gatewayQuotaLow(quota)
        }
        return nil
    }

    var displayResult: String {
        guard let lastReadout else { return "" }
        if lastReadout.gatewayLimited { return l10n.gatewayLimitedResult }
        if lastReadout.aimMissed { return l10n.aimSoftResult }
        if lastReadout.localOnly && !lastReadout.alarmed && !alarming { return l10n.localOnlyResult }
        if lastReadout.cloudPaused && !lastReadout.alarmed && !alarming { return l10n.pausedResult }
        if lastReadout.unjudged { return l10n.unjudged }
        return l10n.resultLabel(lastReadout.vote ?? .uncertain, alarmed: lastReadout.alarmed || alarming)
    }

    var displayReason: String {
        guard let lastReadout else { return "" }
        if lastReadout.gatewayLimited { return l10n.gatewayLimitedReason }
        if lastReadout.aimMissed { return l10n.aimSoftHint }
        if lastReadout.localOnly { return l10n.resumeExhausted }
        if lastReadout.cloudPaused { return l10n.pausedReason }
        if lastReadout.unjudged { return l10n.noKeyReason }
        return l10n.cloudReason(lastReadout.vote ?? .uncertain, raw: lastReadout.raw)
    }

    var previewLayer: AVCaptureVideoPreviewLayer { capture.previewLayer }

    /// Live finder before Start, and again during the lie-down minute.
    var showsAimPreview: Bool {
        guard cameraLive else { return false }
        if settling { return true }
        if phase == .guarding { return false }
        return aimPreviewOn
    }

    var headline: String {
        if settling && (phase == .guarding || phase == .idle) {
            return l10n.settleHeadline(settleSecondsLeft)
        }
        return l10n.headline(
            phase: phase,
            alarming: alarming,
            failures: consecutiveFailures,
            vote: lastReadout?.vote,
            unjudged: lastReadout?.unjudged ?? false,
            aimMissed: lastReadout?.aimMissed ?? false
        )
    }

    var detail: String {
        if settling && (phase == .guarding || phase == .idle) {
            return l10n.settleDetail
        }
        return l10n.detail(
            phase: phase,
            mode: fillMode,
            torchOn: torchHardwareOn,
            interval: intervalSeconds,
            alarming: alarming,
            reason: lastReadout == nil ? nil : displayReason,
            snore: snoreHint
        )
    }

    func setLanguage(_ language: AppLanguage) {
        self.language = language
        persist()
        snoreHint = l10n.snoreLine(night.snapshot())
    }

    func setInterval(_ seconds: Int) {
        intervalSeconds = min(max(seconds, 5), 10)
        persist()
    }

    func setFillMode(_ mode: FillLightMode) async {
        let changed = fillMode != mode
        fillMode = mode
        persist()
        if mode == .frontScreen {
            torchEnabled = false
            if phase == .guarding {
                beginWhiteScreen()
                await retuneCapture()
            } else {
                endWhiteScreen()
                await lightsOffTorch()
            }
        } else if phase == .guarding {
            endWhiteScreen()
            await retuneCapture()
        } else {
            endWhiteScreen()
            await lightsOffTorch()
        }
        if changed {
            record(l10n.logFill(mode))
        }
    }

    func setScreenBrightness(_ value: Double) {
        screenBrightness = min(max(value, 0.05), 1)
        persist()
        guard whiteScreenActive else { return }
        UIScreen.main.brightness = CGFloat(screenBrightness)
    }

    func setTorchEnabled(_ on: Bool) async {
        if fillMode != .rearAmbient {
            torchEnabled = false
            if phase == .guarding {
                let report = await capture.setTorch(enabled: false, level: 0)
                applyTorchReport(report)
            } else {
                await lightsOffTorch()
            }
            return
        }
        torchEnabled = on
        if phase == .guarding || cameraLive {
            let report = await capture.setTorch(enabled: on && fillMode == .rearAmbient, level: Float(torchLevel))
            applyTorchReport(report)
        } else if on {
            await applyTorch()
        } else {
            await lightsOffTorch()
        }
        record(torchHardwareOn ? l10n.logTorchOn(torchStatus) : l10n.logTorchOff)
    }

    func setTorchLevel(_ value: Double) {
        torchLevel = TorchLevel.clamp(value)
        persist()
        guard torchEnabled, fillMode == .rearAmbient else { return }
        Task { await pushTorchLevel() }
    }

    func start() async {
        if busy || phase == .guarding { return }
        busy = true
        defer { busy = false }
        transition += 1
        let token = transition
        alarming = false
        alarmGeneration += 1
        night.stopAlarm()
        roundNote = nil
        permissionNote = nil
        consecutiveFailures = 0
        guardHealth = GuardHealthState()
        lastJudgmentAt = nil
        judgmentAgeSeconds = -1
        watchdogStale = false
        debouncer.reset()
        ignoreUntil = nil
        health = nil
        guardStartedAt = Date()
        maskOnStreak = 0
        alarmArmed = false
        settling = true
        settleSecondsLeft = SettleGate.secondsRemaining(elapsed: 0)
        startSettleClock()
        await night.stopSnore()
        await torch.shutdown()
        torchHardwareOn = false

        let allowed = await ensureCamera()
        guard token == transition else { return }
        guard allowed else {
            if token == transition { endSettle() }
            phase = .idle
            record(l10n.logCameraDenied, kind: "guard")
            return
        }
        guard await waitUntilInteractive(token) else { return }

        let position: AVCaptureDevice.Position = fillMode == .frontScreen ? .front : .back
        let torchOn = fillMode == .rearAmbient && torchEnabled
        do {
            let report = try await capture.start(position: position, torchEnabled: torchOn, level: Float(torchLevel))
            cameraLive = true
            applyTorchReport(report)
        } catch {
            cameraLive = false
            if token == transition { endSettle() }
            phase = .idle
            let message = l10n.failureMessage(error)
            roundNote = l10n.guardInterrupted
            record("\(l10n.guardInterrupted) \(message)", kind: "guard")
            UIApplication.shared.isIdleTimerDisabled = false
            alertGuard(title: l10n.guardInterrupted, body: message)
            return
        }
        guard await waitUntilInteractive(token) else { return }

        phase = .guarding
        UIApplication.shared.isIdleTimerDisabled = true
        if fillMode == .frontScreen {
            torchEnabled = false
            beginWhiteScreen()
        } else {
            endWhiteScreen()
        }
        record(l10n.logStarted, kind: "guard")
        warnIfVolumeLow()
        guard await waitUntilInteractive(token) else { return }
        launchLoop(token: token)
        Task { [weak self] in
            await self?.prepareSideChannels()
        }
    }

    func stop() async {
        transition += 1
        let token = transition
        alarmGeneration += 1
        monitorTask?.cancel()
        monitorTask = nil
        busy = true
        defer { if token == transition { busy = false } }
        phase = .stopped
        alarming = false
        cameraLive = false
        aimPreviewOn = false
        endSettle()
        night.stopAlarm()
        await night.stopSnore()
        await capture.stop()
        torchEnabled = false
        torchHardwareOn = false
        UIApplication.shared.isIdleTimerDisabled = false
        endWhiteScreen()
        await lightsOffTorch()
        snoreHint = l10n.snoreLine(.off)
        record(l10n.logStopped, kind: "guard")
    }

    func leaveForeground() async {
        let wasGuarding = phase == .guarding || alarming
        let active = wasGuarding || cameraLive || torchHardwareOn || whiteScreenActive
        guard active else { return }
        transition += 1
        alarmGeneration += 1
        monitorTask?.cancel()
        monitorTask = nil
        alarming = false
        cameraLive = false
        aimPreviewOn = false
        endSettle()
        night.stopAlarm()
        await night.stopSnore()
        await capture.stop()
        torchEnabled = false
        torchHardwareOn = false
        UIApplication.shared.isIdleTimerDisabled = false
        endWhiteScreen()
        await lightsOffTorch()
        snoreHint = l10n.snoreLine(.off)
        phase = .leftForeground
        if wasGuarding {
            record(l10n.logLeft, kind: "guard")
            await postLeftNotification()
        } else {
            record(l10n.logLeftLight, kind: "guard")
        }
    }

    func resumeLoopIfStillGuarding() {
        guard phase == .guarding else { return }
        if monitorTask == nil {
            launchLoop(token: transition)
            record(l10n.logResumedAfterInactive, kind: "guard")
        }
    }

    func resumeUpload() {
        sceneWatch = SceneWatch()
        switch cloudMemory.resume(now: Date()) {
        case .accepted(let state):
            publishCloud(state)
            let text = l10n.resumeAccepted(CloudUploadPolicy.remaining(state))
            roundNote = text
            record(text, kind: "upload")
        case .denied(let state):
            publishCloud(state)
            roundNote = l10n.resumeExhausted
            record(l10n.resumeExhausted, kind: "upload")
        case .alreadyOn(let state):
            publishCloud(state)
            roundNote = l10n.resumeAlready
        }
    }

    func acknowledgeAlarm() {
        guard alarming else { return }
        alarming = false
        alarmGeneration += 1
        night.stopAlarm()
        ignoreUntil = Date().addingTimeInterval(2)
        debouncer.reset()
        record(l10n.logAlarmStopped)
    }

    func captureSample() async {
        guard phase != .guarding else {
            sampleNote = l10n.sampleWhileGuarding
            return
        }
        if busy { return }
        busy = true
        defer { busy = false }
        sampleNote = nil
        let allowed = await ensureCamera()
        guard allowed else {
            sampleNote = l10n.cameraDenied
            record(l10n.logCameraDenied)
            return
        }
        guard isForeground else { return }
        await torch.shutdown()
        torchHardwareOn = false
        let position: AVCaptureDevice.Position = fillMode == .frontScreen ? .front : .back
        do {
            _ = try await capture.start(position: position, torchEnabled: false, level: 0)
            cameraLive = true
            guard isForeground else {
                await leaveForeground()
                return
            }
            // Silent video-frame JPEG. Same Data the aim gate and cloud path already take.
            let raw = try await capture.takeJPEG()
            await capture.stop()
            cameraLive = false
            guard let payload = ImageEncode.jpegForCloud(raw) else {
                sampleNote = l10n.failureMessage(CaptureError.empty)
                return
            }
            var result = l10n.unjudged
            var vote = "SAMPLE"
            var latency = 0
            var reason = l10n.sampleUnjudgedReason
            let aimed = (await aimScan(payload)).verdict.valid
            if !aimed {
                result = l10n.aimSoftResult
                vote = "AIM_MISS"
                reason = l10n.aimSoftHint
                _ = cloudMemory.noteAimInvalid(now: Date())
                publishCloud(cloudMemory.load(now: Date()))
            } else if AppSecrets.cloudConfigured && !cloudMemory.load(now: Date()).uploadEnabled {
                result = l10n.pausedResult
                vote = "CLOUD_PAUSED"
                reason = l10n.pausedReason
            } else if AppSecrets.cloudConfigured {
                do {
                    let started = Date()
                    let classified = try await classifyCloud(payload)
                    rememberClassificationQuota(classified)
                    latency = MonitorClock.milliseconds(since: started)
                    let updated = cloudMemory.noteCloud(classified.vote, now: Date())
                    publishCloud(updated)
                    result = l10n.resultLabel(classified.vote, alarmed: false)
                    vote = classified.vote.rawValue
                    reason = l10n.cloudReason(classified.vote, raw: classified.raw)
                    if !updated.uploadEnabled {
                        record(l10n.uploadStopped(updated.consecutiveMaskOff, CloudUploadPolicy.remaining(updated)), kind: "upload")
                    }
                } catch {
                    let http = absorbRelayFailure(error)
                    reason = l10n.failureMessage(error)
                    let code = http?.status == 429
                        ? (http?.errorCode ?? "quota_exhausted")
                        : (http?.errorCode ?? (http == nil ? "error" : "http_\(http?.status ?? 0)"))
                    writeEvent(
                        kind: "cycle",
                        note: reason,
                        vote: nil,
                        error: code,
                        httpStatus: http?.status,
                        quotaProbe: false,
                        sleepDay: http?.sleepDay
                    )
                }
            }
            FrameArchive.save(ArchivePayload(
                capturedAt: Date(),
                result: result,
                vote: vote,
                latencyMs: latency,
                roundMs: latency,
                fillMode: fillMode.rawValue,
                reason: reason,
                jpeg: payload
            ))
            sampleNote = result
            lastThumbnail = UIImage(data: payload)
            record(l10n.logSample(result))
        } catch {
            await capture.stop()
            cameraLive = false
            let message = l10n.failureMessage(error)
            sampleNote = message
            record(message)
        }
    }

    func requestMicrophone() async {
        let ok = await AVCaptureDevice.requestAccess(for: .audio)
        micNote = ok ? l10n.micGranted : l10n.micDenied
        record(l10n.logMic(ok))
        if ok, phase == .guarding {
            await night.startSnore()
        }
        snoreHint = l10n.snoreLine(night.snapshot())
    }

    func requestNotifications() async {
        do {
            let ok = try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
            notificationNote = ok ? l10n.notifyGranted : l10n.notifyDenied
            record(l10n.logNotify(ok))
        } catch {
            notificationNote = error.localizedDescription
            record(l10n.logNotifyFailed(error.localizedDescription))
        }
    }

    func openSystemSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    private func launchLoop(token: Int) {
        monitorTask?.cancel()
        monitorTask = Task { [weak self] in
            await self?.runMonitor(token: token)
        }
    }

    private func runMonitor(token: Int) async {
        while !Task.isCancelled {
            refreshSettle()
            if !(await pauseIfInactive(token)) { return }
            guard stillGuarding(token) else { return }
            tickWatchdog()
            let started = Date()
            do {
                if fillMode == .rearAmbient {
                    let report = await capture.setTorch(enabled: torchEnabled, level: Float(torchLevel))
                    guard stillGuarding(token) else { return }
                    applyTorchReport(report)
                }
                // Silent video-frame JPEG. Interval below is unchanged.
                let raw = try await capture.takeJPEG()
                guard stillGuarding(token) else { return }
                let capturedAt = Date()
                guard let payload = ImageEncode.jpegForCloud(raw) else {
                    throw CaptureError.empty
                }
                let scan = await aimScan(payload)
                guard stillGuarding(token) else { return }
                if !scan.verdict.valid {
                    let updated = cloudMemory.noteAimInvalid(now: capturedAt)
                    publishCloud(updated)
                    publishAimMiss(payload: payload, capturedAt: capturedAt, started: started)
                } else if !AppSecrets.cloudConfigured {
                    publishUnjudged(payload: payload, capturedAt: capturedAt, started: started)
                } else if gatewayGate(now: capturedAt) == .skip {
                    publishGatewaySkip(payload: payload, capturedAt: capturedAt, started: started)
                } else {
                    let probing = gatewayGate(now: capturedAt) == .probe
                    let state = cloudMemory.load(now: capturedAt)
                    if !state.uploadEnabled {
                        if CloudUploadPolicy.remaining(state) == 0 {
                            publishLocalOnly(payload: payload, capturedAt: capturedAt, started: started)
                        } else {
                            publishCloudPaused(payload: payload, capturedAt: capturedAt, started: started)
                        }
                        considerAutoResume(scan.signature, now: capturedAt)
                    } else {
                        if probing { lastGatewayProbe = capturedAt }
                        cycleQuotaProbe = probing
                        try await classifyOrSkip(
                            payload: payload,
                            capturedAt: capturedAt,
                            started: started,
                            token: token
                        )
                    }
                }
            } catch {
                guard stillGuarding(token) else { return }
                noteRoundError(error)
                maybeAudioOnlyAlarm()
            }
            guard stillGuarding(token) else { return }
            let spent = Date().timeIntervalSince(started)
            var left = Double(intervalSeconds) - spent
            while left > 0 {
                if !(await pauseIfInactive(token)) { return }
                let slice = min(left, 1.5)
                do {
                    try await Task.sleep(nanoseconds: UInt64(slice * 1_000_000_000))
                } catch {
                    return
                }
                guard stillGuarding(token) else { return }
                tickWatchdog()
                maybeAudioOnlyAlarm()
                left -= slice
            }
        }
    }

    private func classifyOrSkip(payload: Data, capturedAt: Date, started: Date, token: Int) async throws {
        var circuit = loadCircuit()
        let action = CloudCircuitPolicy.action(circuit, now: capturedAt)
        if action == .local {
            publishCircuitSkip(payload: payload, capturedAt: capturedAt, started: started)
            return
        }
        if action == .probe {
            circuit = CloudCircuitPolicy.noteProbe(circuit, now: capturedAt)
            saveCircuit(circuit)
            record(l10n.cloudProbe)
        }
        let classifyStarted = Date()
        do {
            let classified = try await classifyCloud(payload)
            guard stillGuarding(token) else {
                cycleQuotaProbe = false
                return
            }
            saveCircuit(CloudCircuitPolicy.onSuccess(circuit))
            noteMaskSeen(classified.vote)
            let armedNow = maskAlarmArmed(at: capturedAt)
            let updated: CloudUploadState
            if !armedNow && classified.vote == .maskOff {
                // Sitting up and arranging tubing must not burn the upload pause.
                updated = cloudMemory.load(now: capturedAt)
            } else {
                updated = cloudMemory.noteCloud(classified.vote, now: capturedAt)
            }
            publishCloud(updated)
            if !updated.uploadEnabled {
                sceneWatch = SceneWatch()
                record(l10n.uploadStopped(updated.consecutiveMaskOff, CloudUploadPolicy.remaining(updated)), kind: "upload")
            }
            publishClassified(
                classified,
                jpeg: payload,
                capturedAt: capturedAt,
                classifyStarted: classifyStarted,
                started: started
            )
        } catch {
            saveCircuit(CloudCircuitPolicy.onFailure(circuit, now: Date()))
            throw error
        }
    }

    private func aimScan(_ jpeg: Data) async -> AimScan {
        guard let image = UIImage(data: jpeg)?.cgImage else {
            return AimScan(
                verdict: AimVerdict(valid: false, reason: .black),
                signature: []
            )
        }
        return await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                continuation.resume(returning: AimScanner.evaluate(image))
            }
        }
    }

    /// `.inactive` pauses the capture loop. It does not leave the guard.
    private func pauseIfInactive(_ token: Int) async -> Bool {
        while UIApplication.shared.applicationState == .inactive {
            if !stillGuarding(token) { return false }
            tickWatchdog()
            do {
                try await Task.sleep(nanoseconds: 300_000_000)
            } catch {
                return false
            }
        }
        return stillGuarding(token)
    }

    private func waitUntilInteractive(_ token: Int) async -> Bool {
        while UIApplication.shared.applicationState == .inactive {
            if token != transition { return false }
            do {
                try await Task.sleep(nanoseconds: 200_000_000)
            } catch {
                return false
            }
        }
        if token != transition { return false }
        if UIApplication.shared.applicationState == .background {
            await leaveForeground()
            return false
        }
        return true
    }

    private func publishCloud(_ state: CloudUploadState) {
        uploadEnabled = state.uploadEnabled
        resumeRemaining = CloudUploadPolicy.remaining(state)
    }

    private func publishAimMiss(payload: Data, capturedAt: Date, started: Date) {
        let roundMs = MonitorClock.milliseconds(since: started)
        lastReadout = CaptureReadout(
            capturedAt: capturedAt,
            vote: .uncertain,
            raw: "",
            alarmed: false,
            unjudged: false,
            inferMs: 0,
            captureToJudgeMs: roundMs,
            roundMs: roundMs,
            aimMissed: true
        )
        lastThumbnail = UIImage(data: payload)
        roundNote = l10n.aimSoftHint
        consecutiveFailures = 0
        markJudgment(capturedAt)
        health = HealthMath.score(
            captureToJudgeMs: roundMs,
            roundMs: roundMs,
            consecutiveFailures: 0,
            language: language
        )
        let line = l10n.logRound(
            time: SessionLogClock.precise(from: capturedAt),
            result: l10n.aimSoftResult,
            inferMs: 0,
            captureToJudgeMs: roundMs,
            roundMs: roundMs
        )
        recordRound(line, vote: "AIM_MISS", error: nil, http: nil)
        FrameArchive.save(ArchivePayload(
            capturedAt: capturedAt,
            result: l10n.aimSoftResult,
            vote: "AIM_MISS",
            latencyMs: 0,
            roundMs: roundMs,
            fillMode: fillMode.rawValue,
            reason: l10n.aimSoftHint,
            jpeg: payload
        ))
        maybeAudioOnlyAlarm()
    }

    private func publishCloudPaused(payload: Data, capturedAt: Date, started: Date) {
        let state = cloudMemory.load(now: capturedAt)
        let hold = state.lastCloudWasMaskOff
        let roundMs = MonitorClock.milliseconds(since: started)
        lastReadout = CaptureReadout(
            capturedAt: capturedAt,
            vote: hold ? .maskOff : .uncertain,
            raw: "",
            alarmed: hold,
            unjudged: false,
            inferMs: 0,
            captureToJudgeMs: roundMs,
            roundMs: roundMs,
            cloudPaused: true
        )
        lastThumbnail = UIImage(data: payload)
        roundNote = nil
        consecutiveFailures = 0
        markJudgment(capturedAt)
        let result = hold ? l10n.resultLabel(.maskOff, alarmed: true) : l10n.pausedResult
        let line = l10n.logRound(
            time: SessionLogClock.precise(from: capturedAt),
            result: result,
            inferMs: 0,
            captureToJudgeMs: roundMs,
            roundMs: roundMs
        )
        recordRound(line, vote: hold ? FrameVote.maskOff.rawValue : "CLOUD_PAUSED", error: nil, http: nil)
        FrameArchive.save(ArchivePayload(
            capturedAt: capturedAt,
            result: result,
            vote: hold ? FrameVote.maskOff.rawValue : "CLOUD_PAUSED",
            latencyMs: 0,
            roundMs: roundMs,
            fillMode: fillMode.rawValue,
            reason: hold ? l10n.pausedAlarm : l10n.pausedReason,
            jpeg: payload
        ))
        guard hold else { return }
        guard maskAlarmArmed(at: capturedAt) else { return }
        beginAlarm(summary: l10n.pausedAlarm)
    }

    private func publishClassified(
        _ classified: CloudClassification,
        jpeg: Data,
        capturedAt: Date,
        classifyStarted: Date,
        started: Date
    ) {
        rememberClassificationQuota(classified)
        let inferMs = MonitorClock.milliseconds(since: classifyStarted)
        let captureToJudgeMs = MonitorClock.milliseconds(since: capturedAt)
        let roundMs = MonitorClock.milliseconds(since: started)
        let armed = maskAlarmArmed(at: capturedAt)
        if !armed {
            debouncer.reset()
        }
        let cameraShouldAlarm = armed && debouncer.onVote(classified.vote)
        let snap = night.snapshot()
        snoreHint = l10n.snoreLine(snap)
        let summary = l10n.cloudReason(classified.vote, raw: classified.raw)
        let decision: AlarmDecision = armed
            ? SnoreFusion.fuse(
                cameraShouldAlarm: cameraShouldAlarm,
                cameraVote: classified.vote,
                snoreConfirmed: snap.state == .confirmed,
                snoreConfirmedForMs: snap.confirmedForMs
            )
            : .quiet
        if case .pendingSnore(let elapsed, let need) = decision {
            snoreHint = l10n.snorePending(elapsed, need, base: snoreHint)
        }
        let alarmed = decision.shouldAlarm
        lastReadout = CaptureReadout(
            capturedAt: capturedAt,
            vote: classified.vote,
            raw: classified.raw,
            alarmed: alarmed,
            unjudged: false,
            inferMs: inferMs,
            captureToJudgeMs: captureToJudgeMs,
            roundMs: roundMs
        )
        lastThumbnail = UIImage(data: jpeg)
        roundNote = nil
        consecutiveFailures = 0
        markJudgment(capturedAt)
        health = HealthMath.score(
            captureToJudgeMs: captureToJudgeMs,
            roundMs: roundMs,
            consecutiveFailures: 0,
            language: language
        )
        let result = l10n.resultLabel(classified.vote, alarmed: alarmed)
        if classified.promptTokens >= 0 || classified.completionTokens >= 0 {
            record("usage prompt=\(classified.promptTokens) completion=\(classified.completionTokens)")
        }
        record(l10n.telemetry(prompt: classified.promptTokens, completion: classified.completionTokens))
        if imageDonationEnabled {
            record(l10n.donationPending)
        }
        let line = l10n.logRound(
            time: SessionLogClock.precise(from: capturedAt),
            result: result,
            inferMs: inferMs,
            captureToJudgeMs: captureToJudgeMs,
            roundMs: roundMs
        )
        let probe = cycleQuotaProbe
        cycleQuotaProbe = false
        recordRound(line, vote: classified.vote.rawValue, error: nil, http: nil, quotaProbe: probe)
        FrameArchive.save(ArchivePayload(
            capturedAt: capturedAt,
            result: result,
            vote: classified.vote.rawValue,
            latencyMs: inferMs,
            roundMs: roundMs,
            fillMode: fillMode.rawValue,
            reason: summary,
            jpeg: jpeg,
            promptTokens: classified.promptTokens,
            completionTokens: classified.completionTokens
        ))
        guard alarmed else { return }
        debouncer.reset()
        beginAlarm(summary: l10n.decisionLine(decision, fallback: summary))
    }

    private func publishUnjudged(payload: Data, capturedAt: Date, started: Date) {
        let roundMs = MonitorClock.milliseconds(since: started)
        lastReadout = CaptureReadout(
            capturedAt: capturedAt,
            vote: nil,
            raw: "",
            alarmed: false,
            unjudged: true,
            inferMs: 0,
            captureToJudgeMs: roundMs,
            roundMs: roundMs
        )
        lastThumbnail = UIImage(data: payload)
        roundNote = l10n.noKeyReason
        consecutiveFailures = 0
        markJudgment(capturedAt)
        let line = l10n.logRound(
            time: SessionLogClock.precise(from: capturedAt),
            result: l10n.unjudged,
            inferMs: 0,
            captureToJudgeMs: roundMs,
            roundMs: roundMs
        )
        recordRound(line, vote: "UNJUDGED", error: nil, http: nil)
        FrameArchive.save(ArchivePayload(
            capturedAt: capturedAt,
            result: l10n.unjudged,
            vote: "UNJUDGED",
            latencyMs: 0,
            roundMs: roundMs,
            fillMode: fillMode.rawValue,
            reason: l10n.noKeyReason,
            jpeg: payload
        ))
        maybeAudioOnlyAlarm()
    }

    private func noteRoundError(_ error: Error) {
        consecutiveFailures += 1
        let http = absorbRelayFailure(error)
        let message = l10n.failureMessage(error)
        let probe = cycleQuotaProbe
        cycleQuotaProbe = false
        let remainingEmpty = (http?.quota?.classifyRemaining ?? 1) <= 0 && http?.quota != nil
        let quotaHit = http?.status == 429 || remainingEmpty
        if quotaHit {
            roundNote = nil
            lastReadout = CaptureReadout(
                capturedAt: Date(),
                vote: nil,
                raw: "",
                alarmed: false,
                unjudged: false,
                inferMs: 0,
                captureToJudgeMs: 0,
                roundMs: 0,
                gatewayLimited: true
            )
            let text = gatewayQuotaLine ?? l10n.gatewayLimitedReason
            recordRound(
                text,
                vote: nil,
                error: http?.errorCode ?? "quota_exhausted",
                http: http?.status,
                quotaProbe: probe,
                sleepDay: http?.sleepDay
            )
        } else {
            roundNote = message
            recordRound(
                message,
                vote: nil,
                error: cycleErrorCode(error, http: http),
                http: http?.status,
                quotaProbe: probe,
                sleepDay: http?.sleepDay
            )
        }
        health = HealthMath.score(
            captureToJudgeMs: nil,
            roundMs: nil,
            consecutiveFailures: consecutiveFailures,
            language: language
        )
        let step = GuardHealthPolicy.onJudgmentFailure(guardHealth, now: Date())
        guardHealth = step.state
        if step.justTripped {
            roundNote = "\(l10n.guardFailedTitle) \(message)"
            alertGuard(title: l10n.guardFailedTitle, body: message)
        }
    }

    private func maybeAudioOnlyAlarm() {
        guard phase == .guarding, !alarming else { return }
        guard maskAlarmArmed(at: Date()) else { return }
        let snap = night.snapshot()
        snoreHint = l10n.snoreLine(snap)
        let decision = SnoreFusion.fuse(
            cameraShouldAlarm: false,
            cameraVote: nil,
            snoreConfirmed: snap.state == .confirmed,
            snoreConfirmedForMs: snap.confirmedForMs
        )
        if case .pendingSnore(let elapsed, let need) = decision {
            snoreHint = l10n.snorePending(elapsed, need, base: snoreHint)
        }
        guard decision.shouldAlarm else { return }
        debouncer.reset()
        let fallback = displayReason.isEmpty ? l10n.waitingFrame : displayReason
        beginAlarm(summary: l10n.decisionLine(decision, fallback: fallback))
    }

    private func beginAlarm(summary: String) {
        guard phase == .guarding, !alarming else { return }
        guard maskAlarmArmed(at: Date()) else { return }
        if let ignoreUntil, ignoreUntil > Date() { return }
        alarming = true
        alarmGeneration += 1
        let generation = alarmGeneration
        record(l10n.logAlarm(summary))
        warnIfVolumeLow()
        Task { await self.postAlarmNotification() }
        Task { [weak self] in
            guard let self else { return }
            await self.night.prepareSessionForAlarm()
            guard generation == self.alarmGeneration, self.alarming else { return }
            let played = self.night.playAlarmOnMain()
            if !played {
                self.record(self.l10n.logAlarmSilent)
            }
        }
    }

    private func prepareSideChannels() async {
        let audioStatus = AVCaptureDevice.authorizationStatus(for: .audio)
        let micOK: Bool
        if audioStatus == .notDetermined {
            micOK = await AVCaptureDevice.requestAccess(for: .audio)
        } else {
            micOK = audioStatus == .authorized
        }
        guard phase == .guarding else { return }
        micNote = micOK ? l10n.micGranted : l10n.micDenied
        record(l10n.logMic(micOK))
        if micOK {
            await night.startSnore()
        }
        snoreHint = l10n.snoreLine(night.snapshot())

        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        let notifyOK: Bool
        if settings.authorizationStatus == .notDetermined {
            notifyOK = (try? await center.requestAuthorization(options: [.alert, .sound])) ?? false
        } else {
            notifyOK = settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional
        }
        guard phase == .guarding else { return }
        notificationNote = notifyOK ? l10n.notifyGranted : l10n.notifyDenied
        record(l10n.logNotify(notifyOK))
    }

    private func postAlarmNotification() async {
        let content = UNMutableNotificationContent()
        content.title = l10n.notifyAlarmTitle
        content.body = l10n.notifyAlarmBody
        content.sound = .default
        let request = UNNotificationRequest(identifier: "mask-off", content: content, trigger: nil)
        try? await UNUserNotificationCenter.current().add(request)
    }

    private func postGuardNotification(title: String, body: String) async {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        let request = UNNotificationRequest(identifier: "guard-failed", content: content, trigger: nil)
        try? await UNUserNotificationCenter.current().add(request)
    }

    private func postLeftNotification() async {
        let content = UNMutableNotificationContent()
        content.title = l10n.notifyLeftTitle
        content.body = l10n.notifyLeftBody
        content.sound = .default
        let request = UNNotificationRequest(identifier: "left-foreground", content: content, trigger: nil)
        try? await UNUserNotificationCenter.current().add(request)
    }

    private func stillGuarding(_ token: Int) -> Bool {
        token == transition && phase == .guarding && !isBackground && !Task.isCancelled
    }

    private var isBackground: Bool {
        UIApplication.shared.applicationState == .background
    }

    private func markJudgment(_ date: Date) {
        lastJudgmentAt = date
        judgmentAgeSeconds = max(0, Int(Date().timeIntervalSince(date)))
        watchdogStale = false
        guardHealth = GuardHealthPolicy.onJudgment(guardHealth)
    }

    private func tickWatchdog() {
        guard phase == .guarding else { return }
        guard let last = lastJudgmentAt else {
            judgmentAgeSeconds = -1
            watchdogStale = false
            return
        }
        let age = max(0, Int(Date().timeIntervalSince(last)))
        judgmentAgeSeconds = age
        let stale = GuardHealthPolicy.stalled(since: last, now: Date(), intervalSeconds: intervalSeconds)
        if stale && !watchdogStale {
            AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
            if !guardHealth.tripped {
                let text = l10n.guardStalled
                roundNote = text
                record(text)
                guardHealth.tripped = true
                guardHealth.reason = .stall
                alertGuard(title: l10n.guardFailedTitle, body: text)
            }
        }
        watchdogStale = stale
    }

    private func alertGuard(title: String, body: String) {
        AudioServicesPlaySystemSound(1007)
        let note = body
        Task { await postGuardNotification(title: title, body: note) }
    }

    private func publishLocalOnly(payload: Data, capturedAt: Date, started: Date) {
        let roundMs = MonitorClock.milliseconds(since: started)
        lastReadout = CaptureReadout(
            capturedAt: capturedAt,
            vote: .uncertain,
            raw: "",
            alarmed: false,
            unjudged: false,
            inferMs: 0,
            captureToJudgeMs: roundMs,
            roundMs: roundMs,
            localOnly: true
        )
        lastThumbnail = UIImage(data: payload)
        roundNote = l10n.resumeExhausted
        consecutiveFailures = 0
        markJudgment(capturedAt)
        recordRound(
            l10n.logRound(
                time: SessionLogClock.precise(from: capturedAt),
                result: l10n.localOnlyResult,
                inferMs: 0,
                captureToJudgeMs: roundMs,
                roundMs: roundMs
            ),
            vote: "LOCAL_ONLY",
            error: "resume_exhausted",
            http: nil
        )
        record(l10n.telemetry(prompt: -1, completion: -1))
        maybeAudioOnlyAlarm()
    }

    private func publishCircuitSkip(payload: Data, capturedAt: Date, started: Date) {
        let roundMs = MonitorClock.milliseconds(since: started)
        lastReadout = CaptureReadout(
            capturedAt: capturedAt,
            vote: .uncertain,
            raw: "",
            alarmed: false,
            unjudged: false,
            inferMs: 0,
            captureToJudgeMs: roundMs,
            roundMs: roundMs
        )
        lastThumbnail = UIImage(data: payload)
        roundNote = l10n.circuitLocal
        consecutiveFailures = 0
        markJudgment(capturedAt)
        recordRound(l10n.circuitLocal, vote: nil, error: "circuit_skip", http: nil)
        record(l10n.telemetry(prompt: -1, completion: -1))
        maybeAudioOnlyAlarm()
    }

    /// Classify budget is empty. Logged without a JPEG. Resume quota is left alone.
    private func publishGatewaySkip(payload: Data, capturedAt: Date, started: Date) {
        let roundMs = MonitorClock.milliseconds(since: started)
        lastReadout = CaptureReadout(
            capturedAt: capturedAt,
            vote: nil,
            raw: "",
            alarmed: false,
            unjudged: false,
            inferMs: 0,
            captureToJudgeMs: roundMs,
            roundMs: roundMs,
            gatewayLimited: true
        )
        lastThumbnail = UIImage(data: payload)
        roundNote = nil
        consecutiveFailures = 0
        markJudgment(capturedAt)
        let text = gatewayQuotaLine ?? l10n.gatewayLimitedReason
        recordRound(text, vote: nil, error: "quota_exhausted", http: nil)
        maybeAudioOnlyAlarm()
    }

    private func considerAutoResume(_ signature: [Float], now: Date) {
        let step = SceneChangePolicy.observe(sceneWatch, signature: signature)
        sceneWatch = step.0
        guard step.1 else { return }
        switch cloudMemory.resume(now: now) {
        case .accepted(let state):
            sceneWatch = SceneWatch()
            publishCloud(state)
            let text = l10n.autoResumed(CloudUploadPolicy.remaining(state))
            roundNote = text
            record(text, kind: "upload")
        case .denied(let state):
            sceneWatch.usedThisPause = true
            publishCloud(state)
            roundNote = l10n.resumeExhausted
            record(l10n.resumeExhausted, kind: "upload")
        case .alreadyOn(let state):
            sceneWatch = SceneWatch()
            publishCloud(state)
        }
    }

    private func loadCircuit() -> CloudCircuitState {
        let defaults = UserDefaults.standard
        return CloudCircuitState(
            consecutiveFailures: max(0, defaults.integer(forKey: Key.circuitFails)),
            openUntil: Date(timeIntervalSince1970: defaults.double(forKey: Key.circuitOpen)),
            lastProbe: Date(timeIntervalSince1970: defaults.double(forKey: Key.circuitProbe))
        )
    }

    private func saveCircuit(_ state: CloudCircuitState) {
        let now = Date()
        let wasOpen = now < loadCircuit().openUntil
        let defaults = UserDefaults.standard
        defaults.set(state.consecutiveFailures, forKey: Key.circuitFails)
        defaults.set(state.openUntil.timeIntervalSince1970, forKey: Key.circuitOpen)
        defaults.set(state.lastProbe.timeIntervalSince1970, forKey: Key.circuitProbe)
        let isOpen = now < state.openUntil
        guard wasOpen != isOpen else { return }
        record(isOpen ? l10n.circuitOpened : l10n.circuitClosed, kind: "circuit")
    }

    private var imageDonationEnabled: Bool {
        UserDefaults.standard.bool(forKey: Key.imageDonation)
    }

    private func warnIfVolumeLow() {
        let volume = night.outputVolume()
        if volume < 0.3 {
            volumeWarning = l10n.volumeLow(Int((Double(volume) * 100).rounded()))
        } else {
            volumeWarning = nil
        }
    }

    private var isForeground: Bool {
        UIApplication.shared.applicationState == .active
    }

    private func ensureCamera() async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            return true
        case .notDetermined:
            let ok = await AVCaptureDevice.requestAccess(for: .video)
            if !ok { permissionNote = l10n.cameraDenied }
            return ok
        default:
            permissionNote = l10n.cameraDenied
            return false
        }
    }

    private func retuneCapture() async {
        let position: AVCaptureDevice.Position = fillMode == .frontScreen ? .front : .back
        let torchOn = fillMode == .rearAmbient && torchEnabled
        do {
            let report = try await capture.start(position: position, torchEnabled: torchOn, level: Float(torchLevel))
            cameraLive = true
            applyTorchReport(report)
        } catch {
            roundNote = l10n.failureMessage(error)
            record(roundNote ?? l10n.logCameraDenied)
        }
    }

    private func pushTorchLevel() async {
        if phase == .guarding || cameraLive {
            let report = await capture.setTorch(enabled: true, level: Float(torchLevel))
            applyTorchReport(report)
        } else {
            await applyTorch()
        }
    }

    func openAimPreviewIfAllowed() async {
        guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized else { return }
        await openAimPreview()
    }

    /// Starts the finder without arming the alarm. Start Guard reuses this session.
    func openAimPreview() async {
        if phase == .guarding || busy { return }
        let allowed = await ensureCamera()
        guard allowed, phase != .guarding else { return }
        guard isForeground else { return }
        await torch.shutdown()
        let position: AVCaptureDevice.Position = fillMode == .frontScreen ? .front : .back
        let torchOn = fillMode == .rearAmbient && torchEnabled
        do {
            let report = try await capture.start(position: position, torchEnabled: torchOn, level: Float(torchLevel))
            guard phase != .guarding else { return }
            cameraLive = true
            aimPreviewOn = true
            applyTorchReport(report)
        } catch {
            if phase != .guarding {
                cameraLive = false
                aimPreviewOn = false
                permissionNote = l10n.failureMessage(error)
            }
        }
    }

    private func classifyCloud(_ jpeg: Data) async throws -> CloudClassification {
        let version = l10n.appVersionLabel
        if AppSecrets.usesRelay {
            let deviceId = DeviceStamp.current()
            do {
                return try await CpapRelayClient.shared.classify(
                    jpeg: jpeg,
                    deviceId: deviceId,
                    appVersion: version
                )
            } catch {
                guard AppSecrets.hasMinimaxKey else { throw error }
                record(l10n.relayFallback)
                return try await MiniMaxClient.classify(
                    jpeg: jpeg,
                    apiKey: AppSecrets.minimaxAPIKey,
                    endpoint: AppSecrets.minimaxEndpoint
                )
            }
        }
        return try await MiniMaxClient.classify(
            jpeg: jpeg,
            apiKey: AppSecrets.minimaxAPIKey,
            endpoint: AppSecrets.minimaxEndpoint
        )
    }

    private func noteMaskSeen(_ vote: FrameVote) {
        if vote == .maskOn {
            maskOnStreak += 1
            if maskOnStreak == SettleGate.sustainedMaskOnFrames {
                record(l10n.maskSeenWhileSettling)
            }
        } else if vote != .uncertain {
            maskOnStreak = 0
        }
    }

    private func maskAlarmArmed(at date: Date) -> Bool {
        guard phase == .guarding, let guardStartedAt else { return false }
        let elapsed = date.timeIntervalSince(guardStartedAt)
        let confirmed = maskOnStreak >= SettleGate.sustainedMaskOnFrames
        return SettleGate.maskOffAlarmAllowed(elapsed: elapsed, maskConfirmed: confirmed)
    }

    private func startSettleClock() {
        settleTimer?.invalidate()
        let timer = Timer(timeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.refreshSettle()
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        settleTimer = timer
    }

    private func refreshSettle() {
        guard phase == .guarding || phase == .idle, let guardStartedAt else {
            return
        }
        let elapsed = Date().timeIntervalSince(guardStartedAt)
        let confirmed = maskOnStreak >= SettleGate.sustainedMaskOnFrames
        let allowed = SettleGate.maskOffAlarmAllowed(elapsed: elapsed, maskConfirmed: confirmed)
        settleSecondsLeft = SettleGate.secondsRemaining(elapsed: elapsed)
        if allowed && !alarmArmed {
            alarmArmed = true
            settling = false
            debouncer.reset()
            record(l10n.settleDone)
            Task { await night.resetSnoreConfirmation() }
        } else if !allowed {
            alarmArmed = false
            settling = true
        }
    }

    private func endSettle() {
        settleTimer?.invalidate()
        settleTimer = nil
        guardStartedAt = nil
        settling = false
        settleSecondsLeft = 0
        alarmArmed = false
        maskOnStreak = 0
    }

    private func applyTorch() async {
        guard isForeground else { return }
        torchTicket += 1
        let ticket = torchTicket
        let allowed = await ensureCamera()
        guard ticket == torchTicket else { return }
        guard allowed else {
            torchEnabled = false
            torchHardwareOn = false
            return
        }
        let report = await torch.apply(enabled: true, level: Float(torchLevel))
        guard ticket == torchTicket else { return }
        applyTorchReport(report)
    }

    private func lightsOffTorch() async {
        torchTicket += 1
        let ticket = torchTicket
        let report = await torch.shutdown()
        guard ticket == torchTicket else { return }
        torchHardwareOn = false
        torchStatus = report.status
        torchError = nil
    }

    private func applyTorchReport(_ report: TorchReport) {
        torchHardwareOn = report.isOn
        torchStatus = report.status
        torchError = report.error
        if report.error != nil {
            torchEnabled = false
        }
    }

    private func beginWhiteScreen() {
        if savedBrightness == nil {
            savedBrightness = UIScreen.main.brightness
        }
        whiteScreenActive = true
        UIScreen.main.brightness = CGFloat(min(max(screenBrightness, 0.05), 1))
    }

    private func endWhiteScreen() {
        whiteScreenActive = false
        if let savedBrightness {
            UIScreen.main.brightness = savedBrightness
            self.savedBrightness = nil
        }
    }

    private enum GatewayGate {
        case allow
        case skip
        case probe
    }

    private func gatewayGate(now: Date) -> GatewayGate {
        guard gatewayExhaustedNow(now) else { return .allow }
        if now.timeIntervalSince(lastGatewayProbe) >= GatewayQuotaPolicy.probeSpacing {
            return .probe
        }
        return .skip
    }

    private func gatewayExhaustedNow(_ now: Date = Date()) -> Bool {
        let today = CloudUploadPolicy.dayKey(now)
        if GatewayQuotaStore.isBlocked(on: today) { return true }
        guard let quota = gatewayQuota, quota.classifyRemaining <= 0 else { return false }
        return quota.sleepDay.isEmpty || quota.sleepDay == today
    }

    private func quotaForToday(_ now: Date = Date()) -> GatewayQuota? {
        let today = CloudUploadPolicy.dayKey(now)
        guard let quota = gatewayQuota else { return nil }
        if quota.sleepDay.isEmpty || quota.sleepDay == today { return quota }
        return nil
    }

    private func rememberClassificationQuota(_ classified: CloudClassification) {
        guard var quota = classified.quota else { return }
        if quota.sleepDay.isEmpty, let day = classified.sleepDay, !day.isEmpty {
            quota.sleepDay = day
        }
        rememberQuota(quota)
    }

    private func rememberQuota(_ quota: GatewayQuota) {
        var copy = quota
        if copy.sleepDay.isEmpty {
            copy.sleepDay = CloudUploadPolicy.dayKey(Date())
        }
        let previous = gatewayQuota
        GatewayQuotaStore.save(copy)
        gatewayQuota = copy
        let sameDay = previous?.sleepDay == copy.sleepDay
        let becameEmpty = copy.classifyRemaining <= 0 && (!sameDay || (previous?.classifyRemaining ?? 1) > 0)
        if becameEmpty {
            lastGatewayProbe = Date()
            record(l10n.gatewayQuotaExhausted(copy), kind: "quota")
        }
    }

    @discardableResult
    private func absorbRelayFailure(_ error: Error) -> RelayHTTPError? {
        guard let http = relayHTTP(error) else { return nil }
        if let quota = http.quota {
            var copy = quota
            if copy.sleepDay.isEmpty, let day = http.sleepDay {
                copy.sleepDay = day
            }
            rememberQuota(copy)
        } else if http.status == 429 {
            let day = (http.sleepDay?.isEmpty == false) ? http.sleepDay! : CloudUploadPolicy.dayKey(Date())
            if !GatewayQuotaStore.isBlocked(on: day) {
                GatewayQuotaStore.markBlocked(day)
                lastGatewayProbe = Date()
                record(l10n.gatewayQuotaExhaustedUnknown, kind: "quota")
            }
        }
        return http
    }

    private func relayHTTP(_ error: Error) -> RelayHTTPError? {
        guard let relay = error as? RelayError, case .http(let http) = relay else { return nil }
        return http
    }

    private func cycleErrorCode(_ error: Error, http: RelayHTTPError?) -> String {
        if let http {
            return http.errorCode ?? "http_\(http.status)"
        }
        if let relay = error as? RelayError {
            switch relay {
            case .disabled: return "relay_disabled"
            case .emptyImage: return "empty_image"
            case .badJSON: return "bad_json"
            case .transport: return "transport"
            case .http(let http): return http.errorCode ?? "http_\(http.status)"
            }
        }
        if error is CaptureError { return "capture" }
        return "error"
    }

    private func recordRound(
        _ text: String,
        vote: String?,
        error: String?,
        http: Int?,
        quotaProbe: Bool = false,
        sleepDay: String? = nil
    ) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        appendMemory(trimmed)
        writeEvent(
            kind: "cycle",
            note: trimmed,
            vote: vote,
            error: error,
            httpStatus: http,
            quotaProbe: quotaProbe,
            sleepDay: sleepDay
        )
    }

    private func writeEvent(
        kind: String,
        note: String?,
        vote: String?,
        error: String?,
        httpStatus: Int?,
        quotaProbe: Bool,
        sleepDay: String?
    ) {
        let now = Date()
        let circuit = loadCircuit()
        let cloud = cloudMemory.load(now: now)
        let open = now < circuit.openUntil
        var sleep = CloudUploadPolicy.dayKey(now)
        if let day = sleepDay, !day.isEmpty {
            sleep = day
        } else if let quota = gatewayQuota, !quota.sleepDay.isEmpty {
            let today = CloudUploadPolicy.dayKey(now)
            if quota.sleepDay == today { sleep = quota.sleepDay }
        }
        var object: [String: Any] = [
            "time": NightEventLog.localISO(now),
            "kind": kind,
            "sleep_day": sleep,
            "circuit": open ? "open" : "closed",
            "circuit_fails": circuit.consecutiveFailures,
            "upload_enabled": cloud.uploadEnabled,
            "resume_used": cloud.resumesUsed,
            "resume_remaining": CloudUploadPolicy.remaining(cloud),
            "foreground": UIApplication.shared.applicationState != .background,
            "settle_armed": alarmArmed,
        ]
        if circuit.openUntil.timeIntervalSince1970 > 1 {
            object["circuit_open_until"] = NightEventLog.localISO(circuit.openUntil)
        }
        if let quota = gatewayQuota, quota.sleepDay.isEmpty || quota.sleepDay == sleep {
            object["classify_used"] = quota.classifyUsed
            object["classify_remaining"] = quota.classifyRemaining
            object["classify_limit"] = quota.classifyLimit
        }
        if let vote, !vote.isEmpty { object["vote"] = vote }
        if let error, !error.isEmpty { object["error"] = error }
        if let httpStatus { object["http_status"] = httpStatus }
        if quotaProbe { object["quota_probe"] = true }
        if let note {
            let trimmed = note.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty {
                object["note"] = String(trimmed.prefix(240))
            }
        }
        NightEventLog.append(object)
    }

    private func noteCameraSession(_ raw: String) {
        let text: String
        if raw.hasPrefix("interrupted:") {
            text = l10n.cameraInterrupted(raw)
        } else if raw == "resumed" {
            text = l10n.cameraResumed
        } else if raw.hasPrefix("runtime:") {
            text = l10n.cameraRuntime(String(raw.dropFirst("runtime:".count)))
        } else {
            text = raw
        }
        record(text, kind: "camera")
    }

    private func persist() {
        let defaults = UserDefaults.standard
        defaults.set(language.rawValue, forKey: Key.language)
        defaults.set(fillMode.rawValue, forKey: Key.fillMode)
        defaults.set(screenBrightness, forKey: Key.brightness)
        defaults.set(torchLevel, forKey: Key.torchLevel)
        defaults.set(intervalSeconds, forKey: Key.interval)
    }

    private enum Key {
        static let language = "guard.language"
        static let fillMode = "guard.fillMode"
        static let brightness = "guard.screenBrightness"
        static let torchLevel = "guard.torchLevel"
        static let interval = "guard.intervalSeconds"
        static let circuitFails = "cloud_circuit_fails"
        static let circuitOpen = "cloud_circuit_open_until"
        static let circuitProbe = "cloud_circuit_last_probe"
        static let imageDonation = "image_donation_enabled"
    }
}

struct SessionLogLine: Identifiable, Equatable {
    let id: UUID
    let date: Date
    let text: String
}
