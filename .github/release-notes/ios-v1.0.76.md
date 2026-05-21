## Highlights

- **Tap-back reactions render horizontally** (iMessage-style) — the long-press emoji picker was stacking the six reactions in a vertical list because the context menu wasn't using SwiftUI's `ControlGroup`. Wrapping the reaction `ForEach` in a `ControlGroup` makes SwiftUI lay them out as a compact horizontal row at the top of the menu.
- **Copy action on long-press** — the bubble's outer context menu was capturing the long-press before iOS's standard text-selection handles could appear, so users couldn't copy a message's text. Added an explicit `Copy` button (only shown when the row carries text content; hidden for image-only or file-only bubbles) that copies the full message to the clipboard. Power users who want partial selection can still long-press the text inside the `textSelection(.enabled)` Text view — the explicit Copy button covers the common case.

iOS-only — Android already had both behaviours.
