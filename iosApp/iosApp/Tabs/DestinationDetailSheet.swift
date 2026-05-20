// SPDX-License-Identifier: MIT
//
// The shared "destination detail" bottom sheet — Phase 1 item 4 of the
// UI redesign (docs/REDESIGN.md §6). One consistent surface, invoked
// from Nodes / Messages / Rooms, that holds everything pulled off the
// list rows: the full destination hash (+ copy), the message / rename /
// contact / delete actions, the routing/key facts, and a QR of the
// address. Mirrors the Android `DestinationDetailSheet.kt` composable —
// reproduce the behaviour, not the Compose code.
//
// Layout order is deliberate: the action buttons sit near the top so
// the common taps never need a scroll; the facts and the (large) QR
// are below the fold with a "details below" hint.

import CoreImage.CIFilterBuiltins
import Shared
import SwiftUI

/// Render `string` as a crisp QR `UIImage`. Used both for the
/// destination-hash QR here and (separately) the IdentityCard QR in
/// Settings. Nearest-neighbour upscaling — pair with
/// `.interpolation(.none)` on the displaying `Image`.
func reticulumQRCode(from string: String) -> UIImage? {
    let context = CIContext()
    let filter = CIFilter.qrCodeGenerator()
    filter.message = Data(string.utf8)
    filter.correctionLevel = "M"
    guard let ciImage = filter.outputImage else { return nil }
    let scaled = ciImage.transformed(by: CGAffineTransform(scaleX: 8, y: 8))
    guard let cg = context.createCGImage(scaled, from: scaled.extent) else { return nil }
    return UIImage(cgImage: cg)
}

struct DestinationDetailSheet: View {
    let dest: StoredDestination
    /// Open the conversation for this hash.
    let onMessage: (String) -> Void
    /// Non-nil only when the experimental RRC feature is enabled — adds
    /// an "Open in Relay Chat" action on `rrc.hub` rows.
    let onOpenAsRrcHub: ((StoredDestination) -> Void)?
    let onRename: (StoredDestination) -> Void
    let onToggleFavorite: (String, Bool) -> Void
    let onDelete: (StoredDestination) -> Void
    /// Pin state + handler — only the Messages caller passes these;
    /// when `onTogglePin` is nil no Pin action is shown.
    var pinned: Bool = false
    var onTogglePin: ((String, Bool) -> Void)? = nil

    @State private var copied = false

    private var isRrcHub: Bool { dest.appName == "rrc.hub" }
    /// Messagable: an LXMF delivery destination, or a manual stub whose
    /// public key hasn't arrived via announce yet.
    private var messagable: Bool {
        dest.appName == "lxmf.delivery" || dest.publicKey.size == 0
    }

    private var title: String {
        let n = dest.effectiveDisplayName
        if !n.isEmpty { return n }
        return dest.appLabel ?? "(unnamed)"
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                Spacer().frame(height: 14)
                hashRow
                Spacer().frame(height: 12)
                Divider()
                Spacer().frame(height: 16)
                actions
                Spacer().frame(height: 10)
                hint
                Spacer().frame(height: 10)
                Divider()
                Spacer().frame(height: 12)
                facts
                Spacer().frame(height: 16)
                Divider()
                Spacer().frame(height: 12)
                qrSection
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 22)
        }
        // The sheet wraps its content — open at medium, expand to large
        // if the user wants the QR; never a forced full-screen.
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    // ── Header: avatar + name + type/hops summary ──
    private var header: some View {
        HStack(spacing: 12) {
            DetailAvatar(name: title, seed: dest.hash)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.title2.weight(.semibold))
                    .lineLimit(1)
                Text(summary)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
    }

    private var summary: String {
        var parts = [dest.appName ?? "unknown"]
        if dest.hopCount > 0 {
            parts.append("\(dest.hopCount) hop\(dest.hopCount == 1 ? "" : "s")")
        }
        return parts.joined(separator: " · ")
    }

    // ── Hash — short label, the full value, and a compact copy icon ──
    private var hashRow: some View {
        HStack(alignment: .center, spacing: 10) {
            Text("HASH")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
            Text(dest.hash)
                .font(.caption.monospaced())
                .frame(maxWidth: .infinity, alignment: .leading)
            Button {
                UIPasteboard.general.string = dest.hash
                copied = true
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { copied = false }
            } label: {
                Image(systemName: copied ? "checkmark" : "doc.on.doc")
                    .font(.system(size: 16))
                    .foregroundStyle(copied ? Color.green : Color.accentColor)
            }
            .buttonStyle(.plain)
        }
    }

    // ── Actions — kept near the top so common taps need no scroll ──
    private var actions: some View {
        VStack(spacing: 8) {
            if isRrcHub, let onOpenAsRrcHub {
                Button {
                    onOpenAsRrcHub(dest)
                } label: {
                    Text("Open in Relay Chat").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
            } else if messagable {
                Button {
                    onMessage(dest.hash)
                } label: {
                    Text("Message").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
            }

            if let onTogglePin {
                Button {
                    onTogglePin(dest.hash, !pinned)
                } label: {
                    Label(pinned ? "Unpin conversation" : "Pin to top",
                          systemImage: pinned ? "pin.slash" : "pin")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }

            Button {
                onToggleFavorite(dest.hash, !dest.favorite)
            } label: {
                Label(dest.favorite ? "Remove from Contacts" : "Add to Contacts",
                      systemImage: dest.favorite ? "person.badge.minus" : "person.badge.plus")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)

            Button {
                onRename(dest)
            } label: {
                Label(dest.userLabel?.isEmpty == false ? "Edit nickname" : "Add a nickname",
                      systemImage: "pencil")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)

            Button(role: .destructive) {
                onDelete(dest)
            } label: {
                Label("Delete destination", systemImage: "trash")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
        }
    }

    // ── Scroll hint — facts + QR continue below the fold ──
    private var hint: some View {
        HStack(spacing: 4) {
            Spacer()
            Text("Details & QR code below")
                .font(.caption)
                .foregroundStyle(.secondary)
            Image(systemName: "chevron.down")
                .font(.caption2)
                .foregroundStyle(.secondary)
            Spacer()
        }
    }

    // ── Facts ──
    private var facts: some View {
        VStack(alignment: .leading, spacing: 6) {
            DetailFact(label: "Public key",
                       value: dest.publicKey.size == 64 ? "known" : "not yet known")
            if dest.lastSeen > 0 {
                DetailFact(label: "Last seen",
                           value: relativeAge(Int64(Date().timeIntervalSince1970 * 1000) - dest.lastSeen))
            }
            // Signal is hidden for TCP-sourced destinations — they
            // report no RSSI (CLAUDE.md "Connect over Internet" note).
            if let rssi = dest.rssi {
                DetailFact(label: "Signal", value: "RSSI \(Int(truncating: rssi)) dBm")
            }
            DetailFact(label: "Source", value: dest.source)
        }
    }

    // ── QR of the hash — scan to identify / share the address ──
    private var qrSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("ADDRESS QR CODE")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
            HStack {
                Spacer()
                if let img = reticulumQRCode(from: dest.hash) {
                    Image(uiImage: img)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 140, height: 140)
                        .padding(8)
                        .background(Color.white)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                }
                Spacer()
            }
        }
    }
}

private struct DetailAvatar: View {
    let name: String
    /// Hex hash used to derive the avatar's background colour — see
    /// `shared/.../util/AvatarColors.kt`.
    let seed: String

    var body: some View {
        let initials = String(name.trimmingCharacters(in: .whitespaces).prefix(2))
            .uppercased()
        let colors = AvatarColorsKt.avatarColors(seed: seed)
        let bg = swiftColor(fromArgb: Int(colors.backgroundArgb))
        let fg: Color = colors.useDarkText ? .black : .white
        ZStack {
            Circle().fill(bg)
            Text(initials.isEmpty ? "?" : initials)
                .font(.headline)
                .foregroundStyle(fg)
        }
        .frame(width: 48, height: 48)
    }
}

private struct DetailFact: View {
    let label: String
    let value: String

    var body: some View {
        HStack(alignment: .top) {
            Text(label)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .frame(width: 116, alignment: .leading)
            Text(value)
                .font(.subheadline)
            Spacer()
        }
    }
}

/// Compact relative age — `42s ago` / `7m ago` / `3h ago` / `2d ago`.
func relativeAge(_ ms: Int64) -> String {
    let s = max(0, ms / 1000)
    if s < 60 { return "\(s)s ago" }
    if s < 3600 { return "\(s / 60)m ago" }
    if s < 86_400 { return "\(s / 3600)h ago" }
    return "\(s / 86_400)d ago"
}

// MARK: - Shared nickname editor

/// Set or clear the local nickname (`userLabel`) for a destination.
/// Stored on-device only — never sent on the wire. Shared by the Nodes
/// and Messages detail-sheet "Add / Edit nickname" actions so both
/// surfaces edit nicknames identically.
struct NicknameEditSheet: View {
    let target: StoredDestination
    let onSave: (String) -> Void

    @State private var draft: String = ""
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Stored on this device only — never sent on the wire.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    if !target.displayName.isEmpty {
                        Text("Announced name: \(target.displayName)")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    Text(target.hash)
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                }
                Section("Nickname") {
                    TextField("Leave empty to clear", text: $draft)
                }
            }
            .navigationTitle("Set nickname")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        onSave(draft.trimmingCharacters(in: .whitespacesAndNewlines))
                        dismiss()
                    }
                }
            }
            .onAppear { draft = target.userLabel ?? "" }
        }
    }
}
