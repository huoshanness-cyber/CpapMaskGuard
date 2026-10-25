import UIKit

enum ImageEncode {
    /// Longest edge 640, JPEG quality 0.55. MiniMax is called with detail low, so this is enough.
    static func jpegForCloud(_ data: Data, maxEdge: CGFloat = 640, quality: CGFloat = 0.55) -> Data? {
        guard let image = UIImage(data: data) else { return nil }
        let width = image.size.width
        let height = image.size.height
        guard width > 1, height > 1 else { return nil }
        let scale = min(1, maxEdge / max(width, height))
        let size = CGSize(width: max(1, floor(width * scale)), height: max(1, floor(height * scale)))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        let rendered = renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
        return rendered.jpegData(compressionQuality: quality)
    }
}
