import Foundation
import Network

/// Accepts incoming WebSocket connections from phones and spawns a
/// `ClientSession` per connection. The same NWListener also carries the
/// Bonjour advertisement (`_padly._tcp`), so one port serves both roles.
///
/// The TCP/WebSocket layer is deliberately plaintext: confidentiality and
/// authentication live in the frame layer (ECDH handshake + AES-GCM, see
/// `CryptoLayer.swift`), which avoids self-signed-certificate juggling.
final class WSListener {
    private weak var state: AppState?
    private var listener: NWListener?
    private var sessions: [ObjectIdentifier: ClientSession] = [:]
    /// Serialises access to `sessions` (connections arrive on a concurrent queue).
    private let sessionsQueue = DispatchQueue(label: "padly.listener.sessions")

    init(state: AppState) {
        self.state = state
    }

    func start(port: Int, serverId: String) {
        let wsOptions = NWProtocolWebSocket.Options()
        wsOptions.autoReplyPing = true

        let tcpOptions = NWProtocolTCP.Options()
        tcpOptions.noDelay = true

        let parameters = NWParameters(tls: nil, tcp: tcpOptions)
        parameters.includePeerToPeer = false
        parameters.defaultProtocolStack.applicationProtocols.insert(wsOptions, at: 0)

        guard let nwPort = NWEndpoint.Port(rawValue: UInt16(port)) else {
            Logger.shared.error("invalid port \(port)")
            return
        }
        do {
            let listener = try NWListener(using: parameters, on: nwPort)
            let txt = NWTXTRecord([
                "id": serverId,
                "ver": "2",
                "name": Host.current().localizedName ?? "Mac",
            ])
            listener.service = NWListener.Service(name: "Padly", type: "_padly._tcp", txtRecord: txt)
            listener.serviceRegistrationUpdateHandler = { change in
                Logger.shared.info("mDNS: \(change)")
            }
            listener.newConnectionHandler = { [weak self] conn in
                self?.accept(conn)
            }
            listener.stateUpdateHandler = { [weak self] s in
                Logger.shared.info("listener: \(s)")
                if case .failed(let e) = s {
                    DispatchQueue.main.async {
                        self?.state?.lastError = "Listener failed: \(e.localizedDescription)"
                    }
                }
            }
            listener.start(queue: .global(qos: .userInitiated))
            self.listener = listener
        } catch {
            Logger.shared.error("listener failed: \(error)")
            DispatchQueue.main.async { [weak self] in
                self?.state?.lastError = "Listener failed: \(error.localizedDescription)"
            }
        }
    }

    private func accept(_ conn: NWConnection) {
        guard let state = state else { conn.cancel(); return }
        let session = ClientSession(connection: conn, state: state)
        let id = ObjectIdentifier(session)
        session.onClose = { [weak self] _ in
            self?.sessionsQueue.async { self?.sessions.removeValue(forKey: id) }
        }
        sessionsQueue.sync { sessions[id] = session }
        session.start()
    }

    func stop() {
        listener?.cancel()
        listener = nil
        sessionsQueue.sync { sessions.removeAll() }
    }
}
