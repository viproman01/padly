import SwiftUI

struct SettingsWindow: View {
    @EnvironmentObject var state: AppState

    var body: some View {
        TabView {
            generalTab
                .tabItem { Label("General", systemImage: "gearshape") }

            devicesTab
                .tabItem { Label("Devices", systemImage: "iphone") }

            pairingTab
                .tabItem { Label("Pair", systemImage: "qrcode") }
        }
        .padding(20)
    }

    private var generalTab: some View {
        Form {
            Section("Cursor") {
                Slider(value: $state.settings.cursorSensitivity, in: 0.3 ... 3.0) {
                    Text("Sensitivity")
                } minimumValueLabel: {
                    Text("0.3").font(.caption)
                } maximumValueLabel: {
                    Text("3.0").font(.caption)
                }
                Toggle("Natural scroll direction", isOn: $state.settings.naturalScroll)
            }
            Section("Tap to click") {
                Toggle("Enable tap to click", isOn: $state.settings.tapToClick)
                Slider(value: $state.settings.hapticStrength, in: 0 ... 1.0) {
                    Text("Haptic feedback")
                }
            }
            Section("Network") {
                Stepper("Port: \(state.settings.preferredPort)",
                        value: $state.settings.preferredPort,
                        in: 1024 ... 65535)
            }
        }
        .formStyle(.grouped)
    }

    private var devicesTab: some View {
        VStack(alignment: .leading) {
            if state.pairedDevices.isEmpty {
                Text("No paired devices yet. Use the Pair tab to add one.")
                    .foregroundStyle(.secondary)
                    .padding()
            } else {
                List {
                    ForEach(state.pairedDevices) { d in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(d.name).font(.headline)
                                Text(d.id).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button("Forget") {
                                state.keyStore.forget(deviceId: d.id)
                                state.pairedDevices = state.keyStore.allPairedDevices()
                            }
                        }
                    }
                }
            }
        }
    }

    private var pairingTab: some View {
        VStack(spacing: 16) {
            Text("Scan with Padly on your phone")
                .font(.headline)

            if let payload = state.qrPayload {
                QRImageView(payload: payload)
                    .frame(width: 240, height: 240)
            } else {
                Button("Generate Pairing QR") {
                    state.pairing.beginQrPairing(state: state)
                }
            }

            if let pin = state.currentPin {
                VStack(spacing: 4) {
                    Text("Enter PIN on phone")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text(pin)
                        .font(.system(size: 36, weight: .bold, design: .monospaced))
                        .tracking(6)
                }
            }
        }
        .padding()
    }
}
