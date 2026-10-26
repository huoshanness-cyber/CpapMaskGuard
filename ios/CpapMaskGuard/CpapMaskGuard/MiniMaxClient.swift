import Foundation

enum MiniMaxError: Error {
    case missingKey
    case emptyImage
    case http(Int, String)
    case api(String)
    case emptyContent
    case badJSON
    case transport(String)
}

/// POST https://api.minimax.cn/v1/chat/completions
/// Same body as Android `MiniMaxCloudMaskDetector`: MiniMax-M3, thinking disabled,
/// image detail low, max 64 completion tokens, temperature 0.1.
enum MiniMaxClient {
    static let defaultEndpoint = URL(string: "https://api.minimax.cn/v1/chat/completions")!
    static let model = "MiniMax-M3"
    static let prompt = """
    You classify one night-camera still for a CPAP/BiPAP mask alarm.
    Reply with EXACTLY one label and nothing else (no punctuation, no explanation):
    MASK_ON — a person is present and wearing a CPAP/BiPAP nasal or full-face mask
    MASK_OFF — a person is present but the mask is not on the face (off / hanging / missing)
    NO_PERSON — no person visible, empty bed, or nearly black frame
    UNCERTAIN — cannot tell (blurry, extreme angle, ambiguous)
    """

    static func classify(
        jpeg: Data,
        apiKey: String,
        endpoint: URL = defaultEndpoint
    ) async throws -> CloudClassification {
        let key = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        if key.isEmpty || key.contains("$(") {
            throw MiniMaxError.missingKey
        }
        guard !jpeg.isEmpty else { throw MiniMaxError.emptyImage }

        let payload = ImageEncode.jpegForCloud(jpeg) ?? jpeg
        guard !payload.isEmpty else { throw MiniMaxError.emptyImage }

        let dataURL = "data:image/jpeg;base64,\(payload.base64EncodedString())"
        let body: [String: Any] = [
            "model": model,
            "thinking": ["type": "disabled"],
            "max_completion_tokens": 64,
            "temperature": 0.1,
            "messages": [
                [
                    "role": "user",
                    "content": [
                        ["type": "text", "text": prompt],
                        [
                            "type": "image_url",
                            "image_url": [
                                "url": dataURL,
                                "detail": "low",
                            ],
                        ],
                    ],
                ],
            ],
        ]
        let httpBody = try JSONSerialization.data(withJSONObject: body, options: [])
        var request = URLRequest(url: endpoint, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 12)
        request.httpMethod = "POST"
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = httpBody

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await URLSession.shared.data(for: request)
        } catch let error as URLError {
            let note = error.code == .timedOut ? "timeout" : error.localizedDescription
            throw MiniMaxError.transport(redact(note, key: key))
        } catch {
            throw MiniMaxError.transport(redact(error.localizedDescription, key: key))
        }

        let code = (response as? HTTPURLResponse)?.statusCode ?? -1
        let rawText = String(data: data, encoding: .utf8) ?? ""
        if !(200...299).contains(code) {
            throw MiniMaxError.http(code, redact(rawText, key: key))
        }
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw MiniMaxError.badJSON
        }
        if let base = json["base_resp"] as? [String: Any] {
            let status = intValue(base["status_code"]) ?? 0
            if status != 0 {
                let message = base["status_msg"] as? String ?? "error"
                throw MiniMaxError.api(redact("\(status) \(message)", key: key))
            }
        }
        guard let content = messageContent(json) else {
            throw MiniMaxError.emptyContent
        }
        let trimmed = content.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { throw MiniMaxError.emptyContent }
        let parsed = LabelParser.parse(trimmed)
        let usage = usageTokens(json)
        return CloudClassification(
            vote: parsed.vote,
            confidence: parsed.confidence,
            raw: parsed.raw,
            promptTokens: usage.prompt,
            completionTokens: usage.completion
        )
    }

    private static func usageTokens(_ json: [String: Any]) -> (prompt: Int, completion: Int) {
        guard let usage = json["usage"] as? [String: Any] else { return (-1, -1) }
        let prompt = intValue(usage["prompt_tokens"]) ?? intValue(usage["input_tokens"]) ?? -1
        let completion = intValue(usage["completion_tokens"]) ?? intValue(usage["output_tokens"]) ?? -1
        return (prompt, completion)
    }

    private static func messageContent(_ json: [String: Any]) -> String? {
        guard let choices = json["choices"] as? [Any], let first = choices.first as? [String: Any] else {
            return nil
        }
        guard let message = first["message"] as? [String: Any] else { return nil }
        if let text = message["content"] as? String { return text }
        if let parts = message["content"] as? [[String: Any]] {
            let texts = parts.compactMap { $0["text"] as? String }
            if texts.isEmpty { return nil }
            return texts.joined(separator: " ")
        }
        return nil
    }

    private static func intValue(_ any: Any?) -> Int? {
        if let value = any as? Int { return value }
        if let value = any as? NSNumber { return value.intValue }
        return nil
    }

    static func redact(_ text: String, key: String) -> String {
        var value = text.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
        if key.count >= 8 {
            value = value.replacingOccurrences(of: key, with: "[key]")
        }
        if value.count > 160 {
            value = String(value.prefix(160))
        }
        return value.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
