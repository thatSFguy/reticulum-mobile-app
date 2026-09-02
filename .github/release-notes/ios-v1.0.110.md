A tapped link no longer leaves the mesh, or joins a stranger's hub, without asking.

Both come out of a full security audit run against `5da60a9` (six surfaces; the report lives outside the repo). The theme is that everything added recently made **content someone else authored tappable** — message bodies, Relay Chat room links, page link labels — and iOS was the platform missing the guardrails.

- **A web link in a message opened straight into Safari.** No prompt, on either the conversation or the rooms surface. This is the one tap that leaves the mesh, and the server on the other end was chosen by the sender: opening it hands them your real IP, ISP and rough location, tied to the identity they messaged. Android has asked first since the July audit; the asymmetry was even written into the shared code, and iOS simply never got the other half. Now both surfaces park the URL for a confirmation, from one shared modifier so the two cannot drift again.

- **A Relay Chat link joined a hub you had never connected to, permanently.** One tap wrote the hub, marked the room joined, and opened a session — and because the room is stored as joined, the app reconnected to that hub at every launch afterwards, until you found the hub list and deleted it. A hub you have not connected to before now asks first, on both platforms. A hub already in your list does not re-ask. The bare `rrc://<hash>` form, which writes the same row, is gated the same way.

- **The page cache had no limit of any kind** — no row cap, no size cap, and nothing that ever removed anything. Now a page above 256 KB is not cached (it still loads and renders), the cache keeps its 500 most recent pages, and an install that already grew is trimmed at startup.

- **A name taken from a link's own label skipped the sanitiser** that announced names go through, so it could carry newlines into your node lists. Fixing that turned up the sanitiser's own gap: it stripped control characters but never bidirectional formatting characters, which means announced names had the same hole. An override reverses the display order of what follows it, so a name can be made to read as something it is not. Both are stripped now — the directional set only, because the joiner that holds multi-part emoji together is technically the same class of character and people put emoji in their names.

- Link handling in message bodies now goes through the shared parser instead of a second, weaker copy that skipped the path checks every other surface applies.

Android was verified on-device against the live mesh: pages fetch and render, the cache takes the right branch, and startup eviction runs clean. The iOS build is compile-verified only.
