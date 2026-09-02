Nodes reached by tapping a link show their real name.

Following a cross-node link called `addManualDestination(hash, "(via cross-node link)")`, and that function writes its label to `userLabel` — the user's *private nickname*. `effectiveDisplayName` is `userLabel ?: displayName`, so a user label outranks everything. The node's announce then arrived, the announce path filled `displayName` in with the real name exactly as it should, and the list went on showing the placeholder. Not until the announce landed — forever, because nothing ever cleared it.

Three parts:

- **`addLinkedDestination`** — a separate entry point for a destination discovered by following something. It puts the name in `displayName` and never touches `userLabel`, so the first real announce replaces it. The same shortcut was in the QR-scan path and in three other link sites.
- **The hint is the link's own label.** A micron link already carries the page author's words for the thing — "Amber Pages", "NomadForum" — so `onLinkClick` now carries `(target, label)` and the cross-node hop names the row with it. On iOS the label rides an extra query item on the internal link URL, which is the only channel between the renderer and the tap handler.
- **A repair migration** (SQLDelight 12.sqm, Room v22→v23 on Android) clearing `userLabel` on rows carrying one of the four exact strings we wrote. Without it the fix would only help rows created from here on.

Also: nodes you merely pass through on the way to a page are no longer added to Favourites. Adding one by hand is an act of intent worth pinning; walking past one is not, and pinning those is what filled the lists. The new `upsertLinkedStub` inserts without favouriting and, on a row that already exists, changes nothing but `hidden` — so following a link to a node you *have* favourited does not un-favourite it.

Verified on Android against the live mesh: a search for "via" in the Nomad list returns only genuine names, and a cross-node hop lands on the node's announced name. The iOS build is compile-verified only.
