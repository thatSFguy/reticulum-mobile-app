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

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                List(observer.messages, id: \.id) { msg in
                    MessageBubble(msg: msg)
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

            // Preview chip for the pending image (or compress-error
            // message) — sits between the timeline and the input HStack
            // so the user can confirm + dismiss before pressing Send.
            ImageAttachmentRow(
                pendingImage: pendingImage,
                compressing: compressing,
                imageError: imageError,
                onClear: {
                    pendingImage = nil
                    imageError = nil
                    pickerItem = nil
                }
            )

            HStack {
                // Attach-image button. PhotosPicker is the modern (iOS
                // 16+) read-only photo-library picker — no Info.plist
                // usage description, no NSPhotoLibraryUsageDescription
                // alert. Tap opens the system sheet; the picked item
                // streams in via the `.onChange(of: pickerItem)` below.
                PhotosPicker(
                    selection: $pickerItem,
                    matching: .images,
                    photoLibrary: .shared()
                ) {
                    Image(systemName: "paperclip")
                }
                .disabled(compressing)

                TextField("Message \(name)", text: $draft, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(1...4)

                Button {
                    let trimmed = draft.trimmingCharacters(in: .whitespacesAndNewlines)
                    // Image-only sends are valid LXMF — title and
                    // content can both be empty if `fields[6]` carries
                    // the payload. Allow Send when either is present.
                    guard !trimmed.isEmpty || pendingImage != nil else { return }
                    store.sendMessage(
                        destinationHash: contact.hash,
                        content: trimmed,
                        imageBytes: pendingImage
                    )
                    draft = ""
                    pendingImage = nil
                    imageError = nil
                    pickerItem = nil
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
                        && pendingImage == nil)
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
            // Drop the home-screen unread badge — opening ANY conversation
            // clears it (quick-fix per todo.md). Per-contact correctness
            // can come later if testers complain.
            IosNotifications.shared.clearBadge()
        }
        .onDisappear { observer.stop() }
        // Fire when PhotosPicker hands us a new item. The
        // `loadTransferable(type: Data.self)` API streams the raw image
        // bytes; we decode to UIImage, run through the ladder, and
        // surface either `pendingImage` or `imageError`. All of this
        // happens on a background Task so the picker sheet stays
        // responsive while large library entries (HEIF, RAW, etc.)
        // decode.
        .onChange(of: pickerItem) { _, newItem in
            guard let item = newItem else { return }
            compressing = true
            imageError = nil
            Task {
                let data: Data? = try? await item.loadTransferable(type: Data.self)
                let compressed: Data? = data.flatMap { raw in
                    guard let image = UIImage(data: raw) else { return nil }
                    return ImageCompress.compressForLxmf(image)
                }
                await MainActor.run {
                    compressing = false
                    if let bytes = compressed {
                        pendingImage = bytes
                        imageError = nil
                    } else {
                        pendingImage = nil
                        imageError = "Image too large to send (max 20 KB after compression)."
                    }
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
