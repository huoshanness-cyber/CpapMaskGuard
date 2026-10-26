import AVFoundation
import Foundation

struct TorchReport: Sendable {
    var isOn: Bool
    var level: Float
    var supportsLevel: Bool
    var status: String
    var error: String?
}

/// Rear-camera torch for the step-1 light check.
/// `setTorchModeOn(level:)` needs a running foreground capture session on device.
/// The session stops when the torch turns off or the app leaves the foreground.
/// This is not a background camera monitor.
final class RearTorch: @unchecked Sendable {
    private let queue = DispatchQueue(label: "app.cpapmaskguard.rear-torch")
    private var session: AVCaptureSession?
    private var device: AVCaptureDevice?
    private var configured = false

    func apply(enabled: Bool, level: Float) async -> TorchReport {
        await withCheckedContinuation { continuation in
            queue.async {
                let report = self.applyOnQueue(enabled: enabled, level: level)
                continuation.resume(returning: report)
            }
        }
    }

    func shutdown() async -> TorchReport {
        await withCheckedContinuation { continuation in
            queue.async {
                self.turnOffOnQueue()
                continuation.resume(returning: TorchReport(
                    isOn: false,
                    level: 0,
                    supportsLevel: self.device?.isTorchModeSupported(.on) == true,
                    status: "已关闭",
                    error: nil
                ))
            }
        }
    }

    private func applyOnQueue(enabled: Bool, level: Float) -> TorchReport {
        if let problem = configureOnQueue() {
            return TorchReport(isOn: false, level: level, supportsLevel: false, status: "手电筒不可用", error: problem)
        }
        guard let device else {
            return TorchReport(isOn: false, level: level, supportsLevel: false, status: "手电筒不可用", error: "没有后置摄像头")
        }
        guard device.hasTorch else {
            return TorchReport(isOn: false, level: level, supportsLevel: false, status: "这台机器没有手电筒", error: "模拟器或无闪光灯机型无法验强弱。请用 iPhone 真机。")
        }
        guard let session, session.isRunning else {
            return TorchReport(isOn: false, level: level, supportsLevel: false, status: "相机没跑起来", error: "后摄会话没有处于运行。请用 iPhone 真机再试。")
        }

        if !enabled {
            turnOffOnQueue()
            return TorchReport(isOn: false, level: level, supportsLevel: device.isTorchModeSupported(.on), status: "已关闭", error: nil)
        }

        let maxLevel = AVCaptureDevice.maxAvailableTorchLevel
        let clamped = min(max(level, 0.01), min(maxLevel, 1))
        guard device.isTorchModeSupported(.on) else {
            return TorchReport(isOn: false, level: clamped, supportsLevel: false, status: "不能打开手电筒", error: "isTorchModeSupported(.on) 为否")
        }
        do {
            try device.lockForConfiguration()
            do {
                try device.setTorchModeOn(level: clamped)
                device.unlockForConfiguration()
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
            return TorchReport(isOn: false, level: level, supportsLevel: false, status: "设置失败", error: error.localizedDescription)
        }
    }

    private func configureOnQueue() -> String? {
        if configured {
            if session?.isRunning != true {
                session?.startRunning()
            }
            return session?.isRunning == true ? nil : "相机会话没有跑起来（模拟器通常没有可用手电筒）。"
        }

        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else {
            return "没有后置摄像头。模拟器请改用 iPhone 真机验手电筒。"
        }
        let session = AVCaptureSession()
        session.beginConfiguration()
        if session.canSetSessionPreset(.photo) {
            session.sessionPreset = .photo
        } else if session.canSetSessionPreset(.high) {
            session.sessionPreset = .high
        }
        do {
            let input = try AVCaptureDeviceInput(device: camera)
            guard session.canAddInput(input) else {
                session.commitConfiguration()
                return "后摄不能加入会话"
            }
            session.addInput(input)
        } catch {
            session.commitConfiguration()
            return "后摄打不开：\(error.localizedDescription)"
        }
        let output = AVCapturePhotoOutput()
        if session.canAddOutput(output) {
            session.addOutput(output)
        }
        session.commitConfiguration()
        session.startRunning()
        self.session = session
        self.device = camera
        self.configured = true
        if !session.isRunning {
            return "相机会话没有跑起来。请用 iPhone 真机验手电筒。"
        }
        return nil
    }

    private func turnOffOnQueue() {
        if let device, device.hasTorch {
            do {
                try device.lockForConfiguration()
                device.torchMode = .off
                device.unlockForConfiguration()
            } catch {
                // Best effort. Stopping the session also drops the torch.
            }
        }
        turnOffSessionOnQueue()
    }

    private func turnOffSessionOnQueue() {
        if let session, session.isRunning {
            session.stopRunning()
        }
        configured = false
        session = nil
        // Keep `device` so a later status line can still mention torch support.
    }
}
