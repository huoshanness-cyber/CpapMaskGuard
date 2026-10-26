import SwiftUI

enum PlaceholderKind: Hashable {
    case burnIn
    case health
    case calibrate
    case logs
}

struct PlaceholderPage: View {
    @EnvironmentObject private var model: GuardModel
    let kind: PlaceholderKind

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text(title)
                    .font(.title2.weight(.bold))
                    .foregroundStyle(GuardTheme.text)
                Text(bodyText)
                    .font(.body)
                    .foregroundStyle(GuardTheme.muted)
                    .fixedSize(horizontal: false, vertical: true)
                Text(model.l10n.foregroundLimit)
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
    }

    private var title: String {
        switch kind {
        case .burnIn: return model.l10n.burnInTitle
        case .health: return model.l10n.healthTitle
        case .calibrate: return model.l10n.calibrateTitle
        case .logs: return model.l10n.logsTitle
        }
    }

    private var bodyText: String {
        switch kind {
        case .burnIn: return model.l10n.burnInBody
        case .health: return model.l10n.healthBody
        case .calibrate: return model.l10n.calibrateBody
        case .logs: return model.l10n.logsBody
        }
    }
}
