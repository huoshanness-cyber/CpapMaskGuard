import Foundation

enum AppSecrets {
    /// Injected from Config/Secrets.xcconfig via INFOPLIST `MINIMAX_API_KEY`.
    /// Step 1 never sends this anywhere.
    static var minimaxAPIKey: String {
        let raw = Bundle.main.object(forInfoDictionaryKey: "MINIMAX_API_KEY") as? String ?? ""
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed.contains("$(") { return "" }
        return trimmed
    }

    static var hasMinimaxKey: Bool { !minimaxAPIKey.isEmpty }

    static var notificationPurpose: String {
        let raw = Bundle.main.object(forInfoDictionaryKey: "GuardNotificationPurpose") as? String
        return raw ?? "以后如果面罩可能脱落，希望能提醒你。当前版本只申请通知权限，还不会报警。"
    }
}
