import AppKit
import SwiftUI

/// Floating overlay window for the laser pointer in presenter mode.
/// Borderless, transparent, always on top, ignores mouse events.
final class PresenterOverlay {
    private var window: NSWindow?

    func show() {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            if self.window != nil { return }
            let screen = NSScreen.main ?? NSScreen.screens.first
            let frame = screen?.frame ?? NSRect(x: 0, y: 0, width: 800, height: 600)
            let w = NSPanel(contentRect: frame,
                            styleMask: [.borderless, .nonactivatingPanel],
                            backing: .buffered, defer: false)
            w.isOpaque = false
            w.backgroundColor = .clear
            w.level = .screenSaver
            w.ignoresMouseEvents = true
            w.collectionBehavior = [.canJoinAllSpaces, .stationary, .fullScreenAuxiliary]
            w.hasShadow = false
            w.contentView = NSHostingView(rootView: LaserDotView(position: .constant(.init(x: 0.5, y: 0.5))))
            w.orderFrontRegardless()
            self.window = w
        }
    }

    func updatePosition(normalised: CGPoint) {
        DispatchQueue.main.async { [weak self] in
            guard let w = self?.window, let host = w.contentView as? NSHostingView<LaserDotView> else { return }
            host.rootView = LaserDotView(position: .constant(normalised))
        }
    }

    func hide() {
        DispatchQueue.main.async { [weak self] in
            self?.window?.orderOut(nil)
            self?.window = nil
        }
    }
}

struct LaserDotView: View {
    @Binding var position: CGPoint

    var body: some View {
        GeometryReader { geo in
            Circle()
                .fill(Color.red)
                .frame(width: 18, height: 18)
                .shadow(color: .red.opacity(0.7), radius: 18)
                .position(x: position.x * geo.size.width, y: (1 - position.y) * geo.size.height)
                .animation(.easeOut(duration: 0.05), value: position)
        }
    }
}
