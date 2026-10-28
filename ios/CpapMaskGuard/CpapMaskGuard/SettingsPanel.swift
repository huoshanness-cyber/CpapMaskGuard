import SwiftUI

struct SettingsPanel: View {
    @EnvironmentObject private var model: GuardModel
    /// Pushes a settings screen on the root stack.
    /// A `NavigationLink` inside this disclosure did not inherit `GuardModel`,
    /// so `PlaceholderPage.title` trapped in `EnvironmentObject.error()`.
    var open: (SettingsRoute) -> Void

    var body: some View {
        DisclosureGroup {
            VStack(alignment: .leading, spacing: 18) {
                languageRow
                lightSection
                phoneSection
                permissionSection
                Text(AppSecrets.usesRelay ? model.l10n.relayStatus : (AppSecrets.hasMinimaxKey ? model.l10n.keyStatusReady : model.l10n.keyStatusEmpty))
                    .font(.footnote)
                    .foregroundStyle(secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Text(model.l10n.appVersionLabel)
                    .font(.footnote)
                    .foregroundStyle(secondary)
            }
            .padding(.top, 8)
        } label: {
            Text(model.l10n.settingsClosed)
                .font(.body.weight(.semibold))
                .foregroundStyle(primary)
        }
        .tint(model.whiteScreenActive ? GuardTheme.ink : GuardTheme.accent)
        .padding(14)
        .background(model.whiteScreenActive ? Color.black.opacity(0.04) : GuardTheme.surface)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private var primary: Color {
        model.whiteScreenActive ? GuardTheme.ink : GuardTheme.text
    }

    private var secondary: Color {
        model.whiteScreenActive ? GuardTheme.ink.opacity(0.62) : GuardTheme.muted
    }

    private var languageRow: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(model.l10n.languageLabel)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(primary)
            Picker(model.l10n.languageLabel, selection: Binding(
                get: { model.language },
                set: { model.setLanguage($0) }
            )) {
                Text("中文").tag(AppLanguage.zh)
                Text("English").tag(AppLanguage.en)
            }
            .pickerStyle(.segmented)
        }
    }

    private var lightSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(model.l10n.sectionLight)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(primary)
            ForEach(FillLightMode.allCases) { mode in
                Button {
                    Task { await model.setFillMode(mode) }
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: model.fillMode == mode ? "checkmark.circle.fill" : "circle")
                            .foregroundStyle(model.whiteScreenActive ? GuardTheme.ink : GuardTheme.accent)
                        Text(model.l10n.fillName(mode))
                            .foregroundStyle(primary)
                        Spacer()
                    }
                    .font(.body)
                }
                .buttonStyle(.plain)
            }
            Text(model.l10n.fillHint(model.fillMode))
                .font(.footnote)
                .foregroundStyle(secondary)
                .fixedSize(horizontal: false, vertical: true)
            intervalSlider

            if model.fillMode == .frontScreen {
                brightnessSlider
            } else {
                torchControls
            }
        }
    }

    private var intervalSlider: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("\(model.l10n.intervalLabel) \(model.intervalSeconds)s")
                .font(.subheadline)
                .foregroundStyle(primary)
            Slider(
                value: Binding(
                    get: { Double(model.intervalSeconds) },
                    set: { model.setInterval(Int($0.rounded())) }
                ),
                in: 5...10,
                step: 1
            )
            .tint(model.whiteScreenActive ? GuardTheme.ink : GuardTheme.accent)
        }
    }

    private var brightnessSlider: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("\(model.l10n.brightnessLabel) \(Int((model.screenBrightness * 100).rounded()))%")
                .font(.subheadline)
                .foregroundStyle(primary)
            Slider(
                value: Binding(
                    get: { model.screenBrightness },
                    set: { model.setScreenBrightness($0) }
                ),
                in: 0.05...1,
                step: 0.05
            )
            .tint(model.whiteScreenActive ? GuardTheme.ink : GuardTheme.accent)
        }
    }

    private var torchControls: some View {
        VStack(alignment: .leading, spacing: 8) {
            Toggle(isOn: Binding(
                get: { model.torchEnabled },
                set: { on in Task { await model.setTorchEnabled(on) } }
            )) {
                Text(model.l10n.torchSwitch)
                    .font(.subheadline)
                    .foregroundStyle(primary)
            }
            .tint(GuardTheme.accent)
            Text("\(model.l10n.torchLevelLabel) \(Int((model.torchLevel * 100).rounded()))% · \(model.torchStatus)")
                .font(.subheadline)
                .foregroundStyle(primary)
            Slider(
                value: Binding(
                    get: { model.torchLevel },
                    set: { model.setTorchLevel($0) }
                ),
                in: 0.01...1,
                step: 0.01
            )
            .tint(GuardTheme.accent)
            Text(model.l10n.torchHint)
                .font(.footnote)
                .foregroundStyle(secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var phoneSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(model.l10n.sectionPhone)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(primary)
            routeButton(model.l10n.healthTitle, route: .status, id: "settings.route.status")
            routeButton(model.l10n.calibrateTitle, route: .baseline, id: "settings.route.baseline")
            routeButton(model.l10n.logsTitle, route: .logs, id: "settings.route.logs")
        }
    }

    private func routeButton(_ title: String, route: SettingsRoute, id: String) -> some View {
        Button {
            open(route)
        } label: {
            HStack {
                Text(title)
                    .foregroundStyle(primary)
                    .multilineTextAlignment(.leading)
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(secondary)
            }
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(id)
    }

    private var permissionSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(model.l10n.sectionPermissions)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(primary)
            Text(AppSecrets.notificationPurpose)
                .font(.footnote)
                .foregroundStyle(secondary)
                .fixedSize(horizontal: false, vertical: true)
            Button(model.l10n.micButton) {
                Task { await model.requestMicrophone() }
            }
            .buttonStyle(QuietButtonStyle(onWhite: model.whiteScreenActive))
            if let micNote = model.micNote {
                Text(micNote).font(.footnote).foregroundStyle(secondary)
            }
            Button(model.l10n.notifyButton) {
                Task { await model.requestNotifications() }
            }
            .buttonStyle(QuietButtonStyle(onWhite: model.whiteScreenActive))
            if let notificationNote = model.notificationNote {
                Text(notificationNote).font(.footnote).foregroundStyle(secondary)
            }
            if model.permissionNote != nil {
                Button(model.l10n.openSettings) {
                    model.openSystemSettings()
                }
                .buttonStyle(QuietButtonStyle(onWhite: model.whiteScreenActive))
            }
        }
    }
}

private struct QuietButtonStyle: ButtonStyle {
    var onWhite: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(onWhite ? GuardTheme.ink : GuardTheme.text)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .background(onWhite ? Color.black.opacity(0.06) : Color.white.opacity(0.04))
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(onWhite ? Color.black.opacity(0.12) : GuardTheme.line, lineWidth: 1)
            )
            .opacity(configuration.isPressed ? 0.75 : 1)
    }
}
