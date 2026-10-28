import CoreGraphics
import Foundation
import Vision

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
    var promptTokens: Int = -1
    var completionTokens: Int = -1
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

/// Same meaning as Android `DetectionConfig.DEBOUNCE_CONSECUTIVE`.
enum DetectionConfig {
    /// Two frames. One side-sleep mislabel must not ring.
    static let debounceConsecutive = 2
}

/// Two consecutive MASK_OFF frames are required (`DetectionConfig.debounceConsecutive` = 2).
/// A single MASK_OFF does not ring.
/// NO_PERSON clears the streak and does not alarm. Too many UNCERTAIN frames clear it too.
final class Debouncer {
    private let required: Int
    private let maxUncertainStreak: Int
    private(set) var maskOffStreak = 0
    private(set) var uncertainStreak = 0

    init(required: Int = DetectionConfig.debounceConsecutive, maxUncertainStreak: Int = 2) {
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

/// Same rules as Android `CloudUploadPolicy`: 10 cloud MASK_OFF results stop uploads,
/// 10 resumes per Asia/Shanghai sleep day (noon, not midnight). An aim miss does not touch the streak.
struct CloudUploadState: Equatable {
    var consecutiveMaskOff: Int = 0
    var uploadEnabled: Bool = true
    var resumesUsed: Int = 0
    var resumeDay: String = ""
    var lastCloudWasMaskOff: Bool = false
}

enum CloudResume {
    case accepted(CloudUploadState)
    case denied(CloudUploadState)
    case alreadyOn(CloudUploadState)

    var state: CloudUploadState {
        switch self {
        case .accepted(let state), .denied(let state), .alreadyOn(let state):
            return state
        }
    }
}

enum CloudUploadPolicy {
    static let maskOffStop = 10
    static let dailyResumeLimit = 10

    static func remaining(_ state: CloudUploadState) -> Int {
        max(0, dailyResumeLimit - state.resumesUsed)
    }

    /// Sleep day rolls at 12:00 Asia/Shanghai. Hours before noon stay on the previous date.
    static func dayKey(_ date: Date) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Shanghai") ?? TimeZone(secondsFromGMT: 8 * 3600)!
        let parts = calendar.dateComponents([.year, .month, .day, .hour], from: date)
        var day = calendar.date(from: DateComponents(
            year: parts.year,
            month: parts.month,
            day: parts.day
        )) ?? date
        if (parts.hour ?? 0) < 12 {
            day = calendar.date(byAdding: .day, value: -1, to: day) ?? day
        }
        let out = calendar.dateComponents([.year, .month, .day], from: day)
        return String(format: "%04d-%02d-%02d", out.year ?? 0, out.month ?? 0, out.day ?? 0)
    }

    static func roll(_ state: CloudUploadState, now: Date) -> CloudUploadState {
        let today = dayKey(now)
        if state.resumeDay == today { return state }
        var copy = state
        copy.resumeDay = today
        copy.resumesUsed = 0
        return copy
    }

    static func onAimInvalid(_ state: CloudUploadState, now: Date) -> CloudUploadState {
        roll(state, now: now)
    }

    static func onCloudVote(_ state: CloudUploadState, vote: FrameVote, now: Date) -> CloudUploadState {
        var rolled = roll(state, now: now)
        if !rolled.uploadEnabled { return rolled }
        if vote != .maskOff {
            rolled.consecutiveMaskOff = 0
            rolled.lastCloudWasMaskOff = false
            return rolled
        }
        rolled.consecutiveMaskOff += 1
        rolled.lastCloudWasMaskOff = true
        if rolled.consecutiveMaskOff >= maskOffStop {
            rolled.uploadEnabled = false
        }
        return rolled
    }

    static func resume(_ state: CloudUploadState, now: Date) -> CloudResume {
        var rolled = roll(state, now: now)
        if rolled.uploadEnabled { return .alreadyOn(rolled) }
        if rolled.resumesUsed >= dailyResumeLimit { return .denied(rolled) }
        rolled.uploadEnabled = true
        rolled.consecutiveMaskOff = 0
        rolled.lastCloudWasMaskOff = false
        rolled.resumesUsed += 1
        return .accepted(rolled)
    }
}

final class CloudUploadMemory {
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load(now: Date) -> CloudUploadState {
        let raw = read()
        let rolled = CloudUploadPolicy.roll(raw, now: now)
        if rolled != raw { write(rolled) }
        return rolled
    }

    func noteAimInvalid(now: Date) -> CloudUploadState {
        let next = CloudUploadPolicy.onAimInvalid(load(now: now), now: now)
        write(next)
        return next
    }

    func noteCloud(_ vote: FrameVote, now: Date) -> CloudUploadState {
        let next = CloudUploadPolicy.onCloudVote(load(now: now), vote: vote, now: now)
        write(next)
        return next
    }

    func resume(now: Date) -> CloudResume {
        let outcome = CloudUploadPolicy.resume(load(now: now), now: now)
        write(outcome.state)
        return outcome
    }

    private func read() -> CloudUploadState {
        let enabled: Bool
        if defaults.object(forKey: Key.enabled) == nil {
            enabled = true
        } else {
            enabled = defaults.bool(forKey: Key.enabled)
        }
        return CloudUploadState(
            consecutiveMaskOff: max(0, defaults.integer(forKey: Key.streak)),
            uploadEnabled: enabled,
            resumesUsed: max(0, defaults.integer(forKey: Key.resumes)),
            resumeDay: defaults.string(forKey: Key.day) ?? "",
            lastCloudWasMaskOff: defaults.bool(forKey: Key.lastOff)
        )
    }

    private func write(_ state: CloudUploadState) {
        defaults.set(state.consecutiveMaskOff, forKey: Key.streak)
        defaults.set(state.uploadEnabled, forKey: Key.enabled)
        defaults.set(state.resumesUsed, forKey: Key.resumes)
        defaults.set(state.resumeDay, forKey: Key.day)
        defaults.set(state.lastCloudWasMaskOff, forKey: Key.lastOff)
    }

    private enum Key {
        static let streak = "cloud_mask_off_streak"
        static let enabled = "cloud_upload_enabled"
        static let resumes = "cloud_resume_used"
        static let day = "cloud_resume_day"
        static let lastOff = "cloud_last_mask_off"
    }
}

enum AimReason: Equatable {
    case face
    case person
    case scene
    case bedside
    case uncertain
    case black
    case white
    case blur
}

struct AimVerdict: Equatable {
    var valid: Bool
    var reason: AimReason
}

/// Thresholds match Android `AimGate`. A face is not required. Uncertain frames are allowed.
enum AimGate {
    static let minFaceWidthFraction: Float = 0.12
    static let minPersonAreaFraction: Float = 0.04
    static let minCenterMean: Float = 0.06
    static let maxCenterMean: Float = 0.90
    static let minCenterVariance: Float = 0.0018
    static let minCenterEdge: Float = 0.012
    static let blackMean: Float = 0.04
    static let blackVariance: Float = 0.0008
    static let whiteMean: Float = 0.93
    static let whiteVariance: Float = 0.002
    static let blurVariance: Float = 0.0005
    static let blurEdge: Float = 0.004

    static func evaluate(
        faceWidthFraction: Float,
        personAreaFraction: Float,
        sceneMatchesBedside: Bool,
        centerMean: Float,
        centerVariance: Float,
        centerEdge: Float
    ) -> AimVerdict {
        if faceWidthFraction >= minFaceWidthFraction {
            return AimVerdict(valid: true, reason: .face)
        }
        if personAreaFraction >= minPersonAreaFraction {
            return AimVerdict(valid: true, reason: .person)
        }
        if sceneMatchesBedside {
            return AimVerdict(valid: true, reason: .scene)
        }
        let bedside = centerMean >= minCenterMean && centerMean <= maxCenterMean
            && centerVariance >= minCenterVariance
            && centerEdge >= minCenterEdge
        if bedside {
            return AimVerdict(valid: true, reason: .bedside)
        }
        if centerMean < blackMean && centerVariance < blackVariance {
            return AimVerdict(valid: false, reason: .black)
        }
        if centerMean > whiteMean && centerVariance < whiteVariance {
            return AimVerdict(valid: false, reason: .white)
        }
        if centerVariance < blurVariance && centerEdge <= blurEdge {
            return AimVerdict(valid: false, reason: .blur)
        }
        return AimVerdict(valid: true, reason: .uncertain)
    }
}

enum AimImageStats {
    static func center(of image: CGImage) -> (mean: Float, variance: Float, edge: Float) {
        let side = 48
        var pixels = [UInt8](repeating: 0, count: side * side * 4)
        let drew = pixels.withUnsafeMutableBytes { raw -> Bool in
            guard let context = CGContext(
                data: raw.baseAddress,
                width: side,
                height: side,
                bitsPerComponent: 8,
                bytesPerRow: side * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue
            ) else { return false }
            context.interpolationQuality = .low
            context.draw(image, in: CGRect(x: 0, y: 0, width: side, height: side))
            return true
        }
        if !drew { return (0, 0, 0) }
        let start = side / 4
        let end = side - start
        var sum = 0.0
        var sumSq = 0.0
        var edgeSum = 0.0
        var count = 0
        var edges = 0
        for y in start..<end {
            var previous = luma(pixels, x: start, y: y, side: side)
            for x in start..<end {
                let value = luma(pixels, x: x, y: y, side: side)
                sum += Double(value)
                sumSq += Double(value * value)
                count += 1
                if x > start {
                    edgeSum += Double(abs(value - previous))
                    edges += 1
                }
                previous = value
            }
        }
        if count == 0 { return (0, 0, 0) }
        let mean = sum / Double(count)
        let variance = max(0, sumSq / Double(count) - mean * mean)
        let edge = edges == 0 ? 0 : edgeSum / Double(edges)
        return (Float(mean), Float(variance), Float(edge))
    }

    /// 4×4 mean luma. Used only to notice an obvious scene change while uploads are paused.
    static func signature(of image: CGImage) -> [Float] {
        let side = 48
        var pixels = [UInt8](repeating: 0, count: side * side * 4)
        let drew = pixels.withUnsafeMutableBytes { raw -> Bool in
            guard let context = CGContext(
                data: raw.baseAddress,
                width: side,
                height: side,
                bitsPerComponent: 8,
                bytesPerRow: side * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue
            ) else { return false }
            context.interpolationQuality = .low
            context.draw(image, in: CGRect(x: 0, y: 0, width: side, height: side))
            return true
        }
        if !drew { return [] }
        let cells = 4
        let cell = side / cells
        var sums = [Float](repeating: 0, count: cells * cells)
        var counts = [Int](repeating: 0, count: cells * cells)
        for y in 0..<side {
            for x in 0..<side {
                let index = min(cells - 1, y / cell) * cells + min(cells - 1, x / cell)
                sums[index] += luma(pixels, x: x, y: y, side: side)
                counts[index] += 1
            }
        }
        return (0..<(cells * cells)).map { index in
            counts[index] == 0 ? 0 : sums[index] / Float(counts[index])
        }
    }

    private static func luma(_ pixels: [UInt8], x: Int, y: Int, side: Int) -> Float {
        let index = (y * side + x) * 4
        let r = Float(pixels[index])
        let g = Float(pixels[index + 1])
        let b = Float(pixels[index + 2])
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255
    }
}

struct AimScan: Equatable {
    var verdict: AimVerdict
    var signature: [Float]
}

/// Vision face / person / bedroom labels, then the same allow-through gate as Android.
enum AimScanner {
    static func evaluate(_ image: CGImage) -> AimScan {
        let stats = AimImageStats.center(of: image)
        let signature = AimImageStats.signature(of: image)
        var faceWidth: Float = 0
        var personArea: Float = 0
        var scene = false
        let faces = VNDetectFaceRectanglesRequest()
        let humans = VNDetectHumanRectanglesRequest()
        let labels = VNClassifyImageRequest()
        let handler = VNImageRequestHandler(cgImage: image, options: [:])
        try? handler.perform([faces, humans, labels])
        if let found = faces.results as? [VNFaceObservation] {
            faceWidth = found.map { Float($0.boundingBox.width) }.max() ?? 0
        }
        if let found = humans.results as? [VNHumanObservation] {
            personArea = found.map { Float($0.boundingBox.width * $0.boundingBox.height) }.max() ?? 0
        }
        if let found = labels.results as? [VNClassificationObservation] {
            scene = found.contains { $0.confidence >= 0.2 && isBedside($0.identifier) }
        }
        let verdict = AimGate.evaluate(
            faceWidthFraction: faceWidth,
            personAreaFraction: personArea,
            sceneMatchesBedside: scene,
            centerMean: stats.mean,
            centerVariance: stats.variance,
            centerEdge: stats.edge
        )
        return AimScan(verdict: verdict, signature: signature)
    }

    private static func isBedside(_ identifier: String) -> Bool {
        let id = identifier.lowercased()
        if id.contains("pillow") || id.contains("blanket") || id.contains("bedroom") { return true }
        let parts = id.split { !$0.isLetter }.map(String.init)
        let tokens: Set<String> = ["bed", "person", "face", "human", "head"]
        return parts.contains { tokens.contains($0) }
    }
}

enum CloudCircuitAction: Equatable {
    case call
    case local
    case probe
}

struct CloudCircuitState: Equatable {
    var consecutiveFailures: Int = 0
    var openUntil: Date = .distantPast
    var lastProbe: Date = .distantPast
}

enum CloudCircuitPolicy {
    static let openAfter = 2
    static let localFirst: TimeInterval = 5 * 60
    static let probeInterval: TimeInterval = 60

    static func action(_ state: CloudCircuitState, now: Date) -> CloudCircuitAction {
        if now >= state.openUntil { return .call }
        if now.timeIntervalSince(state.lastProbe) >= probeInterval { return .probe }
        return .local
    }

    static func onSuccess(_ state: CloudCircuitState) -> CloudCircuitState {
        CloudCircuitState()
    }

    static func onFailure(_ state: CloudCircuitState, now: Date) -> CloudCircuitState {
        let fails = state.consecutiveFailures + 1
        if fails >= openAfter {
            return CloudCircuitState(
                consecutiveFailures: fails,
                openUntil: now.addingTimeInterval(localFirst),
                lastProbe: now
            )
        }
        var copy = state
        copy.consecutiveFailures = fails
        return copy
    }

    static func noteProbe(_ state: CloudCircuitState, now: Date) -> CloudCircuitState {
        var copy = state
        copy.lastProbe = now
        return copy
    }
}

enum GuardFailureReason: Equatable {
    case judgment
    case camera
    case stall
}

struct GuardHealthState: Equatable {
    var failureTimes: [Date] = []
    var tripped: Bool = false
    var reason: GuardFailureReason?
}

struct GuardHealthStep: Equatable {
    var state: GuardHealthState
    var justTripped: Bool
    var reason: GuardFailureReason?
}

enum GuardHealthPolicy {
    static let window: TimeInterval = 30
    static let failuresToTrip = 3
    static let stallIntervals = 3

    static func onJudgmentFailure(_ state: GuardHealthState, now: Date) -> GuardHealthStep {
        let recent = (state.failureTimes + [now]).filter { now.timeIntervalSince($0) <= window }
        if recent.count >= failuresToTrip {
            let just = !state.tripped
            return GuardHealthStep(
                state: GuardHealthState(failureTimes: recent, tripped: true, reason: .judgment),
                justTripped: just,
                reason: just ? .judgment : nil
            )
        }
        var copy = state
        copy.failureTimes = recent
        return GuardHealthStep(state: copy, justTripped: false, reason: nil)
    }

    static func onCameraBindFailure(_ state: GuardHealthState) -> GuardHealthStep {
        var copy = state
        copy.tripped = true
        copy.reason = .camera
        return GuardHealthStep(state: copy, justTripped: true, reason: .camera)
    }

    static func stalled(since last: Date?, now: Date, intervalSeconds: Int) -> Bool {
        guard let last, intervalSeconds > 0 else { return false }
        return now.timeIntervalSince(last) > Double(stallIntervals * intervalSeconds)
    }

    static func onJudgment(_ state: GuardHealthState) -> GuardHealthState {
        GuardHealthState()
    }
}

struct SceneWatch: Equatable {
    var baseline: [Float] = []
    var streak: Int = 0
    var usedThisPause: Bool = false
}

enum SceneChangePolicy {
    static let confirmFrames = 3
    static let minMeanAbsDelta: Float = 0.12

    static func observe(_ state: SceneWatch, signature: [Float]) -> (SceneWatch, Bool) {
        if signature.isEmpty || state.usedThisPause { return (state, false) }
        if state.baseline.isEmpty || state.baseline.count != signature.count {
            return (SceneWatch(baseline: signature), false)
        }
        let delta = meanAbsDelta(state.baseline, signature)
        if delta < minMeanAbsDelta {
            var copy = state
            copy.streak = 0
            return (copy, false)
        }
        let streak = state.streak + 1
        if streak >= confirmFrames {
            var copy = state
            copy.streak = streak
            copy.usedThisPause = true
            return (copy, true)
        }
        var copy = state
        copy.streak = streak
        return (copy, false)
    }

    static func meanAbsDelta(_ baseline: [Float], _ signature: [Float]) -> Float {
        if baseline.isEmpty || baseline.count != signature.count { return 0 }
        var sum: Float = 0
        for index in baseline.indices {
            sum += abs(baseline[index] - signature[index])
        }
        return sum / Float(baseline.count)
    }
}

/// Lie-down window after Start. The 02:16 session rang on the first MASK_OFF
/// (~8s, debounce = 1) while the person was still sitting up, with zero MASK_ON.
enum SettleGate {
    static let duration: TimeInterval = 60
    /// Two aim-valid MASK_ON frames in a row. Recorded for the log.
    /// It does not open the alarm inside [duration].
    static let sustainedMaskOnFrames = 2

    static func secondsRemaining(elapsed: TimeInterval) -> Int {
        if elapsed >= duration { return 0 }
        let left = duration - elapsed
        return max(1, Int(ceil(left - 0.000_1)))
    }

    /// MASK_OFF and snore-only alarms stay off for the whole minute.
    /// After that, the alarm is armed even if the mask was never confirmed,
    /// so forgetting the mask still rings. `maskConfirmed` cannot shorten the minute:
    /// a false MASK_ON while arranging tubing must not re-open the alarm.
    static func maskOffAlarmAllowed(elapsed: TimeInterval, maskConfirmed: Bool) -> Bool {
        if elapsed < duration { return false }
        return elapsed >= duration || (maskConfirmed && elapsed >= duration)
    }
}

enum TorchLevel {
    /// Hardware floor. Devices that only switch on/off ignore this and stay full-on.
    static let minimum = 0.01
    /// Suggested overnight level. The slider can still go down to [minimum].
    static let suggested = 0.02

    static func clamp(_ value: Double) -> Double {
        min(Swift.max(value, minimum), 1)
    }

    static func clamp(_ value: Float) -> Float {
        Float(clamp(Double(value)))
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
