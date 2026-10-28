import CryptoKit
import Foundation
import UIKit

enum RelayError: LocalizedError {
    case disabled
    case emptyImage
    case http(Int, String)
    case badJSON
    case transport(String)

    var errorDescription: String? {
        switch self {
        case .disabled:
            return "官方中转已关闭"
        case .emptyImage:
            return "这一张是空的"
        case .http(let code, let body):
            return "中转 HTTP \(code) \(body)"
        case .badJSON:
            return "中转返回看不懂"
        case .transport(let note):
            return "中转连不上 \(note)"
        }
    }
}

/// Official cloud path. No MiniMax key on device.
/// GET /v1/config → POST /v1/auth/device → POST /v1/classify.
actor CpapRelayClient {
    static let shared = CpapRelayClient()

    private var accessToken: String?
    private var tokenExpiry = Date.distantPast
    private var pepperHint: String?

    func classify(jpeg: Data, deviceId: String, appVersion: String) async throws -> CloudClassification {
        guard let base = AppSecrets.relayBaseURL else { throw RelayError.disabled }
        guard !jpeg.isEmpty else { throw RelayError.emptyImage }
        let payload = ImageEncode.jpegForCloud(jpeg) ?? jpeg
        guard !payload.isEmpty else { throw RelayError.emptyImage }
        do {
            return try await classifyOnce(
                jpeg: payload,
                base: base,
                deviceId: deviceId,
                appVersion: appVersion,
                allowRetry: true
            )
        } catch let error as RelayError {
            guard case .http(401, _) = error else { throw error }
            accessToken = nil
            tokenExpiry = .distantPast
            return try await classifyOnce(
                jpeg: payload,
                base: base,
                deviceId: deviceId,
                appVersion: appVersion,
                allowRetry: false
            )
        }
    }

    private func classifyOnce(
        jpeg: Data,
        base: URL,
        deviceId: String,
        appVersion: String,
        allowRetry: Bool
    ) async throws -> CloudClassification {
        let token = try await ensureToken(base: base, deviceId: deviceId, appVersion: appVersion)
        let image: [String: Any] = [
            "encoding": "jpeg_base64",
            "data": jpeg.base64EncodedString(),
            "max_side": 640,
            "jpeg_quality": 55,
            "exif_stripped": true,
        ]
        let body: [String: Any] = [
            "schema": "cpap.classify.v1",
            "captured_at": Int(Date().timeIntervalSince1970 * 1000),
            "image": image,
        ]
        let (code, raw) = try await send(
            base: base,
            path: "/v1/classify",
            method: "POST",
            json: body,
            bearer: token,
            appVersion: appVersion,
            timeout: 20
        )
        if code == 401 && allowRetry {
            throw RelayError.http(401, clip(raw))
        }
        guard (200...299).contains(code) else {
            throw RelayError.http(code, clip(raw))
        }
        return try parseClassify(raw)
    }

    private func ensureToken(base: URL, deviceId: String, appVersion: String) async throws -> String {
        if let accessToken, tokenExpiry.timeIntervalSinceNow > 60 {
            return accessToken
        }
        let hint = try await fetchPepper(base: base, appVersion: appVersion)
        pepperHint = hint
        let hash = Self.deviceHash(deviceId: deviceId, pepper: hint)
        let body: [String: Any] = [
            "device_id_hash": hash,
            "platform": "ios",
            "app_version": appVersion,
        ]
        let (code, raw) = try await send(
            base: base,
            path: "/v1/auth/device",
            method: "POST",
            json: body,
            bearer: nil,
            appVersion: appVersion,
            timeout: 12
        )
        guard (200...299).contains(code) else {
            throw RelayError.http(code, clip(raw))
        }
        guard
            let json = try? JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any],
            let token = json["access_token"] as? String,
            !token.isEmpty
        else {
            throw RelayError.badJSON
        }
        let expires = (json["expires_in"] as? NSNumber)?.intValue ?? 3600
        accessToken = token
        tokenExpiry = Date().addingTimeInterval(TimeInterval(max(60, expires)))
        return token
    }

    private func fetchPepper(base: URL, appVersion: String) async throws -> String {
        if let pepperHint, !pepperHint.isEmpty { return pepperHint }
        let (code, raw) = try await send(
            base: base,
            path: "/v1/config",
            method: "GET",
            json: nil,
            bearer: nil,
            appVersion: appVersion,
            timeout: 12
        )
        guard (200...299).contains(code) else {
            throw RelayError.http(code, clip(raw))
        }
        guard
            let json = try? JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any],
            let hint = json["pepper_hint"] as? String,
            !hint.isEmpty
        else {
            throw RelayError.badJSON
        }
        return hint
    }

    private func send(
        base: URL,
        path: String,
        method: String,
        json: [String: Any]?,
        bearer: String?,
        appVersion: String,
        timeout: TimeInterval
    ) async throws -> (Int, String) {
        let url = Self.endpoint(base, path)
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: timeout)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("ios", forHTTPHeaderField: "X-Cpap-Client")
        request.setValue(appVersion, forHTTPHeaderField: "X-Cpap-App-Version")
        request.setValue("1", forHTTPHeaderField: "X-Cpap-Contract")
        if let bearer {
            request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        }
        if let json {
            request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: json)
        }
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            let code = (response as? HTTPURLResponse)?.statusCode ?? -1
            let text = String(data: data, encoding: .utf8) ?? ""
            return (code, text)
        } catch {
            throw RelayError.transport(error.localizedDescription)
        }
    }

    private func parseClassify(_ raw: String) throws -> CloudClassification {
        guard let json = try? JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any] else {
            throw RelayError.badJSON
        }
        let voteRaw = (json["vote"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !voteRaw.isEmpty else { throw RelayError.badJSON }
        let modelText = (json["raw_model_text"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let parsed = LabelParser.parse(modelText.isEmpty ? voteRaw : modelText)
        let vote = Self.vote(voteRaw) ?? parsed.vote
        let usage = json["usage"] as? [String: Any]
        let prompt = (usage?["prompt_tokens"] as? NSNumber)?.intValue
            ?? (usage?["input_tokens"] as? NSNumber)?.intValue
            ?? -1
        let completion = (usage?["completion_tokens"] as? NSNumber)?.intValue
            ?? (usage?["output_tokens"] as? NSNumber)?.intValue
            ?? -1
        return CloudClassification(
            vote: vote,
            confidence: vote == .uncertain ? 0.35 : 0.85,
            raw: String((modelText.isEmpty ? voteRaw : modelText).prefix(80)),
            promptTokens: prompt,
            completionTokens: completion
        )
    }

    private static func vote(_ raw: String) -> FrameVote? {
        switch raw.uppercased() {
        case "MASK_ON": return .maskOn
        case "MASK_OFF": return .maskOff
        case "NO_PERSON": return .noPerson
        case "UNCERTAIN": return .uncertain
        default: return nil
        }
    }

    private static func endpoint(_ base: URL, _ path: String) -> URL {
        var root = base.absoluteString
        while root.hasSuffix("/") { root.removeLast() }
        let tail = path.hasPrefix("/") ? path : "/" + path
        return URL(string: root + tail) ?? base
    }

    private static func deviceHash(deviceId: String, pepper: String) -> String {
        let material = Data("\(deviceId):\(pepper)".utf8)
        let digest = SHA256.hash(data: material)
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    private func clip(_ text: String) -> String {
        let one = text.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
        if one.count > 160 { return String(one.prefix(160)) }
        return one
    }
}

enum DeviceStamp {
    private static let fallbackKey = "cpap.relay.device"

    @MainActor
    static func current() -> String {
        if let id = UIDevice.current.identifierForVendor?.uuidString, !id.isEmpty {
            return id
        }
        if let saved = UserDefaults.standard.string(forKey: fallbackKey), !saved.isEmpty {
            return saved
        }
        let made = UUID().uuidString
        UserDefaults.standard.set(made, forKey: fallbackKey)
        return made
    }
}
