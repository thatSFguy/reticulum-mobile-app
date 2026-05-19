## Highlights

- **File attachments** — the "+" button in a conversation now picks any file (not just images) and sends it as an LXMF `FIELD_FILE` attachment.
- **Image resolution tiers** — when sending an image you can choose Small / Medium / Large / Original; large images are stored full-resolution off-row.
- **Off-row attachment store** — attachments live as files alongside the SQLite DB (capped at 4 MB per attachment); the conversation list and search no longer have to walk image bytes. Includes delete-path cleanup and a startup orphan sweep.
- **Sideband large images display** — inbound `FIELD_IMAGE` ceiling raised from 32 KB to 512 KB, so images from Sideband peers render instead of being silently dropped.
- **Link-delivered LXMF re-verify** — signature is re-verified on link-delivered messages, not only opportunistic, closing a gap where a malformed link-delivered payload could surface without the same checks.
- **Resource §10 retransmit fixes** — three SPEC-cited fixes to the outbound Resource sender:
  - never bundle parts into an exhausted `RESOURCE_REQ` window (§10.7)
  - bound the `RESOURCE_REQ` window to the sender's serve guard (§10.6)
  - retransmit watchdog for lost `RESOURCE_REQ` / part / HMU
- **iOS build fix** — `NSNumber.numberWithBool` K/N Foundation binding workaround so the iosArm64 target compiles.
