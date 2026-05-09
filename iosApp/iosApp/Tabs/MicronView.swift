// SPDX-License-Identifier: MIT
//
// SwiftUI renderer for parsed v0.1.48 micron + v0.1.50 form fields.
// Mirrors the Android `MicronView.kt` composable: heading levels,
// paragraphs, fields, tables, partials, literals, alignment, color/bg,
// link click handling (plain GET vs POST-with-fields), and inline-style
// runs. Bound to the same `Block` / `Inline` model that
// `commonMain/.../nomad/Micron.kt` produces, so feature parity is
// "render every node Android renders" — no parser duplication.
//
// Form-field interaction (per upstream Browser.py):
//   - `Inline.Field` runs render as native iOS inputs (TextField,
//     Toggle for checkbox, custom radio group).
//   - User input lands in a per-page `@State` dict keyed by field
//     name. Tapping an `Inline.Link` whose `fields` list is non-empty
//     fires `onLinkClickWithFields(target, submitDict)` — the caller
//     (NomadView) forwards as the request envelope's `data` field.
//   - Plain links (no `fields`) fire `onLinkClick(target)`.

import Shared
import SwiftUI

struct MicronView: View {
    let source: String
    var onLinkClick: (String) -> Void = { _ in }
    var onLinkClickWithFields: (String, [String: String]) -> Void = { _, _ in }
    /// Async fetcher for `\`{url}` partials. Default no-op — partials
    /// inside partials show "loading" forever (rare; matches upstream
    /// behavior of dropping refresh < 1s).
    var fetchPartial: (String, [String]) async -> String? = { _, _ in nil }

    /// Parsed once per source change. The Kotlin parser runs on the
    /// caller thread; for typical pages it's microseconds.
    @State private var document: MicronDocument? = nil
    /// Live form-input values keyed by field name. Reset when [source]
    /// changes (a fresh fetch). Mirrors the Android
    /// `mutableStateMapOf<String, String>` lifecycle.
    @State private var fieldValues: [String: String] = [:]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let doc = document {
                let baseColor = parseHexColor(doc.pageFg, fallback: .primary)
                let pageBg = parseHexColor(doc.pageBg, fallback: .clear)
                // K/N exports List<Block> directly as [Block] in Swift,
                // so the earlier `compactMap { $0 as? Block }` was a
                // no-op the compiler flagged ("always succeeds").
                let blocks = doc.blocks

                ForEach(0..<blocks.count, id: \.self) { idx in
                    blockView(blocks[idx], baseColor: baseColor)
                }
                .padding(.horizontal, 4)
                .background(pageBg)
            } else {
                ProgressView()
            }
        }
        .task(id: source) {
            // Re-parse + reset field state on every source swap.
            document = Micron.shared.parseDocument(source: source)
            fieldValues = seedFieldValues(from: document)
        }
    }

    // MARK: - Block dispatch

    @ViewBuilder
    private func blockView(_ block: Block, baseColor: Color) -> some View {
        if let h = block as? Block.Heading {
            HeadingBlockView(block: h, baseColor: baseColor, fieldValues: $fieldValues, onLinkClick: onLinkClick, onLinkClickWithFields: onLinkClickWithFields)
        } else if let p = block as? Block.Paragraph {
            ParagraphBlockView(block: p, baseColor: baseColor, fieldValues: $fieldValues, onLinkClick: onLinkClick, onLinkClickWithFields: onLinkClickWithFields)
        } else if let lit = block as? Block.Literal {
            LiteralBlockView(block: lit, baseColor: baseColor)
        } else if let tbl = block as? Block.Table {
            TableBlockView(block: tbl, baseColor: baseColor)
        } else if let part = block as? Block.Partial {
            PartialBlockView(block: part, baseColor: baseColor, fetchPartial: fetchPartial)
        } else if let hr = block as? Block.HorizontalRule {
            HorizontalRuleView(rune: Character(String(hr.rune)))
        } else {
            EmptyView()
        }
    }

    /// Pre-seed [fieldValues] from the parsed document so the initial
    /// render of TEXT inputs already shows their default text and any
    /// `prechecked` checkbox/radio is in fieldValues for submit.
    /// Mirrors the Android `LaunchedEffect(source)` block at
    /// MicronView.kt:91-115.
    private func seedFieldValues(from doc: MicronDocument?) -> [String: String] {
        guard let doc = doc else { return [:] }
        var out: [String: String] = [:]
        for blockObj in doc.blocks {
            let runs: [Inline]
            if let h = blockObj as? Block.Heading {
                runs = h.text
            } else if let p = blockObj as? Block.Paragraph {
                runs = p.runs
            } else {
                continue
            }
            for r in runs {
                guard let f = r as? Inline.Field else { continue }
                if out[f.name] != nil { continue }
                switch f.type {
                case FieldType.text:
                    out[f.name] = f.value
                case FieldType.checkbox, FieldType.radio:
                    if f.prechecked { out[f.name] = f.value }
                default:
                    break
                }
            }
        }
        return out
    }
}

// MARK: - Heading

private struct HeadingBlockView: View {
    let block: Block.Heading
    let baseColor: Color
    @Binding var fieldValues: [String: String]
    let onLinkClick: (String) -> Void
    let onLinkClickWithFields: (String, [String: String]) -> Void

    var body: some View {
        let runs = block.text
        let size: CGFloat = block.level == 1 ? 22 : (block.level == 2 ? 18 : 15)
        VStack(alignment: alignment(block.align), spacing: 4) {
            buildAttributedText(runs: runs, baseColor: baseColor, defaultBold: true)
                .font(.system(size: size, weight: .medium))
                .frame(maxWidth: .infinity, alignment: alignmentToFrame(block.align))
                .multilineTextAlignment(textAlign(block.align))
            FieldRowsView(runs: runs, fieldValues: $fieldValues)
            LinkRowsView(runs: runs, fieldValues: fieldValues, onLinkClick: onLinkClick, onLinkClickWithFields: onLinkClickWithFields)
        }
    }
}

// MARK: - Paragraph

private struct ParagraphBlockView: View {
    let block: Block.Paragraph
    let baseColor: Color
    @Binding var fieldValues: [String: String]
    let onLinkClick: (String) -> Void
    let onLinkClickWithFields: (String, [String: String]) -> Void

    var body: some View {
        let runs = block.runs
        VStack(alignment: alignment(block.align), spacing: 4) {
            buildAttributedText(runs: runs, baseColor: baseColor, defaultBold: false)
                .font(.system(size: 14))
                .foregroundStyle(baseColor)
                .frame(maxWidth: .infinity, alignment: alignmentToFrame(block.align))
                .multilineTextAlignment(textAlign(block.align))
                .textSelection(.enabled)
            FieldRowsView(runs: runs, fieldValues: $fieldValues)
            LinkRowsView(runs: runs, fieldValues: fieldValues, onLinkClick: onLinkClick, onLinkClickWithFields: onLinkClickWithFields)
        }
    }
}

// MARK: - Literal pre-formatted block

private struct LiteralBlockView: View {
    let block: Block.Literal
    let baseColor: Color

    var body: some View {
        Text(block.lines.joined(separator: "\n"))
            .font(.system(size: 13, design: .monospaced))
            .foregroundStyle(baseColor)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
            .background(Color.gray.opacity(0.18))
            .textSelection(.enabled)
    }
}

// MARK: - Table

/// Builds a Unicode-bordered monospace table the same way Android does
/// (┌─┐ │ ├─┤ └─┘). Padding per column is the widest cell across all
/// rows; alignment respects `Block.Table.align` (LEFT / CENTER / RIGHT).
private struct TableBlockView: View {
    let block: Block.Table
    let baseColor: Color

    var body: some View {
        let rows = block.rows
        let rendered = renderTable(rows: rows, align: block.align)
        Text(rendered)
            .font(.system(size: 12, design: .monospaced))
            .foregroundStyle(baseColor)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
            .background(Color.gray.opacity(0.18))
            .textSelection(.enabled)
    }

    private func renderTable(rows: [[String]], align: Align?) -> String {
        guard !rows.isEmpty else { return "" }
        let colCount = rows.map { $0.count }.max() ?? 0
        guard colCount > 0 else { return "" }
        var widths = [Int](repeating: 0, count: colCount)
        for row in rows {
            for c in 0..<colCount {
                let cell = c < row.count ? row[c] : ""
                widths[c] = max(widths[c], cell.count)
            }
        }
        let dashGroups = widths.map { String(repeating: "─", count: $0) }
        let top = "┌─" + dashGroups.joined(separator: "─┬─") + "─┐"
        let sep = "├─" + dashGroups.joined(separator: "─┼─") + "─┤"
        let bot = "└─" + dashGroups.joined(separator: "─┴─") + "─┘"

        var lines: [String] = [top]
        for (rowIdx, row) in rows.enumerated() {
            var line = "│ "
            for c in 0..<colCount {
                let cell = c < row.count ? row[c] : ""
                let pad = widths[c] - cell.count
                switch align {
                case Align.right:
                    line += String(repeating: " ", count: pad) + cell
                case Align.center:
                    let left = pad / 2
                    line += String(repeating: " ", count: left) + cell + String(repeating: " ", count: pad - left)
                default:
                    line += cell + String(repeating: " ", count: pad)
                }
                if c < colCount - 1 { line += " │ " }
            }
            line += " │"
            lines.append(line)
            if rowIdx < rows.count - 1 { lines.append(sep) }
        }
        lines.append(bot)
        return lines.joined(separator: "\n")
    }
}

// MARK: - Partial server-side include

/// Fetches the partial URL when it appears, then renders the response
/// body as another MicronView. Refresh timer if `refreshSeconds` set;
/// recursive partial uses default no-op fetcher to prevent loops.
private struct PartialBlockView: View {
    let block: Block.Partial
    let baseColor: Color
    let fetchPartial: (String, [String]) async -> String?

    @State private var content: String? = nil
    @State private var failed: String? = nil

    var body: some View {
        Group {
            if let sub = content {
                MicronView(source: sub)
            } else if let err = failed {
                Text("⚠ partial failed: \(err) (\(block.url))")
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(.red)
            } else {
                Text("⧖ Loading \(block.url)…")
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(baseColor.opacity(0.6))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 6)
                    .background(Color.gray.opacity(0.18))
            }
        }
        .task(id: "\(block.url)|\(block.refreshSeconds?.description ?? "")") {
            // Tight fetch loop with optional refresh; refresh < 1s
            // dropped at parse time per upstream MicronParser.py.
            while !Task.isCancelled {
                let res = await fetchPartial(block.url, block.fields)
                content = res
                failed = res == nil ? "no content" : nil
                guard let refresh = block.refreshSeconds else { break }
                try? await Task.sleep(nanoseconds: UInt64(refresh.doubleValue * 1_000_000_000))
            }
        }
    }
}

// MARK: - Horizontal rule

private struct HorizontalRuleView: View {
    let rune: Character

    var body: some View {
        if rune == "─" {
            Divider()
        } else {
            Text(String(repeating: String(rune), count: 48))
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(Color.gray.opacity(0.5))
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

// MARK: - Field rows under a block

/// Renders every `Inline.Field` inside [runs] beneath the paragraph's
/// text. TEXT → TextField (SecureField if masked), CHECKBOX → Toggle,
/// RADIO → custom Button row (since SwiftUI's Picker doesn't fit the
/// per-option-line micron model). Field state lives in the parent's
/// [fieldValues] binding so a Send link below collects current values.
private struct FieldRowsView: View {
    let runs: [Inline]
    @Binding var fieldValues: [String: String]

    var body: some View {
        let fields = runs.compactMap { $0 as? Inline.Field }
        if !fields.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                ForEach(0..<fields.count, id: \.self) { i in
                    fieldRow(fields[i])
                }
            }
        }
    }

    @ViewBuilder
    private func fieldRow(_ field: Inline.Field) -> some View {
        switch field.type {
        case FieldType.text:
            TextInputField(field: field, fieldValues: $fieldValues)
        case FieldType.checkbox:
            CheckboxField(field: field, fieldValues: $fieldValues)
        case FieldType.radio:
            RadioField(field: field, fieldValues: $fieldValues)
        default:
            EmptyView()
        }
    }
}

private struct TextInputField: View {
    let field: Inline.Field
    @Binding var fieldValues: [String: String]

    var body: some View {
        // Security S7 (v0.1.60): cap pasted input by byte size so a
        // hostile peer can't make us ship multi-MB form values.
        let maxBytes: Int = max(64, min(4096, Int(field.width) * 4))

        VStack(alignment: .leading, spacing: 2) {
            Text(field.name)
                .font(.caption)
                .foregroundStyle(.secondary)
            Group {
                if field.masked {
                    SecureField("", text: binding(maxBytes: maxBytes))
                } else {
                    TextField("", text: binding(maxBytes: maxBytes))
                }
            }
            .textFieldStyle(.roundedBorder)
            .autocorrectionDisabled(true)
            .textInputAutocapitalization(.never)
        }
    }

    private func binding(maxBytes: Int) -> Binding<String> {
        Binding(
            get: { fieldValues[field.name] ?? field.value },
            set: { incoming in
                if incoming.utf8.count <= maxBytes {
                    fieldValues[field.name] = incoming
                }
            }
        )
    }
}

private struct CheckboxField: View {
    let field: Inline.Field
    @Binding var fieldValues: [String: String]

    var body: some View {
        Toggle(isOn: binding) {
            Text(field.label.isEmpty ? field.name : field.label)
        }
    }

    /// v0.1.61 / Browser.py:226-241 — unchecked checkboxes are
    /// OMITTED from the submit dict, not sent as "". Removing the key
    /// when the user unchecks keeps the upstream-server `if "field_x"
    /// in env` test working.
    private var binding: Binding<Bool> {
        Binding(
            get: { fieldValues[field.name] != nil },
            set: { now in
                if now { fieldValues[field.name] = field.value }
                else   { fieldValues.removeValue(forKey: field.name) }
            }
        )
    }
}

private struct RadioField: View {
    let field: Inline.Field
    @Binding var fieldValues: [String: String]

    var body: some View {
        let selected = (fieldValues[field.name] ?? "") == field.value
        Button {
            fieldValues[field.name] = field.value
        } label: {
            HStack(spacing: 8) {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                    .foregroundStyle(selected ? Color.accentColor : .secondary)
                Text(field.label.isEmpty ? field.value : field.label)
                    .foregroundStyle(.primary)
                Spacer()
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Link rows under a block

/// Renders every `Inline.Link` in [runs] as a tappable row beneath
/// the paragraph. Plain links call onLinkClick. Links with `fields`
/// (form-submit links per `[Send`/page/post.mu`message]`) collect
/// values and call onLinkClickWithFields.
private struct LinkRowsView: View {
    let runs: [Inline]
    let fieldValues: [String: String]
    let onLinkClick: (String) -> Void
    let onLinkClickWithFields: (String, [String: String]) -> Void

    var body: some View {
        let links = runs.compactMap { $0 as? Inline.Link }
        if !links.isEmpty {
            VStack(alignment: .leading, spacing: 2) {
                ForEach(0..<links.count, id: \.self) { i in
                    linkRow(links[i])
                }
            }
        }
    }

    @ViewBuilder
    private func linkRow(_ link: Inline.Link) -> some View {
        // K/N exports `link.fields: [String]` already; the `as?` casts
        // were redundant and produced "always succeeds" warnings.
        let fields = link.fields
        let isPost = !fields.isEmpty
        // Label has to be a single expression — @ViewBuilder rejects
        // assignment statements inside the function body. The earlier
        // `let label: String; if … { label = … } else { label = … }`
        // tripped Swift's View synthesis ("buildExpression unavailable
        // — this expression does not conform to View").
        let label: String = isPost
            ? "↳ \(link.label)  →  \(link.target)  (POST: \(fields.joined(separator: ",")))"
            : "↳ \(link.label)  →  \(link.target)"
        Button {
            if isPost {
                onLinkClickWithFields(link.target, buildSubmitData(fields: fields))
            } else {
                onLinkClick(link.target)
            }
        } label: {
            Text(label)
                .font(.system(size: 12))
                .foregroundStyle(Color.accentColor)
                .padding(.leading, 4)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .buttonStyle(.plain)
    }

    /// Per upstream Browser.py:198-241 each entry is either:
    ///   `key=value` → `var_<key>` (URL-query param)
    ///   `<name>`    → `field_<name>` (form widget value); OMITTED if
    ///                 the widget is absent (unchecked checkbox /
    ///                 untouched radio) per Browser.py:226-241.
    private func buildSubmitData(fields: [String]) -> [String: String] {
        var out: [String: String] = [:]
        for entry in fields {
            if let eqIdx = entry.firstIndex(of: "="), entry.distance(from: entry.startIndex, to: eqIdx) > 0 {
                let k = String(entry[..<eqIdx])
                let v = String(entry[entry.index(after: eqIdx)...])
                out["var_\(k)"] = v
            } else {
                if let v = fieldValues[entry] {
                    out["field_\(entry)"] = v
                }
            }
        }
        return out
    }
}

// MARK: - Helpers

/// Build an inline AttributedString from a list of `Inline` runs,
/// honoring `Inline.Text.style` (bold/italic/underline/fg/bg) and
/// rendering `Inline.Link` runs as accent-colored underlined runs.
/// `Inline.Field` runs render as nothing inline (the actual input
/// widget appears below the paragraph in [FieldRowsView]); without
/// this skip the paragraph would carry redundant "[ name ]" text.
private func buildAttributedText(runs: [Inline], baseColor: Color, defaultBold: Bool) -> Text {
    var combined = Text("")
    for run in runs {
        if let t = run as? Inline.Text {
            combined = combined + applyStyle(Text(t.text), style: t.style, baseColor: baseColor, defaultBold: defaultBold)
        } else if let l = run as? Inline.Link {
            // Links use the accent color + underline regardless of the
            // run's own style (Android does the same — link styling
            // overrides run styling). Tap handling lives below in
            // LinkRowsView so the inline text just shows what the user
            // is being offered.
            combined = combined + Text(l.label)
                .foregroundColor(Color.accentColor)
                .underline()
                .fontWeight(defaultBold ? .bold : .regular)
        }
        // Inline.Field intentionally not appended.
    }
    return combined
}

private func applyStyle(_ text: Text, style: InlineStyle, baseColor: Color, defaultBold: Bool) -> Text {
    var out = text.foregroundColor(parseHexColor(style.fg, fallback: baseColor))
    if style.bold || defaultBold { out = out.bold() }
    if style.italic              { out = out.italic() }
    if style.underline           { out = out.underline() }
    return out
}

private func alignment(_ a: Align) -> HorizontalAlignment {
    switch a {
    case Align.left:   return .leading
    case Align.center: return .center
    case Align.right:  return .trailing
    default:           return .leading
    }
}

private func alignmentToFrame(_ a: Align) -> Alignment {
    switch a {
    case Align.left:   return .leading
    case Align.center: return .center
    case Align.right:  return .trailing
    default:           return .leading
    }
}

private func textAlign(_ a: Align) -> TextAlignment {
    switch a {
    case Align.left:   return .leading
    case Align.center: return .center
    case Align.right:  return .trailing
    default:           return .leading
    }
}

/// Parse a 3- or 6-digit hex color (no leading #). Returns [fallback]
/// on any malformed input. Mirrors the Android `parseHexColor` so a
/// page rendered on Android and iOS shows the same accent / heading
/// tints.
private func parseHexColor(_ code: String?, fallback: Color) -> Color {
    guard let code = code, !code.isEmpty else { return fallback }
    let r: Int
    let g: Int
    let b: Int
    let chars = Array(code)
    if chars.count == 3 {
        guard let rh = Int(String(chars[0]), radix: 16),
              let gh = Int(String(chars[1]), radix: 16),
              let bh = Int(String(chars[2]), radix: 16) else { return fallback }
        r = rh * 0x11; g = gh * 0x11; b = bh * 0x11
    } else if chars.count == 6 {
        guard let rh = Int(String(chars[0..<2]), radix: 16),
              let gh = Int(String(chars[2..<4]), radix: 16),
              let bh = Int(String(chars[4..<6]), radix: 16) else { return fallback }
        r = rh; g = gh; b = bh
    } else {
        return fallback
    }
    return Color(red: Double(r) / 255.0, green: Double(g) / 255.0, blue: Double(b) / 255.0)
}
