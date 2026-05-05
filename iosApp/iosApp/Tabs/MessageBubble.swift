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

    var body: some View {
        HStack {
            if outgoing { Spacer(minLength: 40) }
            VStack(alignment: outgoing ? .trailing : .leading, spacing: 4) {
                if !msg.title.isEmpty {
                    Text(msg.title)
                        .font(.caption.bold())
                }
                Text(msg.content)
                    .textSelection(.enabled)
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
    }

    private var outgoing: Bool { msg.direction == "outgoing" }

    private var timeLabel: String {
        let date = Date(timeIntervalSince1970: TimeInterval(msg.timestamp) / 1000)
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: date)
    }

    private var stateGlyph: String? {
        switch msg.state {
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
}
