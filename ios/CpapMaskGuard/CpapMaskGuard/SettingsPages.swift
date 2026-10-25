import AVFoundation
import Combine
import SwiftUI
import UIKit

/// Routes under「设置与本机」. Pushed on the root `NavigationStack`.
/// There is no burn-in / pre-use check route.
enum SettingsRoute: Hashable {
    case status
    case baseline
    case logs
}

struct SettingsRouteScreen: View {
    @ObservedObject var model: GuardModel
    let route: SettingsRoute

    var body: some View {
        switch route {
        case .status:
            DeviceStatusPage(model: model)
        case .baseline:
            BaselinePhotosPage(model: model)
        case .logs:
            SessionLogPage(model: model)
        }
    }
}

private struct SettingsPageChrome<Content: View>: View {
    let title: String
    let footnote: String
    @ViewBuilder var content: () -> Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text(title)
                    .font(.title2.weight(.bold))
                    .foregroundStyle(GuardTheme.text)
                content()
                Text(footnote)
                    .font(.footnote)
                    .foregroundStyle(GuardTheme.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(GuardTheme.bg.ignoresSafeArea())
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
    }
}

/// Read-only phone facts. The monitoring score is not invented here.
private struct DeviceStatusPage: View {
    @ObservedObject var model: GuardModel
    @State private var batteryLevel: Float = -1

    var body: some View {
        SettingsPageChrome(title: model.l10n.healthTitle, footnote: model.l10n.foregroundLimit) {
            Text(model.l10n.healthComingSoon)
                .font(.body)
                .foregroundStyle(GuardTheme.text)
                .fixedSize(horizontal: false, vertical: true)
            Text(model.detail)
                .font(.subheadline)
                .foregroundStyle(GuardTheme.muted)
                .fixedSize(horizontal: false, vertical: true)
            VStack(alignment: .leading, spacing: 10) {
                row(model.l10n.rowPhase, model.headline)
                row(model.l10n.rowFill, model.l10n.fillName(model.fillMode))
                row(model.l10n.rowTorch, model.torchHardwareOn ? model.torchStatus : model.l10n.torchOffNow)
                row(model.l10n.rowCamera, cameraAccess)
                row(model.l10n.rowDevice, deviceLine)
                row(model.l10n.rowBattery, batteryLine)
                row(model.l10n.rowHealth, model.health.map { "\($0.score)" } ?? model.l10n.healthPending)
                if let health = model.health {
                    Text(health.reason)
                        .font(.subheadline)
                        .foregroundStyle(GuardTheme.muted)
                        .fixedSize(horizontal: false, vertical: true)
                }
                row(model.l10n.rowInterval, "\(model.intervalSeconds)s")
                row(model.l10n.rowLastResult, model.lastReadout == nil ? model.l10n.valueUnknown : model.displayResult)
                row(
                    model.l10n.rowCaptureTime,
                    model.lastReadout.map { SessionLogClock.precise(from: $0.capturedAt) } ?? model.l10n.valueUnknown
                )
                row(
                    model.l10n.rowLatency,
                    model.lastReadout.map { model.l10n.latencyLine($0) } ?? model.l10n.valueUnknown
                )
                row(model.l10n.rowSnore, model.snoreHint.isEmpty ? model.l10n.valueUnknown : model.snoreHint)
                row(model.l10n.rowKey, AppSecrets.hasMinimaxKey ? model.l10n.keyYes : model.l10n.keyNo)
            }
        }
        .onAppear(perform: readBattery)
        .onReceive(NotificationCenter.default.publisher(for: UIDevice.batteryLevelDidChangeNotification)) { _ in
            readBattery()
        }
    }

    private func row(_ label: String, _ value: String) -> some View {
        Text(model.l10n.pair(label, value))
            .font(.body)
            .foregroundStyle(GuardTheme.text)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var cameraAccess: String {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            return model.l10n.cameraAllowed
        case .denied, .restricted:
            return model.l10n.cameraBlocked
        case .notDetermined:
            return model.l10n.cameraNotAsked
        @unknown default:
            return model.l10n.valueUnknown
        }
    }

    private var deviceLine: String {
        let device = UIDevice.current
        let modelName = device.model.trimmingCharacters(in: .whitespacesAndNewlines)
        let system = device.systemVersion.trimmingCharacters(in: .whitespacesAndNewlines)
        if modelName.isEmpty && system.isEmpty { return model.l10n.valueUnknown }
        if system.isEmpty { return modelName }
        if modelName.isEmpty { return "iOS \(system)" }
        return "\(modelName) · iOS \(system)"
    }

    private var batteryLine: String {
        guard batteryLevel.isFinite, batteryLevel >= 0 else { return model.l10n.batteryUnknown }
        let percent = Int((Double(batteryLevel) * 100).rounded())
        return model.l10n.batteryPercent(min(max(percent, 0), 100))
    }

    private func readBattery() {
        let device = UIDevice.current
        device.isBatteryMonitoringEnabled = true
        let level = device.batteryLevel
        batteryLevel = level.isFinite ? level : -1
    }
}

/// Local sample frames. Opening the page does not start a camera.
/// The explicit button takes one foreground still, then closes the session.
private struct BaselinePhotosPage: View {
    @ObservedObject var model: GuardModel
    @State private var items: [ArchiveItem] = []

    var body: some View {
        SettingsPageChrome(title: model.l10n.calibrateTitle, footnote: model.l10n.foregroundLimit) {
            Text(model.phase == .guarding ? model.l10n.sampleWhileGuarding : model.l10n.baselineComingSoon)
                .font(.body)
                .foregroundStyle(GuardTheme.text)
                .fixedSize(horizontal: false, vertical: true)
            Button(model.l10n.refreshSamples) {
                reload()
            }
            .buttonStyle(.bordered)
            .tint(GuardTheme.accent)
            if model.phase != .guarding {
                Button(model.l10n.captureSample) {
                    Task {
                        await model.captureSample()
                        reload()
                    }
                }
                .buttonStyle(.borderedProminent)
                .tint(GuardTheme.accent)
                .disabled(model.busy)
            }
            if let note = model.sampleNote, !note.isEmpty {
                Text(note)
                    .font(.subheadline)
                    .foregroundStyle(GuardTheme.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if items.isEmpty {
                Text(model.l10n.samplesEmpty)
                    .font(.body)
                    .foregroundStyle(GuardTheme.muted)
            } else {
                VStack(alignment: .leading, spacing: 14) {
                    ForEach(items) { item in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(SessionLogClock.precise(from: item.capturedAt))
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(GuardTheme.muted)
                            Text(item.result)
                                .font(.body.weight(.semibold))
                                .foregroundStyle(GuardTheme.text)
                            if let latency = item.latencyMs {
                                Text(model.l10n.pair(model.l10n.rowLatency, "\(latency)ms"))
                                    .font(.footnote)
                                    .foregroundStyle(GuardTheme.muted)
                            }
                            if let jpeg = item.jpeg, let image = UIImage(data: jpeg) {
                                Image(uiImage: image)
                                    .resizable()
                                    .scaledToFit()
                                    .frame(maxWidth: .infinity, maxHeight: 160)
                                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
        }
        .onAppear(perform: reload)
    }

    private func reload() {
        items = FrameArchive.list()
    }
}

/// In-memory session notes. Nothing is read from a file path.
private struct SessionLogPage: View {
    @ObservedObject var model: GuardModel

    var body: some View {
        SettingsPageChrome(title: model.l10n.logsTitle, footnote: model.l10n.foregroundLimit) {
            Text(model.l10n.logsComingSoon)
                .font(.body)
                .foregroundStyle(GuardTheme.text)
                .fixedSize(horizontal: false, vertical: true)
            if model.logs.isEmpty {
                Text(model.l10n.logsEmpty)
                    .font(.body)
                    .foregroundStyle(GuardTheme.muted)
            } else {
                VStack(alignment: .leading, spacing: 12) {
                    ForEach(model.logs) { line in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(SessionLogClock.text(from: line.date))
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(GuardTheme.muted)
                            Text(line.text)
                                .font(.body)
                                .foregroundStyle(GuardTheme.text)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
        }
    }
}

enum SessionLogClock {
    static func text(from date: Date) -> String {
        formatter.string(from: date)
    }

    /// Capture clock with milliseconds, same shape as the Android monitor log.
    static func precise(from date: Date) -> String {
        preciseFormatter.string(from: date)
    }

    private static let formatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .current
        formatter.dateFormat = "HH:mm:ss"
        return formatter
    }()

    private static let preciseFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .current
        formatter.dateFormat = "HH:mm:ss.SSS"
        return formatter
    }()
}
