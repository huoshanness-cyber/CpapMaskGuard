import SwiftUI

enum GuardTheme {
    static let bg = Color(red: 0x12 / 255, green: 0x16 / 255, blue: 0x1C / 255)
    static let surface = Color(red: 0x1C / 255, green: 0x23 / 255, blue: 0x2D / 255)
    static let text = Color(red: 0xF2 / 255, green: 0xF4 / 255, blue: 0xF7 / 255)
    static let muted = Color(red: 0xA8 / 255, green: 0xB0 / 255, blue: 0xBD / 255)
    static let accent = Color(red: 0x9C / 255, green: 0xC4 / 255, blue: 0xFF / 255)
    static let onAccent = Color(red: 0x0B / 255, green: 0x12 / 255, blue: 0x20 / 255)
    static let amber = Color(red: 0xFF / 255, green: 0xC5 / 255, blue: 0x6E / 255)
    static let line = Color(red: 0x33 / 255, green: 0x40 / 255, blue: 0x4E / 255)
    static let warningBg = Color(red: 0x2A / 255, green: 0x21 / 255, blue: 0x12 / 255)
    static let ink = Color(red: 0x12 / 255, green: 0x16 / 255, blue: 0x1C / 255)
}

enum FillLightMode: String, CaseIterable, Identifiable {
    case frontScreen = "FRONT_SCREEN"
    case rearAmbient = "REAR_AMBIENT"

    var id: String { rawValue }
}

enum AppLanguage: String, CaseIterable, Identifiable {
    case zh
    case en

    var id: String { rawValue }
}

enum GuardPhase {
    case idle
    case guarding
    case stopped
    case leftForeground
}
