import Foundation
import SwiftUI

/// Wraps `UserDefaults` with `@AppStorage`-friendly keys.
/// Marked `@ObservableObject` via @Published-like wrappers so SwiftUI Bindings work.
final class SettingsStore: ObservableObject {
    @AppStorage("padly.cursorSensitivity") var cursorSensitivity: Double = 1.0
    @AppStorage("padly.naturalScroll") var naturalScroll: Bool = true
    @AppStorage("padly.tapToClick") var tapToClick: Bool = true
    @AppStorage("padly.hapticStrength") var hapticStrength: Double = 0.6
    @AppStorage("padly.preferredPort") var preferredPort: Int = 47821
    @AppStorage("padly.allowBluetooth") var allowBluetooth: Bool = true
    @AppStorage("padly.presenterEdgeExit") var presenterEdgeExit: Bool = true
}
