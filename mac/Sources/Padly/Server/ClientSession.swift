import CryptoKit
import Foundation
import Network

/// One phone connection. Runs the v2 handshake (ECDH → AES-GCM channel),
/// drives pairing / resume, and dispatches decoded messages to the injectors.
///
/// Handshake wire flow:
///   phone → mac: helloE  (raw msgpack: deviceId, name, ver, caps, pub)
///   mac → phone: welcomeE (raw msgpack: serverId, name, ver, pub, known)
///   — channel keys derived on both sides —
///   pairing: phone → pin{proof} (sealed) → mac → pairOk{ltk} / pairFail{left}
///   resume:  mac → welcome{accepted:true} (sealed); any valid sealed frame
///            from the phone proves it holds the same ltk.
final class ClientSession {
    enum State {
        case awaitingHelloE
        case pairing(deviceId: String, deviceName: String, sessionKey: SymmetricKey)
        case authenticated(deviceId: String)
        case closed
    }

    let connection: NWConnection
    private let interpreter = MessageInterpreter()
    private let cursor = CursorInjector()
    private let keys = KeyInjector()
    private let gestureSynth = GestureSynth()
    private let presenter = PresenterOverlay()
    private weak var state: AppState?
    private var sessionState: State = .awaitingHelloE
    private var channel: SecureChannel?
    private var phoneChannel: SecureChannel? // decrypts phone→mac frames
    private let queue: DispatchQueue
    private var failureCount: Int = 0
    private var browserLivenessTimer: DispatchSourceTimer?
    private var lastValidBrowserFrameAt: TimeInterval = 0
    /// Called once when the session ends, so the listener can drop it.
    var onClose: ((ClientSession) -> Void)?

    init(connection: NWConnection, state: AppState) {
        self.connection = connection
        self.state = state
        self.queue = DispatchQueue(label: "padly.session.\(UUID().uuidString)")
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] s in
            switch s {
            case .ready: self?.receiveNext()
            case .failed(let e), .waiting(let e):
                Logger.shared.warn("connection \(s): \(e)")
                self?.close()
            case .cancelled:
                self?.close()
            default: break
            }
        }
        connection.start(queue: queue)
    }

    private func receiveNext() {
        connection.receiveMessage { [weak self] data, _, complete, error in
            guard let self else { return }
            if let error = error {
                Logger.shared.warn("recv error: \(error)")
                self.close()
                return
            }
            if let data = data, !data.isEmpty {
                self.handleFrame(data)
            }
            if case .closed = self.sessionState {
                return
            }
            if complete && data == nil {
                self.close()
            } else {
                self.receiveNext()
            }
        }
    }

    private func handleFrame(_ data: Data) {
        switch sessionState {
        case .awaitingHelloE:
            handleHelloE(data)
        case .pairing, .authenticated:
            handleSealedFrame(data)
        case .closed:
            break
        }
    }

    // MARK: - Handshake

    private func handleHelloE(_ raw: Data) {
        guard let state = state else { close(); return }
        do {
            let hello = try interpreter.parseHelloE(raw)
            let mine = HandshakeCrypto()
            let known = state.keyStore.find(deviceId: hello.deviceId)

            let sessionKey = try mine.deriveSessionKey(peerPublicKey: hello.publicKey,
                                                       clientPub: hello.publicKey,
                                                       serverPub: mine.publicKeyBytes,
                                                       ltk: known?.ltk)

            sendWelcomeE(publicKey: mine.publicKeyBytes, known: known != nil)

            // Channel established. Mac seals with direction 0x02, opens 0x01.
            channel = SecureChannel(key: sessionKey, sendDirection: 0x02)
            phoneChannel = SecureChannel(key: sessionKey, sendDirection: 0x01)
            if hello.capabilities.contains("browser") { startBrowserLivenessWatchdog() }

            if let known = known {
                sessionState = .authenticated(deviceId: known.id)
                var updated = known
                updated.lastSeen = Date()
                state.keyStore.upsert(device: updated)
                DispatchQueue.main.async { [weak self] in
                    self?.state?.connectedDeviceIds.insert(known.id)
                }
                sendSealed(type: "welcome", fields: [("accepted", .bool(true))])
                Logger.shared.info("resumed \(known.name) [\(known.id)]")
            } else {
                guard state.pairing.hasActivePin else {
                    // No pairing window open on the Mac — reject.
                    sendSealed(type: "pairFail", fields: [("reason", .str("pairing-not-active")), ("left", .uint(0))])
                    close()
                    return
                }
                sessionState = .pairing(deviceId: hello.deviceId,
                                        deviceName: hello.deviceName,
                                        sessionKey: sessionKey)
                sendSealed(type: "welcome", fields: [("accepted", .bool(true)), ("requirePin", .bool(true))])
                Logger.shared.info("pairing started for \(hello.deviceName) [\(hello.deviceId)]")
            }
        } catch {
            Logger.shared.warn("helloE failed: \(error)")
            close()
        }
    }

    private func handleSealedFrame(_ data: Data) {
        guard let phoneChannel = phoneChannel else { close(); return }
        do {
            let plain = try phoneChannel.open(data)
            let message = try interpreter.interpret(plain)
            lastValidBrowserFrameAt = ProcessInfo.processInfo.systemUptime
            failureCount = 0
            handleMessage(message)
        } catch {
            Logger.shared.warn("frame rejected: \(error)")
            failureCount += 1
            if failureCount > 10 { close() }
        }
    }

    private func handleMessage(_ msg: PadlyMessage) {
        switch msg {
        case .pinProof(let proof):
            handlePinProof(proof)
        case .ping(let ts):
            sendSealed(type: "pong", fields: [
                ("ts", .uint(ts)),
                ("serverTs", .uint(UInt64(Date().timeIntervalSince1970 * 1000))),
            ])
        case .cursorMove(let dx, let dy, _):
            guard isAuthenticated else { return }
            let sens = state?.settings.cursorSensitivity ?? 1.0
            cursor.moveBy(dx: CGFloat(dx) * sens, dy: CGFloat(dy) * sens)
        case .cursorClick(let btn, let down, _):
            guard isAuthenticated else { return }
            cursor.click(button: btn, down: down)
        case .scroll(let dx, let dy, _, _):
            guard isAuthenticated else { return }
            cursor.scroll(dx: CGFloat(dx), dy: CGFloat(dy), naturalScroll: state?.settings.naturalScroll ?? true)
        case .gesture(let g):
            guard isAuthenticated else { return }
            gestureSynth.synthesize(g)
        case .key(let k):
            guard isAuthenticated else { return }
            keys.send(event: k)
        case .text(let s):
            guard isAuthenticated else { return }
            keys.typeText(s)
        case .presenter(let p):
            guard isAuthenticated else { return }
            handlePresenter(p)
        case .bye(let reason):
            Logger.shared.info("bye: \(reason)")
            close()
        }
    }

    private var isAuthenticated: Bool {
        if case .authenticated = sessionState { return true }
        return false
    }

    /// Browser pages send sealed application pings. A dead WiFi path need not
    /// produce a TCP close promptly, so release held input after missed pings.
    /// Waiting for the human's PIN is deliberately outside this timeout.
    private func startBrowserLivenessWatchdog() {
        lastValidBrowserFrameAt = ProcessInfo.processInfo.systemUptime
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 3, repeating: 3)
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            if BrowserClientLiveness.shouldClose(isAuthenticated: self.isAuthenticated,
                                                 lastValidFrameAt: self.lastValidBrowserFrameAt,
                                                 now: ProcessInfo.processInfo.systemUptime) {
                Logger.shared.warn("browser heartbeat timed out")
                self.close()
            }
        }
        browserLivenessTimer = timer
        timer.resume()
    }

    // MARK: - Pairing

    private func handlePinProof(_ proof: Data) {
        guard case .pairing(let deviceId, let deviceName, let sessionKey) = sessionState,
              let state = state else { return }

        switch state.pairing.tryPinProof(proof, sessionKey: sessionKey) {
        case .ok:
            let ltk = HMACUtil.randomBytes(32)
            let device = PairedDevice(id: deviceId,
                                      name: deviceName,
                                      ltk: ltk,
                                      lastSeen: Date())
            state.keyStore.upsert(device: device)
            sessionState = .authenticated(deviceId: deviceId)
            DispatchQueue.main.async { [weak self] in
                self?.state?.pairedDevices = self?.state?.keyStore.allPairedDevices() ?? []
                self?.state?.connectedDeviceIds.insert(deviceId)
                self?.state?.currentPin = nil
                self?.state?.qrPayload = nil
            }
            sendSealed(type: "pairOk", fields: [("ltk", .bin(ltk))])
            Logger.shared.info("paired \(deviceName) [\(deviceId)]")
        case .retry(let left):
            sendSealed(type: "pairFail", fields: [("reason", .str("bad-pin")), ("left", .uint(UInt64(left)))])
        case .lockedOut(let until):
            sendSealed(type: "pairFail", fields: [("reason", .str("locked")), ("left", .uint(0))])
            Logger.shared.warn("pin locked until \(until)")
            close()
        case .expired:
            sendSealed(type: "pairFail", fields: [("reason", .str("expired")), ("left", .uint(0))])
            close()
        }
    }

    private func handlePresenter(_ p: PresenterAction) {
        switch p.kind {
        case .next: keys.tap(code: 0x4F)
        case .prev: keys.tap(code: 0x50)
        case .black: keys.tap(code: 0x05) // B
        case .white: keys.tap(code: 0x1A) // W
        case .pointer:
            presenter.show()
            if let point = p.pointerXY { presenter.updatePosition(normalised: point) }
        case .stop:
            presenter.hide()
        }
    }

    // MARK: - Outbound

    /// welcomeE goes out as raw msgpack — the channel doesn't exist yet.
    private func sendWelcomeE(publicKey: Data, known: Bool) {
        let payload: MsgPack = .map([
            (.str("t"), .str("welcomeE")),
            (.str("d"), .map([
                (.str("serverId"), .str(state?.keyStore.serverId ?? "")),
                (.str("name"), .str(Host.current().localizedName ?? "Mac")),
                (.str("serverVer"), .str("0.1.0")),
                (.str("pub"), .bin(publicKey)),
                (.str("known"), .bool(known)),
            ])),
        ])
        sendRaw(MsgPackCodec.encode(payload))
    }

    private func sendSealed(type: String, fields: [(String, MsgPack)]) {
        guard let channel = channel else { return }
        let payload: MsgPack = .map([
            (.str("t"), .str(type)),
            (.str("d"), .map(fields.map { (.str($0.0), $0.1) })),
        ])
        do {
            let frame = try channel.seal(MsgPackCodec.encode(payload))
            sendRaw(frame)
        } catch {
            Logger.shared.error("seal failed: \(error)")
        }
    }

    private func sendRaw(_ bytes: Data) {
        let metadata = NWProtocolWebSocket.Metadata(opcode: .binary)
        let context = NWConnection.ContentContext(identifier: "frame", metadata: [metadata])
        connection.send(content: bytes, contentContext: context, completion: .contentProcessed { _ in })
    }

    private func close() {
        if case .closed = sessionState { return }
        if case .authenticated(let id) = sessionState {
            DispatchQueue.main.async { [weak self] in
                self?.state?.connectedDeviceIds.remove(id)
            }
        }
        sessionState = .closed
        browserLivenessTimer?.cancel()
        browserLivenessTimer = nil
        cursor.releaseAllHeldButtons()
        keys.releaseAllHeldKeys()
        presenter.hide()
        connection.cancel()
        onClose?(self)
        onClose = nil
    }
}

enum BrowserClientLiveness {
    static let idleTimeout: TimeInterval = 15

    static func shouldClose(isAuthenticated: Bool, lastValidFrameAt: TimeInterval, now: TimeInterval) -> Bool {
        isAuthenticated && now - lastValidFrameAt > idleTimeout
    }
}
