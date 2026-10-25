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

    static var notificationPurpose: String {
        let raw = Bundle.main.object(forInfoDictionaryKey: "GuardNotificationPurpose") as? String
        return raw ?? "面罩可能脱落时提醒你。请保持手机亮屏并停在这个应用里；锁屏后不会在后台监测。"
    }
}
