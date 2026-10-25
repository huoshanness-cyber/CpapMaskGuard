import AudioToolbox
import AVFoundation
import Foundation

/// Local snore tap plus a looping siren.
/// The microphone path never uploads audio. If it fails, the camera loop keeps going.
/// The siren uses playback / play-and-record, so the ring-silent switch does not mute it.
/// Media volume still applies; the app does not override the hardware volume.
final class NightAudio: @unchecked Sendable {
    private let queue = DispatchQueue(label: "app.cpapmaskguard.snore")
    private let engine = AVAudioEngine()
    private let tracker = SnoreTracker()
    private var stored = SnoreSnapshot.off
    private var tapInstalled = false
    private var snoreWanted = false
    private var window: [Int16] = []
    private var resamplePhase = 0.0
    private var pauseUntilMs = 0
    private var windowsSeen = 0
    private var player: AVAudioPlayer?
    private var vibrateTimer: Timer?
    private var alarmPlaying = false

    func snapshot() -> SnoreSnapshot {
        queue.sync { stored }
    }

    func startSnore() async {
        await withCheckedContinuation { continuation in
            queue.async {
                self.startSnoreOnQueue()
                continuation.resume()
            }
        }
    }

    func stopSnore() async {
        await withCheckedContinuation { continuation in
            queue.async {
                self.stopSnoreOnQueue()
                continuation.resume()
            }
        }
    }

    func prepareSessionForAlarm() async {
        await withCheckedContinuation { continuation in
            queue.async {
                self.activateSession(preferRecord: self.snoreWanted)
                continuation.resume()
            }
        }
    }

    func playAlarmOnMain() -> Bool {
        dispatchPrecondition(condition: .onQueue(.main))
        activateSessionOnCaller(preferRecord: false)
        alarmPlaying = true
        beginVibrate()
        do {
            let player = try AVAudioPlayer(data: Self.sirenWAV())
            player.numberOfLoops = -1
            player.volume = 1
            player.prepareToPlay()
            self.player = player
            return player.play()
        } catch {
            return false
        }
    }

    func stopAlarm() {
        if Thread.isMainThread {
            stopAlarmOnMain()
        } else {
            DispatchQueue.main.async { self.stopAlarmOnMain() }
        }
        queue.async {
            guard self.snoreWanted else { return }
            if !self.tapInstalled {
                self.startSnoreOnQueue()
            } else if !self.engine.isRunning {
                try? self.engine.start()
            }
        }
    }

    func stopAll() async {
        stopAlarm()
        await stopSnore()
        await withCheckedContinuation { continuation in
            queue.async {
                try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
                continuation.resume()
            }
        }
    }

    func outputVolume() -> Float {
        AVAudioSession.sharedInstance().outputVolume
    }

    private func startSnoreOnQueue() {
        guard AVCaptureDevice.authorizationStatus(for: .audio) == .authorized else {
            stored = SnoreSnapshot(state: .denied, confirmedForMs: 0, positives: 0, windows: 0, rms: 0)
            return
        }
        guard AVAudioSession.sharedInstance().isInputAvailable else {
            stored = SnoreSnapshot(state: .unavailable, confirmedForMs: 0, positives: 0, windows: 0, rms: 0)
            return
        }
        if tapInstalled && engine.isRunning {
            snoreWanted = true
            publishTracker(state: tracker.confirmed ? .confirmed : .listening)
            return
        }
        if tapInstalled {
            engine.inputNode.removeTap(onBus: 0)
            tapInstalled = false
        }
        activateSession(preferRecord: true)
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else {
            stored = SnoreSnapshot(state: .unavailable, confirmedForMs: 0, positives: 0, windows: 0, rms: 0)
            return
        }
        input.installTap(onBus: 0, bufferSize: 4096, format: format) { [weak self] buffer, _ in
            self?.enqueue(buffer)
        }
        tapInstalled = true
        do {
            engine.prepare()
            try engine.start()
        } catch {
            input.removeTap(onBus: 0)
            tapInstalled = false
            stored = SnoreSnapshot(state: .unavailable, confirmedForMs: 0, positives: 0, windows: 0, rms: 0)
            return
        }
        snoreWanted = true
        tracker.reset()
        window.removeAll(keepingCapacity: true)
        stored = SnoreSnapshot(state: .listening, confirmedForMs: 0, positives: 0, windows: 0, rms: 0)
    }

    private func stopSnoreOnQueue() {
        snoreWanted = false
        if tapInstalled {
            engine.inputNode.removeTap(onBus: 0)
            tapInstalled = false
        }
        if engine.isRunning {
            engine.stop()
        }
        tracker.reset()
        window.removeAll(keepingCapacity: true)
        stored = .off
    }

    private func enqueue(_ buffer: AVAudioPCMBuffer) {
        guard let channels = buffer.floatChannelData else { return }
        let frames = Int(buffer.frameLength)
        guard frames > 0 else { return }
        let sampleRate = buffer.format.sampleRate
        guard sampleRate > 0 else { return }
        let pointer = channels[0]
        var copy = [Float]()
        copy.reserveCapacity(frames)
        for index in 0..<frames {
            copy.append(pointer[index])
        }
        queue.async {
            self.consume(copy, sampleRate: sampleRate)
        }
    }

    private func consume(_ samples: [Float], sampleRate: Double) {
        guard snoreWanted else { return }
        let now = MonitorClock.uptimeMilliseconds()
        if now < pauseUntilMs { return }
        let ratio = sampleRate / 16_000.0
        guard ratio > 0 else { return }
        for sample in samples {
            resamplePhase += 1
            if resamplePhase < ratio { continue }
            resamplePhase -= ratio
            let clipped = min(1, max(-1, sample))
            window.append(Int16(clipped * 32767))
            if window.count >= 8_000 {
                let features = SnoreHeuristics.analyze(window)
                tracker.onWindow(snoreLike: features.snoreLike, elapsedMs: MonitorClock.uptimeMilliseconds())
                windowsSeen += 1
                publishTracker(state: tracker.confirmed ? .confirmed : .listening, rms: features.rms)
                window.removeAll(keepingCapacity: true)
                pauseUntilMs = MonitorClock.uptimeMilliseconds() + 200
                break
            }
        }
    }

    private func publishTracker(state: SnoreListenState, rms: Float? = nil) {
        stored = SnoreSnapshot(
            state: state,
            confirmedForMs: tracker.confirmedForMs,
            positives: tracker.positives,
            windows: max(tracker.windowCount, windowsSeen > 0 ? tracker.windowCount : 0),
            rms: rms ?? stored.rms
        )
    }

    private func activateSession(preferRecord: Bool) {
        let session = AVAudioSession.sharedInstance()
        if preferRecord {
            do {
                try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
                try session.setActive(true)
                try? session.overrideOutputAudioPort(.speaker)
                return
            } catch {
                // Fall through to playback so the siren can still sound.
            }
        }
        try? session.setCategory(.playback, mode: .default, options: [])
        try? session.setActive(true)
    }

    private func activateSessionOnCaller(preferRecord: Bool) {
        activateSession(preferRecord: preferRecord)
    }

    private func beginVibrate() {
        vibrateTimer?.invalidate()
        AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
        vibrateTimer = Timer.scheduledTimer(withTimeInterval: 0.8, repeats: true) { [weak self] _ in
            guard self?.alarmPlaying == true else { return }
            AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
        }
    }

    private func stopAlarmOnMain() {
        alarmPlaying = false
        vibrateTimer?.invalidate()
        vibrateTimer = nil
        player?.stop()
        player = nil
    }

    private static func sirenWAV() -> Data {
        let sampleRate = 44_100
        let count = sampleRate
        var samples = [Int16](repeating: 0, count: count)
        var phase = 0.0
        let quarter = sampleRate / 4
        for index in 0..<count {
            let freq = (index / quarter) % 2 == 0 ? 880.0 : 1320.0
            phase += 2.0 * Double.pi * freq / Double(sampleRate)
            let shaped = sin(phase) * 0.92
            samples[index] = Int16(min(1, max(-1, shaped)) * 32767)
        }
        return wav(samples: samples, sampleRate: sampleRate)
    }

    private static func wav(samples: [Int16], sampleRate: Int) -> Data {
        let dataSize = samples.count * MemoryLayout<Int16>.size
        var data = Data()
        func appendASCII(_ value: String) { data.append(contentsOf: value.utf8) }
        func append32(_ value: UInt32) {
            let little = value.littleEndian
            data.append(UInt8(little & 0xff))
            data.append(UInt8((little >> 8) & 0xff))
            data.append(UInt8((little >> 16) & 0xff))
            data.append(UInt8((little >> 24) & 0xff))
        }
        func append16(_ value: UInt16) {
            let little = value.littleEndian
            data.append(UInt8(little & 0xff))
            data.append(UInt8((little >> 8) & 0xff))
        }
        appendASCII("RIFF")
        append32(UInt32(36 + dataSize))
        appendASCII("WAVE")
        appendASCII("fmt ")
        append32(16)
        append16(1)
        append16(1)
        append32(UInt32(sampleRate))
        append32(UInt32(sampleRate * 2))
        append16(2)
        append16(16)
        appendASCII("data")
        append32(UInt32(dataSize))
        for sample in samples {
            let little = UInt16(bitPattern: sample).littleEndian
            data.append(UInt8(little & 0xff))
            data.append(UInt8((little >> 8) & 0xff))
        }
        return data
    }
}
