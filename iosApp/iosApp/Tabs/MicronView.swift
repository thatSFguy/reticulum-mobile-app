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
            Text(buildAttributedString(runs: runs, baseColor: baseColor, defaultBold: true))
                .font(.system(size: size, weight: .medium))
                .frame(maxWidth: .infinity, alignment: alignmentToFrame(block.align))
                .multilineTextAlignment(textAlign(block.align))
                .environment(\.openURL, makeMicronLinkAction(
                    fieldValues: fieldValues,
                    onLinkClick: onLinkClick,
                    onLinkClickWithFields: onLinkClickWithFields,
                ))
            FieldRowsView(runs: runs, fieldValues: $fieldValues)
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
            Text(buildAttributedString(runs: runs, baseColor: baseColor, defaultBold: false))
                .font(.system(size: 14))
                .foregroundStyle(baseColor)
                .frame(maxWidth: .infinity, alignment: alignmentToFrame(block.align))
                .multilineTextAlignment(textAlign(block.align))
                .textSelection(.enabled)
                .environment(\.openURL, makeMicronLinkAction(
                    fieldValues: fieldValues,
                    onLinkClick: onLinkClick,
                    onLinkClickWithFields: onLinkClickWithFields,
                ))
            FieldRowsView(runs: runs, fieldValues: $fieldValues)
        }
    }
}

// MARK: - Literal pre-formatted block

private struct LiteralBlockView: View {
    let block: Block.Literal
    let baseColor: Color

    /// Base monospace size for non-art Literal blocks. ASCII-art
    /// blocks downscale from here to fit the device width.
    private let baseSize: CGFloat = 13
    /// Legibility floor — below this the text becomes unreadable
    /// even on a Retina display. Matches the Android renderer.
    private let minSize: CGFloat = 6
    /// `.font(.system(... design: .monospaced))` on iOS sits at
    /// ~0.6 em per glyph, same as Compose's `FontFamily.Monospace`.
    private let monospaceEmRatio: CGFloat = 0.6

    var body: some View {
        // ASCII-art autoshrink: NomadNet pages frequently lead with
        // 80-col banner art that wraps and loses its shape at phone
        // width. When the shared heuristic flags a Literal block as
        // banner art, downscale the monospace size so the widest
        // line fits the available width; clamp to a 6 pt floor for
        // legibility. Detection lives in commonMain so both
        // platforms agree on what counts as art.
        let lines = block.lines
        let isArt = AsciiArtDetectKt.isAsciiArtBlock(lines: lines)
        let maxLen = CGFloat(AsciiArtDetectKt.maxLineLength(lines: lines))
        let joined = lines.joined(separator: "\n")

        Group {
            if isArt && maxLen > 0 {
                GeometryReader { geo in
                    let charWidthAtBase = baseSize * monospaceEmRatio
                    let maxLineWidthAtBase = maxLen * charWidthAtBase
                    let availableWidth = max(0, geo.size.width - 16) // padding-inclusive
                    let scale: CGFloat = (maxLineWidthAtBase > availableWidth && availableWidth > 0)
                        ? availableWidth / maxLineWidthAtBase
                        : 1.0
                    let shrunkSize = max(minSize, baseSize * scale)
                    Text(joined)
                        .font(.system(size: shrunkSize, design: .monospaced))
                        .foregroundStyle(baseColor)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .textSelection(.enabled)
                }
                // GeometryReader expands to fill — give it a
                // line-count-derived height so the page scroller
                // doesn't see a zero-height block.
                .frame(height: CGFloat(lines.count) * baseSize * 1.25)
            } else {
                Text(joined)
                    .font(.system(size: baseSize, design: .monospaced))
                    .foregroundStyle(baseColor)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(Color.gray.opacity(0.18))
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

// MARK: - Helpers

/// Custom URL scheme used to carry micron link taps from
/// `AttributedString.link` into our `OpenURLAction` handler.
/// `nomad-get://n?t=<target>` for plain navigation links;
/// `nomad-post://n?t=<target>&f=<field>&f=<field>...` for form-
/// submit links. The handler decodes `t` + `f` query items and
/// dispatches to onLinkClick / onLinkClickWithFields with the live
/// fieldValues dict — so values reflect what the user typed at tap
/// time, not at render time.
private let micronLinkSchemeGet  = "nomad-get"
private let micronLinkSchemePost = "nomad-post"

/// Build an inline AttributedString from a list of `Inline` runs,
/// honoring `Inline.Text.style` (bold/italic/underline/fg/bg) and
/// wrapping `Inline.Link` runs in a `link` attribute so Compose-equivalent
/// inline tap dispatch works (SwiftUI's `Text(AttributedString)` makes
/// link spans tappable and routes through `\.openURL`).
/// `Inline.Field` runs render as nothing inline (the actual input
/// widget appears below the paragraph in [FieldRowsView]).
///
/// Pre-fix MicronView rendered links twice: once inline as styled-but-
/// inert text and again as a separate tappable row beneath the
/// paragraph. Users tapped the underlined inline text, nothing
/// happened, and the actual control was a few lines down. Surfacing
/// link taps inline matches what every browser does and removes the
/// double-render entirely.
private func buildAttributedString(runs: [Inline], baseColor: Color, defaultBold: Bool) -> AttributedString {
    var combined = AttributedString()
    for run in runs {
        if let t = run as? Inline.Text {
            var part = AttributedString(t.text)
            applyStyle(&part, style: t.style, baseColor: baseColor, defaultBold: defaultBold)
            combined.append(part)
        } else if let l = run as? Inline.Link {
            var part = AttributedString(l.label)
            // Link styling: accent + underline + (inherited) bold for
            // headings. inlinePresentationIntent.stronglyEmphasized
            // respects the surrounding Text's `.font(.system(size:))`
            // size — setting `part.font = .system(size: N).bold()`
            // would shrink links in h1/h2 down to a fixed size.
            part.foregroundColor = Color.accentColor
            part.underlineStyle = .single
            if defaultBold { part.inlinePresentationIntent = .stronglyEmphasized }
            part.link = micronLinkURL(target: l.target, fields: l.fields)
            combined.append(part)
        }
        // Inline.Field intentionally not appended.
    }
    return combined
}

/// Build the custom-scheme URL we plant in an `AttributedString.link`
/// attribute. URLComponents handles percent-escaping so targets with
/// `:`, `/`, `=`, `&`, or `?` survive a round-trip through the
/// SwiftUI link-tap pipeline.
private func micronLinkURL(target: String, fields: [String]) -> URL? {
    var comps = URLComponents()
    comps.scheme = fields.isEmpty ? micronLinkSchemeGet : micronLinkSchemePost
    comps.host = "n"
    var items: [URLQueryItem] = [URLQueryItem(name: "t", value: target)]
    for f in fields {
        items.append(URLQueryItem(name: "f", value: f))
    }
    comps.queryItems = items
    return comps.url
}

/// Build an OpenURLAction that decodes our micron-link custom URLs
/// and dispatches to the right callback with the live fieldValues
/// dict. Returns `.systemAction` for anything that isn't one of our
/// schemes so the caller (or the system) can still handle other URLs.
private func makeMicronLinkAction(
    fieldValues: [String: String],
    onLinkClick: @escaping (String) -> Void,
    onLinkClickWithFields: @escaping (String, [String: String]) -> Void,
) -> OpenURLAction {
    OpenURLAction { url in
        guard url.scheme == micronLinkSchemeGet || url.scheme == micronLinkSchemePost,
              let comps = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let target = comps.queryItems?.first(where: { $0.name == "t" })?.value
        else {
            return .systemAction
        }
        if url.scheme == micronLinkSchemePost {
            let fields = comps.queryItems?
                .filter { $0.name == "f" }
                .compactMap { $0.value } ?? []
            onLinkClickWithFields(target, buildSubmitData(fields: fields, fieldValues: fieldValues))
        } else {
            onLinkClick(target)
        }
        return .handled
    }
}

/// Per upstream Browser.py:198-241 each entry is either:
///   `key=value` → `var_<key>` (URL-query param)
///   `<name>`    → `field_<name>` (form widget value); OMITTED if
///                 the widget is absent (unchecked checkbox /
///                 untouched radio) per Browser.py:226-241.
private func buildSubmitData(fields: [String], fieldValues: [String: String]) -> [String: String] {
    var out: [String: String] = [:]
    for entry in fields {
        if let eqIdx = entry.firstIndex(of: "="), entry.distance(from: entry.startIndex, to: eqIdx) > 0 {
            let k = String(entry[..<eqIdx])
            let v = String(entry[entry.index(after: eqIdx)...])
            out["var_\(k)"] = v
        } else if let v = fieldValues[entry] {
            out["field_\(entry)"] = v
        }
    }
    return out
}

private func applyStyle(_ part: inout AttributedString, style: InlineStyle, baseColor: Color, defaultBold: Bool) {
    part.foregroundColor = parseHexColor(style.fg, fallback: baseColor)
    // Use InlinePresentationIntent — these are "logical" weight/style
    // markers that the renderer composes with the surrounding Text's
    // `.font(.system(size:))`, so headings stay h-sized when a span
    // is bold/italic. Setting `part.font = ...` with an explicit size
    // would clobber the heading's size for that span.
    var intent: InlinePresentationIntent = []
    if style.bold || defaultBold { intent.insert(.stronglyEmphasized) }
    if style.italic              { intent.insert(.emphasized) }
    if !intent.isEmpty           { part.inlinePresentationIntent = intent }
    if style.underline           { part.underlineStyle = .single }
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
