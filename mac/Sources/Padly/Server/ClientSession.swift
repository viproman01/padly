import Foundation
import Network

/// One paired (or pairing) phone connection. Holds the WebSocket connection,
/// the per-device HMAC key, and dispatches decoded messages to the injectors.
final class ClientSession {
    enum State {
        case awaitingHello
        case pairing(bootstrapKey: Data)
        case authenticated(deviceId: String, hmacKey: Data)
        case closed
    }

    let connection: NWConnection
    private let decoder = Decoder()
    private let cursor = CursorInjector()
    private let keys = KeyInjector()
    private let gestureSynth = GestureSynth()
    private let presenter = PresenterOverlay()
    private weak var state: AppState?
    private var sessionState: State = .awaitingHello
    private let queue: DispatchQueue

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
            if let data = data {
                self.handleFrame(data)
            }
            if complete {
                self.close()
            } else {
                self.receiveNext()
            }
        }
    }

    private func handleFrame(_ data: Data) {
        do {
            let key = try keyForCurrentState(data: data)
            let message = try decoder.decode(frame: data, hmacKey: key)
            handleMessage(message)
        } catch {
            Logger.shared.warn("decode failed: \(error)")
            // 10 failures in 1s → disconnect (rudimentary rate-limit)
            failureCount += 1
            if failureCount > 10 {
                close()
                return
            }
        }
    }

    private var failureCount: Int = 0

    private func keyForCurrentState(data: Data) throws -> Data {
        switch sessionState {
        case .awaitingHello, .pairing:
            // Until paired we use the pending bootstrap token as the key.
            guard let boot = state?.pairing.currentBootstrapKey() else {
                throw DecodeError.badHmac
            }
            return boot
        case .authenticated(_, let key):
            return key
        case .closed:
            throw DecodeError.badHmac
        }
    }

    private func handleMessage(_ msg: PadlyMessage) {
        switch msg {
        case .hello(let h):
            handleHello(h)
        case .pinAttempt(let p):
            handlePin(p)
        case .ping(let ts):
            sendPong(ts: ts)
        case .cursorMove(let dx, let dy, _):
            guard isAuthenticated else { return }
            cursor.moveBy(dx: CGFloat(dx), dy: CGFloat(dy))
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

    // MARK: - Pairing / handshake

    private func handleHello(_ h: Hello) {
        Logger.shared.info("hello from \(h.deviceName) [\(h.deviceId)]")
        // If we already have a paired key for this id, accept directly.
        if let saved = state?.keyStore.find(deviceId: h.deviceId) {
            sessionState = .authenticated(deviceId: h.deviceId, hmacKey: saved.hmacKey)
            DispatchQueue.main.async { [weak self] in
                self?.state?.connectedDeviceIds.insert(h.deviceId)
            }
            sendWelcome(accepted: true, requirePin: false)
            return
        }
        guard state?.pairing.currentBootstrapKey() != nil else {
            sendWelcome(accepted: false, requirePin: false)
            close()
            return
        }
        sessionState = .pairing(bootstrapKey: state!.pairing.currentBootstrapKey()!)
        sendWelcome(accepted: true, requirePin: true)
    }

    private func handlePin(_ p: PinAttempt) {
        guard let result = state?.pairing.tryPin(incomingHash: p.hash, incomingSalt: p.salt) else { return }
        switch result {
        case .ok(let key):
            // Persist new device. We don't know the friendly name here without
            // re-reading the Hello — keep a minimal store and let SettingsWindow rename.
            let device = PairedDevice(id: UUID().uuidString,
                                      name: "New phone",
                                      hmacKey: key,
                                      certPin: Data(),
                                      lastSeen: Date())
            state?.keyStore.upsert(device: device)
            sessionState = .authenticated(deviceId: device.id, hmacKey: key)
            DispatchQueue.main.async { [weak self] in
                self?.state?.pairedDevices = self?.state?.keyStore.allPairedDevices() ?? []
                self?.state?.connectedDeviceIds.insert(device.id)
                self?.state?.currentPin = nil
            }
            sendPairOk(hmacKey: key)
        case .retry(let left):
            Logger.shared.info("pin retry, attempts left: \(left)")
        case .lockedOut(let until):
            Logger.shared.warn("pin locked until \(until)")
            close()
        case .expired:
            Logger.shared.warn("pin expired")
            close()
        }
    }

    private func handlePresenter(_ p: PresenterAction) {
        switch p.kind {
        case .next: keys.send(event: KeyEvent(code: 0x4F, modifiers: 0, down: true, ts: 0))
        case .prev: keys.send(event: KeyEvent(code: 0x50, modifiers: 0, down: true, ts: 0))
        case .black: keys.send(event: KeyEvent(code: 0x05, modifiers: 0, down: true, ts: 0)) // B
        case .white: keys.send(event: KeyEvent(code: 0x1A, modifiers: 0, down: true, ts: 0)) // W
        case .pointer:
            presenter.show()
            if let p = p.pointerXY { presenter.updatePosition(normalised: p) }
        case .stop:
            presenter.hide()
        }
    }

    // MARK: - Outbound

    private func sendWelcome(accepted: Bool, requirePin: Bool) {
        let payload: MsgPack = .map([
            (.str("t"), .str("welcome")),
            (.str("d"), .map([
                (.str("sessionId"), .str(UUID().uuidString)),
                (.str("serverVer"), .str("0.1.0")),
                (.str("accepted"), .bool(accepted)),
                (.str("requirePin"), .bool(requirePin)),
            ])),
        ])
        sendFramed(payload)
    }

    private func sendPong(ts: UInt64) {
        let payload: MsgPack = .map([
            (.str("t"), .str("pong")),
            (.str("d"), .map([
                (.str("ts"), .uint(ts)),
                (.str("serverTs"), .uint(UInt64(Date().timeIntervalSince1970 * 1000))),
            ])),
        ])
        sendFramed(payload)
    }

    private func sendPairOk(hmacKey: Data) {
        let payload: MsgPack = .map([
            (.str("t"), .str("pairOk")),
            (.str("d"), .map([
                (.str("hmacKey"), .bin(hmacKey)),
            ])),
        ])
        sendFramed(payload)
    }

    private var outSeq: UInt32 = 1

    private func sendFramed(_ msg: MsgPack) {
        let payload = MsgPackCodec.encode(msg)
        var seqBytes = Data(count: 4)
        let s = outSeq
        seqBytes[0] = UInt8(s & 0xFF)
        seqBytes[1] = UInt8((s >> 8) & 0xFF)
        seqBytes[2] = UInt8((s >> 16) & 0xFF)
        seqBytes[3] = UInt8((s >> 24) & 0xFF)
        outSeq &+= 1

        let ver: UInt8 = 0x01
        let key = currentOutboundKey()
        let hmac = HMACUtil.sha256(key: key, parts: [Data([ver]), seqBytes, payload])

        var frame = Data()
        frame.append(ver)
        frame.append(seqBytes)
        frame.append(payload)
        frame.append(hmac)

        let metadata = NWProtocolWebSocket.Metadata(opcode: .binary)
        let context = NWConnection.ContentContext(identifier: "frame", metadata: [metadata])
        connection.send(content: frame, contentContext: context, completion: .contentProcessed { _ in })
    }

    private func currentOutboundKey() -> Data {
        switch sessionState {
        case .authenticated(_, let k): return k
        case .pairing(let boot): return boot
        case .awaitingHello, .closed:
            return state?.pairing.currentBootstrapKey() ?? Data()
        }
    }

    private func close() {
        if case .closed = sessionState { return }
        if case .authenticated(let id, _) = sessionState {
            DispatchQueue.main.async { [weak self] in
                self?.state?.connectedDeviceIds.remove(id)
            }
        }
        sessionState = .closed
        keys.releaseAllHeldKeys()
        connection.cancel()
    }
}
