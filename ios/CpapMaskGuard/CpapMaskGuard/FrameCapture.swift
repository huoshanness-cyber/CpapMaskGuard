import AVFoundation
import CoreImage
import Foundation
import ImageIO
import UIKit

enum CaptureError: Error {
    case noCamera
    case notRunning
    case timeout
    case empty
    case stopped
    case failed(String)
}

/// Foreground camera for monitoring and baseline JPEGs, and for the rear torch.
///
/// `takeJPEG()` encodes one frame from `AVCaptureVideoDataOutput`. It does not
/// call `AVCapturePhotoOutput.capturePhoto`, which plays a shutter sound on
/// China-region iPhones that the mute switch cannot turn off. Callers still
/// receive JPEG `Data` for the thumbnail, the aim gate, and cloud classify.
/// The session stops when guarding stops or the app leaves the foreground.
/// This is not a background camera.
final class FrameCapture: NSObject, @unchecked Sendable {
    private let queue = DispatchQueue(label: "app.cpapmaskguard.capture")
    private let videoQueue = DispatchQueue(label: "app.cpapmaskguard.capture.video")
    private let session = AVCaptureSession()
    private let video = AVCaptureVideoDataOutput()
    private let sink = VideoFrameSink()
    private let ciContext = CIContext(options: [.cacheIntermediates: false])
    private let frameTimeout: TimeInterval = 8
    private static let jpegSOI: [UInt8] = [0xFF, 0xD8]
    /// Let auto-exposure move before the first JPEG after the sensor starts.
    private let warmUp: TimeInterval = 0.25

    /// Live finder for aiming at the pillow. Same session as the stills.
    let previewLayer = AVCaptureVideoPreviewLayer()

    private var device: AVCaptureDevice?
    private var position: AVCaptureDevice.Position?
    private var wantTorch = false
    private var torchLevel: Float = 0

    /// Touched only on `videoQueue`.
    private var acceptingFrames = false
    private var acceptAfter = Date.distantFuture
    private var buffersArePortrait = false
    private var activePosition: AVCaptureDevice.Position = .back
    private var waiters: [FrameWaiter] = []

    override init() {
        super.init()
        previewLayer.videoGravity = .resizeAspectFill
        previewLayer.session = session
        sink.owner = self
    }

    deinit {
        sink.owner = nil
        let output = video
        let runningSession = session
        let schedule = queue
        output.setSampleBufferDelegate(nil, queue: nil)
        schedule.async {
            if runningSession.isRunning {
                runningSession.stopRunning()
            }
        }
    }

    func start(position: AVCaptureDevice.Position, torchEnabled: Bool, level: Float) async throws -> TorchReport {
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                do {
                    let report = try self.configureOnQueue(position: position, torchEnabled: torchEnabled, level: level)
                    continuation.resume(returning: report)
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    func setTorch(enabled: Bool, level: Float) async -> TorchReport {
        await withCheckedContinuation { continuation in
            queue.async {
                continuation.resume(returning: self.setTorchOnQueue(enabled: enabled, level: level))
            }
        }
    }

    /// Next video frame as JPEG. Waits out the post-start warm-up, then the next buffer.
    func takeJPEG() async throws -> Data {
        let once = ResumeOnce()
        return try await withCheckedThrowingContinuation { continuation in
            queue.async {
                guard self.session.isRunning else {
                    once.resume(continuation, .failure(CaptureError.notRunning))
                    return
                }
                let waiter = FrameWaiter(once: once, continuation: continuation)
                self.videoQueue.async {
                    guard self.acceptingFrames else {
                        once.resume(continuation, .failure(CaptureError.notRunning))
                        return
                    }
                    self.cancelWaiters(.failure(CaptureError.stopped))
                    self.waiters.append(waiter)
                }
                self.queue.asyncAfter(deadline: .now() + self.frameTimeout) {
                    self.videoQueue.async {
                        self.timeout(waiter)
                    }
                }
            }
        }
    }

    func stop() async {
        await withCheckedContinuation { continuation in
            queue.async {
                self.video.setSampleBufferDelegate(nil, queue: nil)
                self.quiesceVideoQueue()
                self.turnTorchOffOnQueue()
                if self.session.isRunning {
                    self.session.stopRunning()
                }
                self.position = nil
                self.wantTorch = false
                continuation.resume()
            }
        }
    }

    fileprivate func ingest(_ sampleBuffer: CMSampleBuffer) {
        guard acceptingFrames, Date() >= acceptAfter else { return }
        guard !waiters.isEmpty else { return }
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        guard let jpeg = jpegData(pixelBuffer: pixelBuffer), jpeg.starts(with: Self.jpegSOI) else { return }
        let pending = waiters
        waiters.removeAll()
        for waiter in pending {
            waiter.once.resume(waiter.continuation, .success(jpeg))
        }
    }

    private func configureOnQueue(
        position: AVCaptureDevice.Position,
        torchEnabled: Bool,
        level: Float
    ) throws -> TorchReport {
        if self.position == position, session.isRunning {
            return setTorchOnQueue(enabled: torchEnabled, level: level)
        }
        video.setSampleBufferDelegate(nil, queue: nil)
        quiesceVideoQueue()
        if session.isRunning {
            session.stopRunning()
        }
        session.beginConfiguration()
        for input in session.inputs {
            session.removeInput(input)
        }
        if session.canSetSessionPreset(.hd1280x720) {
            session.sessionPreset = .hd1280x720
        } else if session.canSetSessionPreset(.vga640x480) {
            session.sessionPreset = .vga640x480
        } else if session.canSetSessionPreset(.high) {
            session.sessionPreset = .high
        }
        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position) else {
            session.commitConfiguration()
            throw CaptureError.noCamera
        }
        let input: AVCaptureDeviceInput
        do {
            input = try AVCaptureDeviceInput(device: camera)
        } catch {
            session.commitConfiguration()
            throw CaptureError.failed(error.localizedDescription)
        }
        guard session.canAddInput(input) else {
            session.commitConfiguration()
            throw CaptureError.noCamera
        }
        session.addInput(input)
        do {
            try ensureVideoOutput()
        } catch {
            session.commitConfiguration()
            throw error
        }
        session.commitConfiguration()
        // Rotation has to be set before startRunning. Doing it in its own
        // configuration pass makes sure the connection already exists.
        session.beginConfiguration()
        preferBGRA()
        let portrait = orientVideoConnection(position: position)
        session.commitConfiguration()
        device = camera
        self.position = position
        tune(camera)
        video.setSampleBufferDelegate(sink, queue: videoQueue)
        session.startRunning()
        guard session.isRunning else {
            video.setSampleBufferDelegate(nil, queue: nil)
            throw CaptureError.notRunning
        }
        videoQueue.sync {
            self.buffersArePortrait = portrait
            self.activePosition = position
            self.acceptingFrames = true
            self.acceptAfter = Date().addingTimeInterval(self.warmUp)
        }
        orientPreviewOnQueue(position: position)
        return setTorchOnQueue(enabled: torchEnabled && position == .back, level: level)
    }

    private func ensureVideoOutput() throws {
        video.alwaysDiscardsLateVideoFrames = true
        if !session.outputs.contains(where: { $0 === video }) {
            if !session.canAddOutput(video), session.canSetSessionPreset(.vga640x480) {
                session.sessionPreset = .vga640x480
            }
            if !session.canAddOutput(video), session.canSetSessionPreset(.high) {
                session.sessionPreset = .high
            }
            guard session.canAddOutput(video) else { throw CaptureError.noCamera }
            session.addOutput(video)
        }
    }

    /// 32BGRA is the format CIImage turns into a JPEG without a YUV conversion surprise.
    /// Native 420f still encodes if this format is not in the list.
    private func preferBGRA() {
        let bgra = kCVPixelFormatType_32BGRA
        guard video.availableVideoPixelFormatTypes.contains(bgra) else { return }
        video.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: NSNumber(value: bgra),
        ]
    }

    /// Portrait pixels, same angle the old photo connection used (`.portrait` == 90°).
    /// Video data output rotates the buffers. Photo output only tagged EXIF.
    private func orientVideoConnection(position: AVCaptureDevice.Position) -> Bool {
        guard let connection = video.connection(with: .video) else { return false }
        let angle: CGFloat = 90
        var portrait = false
        if connection.isVideoRotationAngleSupported(angle) {
            connection.videoRotationAngle = angle
            portrait = true
        }
        if position == .front, connection.isVideoMirroringSupported {
            connection.automaticallyAdjustsVideoMirroring = false
            connection.isVideoMirrored = false
        }
        return portrait
    }

    private func jpegData(pixelBuffer: CVPixelBuffer) -> Data? {
        var image = CIImage(cvPixelBuffer: pixelBuffer, options: [.applyOrientationProperty: false])
        if !buffersArePortrait {
            // Sensor-native fallback when the connection cannot rotate.
            // Back camera, phone upright: 90° clockwise. Front, unmirrored: the other way.
            let orientation: CGImagePropertyOrientation = activePosition == .front ? .left : .right
            image = image.oriented(orientation)
        }
        let extent = image.extent
        guard !extent.isNull, !extent.isInfinite, extent.width > 1, extent.height > 1 else { return nil }
        guard let cgImage = ciContext.createCGImage(image, from: extent) else { return nil }
        return UIImage(cgImage: cgImage).jpegData(compressionQuality: 0.85)
    }

    private func tune(_ camera: AVCaptureDevice) {
        do {
            try camera.lockForConfiguration()
            if camera.isFocusModeSupported(.continuousAutoFocus) {
                camera.focusMode = .continuousAutoFocus
            }
            if camera.isExposureModeSupported(.continuousAutoExposure) {
                camera.exposureMode = .continuousAutoExposure
            }
            if camera.isLowLightBoostSupported {
                camera.automaticallyEnablesLowLightBoostWhenAvailable = true
            }
            camera.unlockForConfiguration()
        } catch {
            // Auto exposure is enough when the lock fails.
        }
    }

    private func orientPreviewOnQueue(position: AVCaptureDevice.Position) {
        guard let connection = previewLayer.connection else { return }
        let angle: CGFloat = 90
        if connection.isVideoRotationAngleSupported(angle) {
            connection.videoRotationAngle = angle
        }
        if position == .front, connection.isVideoMirroringSupported {
            connection.automaticallyAdjustsVideoMirroring = false
            connection.isVideoMirrored = false
        }
    }

    private func setTorchOnQueue(enabled: Bool, level: Float) -> TorchReport {
        wantTorch = enabled
        torchLevel = TorchLevel.clamp(level)
        guard let device else {
            return TorchReport(isOn: false, level: torchLevel, supportsLevel: false, status: "手电筒不可用", error: "相机还没打开")
        }
        guard session.isRunning else {
            return TorchReport(isOn: false, level: torchLevel, supportsLevel: false, status: "相机没跑起来", error: "相机会话没有处于运行")
        }
        guard device.position == .back else {
            turnTorchOffOnQueue()
            return TorchReport(isOn: false, level: 0, supportsLevel: false, status: "已关闭", error: nil)
        }
        guard device.hasTorch else {
            return TorchReport(
                isOn: false,
                level: torchLevel,
                supportsLevel: false,
                status: "这台机器没有手电筒",
                error: "模拟器或无闪光灯机型无法开灯。请用 iPhone 真机。"
            )
        }
        if !enabled {
            turnTorchOffOnQueue()
            return TorchReport(isOn: false, level: torchLevel, supportsLevel: true, status: "已关闭", error: nil)
        }
        let maxLevel = AVCaptureDevice.maxAvailableTorchLevel
        let clamped = min(max(torchLevel, 0.01), min(maxLevel, 1))
        guard device.isTorchModeSupported(.on) else {
            return TorchReport(isOn: false, level: clamped, supportsLevel: false, status: "不能打开手电筒", error: "isTorchModeSupported(.on) 为否")
        }
        do {
            try device.lockForConfiguration()
            do {
                try device.setTorchModeOn(level: clamped)
                device.unlockForConfiguration()
                torchLevel = clamped
                return TorchReport(
                    isOn: true,
                    level: clamped,
                    supportsLevel: true,
                    status: String(format: "手电筒 %.0f%%", clamped * 100),
                    error: nil
                )
            } catch {
                device.torchMode = .on
                device.unlockForConfiguration()
                return TorchReport(
                    isOn: true,
                    level: 1,
                    supportsLevel: false,
                    status: "这台手机只能开关，已开灯",
                    error: nil
                )
            }
        } catch {
            return TorchReport(isOn: false, level: torchLevel, supportsLevel: false, status: "设置失败", error: error.localizedDescription)
        }
    }

    private func turnTorchOffOnQueue() {
        guard let device, device.hasTorch else { return }
        do {
            try device.lockForConfiguration()
            device.torchMode = .off
            device.unlockForConfiguration()
        } catch {
            // Stopping the session also drops the torch.
        }
    }

    /// Caller is on `queue`. Waits until any in-flight frame callback finishes.
    private func quiesceVideoQueue() {
        videoQueue.sync {
            acceptingFrames = false
            acceptAfter = Date.distantFuture
            cancelWaiters(.failure(CaptureError.stopped))
        }
    }

    private func cancelWaiters(_ result: Result<Data, Error>) {
        let pending = waiters
        waiters.removeAll()
        for waiter in pending {
            waiter.once.resume(waiter.continuation, result)
        }
    }

    private func timeout(_ waiter: FrameWaiter) {
        guard let index = waiters.firstIndex(where: { $0 === waiter }) else { return }
        waiters.remove(at: index)
        waiter.once.resume(waiter.continuation, .failure(CaptureError.timeout))
    }
}

/// Breaks the retain cycle: the video output keeps its sample-buffer delegate.
private final class VideoFrameSink: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    weak var owner: FrameCapture?

    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        owner?.ingest(sampleBuffer)
    }
}

private final class FrameWaiter {
    let once: ResumeOnce
    let continuation: CheckedContinuation<Data, Error>

    init(once: ResumeOnce, continuation: CheckedContinuation<Data, Error>) {
        self.once = once
        self.continuation = continuation
    }
}

private final class ResumeOnce: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false

    func resume(_ continuation: CheckedContinuation<Data, Error>, _ result: Result<Data, Error>) {
        lock.lock()
        let first = !done
        done = true
        lock.unlock()
        if first {
            continuation.resume(with: result)
        }
    }
}
