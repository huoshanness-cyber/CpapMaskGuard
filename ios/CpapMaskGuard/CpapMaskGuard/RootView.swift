import SwiftUI

struct RootView: View {
    @StateObject private var model = GuardModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack {
            ZStack {
                background.ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        header
                        statusBlock
                        notices
                        heroButton
                        Text(model.l10n.foregroundLimit)
                            .font(.footnote)
                            .foregroundStyle(foregroundMuted)
                            .fixedSize(horizontal: false, vertical: true)
                        SettingsPanel()
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
            .toolbar(.hidden, for: .navigationBar)
            .environmentObject(model)
        }
        .preferredColorScheme(model.whiteScreenActive ? .light : .dark)
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
    private var notices: some View {
        if let note = model.permissionNote {
            notice(note)
        }
        if let err = model.torchError, model.fillMode == .rearAmbient {
            notice(err)
        }
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
