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

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                List(observer.messages, id: \.id) { msg in
                    MessageBubble(msg: msg)
                        .listRowSeparator(.hidden)
                        .id(msg.id)
                }
                .listStyle(.plain)
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
        .onAppear { observer.start(repos: store.repos, scope: store.scope, contactHash: contact.hash) }
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
