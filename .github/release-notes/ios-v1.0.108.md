A room name that will not work is now refused where you type it, not discovered later.

`RrcRoomName` has been shared code since the Android 1.2.112 line; iOS never called it. Typing `off topic` into the join field produced a room the hub accepted and this client could not later share — a link's room segment is literal and a link in running text ends at the first space, so there is no way to write that room down. The only warning came at the point of sharing, long after the room existed.

Join is now disabled while a problem stands, with the reason under the field. The strings come from the shared `problem()`, so both platforms say the same thing.

**Where the rule does not apply matters more than the rule.** Typing a name in this field creates the room when the hub does not have it, which is the one place a local naming preference is legitimate. The browse sheet and shared room links reach rooms that *already exist* and are deliberately unfiltered: a room called `off topic` that somebody else made has to stay reachable from here, or a cosmetic preference becomes an interop bug — rooms that exist, that other clients are sitting in, silently unreachable on this one.

With this, the iOS Rooms parity list from 1.0.104 is empty: routing and linkifying (1.0.106), the share sheet (1.0.107), and name validation (this).
