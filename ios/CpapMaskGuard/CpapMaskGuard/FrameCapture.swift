import AVFoundation
import Foundation

enum CaptureError: Error {
    case noCamera
    case notRunning
    case timeout
    case empty
    case stopped
    case failed(String)
}

/// One foreground `AVCaptureSession` for stills and, on the rear camera, the torch.
/// The session is stopped when guarding stops or the app leaves the foreground.
/// This is not a background camera.
final class FrameCapture: NSObject, AVCapturePhotoCaptureDelegate, @unchecked Sendable {
    private let queue = DispatchQueue(label: "app.cpapmaskguard.capture")
    private let session = AVCaptureSession()
    private let photo = AVCapturePhotoOutput()
    private var device: AVCaptureDevice?
    private var position: AVCaptureDevice.Position?
    private var wantTorch = false
    private var torchLevel: Float = 0
    private var shotTicket = 0
    private var pending: PendingShot?

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

    func takeJPEG() async throws -> Data {
        let once = ResumeOnce()
        return try await withCheckedThrowingContinuation { continuation in
            queue.async {
                self.finishPending(.failure(CaptureError.stopped))
                self.shotTicket += 1
                let ticket = self.shotTicket
                guard self.session.isRunning else {
                    once.resume(continuation, .failure(CaptureError.notRunning))
                    return
                }
                guard !self.photo.availablePhotoCodecTypes.isEmpty else {
                    once.resume(continuation, .failure(CaptureError.noCamera))
                    return
                }
                let codec: AVVideoCodecType = self.photo.availablePhotoCodecTypes.contains(.jpeg)
                    ? .jpeg
                    : self.photo.availablePhotoCodecTypes[0]
                let settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: codec])
                if self.photo.supportedFlashModes.contains(.off) {
                    settings.flashMode = .off
                }
                settings.photoQualityPrioritization = .speed
                self.pending = PendingShot(ticket: ticket, once: once, continuation: continuation)
                self.photo.capturePhoto(with: settings, delegate: self)
                self.queue.asyncAfter(deadline: .now() + 8) {
                    guard self.shotTicket == ticket else { return }
                    self.finishPending(.failure(CaptureError.timeout))
                }
            }
        }
    }

    func stop() async {
        await withCheckedContinuation { continuation in
            queue.async {
                self.finishPending(.failure(CaptureError.stopped))
                self.shotTicket += 1
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

    func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishProcessingPhoto photo: AVCapturePhoto,
        error: Error?
    ) {
        queue.async {
            if let error {
                self.finishPending(.failure(error))
                return
            }
            guard let data = photo.fileDataRepresentation(), !data.isEmpty else {
                self.finishPending(.failure(CaptureError.empty))
                return
            }
            self.finishPending(.success(data))
            if self.session.isRunning {
                _ = self.setTorchOnQueue(enabled: self.wantTorch, level: self.torchLevel)
            }
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
        } else if session.canSetSessionPreset(.photo) {
            session.sessionPreset = .photo
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
        if !session.outputs.contains(where: { $0 === photo }) {
            if !session.canAddOutput(photo), session.canSetSessionPreset(.photo) {
                session.sessionPreset = .photo
            }
            guard session.canAddOutput(photo) else {
                session.commitConfiguration()
                throw CaptureError.noCamera
            }
            session.addOutput(photo)
        }
        photo.maxPhotoQualityPrioritization = .speed
        session.commitConfiguration()
        if let connection = photo.connection(with: .video) {
            if connection.isVideoOrientationSupported {
                connection.videoOrientation = .portrait
            }
            if position == .front, connection.isVideoMirroringSupported {
                connection.automaticallyAdjustsVideoMirroring = false
                connection.isVideoMirrored = false
            }
        }
        device = camera
        self.position = position
        tune(camera)
        session.startRunning()
        guard session.isRunning else { throw CaptureError.notRunning }
        return setTorchOnQueue(enabled: torchEnabled && position == .back, level: level)
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

    private func setTorchOnQueue(enabled: Bool, level: Float) -> TorchReport {
        wantTorch = enabled
        torchLevel = min(max(level, 0.05), 1)
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
                    status: "手电筒只支持开/关",
                    error: "setTorchModeOn 失败，已退回全开：\(error.localizedDescription)"
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

    private func finishPending(_ result: Result<Data, Error>) {
        guard let pending else { return }
        self.pending = nil
        pending.once.resume(pending.continuation, result)
    }
}

private final class PendingShot {
    let ticket: Int
    let once: ResumeOnce
    let continuation: CheckedContinuation<Data, Error>

    init(ticket: Int, once: ResumeOnce, continuation: CheckedContinuation<Data, Error>) {
        self.ticket = ticket
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
