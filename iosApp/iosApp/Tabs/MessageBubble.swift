// SPDX-License-Identifier: MIT
//
// Single message bubble — outgoing (right-aligned, primary tint) vs
// incoming (left-aligned, surface tint). RSSI / hop-count footer on
// incoming messages mirrors the Android v0.1.85 footer; outgoing
// messages show their state glyph (✓, ✓✓, ⏳, ✗).

import Shared
import SwiftUI

struct MessageBubble: View {
    let msg: StoredMessage

    @State private var showZoom = false

    var body: some View {
        // Decode the image once per body evaluation. SwiftUI's diffing
        // engine reruns body only when @State/@Binding/@ObservedObject
        // changes — for a fixed StoredMessage row in a List, this fires
        // on insert and on showZoom toggle, not on every frame. The
        // KotlinByteArray → Data byte-loop costs O(imageBytes.size); at
        // the ≤ 20 KB sender ladder ceiling that's negligible.
        let uiImage: UIImage? = decodedImage()

        HStack {
            if outgoing { Spacer(minLength: 40) }
            VStack(alignment: outgoing ? .trailing : .leading, spacing: 4) {
                if !msg.title.isEmpty {
                    Text(msg.title)
                        .font(.caption.bold())
                }
                // Image renders ABOVE the text content — matches
                // iMessage / WhatsApp layout (caption-below-image).
                // Tap opens a full-screen zoom sheet.
                if let uiImage = uiImage {
                    Image(uiImage: uiImage)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: 240, maxHeight: 220)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                        .onTapGesture { showZoom = true }
                }
                if !msg.content.isEmpty {
                    Text(msg.content)
                        .textSelection(.enabled)
                }
                // Partial-delivery indicator. The engine writes the
                // IMAGE_DROPPED_MARKER prefix ("image dropped — ") to
                // lastError when an image-bearing send had to fall
                // back to opportunistic (which strips images). The
                // PROOF eventually flips state to "delivered" but
                // leaves lastError untouched, so this condition holds
                // for the row's lifetime. The local image bitmap
                // above stays rendered — the sender DID try to send
                // it; we just couldn't get it to the recipient.
                if imageDropped {
                    Text("⚠ Image not delivered — link unreachable, text only")
                        .font(.caption2)
                        .foregroundStyle(Color(red: 1.0, green: 0.70, blue: 0.0))
                }
                HStack(spacing: 6) {
                    Text(timeLabel)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    if outgoing, let glyph = stateGlyph {
                        Text(glyph)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    } else if !outgoing, let footer = metadataFooter {
                        Text("· \(footer)")
                            .font(.caption2)
                            .foregroundStyle(.secondary.opacity(0.7))
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: 14)
                    .fill(outgoing ? Color.accentColor.opacity(0.85) : Color.gray.opacity(0.18))
            )
            .foregroundStyle(outgoing ? .white : .primary)
            if !outgoing { Spacer(minLength: 40) }
        }
        .fullScreenCover(isPresented: $showZoom) {
            if let uiImage = uiImage {
                ImageZoomView(image: uiImage, onDismiss: { showZoom = false })
            }
        }
    }

    private var outgoing: Bool { msg.direction == "outgoing" }

    /// True when an image-bearing send had to fall back to the
    /// opportunistic (text-only) path because link establishment
    /// failed. Keyed on the IMAGE_DROPPED_MARKER prefix the engine
    /// writes to lastError just before that fallback. Only fires on
    /// outgoing rows — incoming never holds this marker.
    private var imageDropped: Bool {
        outgoing
            && msg.state == "delivered"
            && (msg.lastError?.hasPrefix("image dropped — ") ?? false)
    }

    private var timeLabel: String {
        let date = Date(timeIntervalSince1970: TimeInterval(msg.timestamp) / 1000)
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: date)
    }

    private var stateGlyph: String? {
        switch msg.state {
        case "queued":    return "🕒"
        case "pending":   return "⏳"
        case "sending":   return "↑"
        case "sent":      return "✓"
        case "delivered": return "✓✓"
        case "failed":    return "✗"
        default:          return nil
        }
    }

    private var metadataFooter: String? {
        // Mirror the Android v0.1.85 footer: "RSSI -85 dBm · 2 hops"
        // with both fields independently optional. Hidden when both
        // null (TCP-only delivery on a pre-v8 row, etc.).
        var parts: [String] = []
        if let rssi = msg.rssi { parts.append("\(Int(truncating: rssi)) dBm") }
        if let hops = msg.hopCount {
            let n = Int(truncating: hops)
            parts.append("\(n) hop\(n == 1 ? "" : "s")")
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    /// Bridge the Kotlin `ByteArray?` (surfaced as `KotlinByteArray?`)
    /// to a Swift `UIImage`. Same byte-by-byte copy pattern as
    /// `ReticulumStore.exportIdentityArchive` — Kotlin/Native doesn't
    /// hand back a `Data` directly, and a faster `withUnsafeBufferPointer`
    /// path would require a Kotlin-side companion API.
    private func decodedImage() -> UIImage? {
        guard let bytes = msg.imageBytes else { return nil }
        let count = Int(bytes.size)
        var data = Data(count: count)
        for i in 0..<count {
            data[i] = UInt8(bitPattern: bytes.get(index: Int32(i)))
        }
        return UIImage(data: data)
    }
}

/// Full-screen zoom sheet for an attached image. Pinch + drag to
/// inspect; tap anywhere outside the image to dismiss, or use the
/// Close button. Implemented with SwiftUI's `MagnificationGesture` +
/// `DragGesture` rather than wrapping `UIScrollView` — adequate for
/// the ≤ 20 KB image budget where the source is already small.
private struct ImageZoomView: View {
    let image: UIImage
    let onDismiss: () -> Void

    @State private var scale: CGFloat = 1.0
    @State private var lastScale: CGFloat = 1.0
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
                .onTapGesture { onDismiss() }

            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .scaleEffect(scale)
                .offset(offset)
                .gesture(
                    SimultaneousGesture(
                        MagnificationGesture()
                            .onChanged { value in
                                scale = max(1.0, min(lastScale * value, 6.0))
                            }
                            .onEnded { _ in lastScale = scale },
                        DragGesture()
                            .onChanged { value in
                                offset = CGSize(
                                    width: lastOffset.width + value.translation.width,
                                    height: lastOffset.height + value.translation.height
                                )
                            }
                            .onEnded { _ in lastOffset = offset }
                    )
                )
                .onTapGesture(count: 2) {
                    // Double-tap resets the zoom — quick escape from
                    // having pinched too far in to find the close
                    // tap-target.
                    withAnimation(.spring(response: 0.3)) {
                        scale = 1.0
                        lastScale = 1.0
                        offset = .zero
                        lastOffset = .zero
                    }
                }

            VStack {
                HStack {
                    Spacer()
                    Button(action: onDismiss) {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 32))
                            .foregroundStyle(.white.opacity(0.85))
                            .padding(16)
                    }
                }
                Spacer()
            }
        }
    }
}
