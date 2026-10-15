import AppKit
import ApplicationServices
import SwiftUI

final class AppState: ObservableObject {
    @Published var pairedDevices: [PairedDevice] = []
    @Published var connectedDeviceIds: Set<String> = []
    @Published var currentPin: String? = nil
    @Published var pinExpiresAt: Date? = nil
    @Published var qrPayload: String? = nil
    @Published var lastError: String? = nil
    @Published var accessibilityGranted: Bool = AXIsProcessTrusted()
    @Published var listenAddress: String = ""
    @Published var browserURL: String? = nil

    let keyStore = KeyStore()
    let settings = SettingsStore()
    lazy var pairing: Pairing = Pairing(keyStore: keyStore)
    lazy var server: WSListener = WSListener(state: self)
    lazy var browserServer: BrowserHTTPServer = BrowserHTTPServer { [weak self] message in
        Logger.shared.error(message)
        DispatchQueue.main.async {
            self?.browserURL = nil
            self?.lastError = message
        }
    }

    private var bootstrapped = false
    private var accessibilityTimer: Timer?

    func bootstrap() {
        guard !bootstrapped else { return }
        bootstrapped = true
        pairedDevices = keyStore.allPairedDevices()
        keyStore.evictStale()
        server.start(port: settings.preferredPort, serverId: keyStore.serverId)
        browserServer.start(webSocketPort: settings.preferredPort, serverId: keyStore.serverId) { [weak self] port in
            DispatchQueue.main.async {
                self?.browserURL = "http://\(NetworkUtil.bestLocalIP()):\(port)/"
            }
        }
        listenAddress = "\(NetworkUtil.bestLocalIP()):\(settings.preferredPort)"
        Logger.shared.info("Padly started on port \(settings.preferredPort)")
    }

    /// Posting mouse/keyboard events needs the Accessibility permission. Shows
    /// the system prompt once, then polls so the menu updates the moment the
    /// user flips the switch — no relaunch needed.
    func requestAccessibility(prompt: Bool) {
        let key = kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String
        accessibilityGranted = AXIsProcessTrustedWithOptions([key: prompt] as CFDictionary)
        guard !accessibilityGranted, accessibilityTimer == nil else { return }
        accessibilityTimer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] timer in
            guard let self else { timer.invalidate(); return }
            if AXIsProcessTrusted() {
                self.accessibilityGranted = true
                timer.invalidate()
                self.accessibilityTimer = nil
            }
        }
    }

    func openAccessibilitySettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility") {
            NSWorkspace.shared.open(url)
        }
    }
}

struct PadlyApp: App {
    @StateObject private var state: AppState

    init() {
        // Menubar-only app (LSUIElement).
        NSApplication.shared.setActivationPolicy(.accessory)
        let appState = AppState()
        _state = StateObject(wrappedValue: appState)
        // Listen from launch — not from the first menu click — so an already
        // paired phone reconnects on its own after the Mac app restarts.
        appState.bootstrap()
        DispatchQueue.main.async { appState.requestAccessibility(prompt: true) }
    }

    var body: some Scene {
        MenuBarExtra("Padly", systemImage: "rectangle.and.hand.point.up.left.filled") {
            MenubarMenu()
                .environmentObject(state)
        }
        .menuBarExtraStyle(.window)

        Window("Padly Settings", id: "settings") {
            SettingsWindow()
                .environmentObject(state)
                .frame(minWidth: 480, minHeight: 360)
        }
        .windowResizability(.contentSize)
    }
}
