import Foundation

enum AppSecrets {
    /// Injected from Config/Secrets.xcconfig via INFOPLIST `MINIMAX_API_KEY`.
    /// Never log or display this value. An empty or unsubstituted `$(...)` string is treated as missing.
    static var minimaxAPIKey: String {
        let raw = Bundle.main.object(forInfoDictionaryKey: "MINIMAX_API_KEY") as? String ?? ""
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed.contains("$(") { return "" }
        return trimmed
    }

    static var hasMinimaxKey: Bool { !minimaxAPIKey.isEmpty }

    /// Official relay. Empty or an unsubstituted `$(...)` keeps https://cpap.njxxzf.cn.
    /// `off` / `none` / `false` disables it and leaves the optional MiniMax key path.
    static var relayBaseURL: URL? {
        let raw = (Bundle.main.object(forInfoDictionaryKey: "CPAP_RELAY_BASE") as? String ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if raw.isEmpty || raw.contains("$(") {
            return URL(string: "https://cpap.njxxzf.cn")
        }
        let lower = raw.lowercased()
        if lower == "off" || lower == "none" || lower == "false" { return nil }
        var trimmed = raw
        while trimmed.hasSuffix("/") { trimmed.removeLast() }
        return URL(string: trimmed)
    }

    static var usesRelay: Bool { relayBaseURL != nil }

    /// Cloud classification is available. The relay does not need a MiniMax key.
    static var cloudConfigured: Bool { usesRelay || hasMinimaxKey }

    /// Override with MINIMAX_ENDPOINT in Secrets.xcconfig. Empty keeps the public MiniMax URL.
    static var minimaxEndpoint: URL {
        let raw = Bundle.main.object(forInfoDictionaryKey: "MINIMAX_ENDPOINT") as? String ?? ""
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed.contains("$(") {
            return MiniMaxClient.defaultEndpoint
        }
        return URL(string: trimmed) ?? MiniMaxClient.defaultEndpoint
    }

    static var notificationPurpose: String {
        let raw = Bundle.main.object(forInfoDictionaryKey: "GuardNotificationPurpose") as? String
        return raw ?? "面罩可能脱落时提醒你。请保持手机亮屏并停在这个应用里；锁屏后不会在后台监测。"
    }
}
