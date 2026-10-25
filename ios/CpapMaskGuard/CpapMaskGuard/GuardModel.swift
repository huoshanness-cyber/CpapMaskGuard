import AVFoundation
import Combine
import UIKit
import UserNotifications

@MainActor
final class GuardModel: ObservableObject {
    @Published var language: AppLanguage
    @Published var fillMode: FillLightMode
    @Published var screenBrightness: Double
    @Published var torchEnabled = false
    @Published var torchLevel: Double
    @Published var phase: GuardPhase = .idle
    @Published var torchHardwareOn = false
    @Published var torchStatus = "已关闭"
    @Published var torchError: String?
    @Published var permissionNote: String?
    @Published var micNote: String?
    @Published var notificationNote: String?
    @Published var busy = false
    @Published var whiteScreenActive = false
    /// This launch only. Not a file, so an empty path cannot crash the log screen.
    @Published private(set) var logs: [SessionLogLine] = []

    private let torch = RearTorch()
    private var savedBrightness: CGFloat?
    private var transition = 0
    private var torchTicket = 0

    init() {
        let defaults = UserDefaults.standard
        language = AppLanguage(rawValue: defaults.string(forKey: Key.language) ?? "") ?? .zh
        fillMode = FillLightMode(rawValue: defaults.string(forKey: Key.fillMode) ?? "") ?? .frontScreen
        let brightness = defaults.object(forKey: Key.brightness) as? Double ?? 0.85
        screenBrightness = min(max(brightness, 0.05), 1)
        let level = defaults.object(forKey: Key.torchLevel) as? Double ?? 0.15
        torchLevel = min(max(level, 0.05), 1)
        logs = [SessionLogLine(id: UUID(), date: Date(), text: L10n(language: language).logOpened)]
    }

    func record(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        logs.append(SessionLogLine(id: UUID(), date: Date(), text: trimmed))
        if logs.count > 200 {
            logs.removeFirst(logs.count - 200)
        }
    }

    var l10n: L10n { L10n(language: language) }

    var headline: String { l10n.headline(phase, mode: fillMode, torchOn: torchHardwareOn) }
    var detail: String { l10n.detail(phase, mode: fillMode, torchOn: torchHardwareOn) }

    func setLanguage(_ language: AppLanguage) {
        self.language = language
        persist()
    }

    func setFillMode(_ mode: FillLightMode) async {
        let changed = fillMode != mode
        fillMode = mode
        persist()
        if mode == .frontScreen {
            torchEnabled = false
            await lightsOffTorch()
            if phase == .guarding {
                beginWhiteScreen()
            }
        } else if phase == .guarding {
            endWhiteScreen()
            if torchEnabled {
                await applyTorch()
            }
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
            await lightsOffTorch()
            return
        }
        torchEnabled = on
        if on {
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
        Task { await applyTorch() }
    }

    func start() async {
        if busy { return }
        busy = true
        defer { busy = false }
        transition += 1
        let token = transition

        let allowed = await ensureCamera()
        guard token == transition, isForeground else { return }
        guard allowed else {
            phase = .idle
            record(l10n.logCameraDenied)
            return
        }
        permissionNote = nil
        phase = .guarding
        UIApplication.shared.isIdleTimerDisabled = true
        if fillMode == .frontScreen {
            torchEnabled = false
            await lightsOffTorch()
            guard token == transition, isForeground else { return }
            beginWhiteScreen()
            record(l10n.logStarted)
        } else {
            endWhiteScreen()
            if torchEnabled {
                await applyTorch()
            } else {
                await lightsOffTorch()
            }
            guard token == transition, isForeground else {
                await leaveForeground()
                return
            }
            record(l10n.logStarted)
            if torchEnabled {
                record(torchHardwareOn ? l10n.logTorchOn(torchStatus) : l10n.logTorchOff)
            }
        }
    }

    func stop() async {
        transition += 1
        let token = transition
        busy = true
        defer { if token == transition { busy = false } }
        phase = .stopped
        torchEnabled = false
        UIApplication.shared.isIdleTimerDisabled = false
        endWhiteScreen()
        await lightsOffTorch()
        record(l10n.logStopped)
    }

    func leaveForeground() async {
        let wasActive = phase == .guarding || torchHardwareOn || whiteScreenActive
        guard wasActive else { return }
        transition += 1
        phase = .leftForeground
        torchEnabled = false
        UIApplication.shared.isIdleTimerDisabled = false
        endWhiteScreen()
        await lightsOffTorch()
        record(l10n.logLeft)
    }

    func requestMicrophone() async {
        let ok = await AVCaptureDevice.requestAccess(for: .audio)
        micNote = ok ? l10n.micGranted : l10n.micDenied
        record(l10n.logMic(ok))
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
        torchHardwareOn = report.isOn
        torchStatus = report.status
        torchError = report.error
        if !report.isOn {
            torchEnabled = false
        }
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
    }

    private enum Key {
        static let language = "guard.language"
        static let fillMode = "guard.fillMode"
        static let brightness = "guard.screenBrightness"
        static let torchLevel = "guard.torchLevel"
    }
}

struct SessionLogLine: Identifiable, Equatable {
    let id: UUID
    let date: Date
    let text: String
}
