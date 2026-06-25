import AppKit
import SwiftUI

final class AppState: ObservableObject {
    @Published var pairedDevices: [PairedDevice] = []
    @Published var connectedDeviceIds: Set<String> = []
    @Published var currentPin: String? = nil
    @Published var qrPayload: String? = nil
    @Published var lastError: String? = nil

    let keyStore = KeyStore()
    let settings = SettingsStore()
    lazy var pairing: Pairing = Pairing(keyStore: keyStore)
    lazy var server: WSListener = WSListener(state: self)
    lazy var advertiser: MDNSAdvertiser = MDNSAdvertiser(state: self)

    func bootstrap() {
        pairedDevices = keyStore.allPairedDevices()
        server.start(port: settings.preferredPort)
        advertiser.advertise(port: settings.preferredPort, serverId: keyStore.serverId)
        Logger.shared.info("Padly started on port \(settings.preferredPort)")
    }
}

struct PadlyApp: App {
    @StateObject private var state = AppState()

    init() {
        // Configure as menubar-only (LSUIElement) at launch.
        NSApplication.shared.setActivationPolicy(.accessory)
    }

    var body: some Scene {
        MenuBarExtra("Padly", systemImage: "rectangle.and.hand.point.up.left.filled") {
            MenubarMenu()
                .environmentObject(state)
                .onAppear { state.bootstrap() }
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
