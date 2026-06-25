import SwiftUI

struct MenubarMenu: View {
    @EnvironmentObject var state: AppState
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Padly")
                    .font(.headline)
                Spacer()
                statusBadge
            }

            Divider()

            if state.pairedDevices.isEmpty {
                Text("No paired devices yet.")
                    .foregroundStyle(.secondary)
                    .font(.callout)
            } else {
                ForEach(state.pairedDevices) { dev in
                    HStack {
                        Image(systemName: state.connectedDeviceIds.contains(dev.id) ? "circle.fill" : "circle")
                            .foregroundStyle(state.connectedDeviceIds.contains(dev.id) ? .green : .secondary)
                            .imageScale(.small)
                        Text(dev.name)
                        Spacer()
                    }
                    .font(.callout)
                }
            }

            Divider()

            Button("Show Pairing QR…") {
                openWindow(id: "settings")
                state.pairing.beginQrPairing(state: state)
            }

            Button("Open Settings…") {
                openWindow(id: "settings")
            }

            if let err = state.lastError {
                Text(err)
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            Divider()
            Button("Quit Padly") {
                NSApplication.shared.terminate(nil)
            }
            .keyboardShortcut("q")
        }
        .padding(14)
        .frame(width: 280)
    }

    @ViewBuilder
    private var statusBadge: some View {
        if state.connectedDeviceIds.isEmpty {
            Label("Listening", systemImage: "wifi")
                .labelStyle(.iconOnly)
                .foregroundStyle(.secondary)
        } else {
            Label("Connected", systemImage: "wifi.circle.fill")
                .labelStyle(.iconOnly)
                .foregroundStyle(.green)
        }
    }
}
