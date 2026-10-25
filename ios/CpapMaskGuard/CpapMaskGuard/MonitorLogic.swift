import Foundation

/// Cloud / fusion votes. Same labels as the Android MiniMax prompt.
enum FrameVote: String, Equatable {
    case maskOn = "MASK_ON"
    case maskOff = "MASK_OFF"
    case noPerson = "NO_PERSON"
    case uncertain = "UNCERTAIN"
}

struct CloudClassification: Equatable {
    var vote: FrameVote
    var confidence: Double
    var raw: String
}

enum LabelParser {
    /// `MASK_OFF` is tested before `MASK_ON` so a single off label cannot match on.
    static func parse(_ content: String) -> CloudClassification {
        let upper = content.uppercased()
        let vote: FrameVote
        if upper.contains("MASK_OFF") {
            vote = .maskOff
        } else if upper.contains("MASK_ON") {
            vote = .maskOn
        } else if upper.contains("NO_PERSON") || upper.contains("NO PERSON") {
            vote = .noPerson
        } else if upper.contains("UNCERTAIN") {
            vote = .uncertain
        } else {
            vote = .uncertain
        }
        let confidence = vote == .uncertain ? 0.35 : 0.85
        let raw = String(content.trimmingCharacters(in: .whitespacesAndNewlines).prefix(80))
        return CloudClassification(vote: vote, confidence: confidence, raw: raw)
    }
}

/// One MASK_OFF frame is enough (`DetectionConfig.DEBOUNCE_CONSECUTIVE` = 1).
/// NO_PERSON clears the streak and does not alarm. Too many UNCERTAIN frames clear it too.
final class Debouncer {
    private let required: Int
    private let maxUncertainStreak: Int
    private(set) var maskOffStreak = 0
    private(set) var uncertainStreak = 0

    init(required: Int = 1, maxUncertainStreak: Int = 2) {
        self.required = required
        self.maxUncertainStreak = maxUncertainStreak
    }

    /// True only on the frame that first reaches `required`.
    func onVote(_ vote: FrameVote) -> Bool {
        let before = maskOffStreak
        switch vote {
        case .maskOff:
            if maskOffStreak < required { maskOffStreak += 1 }
            uncertainStreak = 0
        case .maskOn, .noPerson:
            maskOffStreak = 0
            uncertainStreak = 0
        case .uncertain:
            uncertainStreak += 1
            if uncertainStreak >= maxUncertainStreak {
                maskOffStreak = 0
            }
        }
        return before < required && maskOffStreak >= required
    }

    func reset() {
        maskOffStreak = 0
        uncertainStreak = 0
    }
}

enum AlarmDecision: Equatable {
    case camera(audioAssisted: Bool)
    case snore(confirmedSeconds: Int, vote: FrameVote?)
    case pendingSnore(elapsedSeconds: Int, needSeconds: Int)
    case quiet

    var shouldAlarm: Bool {
        switch self {
        case .camera, .snore:
            return true
        case .pendingSnore, .quiet:
            return false
        }
    }
}

/// Camera vote plus local snore confirmation. Mirrors `SnoreFusion` on Android.
enum SnoreFusion {
    static let audioAlarmMsWhenNotOn = 6_000
    static let audioAlarmMsWhenOn = 12_000

    static func fuse(
        cameraShouldAlarm: Bool,
        cameraVote: FrameVote?,
        snoreConfirmed: Bool,
        snoreConfirmedForMs: Int
    ) -> AlarmDecision {
        if cameraShouldAlarm {
            return .camera(audioAssisted: snoreConfirmed)
        }
        guard snoreConfirmed else { return .quiet }
        let need = cameraVote == .maskOn ? audioAlarmMsWhenOn : audioAlarmMsWhenNotOn
        if snoreConfirmedForMs >= need {
            return .snore(confirmedSeconds: snoreConfirmedForMs / 1000, vote: cameraVote)
        }
        return .pendingSnore(elapsedSeconds: snoreConfirmedForMs / 1000, needSeconds: need / 1000)
    }
}

struct SnoreFeatures: Equatable {
    var rms: Float
    var lowRatio: Float
    var zcr: Float
    var snoreLike: Bool
}

enum SnoreHeuristics {
    static let minRMS: Float = 0.018
    static let minLowRatio: Float = 0.55
    static let maxZCR: Float = 0.22
    static let confirmWindows = 4
    static let holdWindows = 3
    static let history = 8

    static func analyze(_ pcm: [Int16]) -> SnoreFeatures {
        let n = pcm.count
        if n < 32 {
            return SnoreFeatures(rms: 0, lowRatio: 0, zcr: 0, snoreLike: false)
        }
        var sumSq = 0.0
        var lowEnergy = 0.0
        var totalDiff = 0.0
        var zeroCross = 0
        var prev = Int(pcm[0])
        for sample in pcm {
            let value = Int(sample)
            let fv = Double(value) / 32768.0
            sumSq += fv * fv
            let diff = abs(value - prev)
            totalDiff += Double(diff)
            let smooth = (Double(value) + Double(prev)) / 2.0 / 32768.0
            lowEnergy += smooth * smooth
            if (prev >= 0 && value < 0) || (prev < 0 && value >= 0) {
                zeroCross += 1
            }
            prev = value
        }
        let rms = Float(sqrt(sumSq / Double(n)))
        let lowRatio = sumSq <= 1e-12 ? 0 : min(2, max(0, Float(lowEnergy / sumSq)))
        let zcr = min(1, max(0, Float(zeroCross) / Float(n - 1)))
        let meanAbsDiff = Float(totalDiff / Double(n) / 32768.0)
        let spectralFlat = rms < 1e-6 ? 1 : min(10, max(0, meanAbsDiff / (rms + 1e-6)))
        let snoreLike = rms >= minRMS && zcr <= maxZCR && spectralFlat <= 2.8 && lowRatio >= minLowRatio
        return SnoreFeatures(rms: rms, lowRatio: lowRatio, zcr: zcr, snoreLike: snoreLike)
    }
}

final class SnoreTracker {
    private let confirmNeed: Int
    private let holdNeed: Int
    private let history: Int
    private var recent: [Bool] = []
    private(set) var confirmed = false
    private(set) var confirmedForMs = 0
    private var confirmStarted = -1
    private(set) var positives = 0

    init(
        confirmNeed: Int = SnoreHeuristics.confirmWindows,
        holdNeed: Int = SnoreHeuristics.holdWindows,
        history: Int = SnoreHeuristics.history
    ) {
        self.confirmNeed = confirmNeed
        self.holdNeed = holdNeed
        self.history = history
    }

    func onWindow(snoreLike: Bool, elapsedMs: Int) {
        if recent.count >= history { recent.removeFirst() }
        recent.append(snoreLike)
        positives = recent.filter { $0 }.count
        if !confirmed {
            let tail = recent.suffix(confirmNeed)
            if positives >= confirmNeed && tail.count >= confirmNeed && tail.allSatisfy({ $0 }) {
                confirmed = true
                confirmStarted = elapsedMs
            }
        } else if positives < holdNeed {
            confirmed = false
            confirmStarted = -1
        }
        if confirmed && confirmStarted >= 0 {
            confirmedForMs = max(0, elapsedMs - confirmStarted)
        } else {
            confirmedForMs = 0
        }
    }

    func reset() {
        recent.removeAll()
        confirmed = false
        confirmStarted = -1
        confirmedForMs = 0
        positives = 0
    }

    var windowCount: Int { recent.count }
}

enum SnoreListenState: Equatable {
    case off
    case denied
    case unavailable
    case listening
    case confirmed
}

struct SnoreSnapshot: Equatable {
    var state: SnoreListenState = .off
    var confirmedForMs: Int = 0
    var positives: Int = 0
    var windows: Int = 0
    var rms: Float = 0

    static let off = SnoreSnapshot()
}

struct HealthSnapshot: Equatable {
    var score: Int
    var reason: String
}

/// Same weights as Android: 0.35 temp + 0.35 latency + 0.30 stability.
/// iOS has no battery-temperature reading here, so that term stays the neutral 70.
enum HealthMath {
    static func score(
        captureToJudgeMs: Int?,
        roundMs: Int?,
        consecutiveFailures: Int,
        language: AppLanguage
    ) -> HealthSnapshot {
        let temp = 70.0
        let latency = latencyScore(captureToJudgeMs, roundMs: roundMs)
        let stability = max(0, 70.0 - Double(min(60, consecutiveFailures * 12)))
        let total = min(100, max(0, Int((0.35 * temp + 0.35 * latency + 0.30 * stability).rounded())))
        let judge: String
        if let captureToJudgeMs {
            judge = "\(captureToJudgeMs)ms"
        } else {
            judge = language == .zh ? "未知" : "unknown"
        }
        let reason: String
        if language == .zh {
            let head = total < 60 ? "不建议长时间开着 · " : ""
            reason = "\(head)电池温度未知 · 判定 \(judge) · 连续失败 \(consecutiveFailures)"
        } else {
            let head = total < 60 ? "Better not to leave it running · " : ""
            reason = "\(head)Battery temperature unknown · Judge \(judge) · Failures \(consecutiveFailures)"
        }
        return HealthSnapshot(score: total, reason: reason)
    }

    private static func latencyScore(_ captureToJudgeMs: Int?, roundMs: Int?) -> Double {
        let base: Double
        if let ms = captureToJudgeMs {
            let value = Double(ms)
            if ms <= 800 {
                base = 100
            } else if ms <= 3000 {
                base = lerp(value, 800, 3000, 100, 30)
            } else if ms <= 5000 {
                base = lerp(value, 3000, 5000, 30, 5)
            } else {
                base = 0
            }
        } else {
            base = 70
        }
        if let roundMs, roundMs > 10_000 {
            return max(0, base - 25)
        }
        return base
    }

    private static func lerp(_ x: Double, _ a: Double, _ b: Double, _ ya: Double, _ yb: Double) -> Double {
        if a == b { return ya }
        let t = (x - a) / (b - a)
        return ya + (yb - ya) * t
    }
}

enum MonitorClock {
    static func milliseconds(since date: Date, now: Date = Date()) -> Int {
        max(0, Int((now.timeIntervalSince(date) * 1000).rounded()))
    }

    static func uptimeMilliseconds() -> Int {
        Int(ProcessInfo.processInfo.systemUptime * 1000)
    }
}
