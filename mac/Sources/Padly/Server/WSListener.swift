import Foundation
import Network

/// Accepts incoming WebSocket connections from phones and spawns a
/// `ClientSession` per connection.
///
/// Uses Network.framework's NWListener with a WebSocket NWProtocolOptions stack.
/// TLS is added when a `SecIdentity` is available; otherwise the listener falls
/// back to plain TCP and `lastError` is set on AppState so the UI can surface it.
final class WSListener {
    private weak var state: AppState?
    private var listener: NWListener?
    private var sessions: [ObjectIdentifier: ClientSession] = [:]

    init(state: AppState) {
        self.state = state
    }

    func start(port: Int) {
        let wsOptions = NWProtocolWebSocket.Options()
        wsOptions.autoReplyPing = true

        let tcpOptions = NWProtocolTCP.Options()
        tcpOptions.noDelay = true

        let parameters: NWParameters
        if let identity = TLSIdentity.shared.secIdentity(),
           let sec = sec_identity_create(identity) {
            let tlsOpts = NWProtocolTLS.Options()
            sec_protocol_options_set_local_identity(tlsOpts.securityProtocolOptions, sec)
            sec_protocol_options_set_min_tls_protocol_version(tlsOpts.securityProtocolOptions, .TLSv13)
            parameters = NWParameters(tls: tlsOpts, tcp: tcpOptions)
        } else {
            DispatchQueue.main.async { [weak self] in
                self?.state?.lastError = "TLS identity not available — running plaintext on localhost only"
            }
            parameters = NWParameters(tls: nil, tcp: tcpOptions)
            parameters.requiredInterfaceType = .loopback
        }
        parameters.includePeerToPeer = false
        parameters.defaultProtocolStack.applicationProtocols.insert(wsOptions, at: 0)

        guard let nwPort = NWEndpoint.Port(rawValue: UInt16(port)) else {
            Logger.shared.error("invalid port \(port)")
            return
        }
        do {
            let listener = try NWListener(using: parameters, on: nwPort)
            listener.newConnectionHandler = { [weak self] conn in
                self?.accept(conn)
            }
            listener.stateUpdateHandler = { s in
                Logger.shared.info("listener: \(s)")
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
        sessions[ObjectIdentifier(session)] = session
        session.start()
    }

    func stop() {
        listener?.cancel()
        listener = nil
        sessions.removeAll()
    }
}
