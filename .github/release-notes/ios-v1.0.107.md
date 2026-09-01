You can share a Relay Chat room from iOS.

1.0.106 made iOS able to *act* on a room link. It still could not make one — `RoomsView` had no share affordance, so the format was read-only on this platform. A share button now sits in the room toolbar next to Rejoin and the member list.

The sheet mirrors Android's, including the two things about it that are not cosmetic:

- **Both ways out are offered, and sending is why it exists.** An LXMF direct message to a contact stays on the mesh; copying hands the link to a channel this app has no opinion about. Copy sits first because it is one tap and always available, with the contact list under it. A contact row disables itself once sent, so a second tap cannot queue the same link twice.
- **When no correct link can be written, the sheet offers neither action and says which of the two reasons applies.** A malformed hub hash is `rrc-room-links.md` §2.1 — a writer that does not know its own destination hash must emit no link rather than a partial one, because a broken link gets pasted onward as though it worked. A room name containing a space is §2.2, and that one the reader can act on: the room segment is literal and a link in running text ends at the first space, so the link would open a shorter, different room. Saying so turns a dead button into a rename.

`roomShareLink` returns its answer straight from the shared `RrcRoomLink.build`, so both platforms refuse to write exactly the same links. The link is sent as a plain text body rather than a new field or message type — the format is text precisely so a client that has never heard of it still shows something a person can read and copy.

Android-only remaining in Rooms: create-field room-name validation.
