import Foundation
import SwiftUI

/// Persists settings in `UserDefaults` via `@Published` + `didSet`,
/// so SwiftUI Bindings work and views refresh on change.
final class SettingsStore: ObservableObject {
    private static let d = UserDefaults.standard

    @Published var cursorSensitivity: Double {
        didSet { Self.d.set(cursorSensitivity, forKey: "padly.cursorSensitivity") }
    }
    @Published var naturalScroll: Bool {
        didSet { Self.d.set(naturalScroll, forKey: "padly.naturalScroll") }
    }
    @Published var tapToClick: Bool {
        didSet { Self.d.set(tapToClick, forKey: "padly.tapToClick") }
    }
    @Published var hapticStrength: Double {
        didSet { Self.d.set(hapticStrength, forKey: "padly.hapticStrength") }
    }
    @Published var preferredPort: Int {
        didSet { Self.d.set(preferredPort, forKey: "padly.preferredPort") }
    }
    @Published var allowBluetooth: Bool {
        didSet { Self.d.set(allowBluetooth, forKey: "padly.allowBluetooth") }
    }
    @Published var presenterEdgeExit: Bool {
        didSet { Self.d.set(presenterEdgeExit, forKey: "padly.presenterEdgeExit") }
    }

    init() {
        let d = Self.d
        cursorSensitivity = d.object(forKey: "padly.cursorSensitivity") as? Double ?? 1.0
        naturalScroll = d.object(forKey: "padly.naturalScroll") as? Bool ?? true
        tapToClick = d.object(forKey: "padly.tapToClick") as? Bool ?? true
        hapticStrength = d.object(forKey: "padly.hapticStrength") as? Double ?? 0.6
        preferredPort = d.object(forKey: "padly.preferredPort") as? Int ?? 47821
        allowBluetooth = d.object(forKey: "padly.allowBluetooth") as? Bool ?? true
        presenterEdgeExit = d.object(forKey: "padly.presenterEdgeExit") as? Bool ?? true
    }
}
