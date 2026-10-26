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

    init() {
        let defaults = UserDefaults.standard
        language = AppLanguage(rawValue: defaults.string(forKey: Key.language) ?? "") ?? .zh
        fillMode = FillLightMode(rawValue: defaults.string(forKey: Key.fillMode) ?? "") ?? .frontScreen
        let brightness = defaults.object(forKey: Key.brightness) as? Double ?? 0.85
        screenBrightness = min(max(brightness, 0.05), 1)
        let level = defaults.object(forKey: Key.torchLevel) as? Double ?? 0.15
        torchLevel = min(max(level, 0.05), 1)
        let storedInterval = defaults.object(forKey: Key.interval) as? Int ?? 8
        intervalSeconds = min(max(storedInterval, 5), 10)
        logs = [SessionLogLine(id: UUID(), date: Date(), text: L10n(language: language).logOpened)]
        snoreHint = L10n(language: language).snoreLine(.off)
        let stored = cloudMemory.load(now: Date())
        uploadEnabled = stored.uploadEnabled
        resumeRemaining = CloudUploadPolicy.remaining(stored)
    }

    func record(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        logs.append(SessionLogLine(id: UUID(), date: Date(), text: trimmed))
        if logs.count > 200 {
            logs.removeFirst(logs.count - 200)
        }
        logger.info("\(trimmed, privacy: .public)")
    }

    var l10n: L10n { L10n(language: language) }

    var displayResult: String {
        guard let lastReadout else { return "" }
        if lastReadout.aimMissed { return l10n.aimSoftResult }
        if lastReadout.cloudPaused && !lastReadout.alarmed && !alarming { return l10n.pausedResult }
        if lastReadout.unjudged { return l10n.unjudged }
        return l10n.resultLabel(lastReadout.vote ?? .uncertain, alarmed: lastReadout.alarmed || alarming)
    }

    var displayReason: String {
        guard let lastReadout else { return "" }
        if lastReadout.aimMissed { return l10n.aimSoftHint }
        if lastReadout.cloudPaused { return l10n.pausedReason }
        if lastReadout.unjudged { return l10n.noKeyReason }
        return l10n.cloudReason(lastReadout.vote ?? .uncertain, raw: lastReadout.raw)
    }

    var headline: String {
        l10n.headline(
            phase: phase,
            alarming: alarming,
            failures: consecutiveFailures,
            vote: lastReadout?.vote,
            unjudged: lastReadout?.unjudged ?? false,
            aimMissed: lastReadout?.aimMissed ?? false
        )
    }

    var detail: String {
        l10n.detail(
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
        if phase == .guarding {
            let report = await capture.setTorch(enabled: on, level: Float(torchLevel))
            applyTorchReport(report)
        } else if on {
            await applyTorch()
        } else {
            await lightsOffTorch()
        }
        record(torchHardwareOn ? l10n.logTorchOn(torchStatus) : l10n.logTorchOff)
    }

    func setTorchLevel(_ value: Double) {
        torchLevel = min(max(value, 0.05), 1)
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
        debouncer.reset()
        ignoreUntil = nil
        health = nil
        await night.stopSnore()
        await torch.shutdown()
        torchHardwareOn = false

        let allowed = await ensureCamera()
        guard token == transition else { return }
        guard allowed else {
            phase = .idle
            record(l10n.logCameraDenied)
            return
        }
        if !isForeground {
            await leaveForeground()
            return
        }

        let position: AVCaptureDevice.Position = fillMode == .frontScreen ? .front : .back
        let torchOn = fillMode == .rearAmbient && torchEnabled
        do {
            let report = try await capture.start(position: position, torchEnabled: torchOn, level: Float(torchLevel))
            cameraLive = true
            applyTorchReport(report)
        } catch {
            cameraLive = false
            phase = .idle
            let message = l10n.failureMessage(error)
            roundNote = message
            record(message)
            UIApplication.shared.isIdleTimerDisabled = false
            return
        }
        guard token == transition, isForeground else {
            await leaveForeground()
            return
        }

        phase = .guarding
        UIApplication.shared.isIdleTimerDisabled = true
        if fillMode == .frontScreen {
            torchEnabled = false
            beginWhiteScreen()
        } else {
            endWhiteScreen()
        }
        record(l10n.logStarted)
        warnIfVolumeLow()
        guard token == transition, isForeground else {
            await leaveForeground()
            return
        }
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
        night.stopAlarm()
        await night.stopSnore()
        await capture.stop()
        torchEnabled = false
        torchHardwareOn = false
        UIApplication.shared.isIdleTimerDisabled = false
        endWhiteScreen()
        await lightsOffTorch()
        snoreHint = l10n.snoreLine(.off)
        record(l10n.logStopped)
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
            record(l10n.logLeft)
            await postLeftNotification()
        } else {
            record(l10n.logLeftLight)
        }
    }

    func resumeUpload() {
        switch cloudMemory.resume(now: Date()) {
        case .accepted(let state):
            publishCloud(state)
            let text = l10n.resumeAccepted(CloudUploadPolicy.remaining(state))
            roundNote = text
            record(text)
        case .denied(let state):
            publishCloud(state)
            roundNote = l10n.resumeExhausted
            record(l10n.resumeExhausted)
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
            let aimed = await aimIsValid(payload)
            if !aimed {
                result = l10n.aimSoftResult
                vote = "AIM_MISS"
                reason = l10n.aimSoftHint
                _ = cloudMemory.noteAimInvalid(now: Date())
                publishCloud(cloudMemory.load(now: Date()))
            } else if AppSecrets.hasMinimaxKey && !cloudMemory.load(now: Date()).uploadEnabled {
                result = l10n.pausedResult
                vote = "CLOUD_PAUSED"
                reason = l10n.pausedReason
            } else if AppSecrets.hasMinimaxKey {
                do {
                    let started = Date()
                    let classified = try await MiniMaxClient.classify(
                        jpeg: payload,
                        apiKey: AppSecrets.minimaxAPIKey,
                        endpoint: AppSecrets.minimaxEndpoint
                    )
                    latency = MonitorClock.milliseconds(since: started)
                    let updated = cloudMemory.noteCloud(classified.vote, now: Date())
                    publishCloud(updated)
                    result = l10n.resultLabel(classified.vote, alarmed: false)
                    vote = classified.vote.rawValue
                    reason = l10n.cloudReason(classified.vote, raw: classified.raw)
                    if !updated.uploadEnabled {
                        record(l10n.uploadStopped(updated.consecutiveMaskOff, CloudUploadPolicy.remaining(updated)))
                    }
                } catch {
                    reason = l10n.failureMessage(error)
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
            guard stillGuarding(token) else { return }
            let started = Date()
            do {
                if fillMode == .rearAmbient {
                    let report = await capture.setTorch(enabled: torchEnabled, level: Float(torchLevel))
                    guard stillGuarding(token) else { return }
                    applyTorchReport(report)
                }
                let raw = try await capture.takeJPEG()
                guard stillGuarding(token) else { return }
                let capturedAt = Date()
                guard let payload = ImageEncode.jpegForCloud(raw) else {
                    throw CaptureError.empty
                }
                let aimed = await aimIsValid(payload)
                guard stillGuarding(token) else { return }
                if !aimed {
                    let updated = cloudMemory.noteAimInvalid(now: capturedAt)
                    publishCloud(updated)
                    publishAimMiss(payload: payload, capturedAt: capturedAt, started: started)
                } else if !AppSecrets.hasMinimaxKey {
                    publishUnjudged(payload: payload, capturedAt: capturedAt, started: started)
                } else if !cloudMemory.load(now: capturedAt).uploadEnabled {
                    publishCloudPaused(payload: payload, capturedAt: capturedAt, started: started)
                } else {
                    let classifyStarted = Date()
                    let classified = try await MiniMaxClient.classify(
                        jpeg: payload,
                        apiKey: AppSecrets.minimaxAPIKey,
                        endpoint: AppSecrets.minimaxEndpoint
                    )
                    guard stillGuarding(token) else { return }
                    let updated = cloudMemory.noteCloud(classified.vote, now: capturedAt)
                    publishCloud(updated)
                    if !updated.uploadEnabled {
                        record(l10n.uploadStopped(updated.consecutiveMaskOff, CloudUploadPolicy.remaining(updated)))
                    }
                    publishClassified(
                        classified,
                        jpeg: payload,
                        capturedAt: capturedAt,
                        classifyStarted: classifyStarted,
                        started: started
                    )
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
                let slice = min(left, 1.5)
                do {
                    try await Task.sleep(nanoseconds: UInt64(slice * 1_000_000_000))
                } catch {
                    return
                }
                guard stillGuarding(token) else { return }
                maybeAudioOnlyAlarm()
                left -= slice
            }
        }
    }

    private func aimIsValid(_ jpeg: Data) async -> Bool {
        guard let image = UIImage(data: jpeg)?.cgImage else { return false }
        return await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                continuation.resume(returning: AimScanner.evaluate(image).valid)
            }
        }
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
        record(line)
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
        let result = hold ? l10n.resultLabel(.maskOff, alarmed: true) : l10n.pausedResult
        let line = l10n.logRound(
            time: SessionLogClock.precise(from: capturedAt),
            result: result,
            inferMs: 0,
            captureToJudgeMs: roundMs,
            roundMs: roundMs
        )
        record(line)
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
        beginAlarm(summary: l10n.pausedAlarm)
    }

    private func publishClassified(
        _ classified: CloudClassification,
        jpeg: Data,
        capturedAt: Date,
        classifyStarted: Date,
        started: Date
    ) {
        let inferMs = MonitorClock.milliseconds(since: classifyStarted)
        let captureToJudgeMs = MonitorClock.milliseconds(since: capturedAt)
        let roundMs = MonitorClock.milliseconds(since: started)
        let cameraShouldAlarm = debouncer.onVote(classified.vote)
        let snap = night.snapshot()
        snoreHint = l10n.snoreLine(snap)
        let summary = l10n.cloudReason(classified.vote, raw: classified.raw)
        let decision = SnoreFusion.fuse(
            cameraShouldAlarm: cameraShouldAlarm,
            cameraVote: classified.vote,
            snoreConfirmed: snap.state == .confirmed,
            snoreConfirmedForMs: snap.confirmedForMs
        )
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
        health = HealthMath.score(
            captureToJudgeMs: captureToJudgeMs,
            roundMs: roundMs,
            consecutiveFailures: 0,
            language: language
        )
        let result = l10n.resultLabel(classified.vote, alarmed: alarmed)
        let line = l10n.logRound(
            time: SessionLogClock.precise(from: capturedAt),
            result: result,
            inferMs: inferMs,
            captureToJudgeMs: captureToJudgeMs,
            roundMs: roundMs
        )
        record(line)
        FrameArchive.save(ArchivePayload(
            capturedAt: capturedAt,
            result: result,
            vote: classified.vote.rawValue,
            latencyMs: inferMs,
            roundMs: roundMs,
            fillMode: fillMode.rawValue,
            reason: summary,
            jpeg: jpeg
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
        let line = l10n.logRound(
            time: SessionLogClock.precise(from: capturedAt),
            result: l10n.unjudged,
            inferMs: 0,
            captureToJudgeMs: roundMs,
            roundMs: roundMs
        )
        record(line)
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
        let message = l10n.failureMessage(error)
        roundNote = message
        record(message)
        health = HealthMath.score(
            captureToJudgeMs: nil,
            roundMs: nil,
            consecutiveFailures: consecutiveFailures,
            language: language
        )
        if consecutiveFailures == 5 {
            record(l10n.logManyFailures)
        }
    }

    private func maybeAudioOnlyAlarm() {
        guard phase == .guarding, !alarming else { return }
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

    private func postLeftNotification() async {
        let content = UNMutableNotificationContent()
        content.title = l10n.notifyLeftTitle
        content.body = l10n.notifyLeftBody
        content.sound = .default
        let request = UNNotificationRequest(identifier: "left-foreground", content: content, trigger: nil)
        try? await UNUserNotificationCenter.current().add(request)
    }

    private func stillGuarding(_ token: Int) -> Bool {
        token == transition && phase == .guarding && isForeground && !Task.isCancelled
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
        if phase == .guarding {
            let report = await capture.setTorch(enabled: true, level: Float(torchLevel))
            applyTorchReport(report)
        } else {
            await applyTorch()
        }
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
    }
}

struct SessionLogLine: Identifiable, Equatable {
    let id: UUID
    let date: Date
    let text: String
}
