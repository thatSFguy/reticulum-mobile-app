// SPDX-License-Identifier: MIT
//
// Notification surface for incoming LXMF messages on iOS — the iOS
// peer of Android's ReticulumService.showIncomingMessageNotification.
//
// When the engine emits a MessageReceived event (link delivery proof
// landed, message decrypted, signature verified-or-not), this helper
// posts a UNNotificationRequest. The request fires whether the app
// is foreground or backgrounded; iOS shows a banner + adds a row to
// Notification Center either way.
//
// Tap-to-open: the request's userInfo carries the contactHash. The
// AppDelegate's UNUserNotificationCenterDelegate.didReceive handler
// pushes that hash into ReticulumStore.openContactEvent, which the
// existing MessagesView observer responds to by routing into the
// conversation. Same end behavior as Android's intent extra path.
//
// First-launch permission prompt: requested lazily on the first
// post() call rather than at app start, so users don't see an
// unexplained "wants to send notifications" alert before they've
// tried sending or receiving a message.

import Foundation
import Shared
import UIKit
import UserNotifications

@MainActor
final class IosNotifications: NSObject {

    static let shared = IosNotifications()

    /// userInfo key carrying the sender's destination hash so the
    /// AppDelegate can route a tap to the matching conversation.
    static let userInfoContactHash = "contactHash"

    private var hasRequestedAuth: Bool = false
    private var authorized: Bool = false

    /// Running count of unread incoming messages, driving the home-screen
    /// app-icon badge. Incremented on every [deliver]; reset to zero by
    /// [clearBadge] which ConversationView calls on appear. Quick-fix
    /// implementation per the todo.md plan: a single global counter, not
    /// per-contact correctness — opening ANY conversation drops the
    /// number to zero. The lastSeen-per-contact version can come later
    /// if testers complain about over-eager clearing.
    private var unreadBadgeCount: Int = 0

    /// Stash for the most recent tap that arrived before the store was
    /// ready to consume it. The ReticulumStore polls this on init and
    /// drains it into `openContactEvent`. Without this, a launch-from-
    /// notification (cold start) loses the deep-link.
    private(set) var pendingDeepLink: String? = nil

    private override init() {
        super.init()
    }

    /// Wire UNUserNotificationCenter's delegate to self so banner
    /// presentation works while the app is in the foreground (default
    /// iOS behavior is to NOT show a banner if the app is foreground —
    /// we override that, since the user being in another tab is just
    /// as much "they need to know" as the app being backgrounded).
    func install() {
        UNUserNotificationCenter.current().delegate = self
    }

    /// Post a notification for an incoming LXMF message. Idempotent
    /// per messageId — re-posting a duplicate (engine retry, etc.)
    /// just refreshes the existing row instead of stacking.
    func post(_ info: IncomingMessageInfo) {
        ensureAuthorized { ok in
            guard ok else { return }
            self.deliver(info)
        }
    }

    /// Drain the pending deep-link (if any). Called by ReticulumStore
    /// on init to handle launch-from-notification.
    func consumePendingDeepLink() -> String? {
        let hash = pendingDeepLink
        pendingDeepLink = nil
        return hash
    }

    /// Zero the unread-message app-icon badge. Called by
    /// ConversationView.onAppear so opening any conversation clears
    /// the home-screen number. Uses the iOS 16+ setBadgeCount API; the
    /// app's deploymentTarget (project.yml) is 17.0 so the fallback
    /// applicationIconBadgeNumber path isn't strictly required, but
    /// keep the API choice in one place in case we ever lower the
    /// target.
    func clearBadge() {
        unreadBadgeCount = 0
        UNUserNotificationCenter.current().setBadgeCount(0) { _ in }
    }

    // MARK: - Private

    private func ensureAuthorized(_ then: @escaping (Bool) -> Void) {
        if authorized { then(true); return }
        if hasRequestedAuth {
            // Authorization was previously requested and we got back
            // false. Don't keep nagging — just skip the post.
            then(false); return
        }
        hasRequestedAuth = true
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { ok, _ in
            Task { @MainActor in
                self.authorized = ok
                then(ok)
            }
        }
    }

    private func deliver(_ info: IncomingMessageInfo) {
        let content = UNMutableNotificationContent()
        content.title = info.verified ? "New message" : "Unverified message"
        // Cap body at 200 chars to keep notification UI legible — the
        // full content is in the in-app conversation view.
        let preview = String(info.content.prefix(200))
        content.body = preview
        content.sound = .default
        content.userInfo = [Self.userInfoContactHash: info.contactHash]

        // Bump the home-screen badge. iOS reads content.badge on notif
        // arrival and updates the app icon automatically, so this works
        // whether the app is foreground, background, or suspended. The
        // counter is cleared by clearBadge() the next time the user
        // opens any conversation.
        unreadBadgeCount += 1
        content.badge = NSNumber(value: unreadBadgeCount)

        // Single per-message notification id keyed off the engine's
        // messageId so a re-emitted MessageReceived (rare; engine
        // dedups but be defensive) refreshes in place rather than
        // stacking. Same shape as Android's
        // NOTIFICATION_ID_MESSAGE_BASE + msgId scheme.
        let identifier = "lxmf-\(info.messageId)"
        let request = UNNotificationRequest(identifier: identifier, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request) { _ in
            // Errors here are benign — usually "notifications are
            // disabled". Don't surface; the user already saw the
            // permission prompt and chose.
        }
    }
}

// MARK: - UNUserNotificationCenterDelegate

extension IosNotifications: UNUserNotificationCenterDelegate {

    /// Called when a notification arrives while the app is FOREGROUND.
    /// Default iOS behavior is to suppress; we explicitly opt to show
    /// the banner so a user in the Nodes / Settings / Nomad tab still
    /// sees that an LXMF message just landed in Messages.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound, .list])
    }

    /// Tap or action on a delivered notification. Stash the hash on
    /// pendingDeepLink; if the store is already alive (warm tap) it
    /// drains immediately via consumePendingDeepLink. If this was a
    /// cold launch, the store's init runs next and picks it up.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let hash = response.notification.request.content.userInfo[Self.userInfoContactHash] as? String
        Task { @MainActor in
            if let hash = hash {
                self.pendingDeepLink = hash
                NotificationCenter.default.post(
                    name: .reticulumOpenContact,
                    object: nil,
                    userInfo: [Self.userInfoContactHash: hash]
                )
            }
            completionHandler()
        }
    }
}

extension Notification.Name {
    /// Posted by IosNotifications when the user taps an LXMF
    /// notification. ReticulumStore observes this to set
    /// openContactEvent so MessagesView routes into the conversation.
    static let reticulumOpenContact = Notification.Name("reticulumOpenContact")
}
