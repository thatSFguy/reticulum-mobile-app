// SPDX-License-Identifier: MIT
//
// Single-conversation view — bubble timeline + compose row at the
// bottom. Each ConversationView owns a small per-conversation
// observer that subscribes to `repos.observeMessagesForContact(hash)`
// while the view is on screen and cancels on disappear. Mirrors the
// Android `ConversationView` composable.

import Shared
import SwiftUI

struct ConversationView: View {
    let contact: StoredDestination
    @EnvironmentObject private var store: ReticulumStore

    @StateObject private var observer = ConversationObserver()
    @State private var draft: String = ""
    @State private var showClearConfirm: Bool = false

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

            HStack {
                TextField("Message \(name)", text: $draft, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(1...4)

                Button {
                    let trimmed = draft.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !trimmed.isEmpty else { return }
                    store.sendMessage(destinationHash: contact.hash, content: trimmed)
                    draft = ""
                    // Sending was the user's "I'm done typing"
                    // signal — dismiss the keyboard so they're back
                    // to the conversation view, same as iMessage.
                    dismissKeyboard()
                } label: {
                    Image(systemName: "paperplane.fill")
                }
                .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
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
    }

    private var name: String {
        let value = contact.effectiveDisplayName
        return value.isEmpty ? "(unnamed)" : value
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
