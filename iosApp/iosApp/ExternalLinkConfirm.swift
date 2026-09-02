import SwiftUI
import UIKit

/// Leave-the-mesh confirmation for a peer-supplied `http(s)` link.
///
/// ## Why this exists
///
/// This is an off-grid, zero-HTTP app. A tapped link in a message body
/// is the ONE channel that leaves the mesh, and the server on the other
/// end was chosen by the sender, not by the user. Opening it hands that
/// server the user's real IP, ISP and coarse location — correlated to
/// the LXMF identity the sender messaged. That is the deanonymisation
/// the whole app exists to prevent, and it should never cost a stray
/// tap.
///
/// Android has had this since the 2026-07-28 audit (L8), which also
/// wrote the asymmetry into `MessageLinks.kt`: mesh links act
/// immediately, http links go through a confirmation. iOS was never
/// given the other half — both `OpenURLAction` interceptors returned
/// `.systemAction` for http(s), so a link opened straight into Safari
/// with no warning at all. Found by the 2026-09-02 audit (M1); this is
/// the fix, shared by both views so the two cannot drift again.
///
/// Attach with `.externalLinkConfirm($pendingExternalURL)` and feed the
/// binding from `externalLinkResult(_:capture:)` inside the view's
/// `OpenURLAction`.
struct ExternalLinkConfirm: ViewModifier {
    @Binding var pendingURL: URL?

    func body(content: Content) -> some View {
        content.alert(
            "Open link?",
            isPresented: Binding(
                get: { pendingURL != nil },
                set: { if !$0 { pendingURL = nil } }
            ),
            presenting: pendingURL
        ) { url in
            Button("Open in browser") {
                UIApplication.shared.open(url)
                pendingURL = nil
            }
            Button("Cancel", role: .cancel) { pendingURL = nil }
        } message: { url in
            Text(
                "This opens in your browser and leaves the mesh. The site — chosen by "
                    + "the sender, not you — will see your real IP address and network.\n\n"
                    + displayableExternalURL(url)
            )
        }
    }
}

extension View {
    /// See ``ExternalLinkConfirm``.
    func externalLinkConfirm(_ pendingURL: Binding<URL?>) -> some View {
        modifier(ExternalLinkConfirm(pendingURL: pendingURL))
    }
}

/// Decide what an `OpenURLAction` should do with [url] when it might be
/// an external web link.
///
/// Returns `.handled` after handing the URL to [capture] (which parks it
/// for the confirmation dialog), or `nil` when this is not a web link
/// and the caller's own routing should continue.
///
/// Kept separate from the modifier so the decision is made in exactly
/// one place: a view that shows message text and forgets to call this
/// is the failure mode being designed out.
func externalLinkResult(
    _ url: URL,
    capture: (URL) -> Void
) -> OpenURLAction.Result? {
    guard let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" else {
        return nil
    }
    capture(url)
    return .handled
}

/// Render an untrusted URL for DISPLAY in the confirmation dialog.
///
/// Two hardenings, both because this string is attacker-authored and is
/// being shown to a user who is about to make a security decision about
/// it (audit 2026-09-02, dialog observation):
///
///  - **Bidi controls are stripped.** An override (U+202E and friends)
///    reverses the display order of everything after it, so a URL can be
///    made to read as a host it does not point at — in the very dialog
///    whose job is to tell the user where they are going.
///  - **The length is bounded, eliding the middle.** An 8 KB URL pushes
///    the alert's buttons off screen; keeping the head (scheme + host,
///    the part that matters) and the tail is more informative than a
///    plain truncation.
func displayableExternalURL(_ url: URL, limit: Int = 120) -> String {
    let cleaned = stripBidiControls(url.absoluteString)
    guard cleaned.count > limit else { return cleaned }
    let head = cleaned.prefix(limit - 24)
    let tail = cleaned.suffix(20)
    return "\(head)…\(tail)"
}

/// Confirmation for a tapped Relay Chat link naming a hub this device
/// has never connected to.
///
/// ## Why this exists
///
/// `MessageLinks.kt` justifies acting on mesh links immediately with
/// "both stay on the mesh, so a tap costs nothing a peer could
/// observe." That holds for fetching a NomadNet page. It does not hold
/// here: the observer IS the attacker who authored the link, the
/// connection is attributable to them, and unlike a page fetch it
/// PERSISTS — the room row is written with `joined: true`, which the
/// WELCOME auto-rejoin acts on at every launch from then on, until the
/// user finds the hub list and deletes it. Found by the 2026-09-02
/// audit (M3).
///
/// Attached once at the app root so every surface that can dispatch a
/// room link is covered, rather than per-view where a new surface would
/// silently miss it. A hub already in the list is never re-confirmed —
/// re-asking on every shared link to a hub someone uses daily is how
/// you train a person to dismiss a dialog without reading it.
struct RoomLinkConfirm: ViewModifier {
    @EnvironmentObject private var store: ReticulumStore

    func body(content: Content) -> some View {
        content.alert(
            store.pendingRoomLink?.room.isEmpty == false
                ? "Join room on a new hub?"
                : "Connect to a new hub?",
            isPresented: Binding(
                get: { store.pendingRoomLink != nil },
                set: { if !$0 { store.dismissPendingRoomLink() } }
            ),
            presenting: store.pendingRoomLink
        ) { link in
            Button(link.room.isEmpty ? "Connect" : "Join") {
                store.confirmPendingRoomLink()
            }
            Button("Cancel", role: .cancel) { store.dismissPendingRoomLink() }
        } message: { link in
            Text(roomLinkWarning(link))
        }
    }
}

extension View {
    /// See ``RoomLinkConfirm``.
    func roomLinkConfirm() -> some View {
        modifier(RoomLinkConfirm())
    }
}

/// Body text for the new-hub confirmation. Names the durable part,
/// which is what separates this from opening a page: accepting does not
/// connect once, it makes the app come back to this hub at every launch.
private func roomLinkWarning(_ link: ReticulumStore.PendingRoomLink) -> String {
    let lead = link.room.isEmpty
        ? "This link connects you to a Relay Chat hub you have never connected to:"
        : "This link joins \"\(boundLinkText(link.room))\" on a Relay Chat hub you have never connected to:"
    return lead + "\n\n\(link.hubHash)\n\n"
        + "The hub operator — chosen by whoever sent the link, not you — will see your "
        + "device connect. Your app will also reconnect to this hub every time it starts, "
        + "until you remove it on the Rooms tab."
}

/// Bound a room name for display. It comes off an attacker-authored
/// link, so it gets the same treatment the URL does: format characters
/// stripped, length capped.
private func boundLinkText(_ s: String, limit: Int = 48) -> String {
    let cleaned = stripBidiControls(s)
    return cleaned.count <= limit ? cleaned : String(cleaned.prefix(limit)) + "…"
}

/// Drop Unicode bidirectional formatting characters.
///
/// Mirrors the shared Kotlin `isBidiControl`, including what it does NOT
/// strip: only the directional set goes, because U+200D ZERO WIDTH
/// JOINER is also a format character and is load-bearing in emoji, which
/// people put in names.
///
///   U+200E/U+200F  LRM / RLM             directional marks
///   U+202A-U+202E  LRE RLE PDF LRO RLO   the legacy embedding set
///   U+2066-U+2069  LRI RLI FSI PDI       the isolate set
private func stripBidiControls(_ s: String) -> String {
    String(String.UnicodeScalarView(s.unicodeScalars.filter { scalar in
        let v = scalar.value
        return !(v == 0x200E || v == 0x200F
            || (0x202A...0x202E).contains(v)
            || (0x2066...0x2069).contains(v))
    }))
}
