import Foundation
import SwiftUI

@main
struct PadlyMain {
    static func main() {
        if CommandLine.arguments.contains("--headless") {
            runHeadless()
        } else {
            PadlyApp.main()
        }
    }

    /// Headless mode for CI / smoke tests: starts the listener + mDNS, opens a
    /// pairing window, and prints the PIN to stdout. No menubar, no windows.
    private static func runHeadless() {
        let state = AppState()
        state.bootstrap()
        state.pairing.beginQrPairing(state: state)

        // currentPin is published on the main queue — read it there.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
            if let pin = state.currentPin {
                print("PADLY_HEADLESS_PIN=\(pin)")
            } else {
                print("PADLY_HEADLESS_PIN=unavailable")
            }
            print("PADLY_HEADLESS_PORT=\(state.settings.preferredPort)")
            print("PADLY_HEADLESS_BROWSER_URL=\(state.browserURL ?? "unavailable")")
            fflush(stdout)
        }

        RunLoop.main.run()
    }
}
