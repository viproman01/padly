import AppKit
import CoreImage
import Foundation
import SwiftUI

/// Encodes a pairing payload as a string and renders it to a QR code.
/// Payload format (URL-style so Android can parse with stdlib):
///   padly://pair?v=2&ip=...&port=...&id=...&name=...
/// Carries no secrets — just connect coordinates. Security comes from the
/// ECDH handshake + PIN proof.
enum QRGenerator {
    static func payload(serverId: String,
                        name: String,
                        port: Int,
                        ipHint: String) -> String
    {
        let encodedName = name.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "Mac"
        return "padly://pair?v=2&ip=\(ipHint)&port=\(port)&id=\(serverId)&name=\(encodedName)"
    }

    static func image(from payload: String, size: CGFloat = 240) -> NSImage? {
        let data = Data(payload.utf8)
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(data, forKey: "inputMessage")
        filter.setValue("Q", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage else { return nil }

        let scale = size / output.extent.width
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let context = CIContext()
        guard let cg = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        let img = NSImage(cgImage: cg, size: NSSize(width: size, height: size))
        return img
    }
}

struct QRImageView: View {
    let payload: String

    var body: some View {
        Group {
            if let img = QRGenerator.image(from: payload) {
                Image(nsImage: img)
                    .resizable()
                    .interpolation(.none)
                    .scaledToFit()
                    .background(Color.white)
                    .padding(8)
                    .background(Color.white)
                    .cornerRadius(8)
            } else {
                Text("QR render failed")
                    .foregroundStyle(.red)
            }
        }
    }
}
