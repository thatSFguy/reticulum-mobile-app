// SPDX-License-Identifier: MIT
//
// Single message bubble — outgoing (right-aligned, primary tint) vs
// incoming (left-aligned, surface tint). RSSI / hop-count footer on
// incoming messages mirrors the Android v0.1.85 footer; outgoing
// messages show their state glyph (✓, ✓✓, ⏳, ✗).

import Shared
import SwiftUI
import UniformTypeIdentifiers

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
    /// Off-row attachment store. When this row carries an
    /// `imageToken` / `attachmentToken`, the bubble decodes the image
    /// (downsampled) and loads the file payload from here. Nil before
    /// the engine factory is built — the bubble then falls back to
    /// the legacy in-row `imageBytes` / `attachmentBytes` columns.
    /// docs/ATTACHMENT-STORE.md §3.3 dual-read.
    let attachmentStore: AttachmentStore?
    /// Invoked when the user picks an emoji from the long-press
    /// context menu. Caller (ConversationView) routes it to
    /// `store.sendReaction(destinationHash, msg.messageId, emoji)`.
    let onReact: (String) -> Void
    /// Invoked when the user swipes right past the threshold —
    /// the conversation view stores `msg` as the reply target.
    let onSwipeReply: () -> Void

    @State private var showZoom = false
    /// Drives the system save dialog for a file attachment.
    @State private var showSaveExporter = false
    /// The bubble's attachment image, decoded off the main actor by
    /// the `.task` below — downsampled from the attachment-store file
    /// when this row carries an `imageToken`, or from the legacy
    /// in-row blob otherwise. Nil while the decode is in flight.
    @State private var resolvedImage: UIImage?
    /// File-attachment bytes, loaded lazily when the user taps the
    /// chip's Save button. Kept off the row's render path so a
    /// file-bearing bubble doesn't pull a multi-MB payload into
    /// memory just to be displayed.
    @State private var exportData: Data?
    /// Drag offset for the swipe-right-to-reply gesture. The
    /// bubble pulls visually rightward as the user drags; on
    /// release, if past `replyThreshold`, we fire onSwipeReply.
    @State private var dragOffsetX: CGFloat = 0
    private let replyThreshold: CGFloat = 60

    var body: some View {
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
                            else if q.imageToken != nil || q.imageBytes != nil { preview = "📷 Image" }
                            else if q.attachmentToken != nil || q.attachmentBytes != nil {
                                preview = "📎 \(q.attachmentName ?? "File")"
                            }
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
                // Tap opens a full-screen zoom sheet. `resolvedImage`
                // is filled by the `.task` below (downsampled decode);
                // while that's in flight an image-bearing row shows a
                // placeholder so the bubble doesn't collapse.
                if let uiImage = resolvedImage {
                    Image(uiImage: uiImage)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: 240, maxHeight: 220)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                        .onTapGesture { showZoom = true }
                } else if hasImage {
                    RoundedRectangle(cornerRadius: 8)
                        .fill(Color.primary.opacity(0.06))
                        .frame(width: 160, height: 100)
                        .overlay(ProgressView())
                }
                if !msg.content.isEmpty {
                    Text(linkifyAttributedString(msg.content))
                        .textSelection(.enabled)
                        .tint(outgoing ? .white : Color.accentColor)
                }
                // LXMF file attachment (FIELD_FILE_ATTACHMENTS, SPEC
                // §5.9.7) — a tappable chip. Tapping opens the system
                // save dialog (.fileExporter) so the user explicitly
                // chooses where the file lands; the bytes are never
                // auto-opened or auto-saved. The file name was
                // sanitised on receive (engine/sanitizeAttachmentName).
                if hasFile {
                    let attachName = msg.attachmentName ?? "attachment"
                    Button {
                        // Lazy-load: the file bytes (token or legacy
                        // blob) are only pulled into memory on the
                        // explicit Save tap, then the exporter opens.
                        Task {
                            if let data = await loadAttachmentData() {
                                exportData = data
                                showSaveExporter = true
                            }
                        }
                    } label: {
                        HStack(spacing: 8) {
                            Image(systemName: "paperclip")
                            VStack(alignment: .leading, spacing: 1) {
                                Text(attachName)
                                    .font(.callout)
                                    .lineLimit(1)
                                    .truncationMode(.middle)
                                Text("\(fileSizeLabel(attachmentByteCount)) · tap to save")
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 8)
                        .background(
                            RoundedRectangle(cornerRadius: 8)
                                .fill(Color.primary.opacity(0.10))
                        )
                    }
                    .buttonStyle(.plain)
                    .fileExporter(
                        isPresented: $showSaveExporter,
                        document: AttachmentFileDocument(data: exportData ?? Data()),
                        contentType: .data,
                        defaultFilename: attachName,
                    ) { _ in }
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
                if let droppedKind = droppedAttachmentKind {
                    Text("⚠ \(droppedKind) not delivered — link unreachable, text only")
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
                // the engine's sendReaction path. Gated on:
                //   - msg.messageId != nil (pre-1.1.33 rows have
                //     no target id, nothing to react to)
                //   - !outgoing (no self-reactions — every reaction
                //     is an LXMF round-trip, and reacting to your
                //     own message is a UX foot-gun)
                // Audit reference: 2026-05-13 reactions + replies.
                if msg.messageId != nil && !outgoing {
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
            if let uiImage = resolvedImage {
                ImageZoomView(image: uiImage, onDismiss: { showZoom = false })
            }
        }
        // Decode the attachment image off the main actor when the row
        // appears. Keyed on `msg.id` so a recycled List cell reloads
        // for its new row. docs/ATTACHMENT-STORE.md §3.6.
        .task(id: msg.id) {
            resolvedImage = await resolveImage()
        }
    }

    private var outgoing: Bool { msg.direction == "outgoing" }

    /// True when this row carries an image — an attachment-store token
    /// (current write path) or a legacy in-row blob (pre-store rows).
    private var hasImage: Bool { msg.imageToken != nil || msg.imageBytes != nil }

    /// True when this row carries a file attachment — token or blob.
    private var hasFile: Bool { msg.attachmentToken != nil || msg.attachmentBytes != nil }

    /// Byte count for the file chip's size label — the off-row token
    /// path carries it as `attachmentSize`; legacy rows read it off
    /// the in-row blob.
    private var attachmentByteCount: Int {
        if let size = msg.attachmentSize { return Int(truncating: size) }
        if let bytes = msg.attachmentBytes { return Int(bytes.size) }
        return 0
    }

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
    /// failed. Keyed on the IMAGE_DROPPED_MARKER / FILE_DROPPED_MARKER
    /// prefix the engine writes to lastError just before that
    /// fallback. Returns "Image" / "File" for the warning text, or nil
    /// when nothing was dropped. Only fires on outgoing rows.
    private var droppedAttachmentKind: String? {
        guard outgoing, msg.state == "delivered", let err = msg.lastError else { return nil }
        if err.hasPrefix("file dropped — ") { return "File" }
        if err.hasPrefix("image dropped — ") { return "Image" }
        return nil
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

    /// Resolve and decode this row's attachment image, downsampled and
    /// off the main actor. Prefers the off-row `imageToken` — decoded
    /// straight from the attachment-store file via ImageIO so a
    /// multi-MB source never fully materialises
    /// (docs/ATTACHMENT-STORE.md §3.6); falls back to the legacy
    /// in-row `imageBytes` blob for rows saved before the store
    /// landed. A single 1600 px decode serves both the bubble
    /// thumbnail and the zoom view — ample for a phone screen and
    /// well clear of the OOM a true full-res decode of a 4 MB JPEG
    /// would risk.
    private func resolveImage() async -> UIImage? {
        let token = msg.imageToken
        let legacy = msg.imageBytes
        let store = attachmentStore
        return await Task.detached(priority: .userInitiated) {
            if let token = token, let path = store?.pathFor(token: token) {
                return ImageCompress.downsampledImage(path: path, maxPixelSize: 1600)
            }
            if let legacy = legacy {
                return UIImage(data: kotlinBytesToData(legacy))
            }
            return nil
        }.value
    }

    /// Load this row's file-attachment bytes for the Save dialog —
    /// from the attachment-store token (read off disk) or the legacy
    /// in-row blob. Off the main actor; nil when neither is present
    /// or the store file is unreadable.
    private func loadAttachmentData() async -> Data? {
        let token = msg.attachmentToken
        let legacy = msg.attachmentBytes
        let store = attachmentStore
        return await Task.detached(priority: .userInitiated) {
            if let token = token, let path = store?.pathFor(token: token) {
                return try? Data(contentsOf: URL(fileURLWithPath: path))
            }
            if let legacy = legacy {
                return kotlinBytesToData(legacy)
            }
            return nil
        }.value
    }
}

/// Copy a Kotlin `ByteArray` (surfaced to Swift as `KotlinByteArray`)
/// into a Swift `Data`. Kotlin/Native hands back no `Data` directly;
/// this is the same byte-loop `ReticulumStore.exportIdentityArchive`
/// uses. Only the legacy in-row blob path needs it — token rows
/// decode straight from the file.
private func kotlinBytesToData(_ bytes: KotlinByteArray) -> Data {
    let count = Int(bytes.size)
    var data = Data(count: count)
    for i in 0..<count {
        data[i] = UInt8(bitPattern: bytes.get(index: Int32(i)))
    }
    return data
}

/// Compact human size for a file-attachment chip — "938 B" / "204 KB".
private func fileSizeLabel(_ bytes: Int) -> String {
    bytes < 1024 ? "\(bytes) B" : "\(bytes / 1024) KB"
}

/// Minimal `FileDocument` wrapper so a received attachment's raw bytes
/// can be handed to SwiftUI's `.fileExporter` save dialog. Export-only
/// in practice; the read initialiser is required by the protocol.
private struct AttachmentFileDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.data] }
    var data: Data

    init(data: Data) { self.data = data }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
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
