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
            if !state.listenAddress.isEmpty {
                Text("Mac в сети: \(state.listenAddress)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }
            if let address = state.browserURL, let url = URL(string: address) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("На телефоне или планшете откройте в браузере:")
                        .font(.caption)
                    Link(address, destination: url)
                        .font(.caption)
                        .textSelection(.enabled)
                }
            }

            if !state.accessibilityGranted {
                accessibilityWarning
            }

            Divider()

            if state.pairedDevices.isEmpty {
                Text("Телефонов пока нет.")
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

            if state.currentPin != nil {
                pairingBlock
            } else {
                Button("Подключить телефон…") {
                    state.pairing.beginQrPairing(state: state)
                }
            }

            Divider()

            Button("Настройки…") {
                NSApp.activate()
                openWindow(id: "settings")
            }

            if let err = state.lastError {
                Text(err)
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            Button("Выйти из Padly") {
                NSApplication.shared.terminate(nil)
            }
            .keyboardShortcut("q")
        }
        .padding(14)
        .frame(width: 300)
    }

    private var accessibilityWarning: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label("Нет доступа «Универсальный доступ» — курсор и клавиши не сработают.",
                  systemImage: "exclamationmark.triangle.fill")
                .font(.caption)
                .foregroundStyle(.orange)
            Button("Открыть настройки доступа") {
                state.openAccessibilitySettings()
            }
            .font(.caption)
        }
    }

    /// PIN + how-to, right in the menu. Counts down so an expired PIN is obvious.
    private var pairingBlock: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let remaining = Int((state.pinExpiresAt ?? .distantPast).timeIntervalSince(context.date))
            VStack(alignment: .leading, spacing: 8) {
                if remaining > 0, let pin = state.currentPin {
                    Text("PIN для телефона")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text(pin.prefix(3) + " " + pin.suffix(3))
                        .font(.system(size: 32, weight: .bold, design: .monospaced))
                        .textSelection(.enabled)
                    Text("Подключите телефон к той же WiFi, откройте адрес выше или QR в браузере и введите PIN. Осталось \(remaining) с.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    if let payload = state.browserURL {
                        QRImageView(payload: payload)
                            .frame(width: 120, height: 120)
                    }
                } else {
                    Text("PIN истёк.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Button(remaining > 0 ? "Новый PIN" : "Подключить телефон…") {
                    state.pairing.beginQrPairing(state: state)
                }
            }
        }
    }

    @ViewBuilder
    private var statusBadge: some View {
        if state.connectedDeviceIds.isEmpty {
            Label("Ожидаю телефон", systemImage: "wifi")
                .labelStyle(.iconOnly)
                .foregroundStyle(.secondary)
        } else {
            Label("Телефон подключён", systemImage: "wifi.circle.fill")
                .labelStyle(.iconOnly)
                .foregroundStyle(.green)
        }
    }
}
