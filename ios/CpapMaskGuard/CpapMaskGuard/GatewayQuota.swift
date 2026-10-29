import CoreFoundation
import Foundation

/// Sleep-day classify budget from `POST /v1/classify`.
/// Live relay shape (cpap.classify.v1):
/// `{ "sleep_day": "2026-10-28", "quota": { "classify_used": 2, "classify_limit": 6000, "classify_remaining": 5998 } }`
/// Error bodies put `sleep_day` and sometimes `quota` under `error`.
struct GatewayQuota: Equatable {
    var classifyUsed: Int
    var classifyLimit: Int
    var classifyRemaining: Int
    var sleepDay: String
}

struct RelayHTTPError: Equatable {
    var status: Int
    var snippet: String
    var errorCode: String?
    var sleepDay: String?
    var quota: GatewayQuota?
}

struct GatewayBodyMeta: Equatable {
    var quota: GatewayQuota?
    var sleepDay: String?
    var errorCode: String?
}

enum GatewayQuotaPolicy {
    /// Show a warning while 5% or less of the sleep-day budget is left.
    static func isLow(_ quota: GatewayQuota) -> Bool {
        guard quota.classifyRemaining > 0, quota.classifyLimit > 0 else { return false }
        return quota.classifyRemaining * 20 <= quota.classifyLimit
    }

    /// One classify attempt after the budget reads empty, so a same-night server raise is visible.
    static let probeSpacing: TimeInterval = 15 * 60
}

enum GatewayQuotaParsing {
    static func read(_ json: [String: Any]) -> GatewayBodyMeta {
        let error = json["error"] as? [String: Any]
        let day = string(json["sleep_day"]) ?? error.flatMap { string($0["sleep_day"]) }
        let code = error.flatMap { string($0["code"]) }
        let box = (json["quota"] as? [String: Any]) ?? (error?["quota"] as? [String: Any])
        let quota = box.flatMap { parseBox($0, sleepDay: day) } ?? parseExplicitRoot(json, sleepDay: day)
        return GatewayBodyMeta(quota: quota, sleepDay: day, errorCode: code)
    }

    /// Numbers inside a `quota` object. `used` / `limit` / `remaining` are aliases there only.
    private static func parseBox(_ box: [String: Any], sleepDay: String?) -> GatewayQuota? {
        let used = int(box["classify_used"]) ?? int(box["used"])
        let limit = int(box["classify_limit"]) ?? int(box["limit"])
        let remaining = int(box["classify_remaining"]) ?? int(box["remaining"])
        guard let used, let limit, let remaining else { return nil }
        let day = string(box["sleep_day"]) ?? sleepDay ?? ""
        return GatewayQuota(
            classifyUsed: used,
            classifyLimit: limit,
            classifyRemaining: remaining,
            sleepDay: day
        )
    }

    /// Top-level fields only when they use the classify_* names, so a stray `used` is ignored.
    private static func parseExplicitRoot(_ json: [String: Any], sleepDay: String?) -> GatewayQuota? {
        guard let used = int(json["classify_used"]),
              let limit = int(json["classify_limit"]),
              let remaining = int(json["classify_remaining"])
        else { return nil }
        return GatewayQuota(
            classifyUsed: used,
            classifyLimit: limit,
            classifyRemaining: remaining,
            sleepDay: sleepDay ?? ""
        )
    }

    private static func string(_ value: Any?) -> String? {
        guard let text = value as? String else { return nil }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private static func int(_ value: Any?) -> Int? {
        if let number = value as? Int { return number }
        if let number = value as? NSNumber {
            if CFGetTypeID(number) == CFBooleanGetTypeID() { return nil }
            return number.intValue
        }
        if let text = value as? String { return Int(text) }
        return nil
    }
}

/// Last classify budget the relay reported. Separate from `cloud_resume_used`.
enum GatewayQuotaStore {
    private static let usedKey = "gateway_classify_used"
    private static let limitKey = "gateway_classify_limit"
    private static let remainingKey = "gateway_classify_remaining"
    private static let dayKey = "gateway_classify_sleep_day"
    private static let blockedKey = "gateway_classify_blocked_day"

    static func load() -> GatewayQuota? {
        let defaults = UserDefaults.standard
        guard defaults.object(forKey: usedKey) != nil,
              defaults.object(forKey: limitKey) != nil,
              defaults.object(forKey: remainingKey) != nil
        else { return nil }
        return GatewayQuota(
            classifyUsed: defaults.integer(forKey: usedKey),
            classifyLimit: defaults.integer(forKey: limitKey),
            classifyRemaining: defaults.integer(forKey: remainingKey),
            sleepDay: defaults.string(forKey: dayKey) ?? ""
        )
    }

    static func save(_ quota: GatewayQuota) {
        let defaults = UserDefaults.standard
        defaults.set(quota.classifyUsed, forKey: usedKey)
        defaults.set(quota.classifyLimit, forKey: limitKey)
        defaults.set(quota.classifyRemaining, forKey: remainingKey)
        defaults.set(quota.sleepDay, forKey: dayKey)
        if quota.classifyRemaining <= 0, !quota.sleepDay.isEmpty {
            defaults.set(quota.sleepDay, forKey: blockedKey)
        } else {
            defaults.removeObject(forKey: blockedKey)
        }
    }

    static func markBlocked(_ sleepDay: String) {
        let day = sleepDay.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !day.isEmpty else { return }
        UserDefaults.standard.set(day, forKey: blockedKey)
    }

    static func isBlocked(on sleepDay: String) -> Bool {
        guard !sleepDay.isEmpty else { return false }
        return UserDefaults.standard.string(forKey: blockedKey) == sleepDay
    }
}
