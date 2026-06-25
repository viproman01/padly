import AppKit
import CoreImage
import Foundation
import SwiftUI

/// Encodes a pairing payload as a string and renders it to a QR code.
/// Payload format (URL-style so Android can parse with stdlib):
///   padly://pair?ip=...&port=...&id=...&fp=...&t=<base64-token>
enum QRGenerator {
    static func payload(serverId: String,
                        port: Int,
                        ipHint: String,
                        certFingerprint: String,
                        bootstrapToken: Data) -> String
    {
        let token = bootstrapToken.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .trimmingCharacters(in: CharacterSet(charactersIn: "="))
        return "padly://pair?ip=\(ipHint)&port=\(port)&id=\(serverId)&fp=\(certFingerprint)&t=\(token)"
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
