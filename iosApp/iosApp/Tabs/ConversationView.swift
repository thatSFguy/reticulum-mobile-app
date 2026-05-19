// SPDX-License-Identifier: MIT
//
// Single-conversation view — bubble timeline + compose row at the
// bottom. Each ConversationView owns a small per-conversation
// observer that subscribes to `repos.observeMessagesForContact(hash)`
// while the view is on screen and cancels on disappear. Mirrors the
// Android `ConversationView` composable.

import PhotosUI
import Shared
import SwiftUI
import UniformTypeIdentifiers

struct ConversationView: View {
    let contact: StoredDestination
    @EnvironmentObject private var store: ReticulumStore

    @StateObject private var observer = ConversationObserver()
    @State private var draft: String = ""
    @State private var showClearConfirm: Bool = false

    // PhotosPicker (PhotosUI, iOS 16+) — read-only photo library scope,
    // no Info.plist usage description required. The user picks one
    // image; we run it through `ImageCompress.compressForLxmf` on a
    // background task and stash the resulting JPEG bytes in
    // `pendingImage` until Send. The `pickerItem` @State is reset to
    // nil after each pick so re-tapping the paperclip re-opens the
    // picker (without this, re-tapping after a successful pick is a
    // no-op because the @Binding value hasn't changed).
    @State private var pickerItem: PhotosPickerItem?
    @State private var pendingImage: Data?
    @State private var compressing: Bool = false
    @State private var imageError: String?
    /// File-attach state — `pendingFileBytes` rides the next Send as
    /// LXMF field 5. Mutually exclusive with `pendingImage`.
    @State private var pendingFileBytes: Data?
    @State private var pendingFileName: String?
    /// Picked photo bytes parked while the resolution-tier chooser is
    /// open; compression runs once the user picks a tier.
    @State private var pendingPhotoData: Data?
    @State private var showTierChooser: Bool = false
    @State private var showPhotoPicker: Bool = false
    @State private var fileImporterOpen: Bool = false
    /// Reply-to target — set by swipe-right on a bubble. The
    /// composer renders a "Replying to <name>: <preview>" banner
    /// above the text field; the next Send packages the reply
    /// with field 16 `{"reply_to": ...}` per Sideband / Columba
    /// convention. Audit reference: 2026-05-13 reactions +
    /// replies feature.
    @State private var replyingTo: StoredMessage?

    var body: some View {
        // Filter out "outgoing-reaction" shadow rows — they exist
        // for delivery-state tracking only, applied locally to the
        // target bubble's reactionsJson on send. The list view
        // would otherwise show an empty bubble for each reaction.
        let bubbles = observer.messages.filter { $0.direction != "outgoing-reaction" }
        // Quick-lookup map for reply previews — bubbles index by
        // messageId so the renderer can pull the target's content
        // for the quoted block. Recomputed when messages changes.
        let byMessageId: [String: StoredMessage] = Dictionary(
            uniqueKeysWithValues: observer.messages.compactMap { m in
                m.messageId.map { ($0, m) }
            }
        )
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                List(bubbles, id: \.id) { msg in
                    let quoted = msg.replyToMessageId.flatMap { byMessageId[$0] }
                    let quotedLabel: String = {
                        guard let q = quoted else { return "Peer" }
                        return q.direction == "outgoing" ? "You" : (contact.effectiveDisplayName.isEmpty ? "Peer" : contact.effectiveDisplayName)
                    }()
                    MessageBubble(
                        msg: msg,
                        quotedMessage: quoted,
                        quotedSenderLabel: quotedLabel,
                        attachmentStore: store.attachmentStore,
                        onReact: { emoji in
                            if let messageId = msg.messageId {
                                Task {
                                    await store.sendReaction(
                                        destinationHash: contact.hash,
                                        targetMessageId: messageId,
                                        emoji: emoji,
                                    )
                                }
                            }
                        },
                        onSwipeReply: {
                            replyingTo = msg
                        },
                    )
                    .listRowSeparator(.hidden)
                    .id(msg.id)
                }
                .listStyle(.plain)
                // Scrolling the message timeline dismisses the
                // keyboard — matches iMessage / Telegram / etc. The
                // multi-line compose TextField below uses Return
                // for newline so there's no submit-key dismiss; this
                // gives the user a gestural way out.
                .scrollDismissesKeyboard(.immediately)
                .onChange(of: observer.messages.count) { _, _ in
                    if let last = observer.messages.last {
                        withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                    }
                }
            }

            Divider()

            // Reply banner — appears when the user swiped-right on
            // a bubble. Shows the target's sender + content
            // preview, with an X to cancel. The next Send packages
            // the reply with field 16 = {"reply_to": id}.
            if let target = replyingTo {
                let targetLabel: String = target.direction == "outgoing" ? "You"
                    : (contact.effectiveDisplayName.isEmpty ? "Peer" : contact.effectiveDisplayName)
                let preview: String = {
                    if !target.content.isEmpty { return String(target.content.prefix(80)) }
                    if target.imageToken != nil || target.imageBytes != nil { return "📷 Image" }
                    if target.attachmentToken != nil || target.attachmentBytes != nil {
                        return "📎 \(target.attachmentName ?? "File")"
                    }
                    return "(empty)"
                }()
                HStack(alignment: .top, spacing: 8) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Replying to \(targetLabel)")
                            .font(.caption.bold())
                            .foregroundStyle(Color.accentColor)
                        Text(preview)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    Spacer()
                    Button { replyingTo = nil } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.borderless)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color.gray.opacity(0.12))
            }

            // Preview chip for the pending image (or compress-error
            // message) — sits between the timeline and the input HStack
            // so the user can confirm + dismiss before pressing Send.
            ImageAttachmentRow(
                pendingImage: pendingImage,
                pendingFileName: pendingFileName,
                pendingFileSize: pendingFileBytes?.count ?? 0,
                compressing: compressing,
                imageError: imageError,
                onClear: {
                    pendingImage = nil
                    pendingFileBytes = nil
                    pendingFileName = nil
                    imageError = nil
                    pickerItem = nil
                }
            )

            HStack {
                // Attach (+) menu — Photo or File. `.photosPicker`
                // presents the modern (iOS 16+) read-only photo-library
                // picker programmatically (no Info.plist usage string);
                // `.fileImporter` is the document picker, the same
                // affordance Settings uses for identity-archive import.
                Menu {
                    Button {
                        showPhotoPicker = true
                    } label: {
                        Label("Photo", systemImage: "photo")
                    }
                    Button {
                        fileImporterOpen = true
                    } label: {
                        Label("File", systemImage: "doc")
                    }
                } label: {
                    Image(systemName: "plus.circle")
                }
                .disabled(compressing)
                .photosPicker(
                    isPresented: $showPhotoPicker,
                    selection: $pickerItem,
                    matching: .images,
                    photoLibrary: .shared()
                )
                .fileImporter(
                    isPresented: $fileImporterOpen,
                    allowedContentTypes: [.data]
                ) { result in
                    guard case .success(let url) = result else { return }
                    let didAccess = url.startAccessingSecurityScopedResource()
                    defer { if didAccess { url.stopAccessingSecurityScopedResource() } }
                    guard let data = try? Data(contentsOf: url) else {
                        imageError = "Couldn't read that file."
                        return
                    }
                    if data.count > fileAttachMaxBytes {
                        imageError = "File too large to send (max 4 MB)."
                        return
                    }
                    pendingFileBytes = data
                    pendingFileName = url.lastPathComponent
                    pendingImage = nil   // image and file are mutually exclusive
                    imageError = nil
                }

                TextField("Message \(name)", text: $draft, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(1...4)

                Button {
                    let trimmed = draft.trimmingCharacters(in: .whitespacesAndNewlines)
                    // Attachment-only sends are valid LXMF — title and
                    // content can both be empty when `fields` carries
                    // the payload. Allow Send when any is present.
                    guard !trimmed.isEmpty || pendingImage != nil
                        || pendingFileBytes != nil else { return }
                    store.sendMessage(
                        destinationHash: contact.hash,
                        content: trimmed,
                        imageBytes: pendingImage,
                        fileBytes: pendingFileBytes,
                        fileName: pendingFileName,
                        replyToMessageId: replyingTo?.messageId,
                    )
                    draft = ""
                    pendingImage = nil
                    pendingFileBytes = nil
                    pendingFileName = nil
                    imageError = nil
                    pickerItem = nil
                    replyingTo = nil
                    // Sending was the user's "I'm done typing"
                    // signal — dismiss the keyboard so they're back
                    // to the conversation view, same as iMessage.
                    dismissKeyboard()
                } label: {
                    Image(systemName: "paperplane.fill")
                }
                .disabled(
                    compressing
                    || (draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                        && pendingImage == nil && pendingFileBytes == nil)
                )
            }
            .padding(8)

            if let err = store.lastSendError {
                Text(err)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .padding(.horizontal, 8)
                    .padding(.bottom, 4)
            }
        }
        .navigationTitle(name)
        .navigationBarTitleDisplayMode(.inline)
        .keyboardDoneToolbar()
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showClearConfirm = true
                } label: {
                    Image(systemName: "trash")
                }
                .disabled(observer.messages.isEmpty)
                .tint(observer.messages.isEmpty ? .secondary : .red)
            }
        }
        .alert("Clear conversation?", isPresented: $showClearConfirm) {
            Button("Clear", role: .destructive) {
                store.deleteMessagesForDestination(hash: contact.hash)
            }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("Removes \(observer.messages.count) message(s) with \(name) from local storage. The destination itself stays in your favorites/inbox; swipe-delete it on the Messages list to remove the destination too.")
        }
        .onAppear {
            observer.start(repos: store.repos, scope: store.scope, contactHash: contact.hash)
            // Record THIS contact as just-opened and recompute the
            // badge across all contacts. Pre-v1.1.23 we cleared the
            // entire badge here, which was incorrect when the user
            // had unread messages from a different contact — opening
            // Alice's thread would also drop the count for unread
            // messages from Bob. The per-contact lastSeen approach
            // only zeros Alice's slice and leaves Bob's contribution
            // in the badge.
            store.markConversationOpened(contactHash: contact.hash)
        }
        .onDisappear { observer.stop() }
        // Fire when PhotosPicker hands us a new item. Stream the raw
        // bytes, park them in `pendingPhotoData`, and open the
        // resolution-tier chooser — compression waits on the tier the
        // user picks.
        .onChange(of: pickerItem) { _, newItem in
            guard let item = newItem else { return }
            Task {
                let data: Data? = try? await item.loadTransferable(type: Data.self)
                await MainActor.run {
                    pickerItem = nil
                    if let data = data {
                        pendingPhotoData = data
                        showTierChooser = true
                    } else {
                        imageError = "Couldn't load that image."
                    }
                }
            }
        }
        // Resolution-tier chooser — Full / Medium / Small / Micro. The
        // tier list comes from Kotlin so the Swift side never has to
        // name the bridged enum's entries.
        .confirmationDialog(
            "Image quality",
            isPresented: $showTierChooser,
            titleVisibility: .visible
        ) {
            ForEach(Array(IosEngineFactoryKt.imageResolutionTiers().enumerated()), id: \.offset) { _, tier in
                Button("\(tier.label) — up to \(tierBudgetLabel(tier))") {
                    compressPendingPhoto(tier: tier)
                }
            }
            Button("Cancel", role: .cancel) { pendingPhotoData = nil }
        } message: {
            Text("Larger sizes look better but take far longer over a LoRa link — Micro is the radio-friendly choice.")
        }
    }

    /// Compress `pendingPhotoData` to [tier] off the main actor, then
    /// surface `pendingImage` or `imageError`.
    private func compressPendingPhoto(tier: ImageResolutionTier) {
        guard let raw = pendingPhotoData else { return }
        pendingPhotoData = nil
        compressing = true
        imageError = nil
        Task.detached(priority: .userInitiated) {
            let compressed: Data? = UIImage(data: raw).flatMap {
                ImageCompress.compressForLxmf($0, tier: tier)
            }
            await MainActor.run {
                compressing = false
                if let bytes = compressed {
                    pendingImage = bytes
                    pendingFileBytes = nil
                    pendingFileName = nil
                    imageError = nil
                } else {
                    pendingImage = nil
                    imageError = "Image too large even at \(tier.label)."
                }
            }
        }
    }

    private var name: String {
        let value = contact.effectiveDisplayName
        return value.isEmpty ? "(unnamed)" : value
    }
}

/// Renders one of three states above the compose HStack:
///   - `compressing == true` → a small spinner + "Compressing…" label
///   - `pendingImage != nil` → a thumbnail of the compressed JPEG,
///     the rendered byte size, and an × button to discard
///   - `imageError != nil` → a red error caption (e.g. "Image too
///     large to send"). The row collapses entirely when none apply.
private struct ImageAttachmentRow: View {
    let pendingImage: Data?
    let pendingFileName: String?
    let pendingFileSize: Int
    let compressing: Bool
    let imageError: String?
    let onClear: () -> Void

    var body: some View {
        if compressing {
            HStack(spacing: 8) {
                ProgressView().scaleEffect(0.7)
                Text("Compressing image…").font(.caption)
                Spacer()
            }
            .padding(.horizontal, 12).padding(.vertical, 6)
        } else if let bytes = pendingImage {
            HStack(spacing: 10) {
                if let uiImage = UIImage(data: bytes) {
                    Image(uiImage: uiImage)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 48, height: 48)
                        .clipShape(RoundedRectangle(cornerRadius: 6))
                }
                Text("\(bytes.count / 1024) KB image attached")
                    .font(.caption)
                Spacer()
                Button(action: onClear) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 6)
        } else if let fileName = pendingFileName {
            HStack(spacing: 10) {
                Image(systemName: "doc")
                Text("\(fileName) · \(fileSizeLabel(pendingFileSize))")
                    .font(.caption)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Spacer()
                Button(action: onClear) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 6)
        } else if let err = imageError {
            HStack(spacing: 8) {
                Text(err)
                    .font(.caption)
                    .foregroundStyle(.red)
                Spacer()
                Button(action: onClear) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 6)
        }
    }
}

/// 4 MB — mirrors the engine's `INBOUND_ATTACHMENT_MAX_BYTES` receive
/// ceiling. A file past this our own receiver would drop.
private let fileAttachMaxBytes: Int = 4 * 1024 * 1024

/// Human "N MB" / "N KB" label for an image tier's byte budget.
private func tierBudgetLabel(_ tier: ImageResolutionTier) -> String {
    let b = Int(tier.byteBudget)
    return b >= 1024 * 1024 ? "\(b / (1024 * 1024)) MB" : "\(b / 1024) KB"
}

/// Compact human size for a file-attachment chip — "938 B" / "204 KB".
private func fileSizeLabel(_ bytes: Int) -> String {
    bytes < 1024 ? "\(bytes) B" : "\(bytes / 1024) KB"
}

/// Per-conversation @Published shim. Subscribes to the repo's Flow
/// while the conversation is on screen and forwards each emission to
/// the SwiftUI @Published list via the existing IosEngineFactoryKt
/// bridge. Cancels in `stop()` so backgrounded conversations don't
/// keep the Kotlin Flow collector alive.
@MainActor
final class ConversationObserver: ObservableObject {
    @Published var messages: [StoredMessage] = []

    private var subscription: FlowSubscription?

    func start(repos: IosRepositories, scope: Kotlinx_coroutines_coreCoroutineScope, contactHash: String) {
        guard subscription == nil else { return }
        subscription = IosEngineFactoryKt.subscribe(
            repos.observeMessagesForContact(contactHash: contactHash),
            scope: scope
        ) { [weak self] list in
            Task { @MainActor in
                self?.messages = list as! [StoredMessage]
            }
        }
    }

    func stop() {
        subscription?.cancel()
        subscription = nil
    }

    deinit { subscription?.cancel() }
}
