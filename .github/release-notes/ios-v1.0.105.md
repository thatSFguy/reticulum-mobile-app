Forms on NomadNet pages send what you typed, pages gained anchors and working partials, and Relay Chat room links moved to the format NomadNet reads.

**Forms.** A form link's field list has a third kind of entry this app did not implement: a bare `*`, meaning "every widget on the page" (`Browser.py:222`, `:246`). It is the shape real pages use, because it is what an author writes once and never revisits as the form grows fields. Without it the app posted the link's own `var_` parameters and nothing the user typed — no error at any layer, just a page that came back blank. The rules now live in a shared `buildFormSubmitData` that both platforms call, so the two submit byte-identical dicts.

**Partials never worked on iOS at all.** `NomadView` passed no fetcher, so every `` `{…} `` placeholder on every page sat at "⧖ Loading…" forever while Android rendered it — the live chat and status panels real community pages are built out of. They now load, and a partial's field list gets the same treatment a form link's does, `*` included, so a panel can be scoped by a box the reader typed into.

**Anchors.** `` `:name `` declarations and heading auto-slugs, with `#name` links scrolling to them. Dispatched before destination parsing and before any form POST, as upstream does, so an anchor tap never leaves the device. One divergence: SwiftUI exposes no scroll offset, so a bare `#` scrolls to the first heading rather than the next one below the current position.

**Checkboxes** sharing a field name comma-join per `Browser.py:255-266` instead of toggling each other.

**Relay Chat room links now use NomadNet's grammar** — `rrc://<32hex>/<room>`. v1 of the format was written on the belief that an `rrc://` scheme would be an invention; NomadNet 1.2.8 had shipped exactly that five weeks earlier, along with a full RRC client, and had claimed the `rrc@` shorthand for a different payload grammar. A v1 link does not fail there — it resolves the hub and yields a room named `room/<x>`, which a hub creates without complaint. Links in the old format are still read.

Still Android-only in Rooms: the share sheet, message-text linkifying, and create-field name validation. iOS understands a room link underneath but has nowhere to make one.
