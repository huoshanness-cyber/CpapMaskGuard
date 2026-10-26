import Foundation

struct ArchivePayload {
    var capturedAt: Date
    var result: String
    var vote: String
    var latencyMs: Int
    var roundMs: Int
    var fillMode: String
    var reason: String
    var jpeg: Data
    var promptTokens: Int = -1
    var completionTokens: Int = -1
}

struct ArchiveItem: Identifiable {
    let id: String
    let capturedAt: Date
    let result: String
    let reason: String
    let latencyMs: Int?
    let jpeg: Data?
}

/// Last few stills plus a small JSON sidecar. Local only. Never stores the API key.
/// Write failures are swallowed so a full disk cannot take down the monitor.
enum FrameArchive {
    private static let keep = 12

    static func save(_ payload: ArchivePayload) {
        guard let folder = folderURL() else { return }
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let stem = fileStem(payload.capturedAt)
            let jpegURL = folder.appendingPathComponent("\(stem).jpg")
            let jsonURL = folder.appendingPathComponent("\(stem).json")
            try payload.jpeg.write(to: jpegURL, options: .atomic)
            var object: [String: Any] = [
                "capturedAt": isoString(payload.capturedAt),
                "result": payload.result,
                "vote": payload.vote,
                "latencyMs": payload.latencyMs,
                "roundMs": payload.roundMs,
                "fillMode": payload.fillMode,
                "reason": payload.reason,
            ]
            if payload.promptTokens >= 0 { object["promptTokens"] = payload.promptTokens }
            if payload.completionTokens >= 0 { object["completionTokens"] = payload.completionTokens }
            let json = try JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted])
            try json.write(to: jsonURL, options: .atomic)
            prune(folder)
        } catch {
            // The monitor keeps running when the archive cannot be written.
        }
    }

    static func list() -> [ArchiveItem] {
        guard let folder = folderURL(),
              let names = try? FileManager.default.contentsOfDirectory(atPath: folder.path)
        else { return [] }
        let stems = Set(names.map { URL(fileURLWithPath: $0).deletingPathExtension().lastPathComponent })
        var items: [ArchiveItem] = []
        for stem in stems where stem.hasPrefix("frame-") {
            let jpegURL = folder.appendingPathComponent("\(stem).jpg")
            let jsonURL = folder.appendingPathComponent("\(stem).json")
            guard let jsonData = try? Data(contentsOf: jsonURL),
                  let object = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any]
            else { continue }
            let trimmed = (object["result"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let safeResult = trimmed.isEmpty ? "样张" : trimmed
            let reason = object["reason"] as? String ?? ""
            let capturedAt = (object["capturedAt"] as? String).flatMap(parseISO) ?? Date()
            let latency = object["latencyMs"] as? Int ?? (object["latencyMs"] as? NSNumber)?.intValue
            let jpeg = try? Data(contentsOf: jpegURL)
            items.append(ArchiveItem(
                id: stem,
                capturedAt: capturedAt,
                result: safeResult,
                reason: reason,
                latencyMs: latency,
                jpeg: jpeg
            ))
        }
        return items.sorted { $0.capturedAt > $1.capturedAt }.prefix(keep).map { $0 }
    }

    private static func prune(_ folder: URL) {
        guard let names = try? FileManager.default.contentsOfDirectory(atPath: folder.path) else { return }
        let stems = Set(names.map { URL(fileURLWithPath: $0).deletingPathExtension().lastPathComponent })
            .filter { $0.hasPrefix("frame-") }
            .sorted()
        if stems.count <= keep { return }
        for stem in stems.dropLast(keep) {
            try? FileManager.default.removeItem(at: folder.appendingPathComponent("\(stem).jpg"))
            try? FileManager.default.removeItem(at: folder.appendingPathComponent("\(stem).json"))
        }
    }

    private static func folderURL() -> URL? {
        FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)
            .first?
            .appendingPathComponent("MaskFrames", isDirectory: true)
    }

    private static func fileStem(_ date: Date) -> String {
        let millis = Int(date.timeIntervalSince1970 * 1000)
        let suffix = UUID().uuidString.prefix(8)
        return "frame-\(millis)-\(suffix)"
    }

    private static func isoString(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: date)
    }

    private static func parseISO(_ value: String) -> Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.date(from: value) ?? ISO8601DateFormatter().date(from: value)
    }
}
