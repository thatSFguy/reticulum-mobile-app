// SPDX-License-Identifier: MIT
//
// Single message bubble — outgoing (right-aligned, primary tint) vs
// incoming (left-aligned, surface tint). RSSI / hop-count footer on
// incoming messages mirrors the Android v0.1.85 footer; outgoing
// messages show their state glyph (✓, ✓✓, ⏳, ✗).

import Shared
import SwiftUI

/// Signal-style tap-back palette. Six emoji mirrors the Android
/// `REACTION_PALETTE` in MessagesScreen.kt — same order so the
/// cross-platform UX is identical.
let REACTION_PALETTE: [String] = ["👍", "❤️", "😂", "😮", "😢", "🙏"]

struct MessageBubble: View {
    let msg: StoredMessage
    /// Locally-found target of this row's reply (used to render
    /// the small quote preview at the top of a reply bubble).
    /// Nil when this isn't a reply or when the target never
    /// arrived locally — in the latter case we render a faded
    /// "Replying to a message…" fallback so the user knows it
    /// WAS a reply even without context.
    let quotedMessage: StoredMessage?
    /// Label for the quoted message's sender — "You" for outgoing
    /// rows, contact display name for incoming.
    let quotedSenderLabel: String
    /// Invoked when the user picks an emoji from the long-press
    /// context menu. Caller (ConversationView) routes it to
    /// `store.sendReaction(destinationHash, msg.messageId, emoji)`.
    let onReact: (String) -> Void
    /// Invoked when the user swipes right past the threshold —
    /// the conversation view stores `msg` as the reply target.
    let onSwipeReply: () -> Void

    @State private var showZoom = false
    /// Drag offset for the swipe-right-to-reply gesture. The
    /// bubble pulls visually rightward as the user drags; on
    /// release, if past `replyThreshold`, we fire onSwipeReply.
    @State private var dragOffsetX: CGFloat = 0
    private let replyThreshold: CGFloat = 60

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
                // Reply-preview block at the top of a reply bubble.
                // Looked up by `quotedMessage` (set by the parent
                // view from a messageId map). Faded fallback when
                // the target hasn't arrived locally.
                if msg.replyToMessageId != nil {
                    let quotedText: String = {
                        if let q = quotedMessage {
                            let preview: String
                            if !q.content.isEmpty { preview = String(q.content.prefix(80)) }
                            else if q.imageBytes != nil { preview = "📷 Image" }
                            else { preview = "(empty)" }
                            return "\(quotedSenderLabel): \(preview)"
                        }
                        return "Replying to a message…"
                    }()
                    Text(quotedText)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 3)
                        .background(
                            RoundedRectangle(cornerRadius: 6)
                                .fill(Color.primary.opacity(0.08))
                        )
                }
                if unverified {
                    // MED-6 affordance: signature couldn't be matched
                    // against any known announce. Attacker can craft
                    // this from an attacker-chosen display name on
                    // first contact, so warn explicitly. Audit
                    // reference: 2026-05-13 MED-6.
                    HStack(spacing: 4) {
                        Image(systemName: "exclamationmark.shield")
                            .foregroundStyle(.orange)
                        Text("Unverified sender")
                            .font(.caption2.bold())
                            .foregroundStyle(.orange)
                    }
                }
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
                    Text(linkifyAttributedString(msg.content))
                        .textSelection(.enabled)
                        .tint(outgoing ? .white : Color.accentColor)
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
                // Aggregated reactions, rendered as small `👍 2`
                // chips. Empty `reactions` collapses cleanly.
                // Audit reference: 2026-05-13 reactions + replies.
                let reactions = decodedReactions
                if !reactions.isEmpty {
                    HStack(spacing: 4) {
                        ForEach(Array(reactions.keys), id: \.self) { emoji in
                            let count = reactions[emoji]?.count ?? 0
                            HStack(spacing: 2) {
                                Text(emoji).font(.caption)
                                if count > 1 {
                                    Text("\(count)")
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(
                                Capsule().fill(Color.primary.opacity(0.1))
                            )
                        }
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: 14)
                    .fill(
                        outgoing ? Color.accentColor.opacity(0.85) :
                        (unverified ? Color.orange.opacity(0.13) : Color.gray.opacity(0.18))
                    )
            )
            .overlay(
                // Amber stroke when unverified — pairs with the
                // "Unverified sender" header to make the bubble's
                // origin status unambiguous at a glance.
                RoundedRectangle(cornerRadius: 14)
                    .stroke(
                        unverified ? Color.orange : Color.clear,
                        lineWidth: 1
                    )
            )
            .foregroundStyle(outgoing ? .white : .primary)
            // Swipe-right-to-reply. Apply the drag offset and the
            // gesture only when the row has a target msg_id —
            // pre-1.1.33 rows can't be replied to (no canonical
            // id to reference). The gesture is gated on horizontal-
            // only drags so it doesn't fight List's vertical scroll.
            .offset(x: dragOffsetX)
            .gesture(
                msg.messageId != nil ? DragGesture(minimumDistance: 12)
                    .onChanged { value in
                        // Only track rightward drag — ignore left swipes
                        // (which would conflict with iOS's swipe-to-
                        // delete affordance on List rows). Cap at
                        // 1.5× threshold for visual elasticity.
                        if value.translation.width > 0 || dragOffsetX > 0 {
                            dragOffsetX = min(
                                max(value.translation.width, 0),
                                replyThreshold * 1.5,
                            )
                        }
                    }
                    .onEnded { _ in
                        if dragOffsetX >= replyThreshold {
                            onSwipeReply()
                        }
                        withAnimation(.spring(response: 0.25)) {
                            dragOffsetX = 0
                        }
                    } : nil
            )
            .contextMenu {
                // Tap-back reaction picker. iOS's .contextMenu is
                // the platform-idiomatic equivalent of Android's
                // long-press popup — surfaces on long-press, dims
                // the rest of the screen, dismissed by tapping
                // outside. Each Button posts the chosen emoji to
                // the engine's sendReaction path. Pre-1.1.33 rows
                // with null messageId get no menu — there's nothing
                // to target. Audit reference: 2026-05-13 reactions
                // + replies feature.
                if msg.messageId != nil {
                    ForEach(REACTION_PALETTE, id: \.self) { emoji in
                        Button { onReact(emoji) } label: {
                            Text(emoji)
                        }
                    }
                }
            }
            if !outgoing { Spacer(minLength: 40) }
        }
        .fullScreenCover(isPresented: $showZoom) {
            if let uiImage = uiImage {
                ImageZoomView(image: uiImage, onDismiss: { showZoom = false })
            }
        }
    }

    private var outgoing: Bool { msg.direction == "outgoing" }

    /// Decode `msg.reactionsJson` into a Swift `[emoji: [sender_hex]]`
    /// map for the chip-row render. Same wire shape the Android
    /// `ReactionsJson.decode` consumes — JSON object with string
    /// keys and string-array values. Falls back to empty on any
    /// parse failure (corrupted row, future format change). Audit
    /// reference: 2026-05-13 reactions + replies feature.
    private var decodedReactions: [String: [String]] {
        guard let json = msg.reactionsJson, !json.isEmpty, json != "{}",
              let data = json.data(using: .utf8),
              let raw = try? JSONSerialization.jsonObject(with: data) as? [String: [String]]
        else { return [:] }
        return raw
    }

    /// True when this is an incoming message whose LXMF signature
    /// couldn't be verified against any known announce. Drives the
    /// amber tint + warning header. Audit reference:
    /// 2026-05-13 MED-6.
    private var unverified: Bool {
        !outgoing && msg.state == "unverified"
    }

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

/// Convert a plain message body into an AttributedString where any
/// http(s) URL substring becomes a tappable link. Uses NSDataDetector
/// to match the same recogniser Mail/Messages use, so things like
/// `https://example.com` get linked without us reinventing a regex.
/// Trailing sentence punctuation that the detector includes is left
/// in place — NSDataDetector already strips the obvious cases. The
/// `.tint(...)` on the parent Text controls the link colour.
private func linkifyAttributedString(_ content: String) -> AttributedString {
    var attributed = AttributedString(content)
    let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
    let range = NSRange(content.startIndex..<content.endIndex, in: content)
    detector?.enumerateMatches(in: content, options: [], range: range) { match, _, _ in
        guard
            let match = match,
            let url = match.url,
            let swiftRange = Range(match.range, in: content),
            let attrRange = attributed.range(of: String(content[swiftRange]))
        else { return }
        // Skip non-web schemes (e.g. mailto:, tel:) — those are
        // useful too but route differently; leave them as plain
        // text for now. Audit reference: 2026-05-13 hyperlink feature.
        let scheme = url.scheme?.lowercased()
        guard scheme == "http" || scheme == "https" else { return }
        attributed[attrRange].link = url
        attributed[attrRange].underlineStyle = .single
    }
    return attributed
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
