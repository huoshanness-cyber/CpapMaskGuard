import Foundation

struct NightEventSummary: Identifiable, Equatable {
    var id: String
    var time: String
    var text: String
}

/// One JSON line per monitor cycle and lifecycle event.
/// File: Application Support `MaskFrames/night-events.jsonl`
/// (app container `Library/Application Support/MaskFrames/night-events.jsonl`).
/// Kept until the file passes 8 MB, then lines older than 48 hours drop first,
/// then the oldest lines, so one sleep-night still fits. Failures are lines, not JPEGs.
enum NightEventLog {
    static let fileName = "night-events.jsonl"
    static let containerRelativePath = "Library/Application Support/MaskFrames/night-events.jsonl"
    private static let maxBytes = 8 * 1024 * 1024
    private static let keepInterval: TimeInterval = 48 * 60 * 60
    private static let lock = NSLock()

    static func append(_ fields: [String: Any]) {
        lock.lock()
        defer { lock.unlock() }
        guard let folder = LocalArchive.maskFramesURL() else { return }
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            var object = fields
            if object["time"] == nil {
                object["time"] = localISO(Date())
            }
            let body = try JSONSerialization.data(withJSONObject: object, options: [])
            var line = body
            line.append(0x0A)
            let url = folder.appendingPathComponent(fileName)
            if FileManager.default.fileExists(atPath: url.path) {
                let handle = try FileHandle(forWritingTo: url)
                defer { try? handle.close() }
                try handle.seekToEnd()
                try handle.write(contentsOf: line)
            } else {
                try line.write(to: url, options: .atomic)
            }
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            if size > maxBytes {
                try compactLocked(url)
            }
        } catch {
            // A full disk must not stop the monitor.
        }
    }

    static func recentSummaries(maxLines: Int = 160) -> [NightEventSummary] {
        lock.lock()
        let raw: String
        if let url = LocalArchive.maskFramesURL()?.appendingPathComponent(fileName),
           let text = try? String(contentsOf: url, encoding: .utf8) {
            raw = text
        } else {
            raw = ""
        }
        lock.unlock()
        let lines = raw.split(whereSeparator: \.isNewline).map(String.init)
        let tail = lines.suffix(maxLines)
        var summaries: [NightEventSummary] = []
        var index = lines.count - tail.count
        for line in tail {
            let summary = summary(line, index: index)
            summaries.append(summary)
            index += 1
        }
        return summaries
    }

    static func localISO(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .current
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSSXXXXX"
        return formatter.string(from: date)
    }

    private static func compactLocked(_ url: URL) throws {
        let raw = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
        var lines = raw.split(whereSeparator: \.isNewline).map(String.init)
        let cutoff = Date().addingTimeInterval(-keepInterval)
        lines = lines.filter { line in
            guard let date = time(in: line) else { return true }
            return date >= cutoff
        }
        while byteCount(lines) > maxBytes, lines.count > 1 {
            lines.removeFirst()
        }
        let text = lines.isEmpty ? "" : lines.joined(separator: "\n") + "\n"
        let temp = url.deletingLastPathComponent().appendingPathComponent(fileName + ".tmp")
        if FileManager.default.fileExists(atPath: temp.path) {
            try? FileManager.default.removeItem(at: temp)
        }
        try Data(text.utf8).write(to: temp, options: .atomic)
        if FileManager.default.fileExists(atPath: url.path) {
            _ = try FileManager.default.replaceItemAt(url, withItemAt: temp)
        } else {
            try FileManager.default.moveItem(at: temp, to: url)
        }
    }

    private static func summary(_ line: String, index: Int) -> NightEventSummary {
        guard let data = line.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else {
            return NightEventSummary(id: "bad-\(index)", time: "", text: String(line.prefix(180)))
        }
        let time = (object["time"] as? String) ?? ""
        var parts: [String] = []
        if let kind = object["kind"] as? String, !kind.isEmpty { parts.append(kind) }
        if let vote = object["vote"] as? String, !vote.isEmpty { parts.append(vote) }
        if let error = object["error"] as? String, !error.isEmpty { parts.append(error) }
        if let http = object["http_status"] as? Int {
            parts.append("HTTP \(http)")
        } else if let http = object["http_status"] as? NSNumber {
            parts.append("HTTP \(http.intValue)")
        }
        if let note = object["note"] as? String, !note.isEmpty { parts.append(note) }
        let text = parts.isEmpty ? line : parts.joined(separator: " · ")
        return NightEventSummary(id: "\(index)-\(time)", time: time, text: text)
    }

    private static func time(in line: String) -> Date? {
        guard let data = line.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let text = object["time"] as? String
        else { return nil }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSSXXXXX"
        if let date = formatter.date(from: text) { return date }
        let fallback = ISO8601DateFormatter()
        fallback.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return fallback.date(from: text) ?? ISO8601DateFormatter().date(from: text)
    }

    private static func byteCount(_ lines: [String]) -> Int {
        lines.reduce(0) { $0 + $1.utf8.count + 1 }
    }
}
