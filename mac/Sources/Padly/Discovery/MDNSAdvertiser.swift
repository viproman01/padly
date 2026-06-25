import Foundation
import Network

/// Advertises the Padly listener via Bonjour so phones on the same LAN can
/// discover the Mac without knowing its IP.
final class MDNSAdvertiser {
    private weak var state: AppState?
    private var listener: NWListener?

    init(state: AppState) {
        self.state = state
    }

    func advertise(port: Int, serverId: String) {
        let txt = NWTXTRecord([
            "id": serverId,
            "fp": TLSIdentity.shared.certificateFingerprint(),
            "ver": "1",
            "name": Host.current().localizedName ?? "Mac",
        ])
        let service = NWListener.Service(name: "Padly", type: "_padly._tcp", txtRecord: txt)
        do {
            let listener = try NWListener(using: .tcp, on: NWEndpoint.Port(rawValue: UInt16(port))!)
            listener.service = service
            listener.serviceRegistrationUpdateHandler = { change in
                Logger.shared.info("mDNS: \(change)")
            }
            listener.newConnectionHandler = { conn in
                // Bonjour-only sentinel: real connections go through WSListener on
                // the same port. Drop anything that lands here.
                conn.cancel()
            }
            listener.start(queue: .global(qos: .utility))
            self.listener = listener
        } catch {
            Logger.shared.error("mdns failed: \(error)")
        }
    }
}
