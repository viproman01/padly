import Foundation
import os

/// Thin wrapper around `os.Logger` so call sites read cleanly.
final class Logger {
    static let shared = Logger(subsystem: "app.padly.mac", category: "Padly")

    private let log: os.Logger
    /// Mirror to stdout — set PADLY_LOG_STDOUT=1 (headless / CI debugging).
    private let mirrorToStdout = ProcessInfo.processInfo.environment["PADLY_LOG_STDOUT"] == "1"

    init(subsystem: String, category: String) {
        self.log = os.Logger(subsystem: subsystem, category: category)
    }

    func info(_ message: String) {
        log.info("\(message, privacy: .public)")
        mirror("INFO", message)
    }

    func warn(_ message: String) {
        log.warning("\(message, privacy: .public)")
        mirror("WARN", message)
    }

    func error(_ message: String) {
        log.error("\(message, privacy: .public)")
        mirror("ERROR", message)
    }

    func debug(_ message: String) {
        log.debug("\(message, privacy: .public)")
        mirror("DEBUG", message)
    }

    private func mirror(_ level: String, _ message: String) {
        guard mirrorToStdout else { return }
        print("[\(level)] \(message)")
        fflush(stdout)
    }
}
