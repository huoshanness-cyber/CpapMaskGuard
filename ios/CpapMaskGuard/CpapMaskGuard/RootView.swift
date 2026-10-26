import SwiftUI

struct RootView: View {
    @StateObject private var model = GuardModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            ZStack {
                background.ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        header
                        statusBlock
                        captureCard
                        notices
                        heroButton
                        resumeUploads
                        Text(model.l10n.foregroundLimit)
                            .font(.footnote)
                            .foregroundStyle(foregroundMuted)
                            .fixedSize(horizontal: false, vertical: true)
                        SettingsPanel { route in
                            path.append(route)
                        }
                        Text(model.l10n.versionFooter)
                            .font(.caption)
                            .foregroundStyle(foregroundMuted)
                            .frame(maxWidth: .infinity, alignment: .center)
                            .padding(.top, 8)
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 12)
                    .padding(.bottom, 28)
                }
            }
            .navigationTitle(model.l10n.appName)
            .toolbar(.hidden, for: .navigationBar)
            .environmentObject(model)
            .navigationDestination(for: SettingsRoute.self) { route in
                // Home-only `.environmentObject` does not reach a pushed screen.
                // PlaceholderPage.title crashed in EnvironmentObject.error()
                // because of that. Inject GuardModel on the destination itself.
                SettingsRouteScreen(model: model, route: route)
                    .environmentObject(model)
            }
        }
        .environmentObject(model)
        .preferredColorScheme(model.whiteScreenActive ? .light : .dark)
        .overlay(alignment: .bottom) {
            if model.alarming {
                alarmBar
            }
        }
        .onChange(of: scenePhase) { _, phase in
            guard phase == .background else { return }
            Task { await model.leaveForeground() }
        }
    }

    private var background: Color {
        model.whiteScreenActive ? .white : GuardTheme.bg
    }

    private var foregroundText: Color {
        model.whiteScreenActive ? GuardTheme.ink : GuardTheme.text
    }

    private var foregroundMuted: Color {
        model.whiteScreenActive ? GuardTheme.ink.opacity(0.62) : GuardTheme.muted
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(model.l10n.appName)
                .font(.title2.weight(.bold))
                .foregroundStyle(foregroundText)
            Text(model.l10n.brand)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(foregroundMuted)
            Text(model.l10n.disclaimer)
                .font(.caption)
                .foregroundStyle(foregroundMuted)
                .padding(.top, 2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var statusBlock: some View {
        VStack(spacing: 10) {
            Text(model.headline)
                .font(.system(size: 34, weight: .bold))
                .multilineTextAlignment(.center)
                .foregroundStyle(foregroundText)
                .frame(maxWidth: .infinity)
            Text(model.detail)
                .font(.body)
                .multilineTextAlignment(.center)
                .foregroundStyle(foregroundMuted)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, 28)
        .padding(.bottom, 8)
    }

    @ViewBuilder
    private var captureCard: some View {
        if model.phase == .guarding || model.lastReadout != nil {
            VStack(alignment: .leading, spacing: 6) {
                Text(model.l10n.lastCaptureTitle)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(foregroundMuted)
                if let readout = model.lastReadout {
                    labeled(model.l10n.capturedLabel, SessionLogClock.precise(from: readout.capturedAt))
                        .accessibilityIdentifier("home.capture.time")
                    labeled(model.l10n.resultWord, model.displayResult)
                        .accessibilityIdentifier("home.capture.result")
                    Text(model.l10n.latencyLine(readout))
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(foregroundText)
                        .accessibilityIdentifier("home.capture.latency")
                    if model.phase == .guarding, !model.displayReason.isEmpty {
                        Text(model.displayReason)
                            .font(.footnote)
                            .foregroundStyle(foregroundMuted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                } else {
                    Text(model.l10n.takingFirst)
                        .font(.body)
                        .foregroundStyle(foregroundText)
                }
                if model.phase == .guarding {
                    Text(model.snoreHint)
                        .font(.footnote)
                        .foregroundStyle(foregroundMuted)
                    Text(model.l10n.uploadNote(hasKey: AppSecrets.hasMinimaxKey))
                        .font(.footnote)
                        .foregroundStyle(foregroundMuted)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if let image = model.lastThumbnail {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: .infinity, maxHeight: 180)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(model.whiteScreenActive ? Color.black.opacity(0.04) : GuardTheme.surface)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
    }

    private func labeled(_ label: String, _ value: String) -> some View {
        Text(model.l10n.pair(label, value))
            .font(.body.monospacedDigit())
            .foregroundStyle(foregroundText)
            .fixedSize(horizontal: false, vertical: true)
    }

    @ViewBuilder
    private var notices: some View {
        if let note = model.permissionNote {
            notice(note)
        }
        if let note = model.roundNote {
            notice(note)
        }
        if let note = model.volumeWarning {
            notice(note)
        }
        if let err = model.torchError, model.fillMode == .rearAmbient {
            notice(err)
        }
    }

    private var alarmBar: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(model.headline)
                .font(.title2.weight(.bold))
                .foregroundStyle(.white)
            Button {
                model.acknowledgeAlarm()
            } label: {
                Text(model.l10n.stopSound)
                    .font(.title3.weight(.bold))
                    .foregroundStyle(GuardTheme.ink)
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: 56)
            }
            .buttonStyle(.borderedProminent)
            .tint(.white)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(red: 0.55, green: 0.09, blue: 0.09))
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
    }

    private func notice(_ text: String) -> some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(model.whiteScreenActive ? GuardTheme.ink : GuardTheme.amber)
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(model.whiteScreenActive ? Color.black.opacity(0.06) : GuardTheme.warningBg)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private var heroButton: some View {
        Button {
            Task {
                if model.phase == .guarding {
                    await model.stop()
                } else {
                    await model.start()
                }
            }
        } label: {
            Text(model.phase == .guarding ? model.l10n.stop : model.l10n.start)
                .font(.system(size: 28, weight: .bold))
                .frame(maxWidth: .infinity)
                .frame(minHeight: 88)
        }
        .buttonStyle(HeroButtonStyle(stopping: model.phase == .guarding, onWhite: model.whiteScreenActive))
        .disabled(model.busy)
        .opacity(model.busy ? 0.55 : 1)
        .padding(.top, 8)
        .accessibilityHint(model.l10n.foregroundLimit)
    }

    @ViewBuilder
    private var resumeUploads: some View {
        if !model.uploadEnabled && AppSecrets.hasMinimaxKey {
            VStack(alignment: .leading, spacing: 8) {
                Text(model.resumeRemaining == 0 ? model.l10n.resumeExhausted : model.l10n.resumeRemaining(model.resumeRemaining))
                    .font(.subheadline)
                    .foregroundStyle(foregroundMuted)
                    .fixedSize(horizontal: false, vertical: true)
                Button {
                    model.resumeUpload()
                } label: {
                    Text(model.l10n.resumeUpload)
                        .font(.body.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .frame(minHeight: 48)
                }
                .buttonStyle(.bordered)
                .disabled(model.resumeRemaining == 0 || model.busy)
                .accessibilityIdentifier("home.resumeUpload")
            }
        }
    }
}

private struct HeroButtonStyle: ButtonStyle {
    var stopping: Bool
    var onWhite: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(labelColor)
            .background(background)
            .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .strokeBorder(border, lineWidth: stopping && !onWhite ? 1.5 : 0)
            )
            .opacity(configuration.isPressed ? 0.82 : 1)
    }

    private var labelColor: Color {
        if onWhite { return .white }
        return stopping ? GuardTheme.text : GuardTheme.onAccent
    }

    private var background: Color {
        if onWhite { return GuardTheme.ink }
        return stopping ? Color.clear : GuardTheme.accent
    }

    private var border: Color {
        stopping ? GuardTheme.line : .clear
    }
}
