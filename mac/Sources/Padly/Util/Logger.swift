import Foundation
import os

/// Thin wrapper around `os.Logger` so call sites read cleanly.
final class Logger {
    static let shared = Logger(subsystem: "app.padly.mac", category: "Padly")

    private let log: os.Logger

    init(subsystem: String, category: String) {
        self.log = os.Logger(subsystem: subsystem, category: category)
    }

    func info(_ message: String) {
        log.info("\(message, privacy: .public)")
    }

    func warn(_ message: String) {
        log.warning("\(message, privacy: .public)")
    }

    func error(_ message: String) {
        log.error("\(message, privacy: .public)")
    }

    func debug(_ message: String) {
        log.debug("\(message, privacy: .public)")
    }
}
