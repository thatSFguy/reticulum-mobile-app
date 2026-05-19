# Attachment store proposal

**Status:** fully shipped — Phases 1, 2 and 3 (2026-05-19). Captures
the "full-res images don't come through" limitation (raised
2026-05-19 as a follow-up to the `android-v1.2.3` inbound-image-cap
fix) and the design that removed it.

Cross-platform: a shared `commonMain` model + storage interface, with
Android (Room) and iOS (SQLDelight) persistence each needing one
additive migration. iOS is SwiftUI, Android is Compose — reproduce the
*behaviour* on each.

---

## 1. The problem

Received LXMF attachments — both `FIELD_IMAGE` (key 6) and
`FIELD_FILE_ATTACHMENTS` (key 5) — are stored as raw `ByteArray` BLOB
columns directly on the `StoredMessage` row (`imageBytes`,
`attachmentBytes`).

- Android reads message rows through a Room `Cursor` backed by a
  `CursorWindow` hard-capped at **2 MB per row**. A row carrying a
  multi-MB attachment blob throws `Row too big to fit into
  CursorWindow` and the whole conversation query fails.
- That ceiling is why every inbound-attachment cap is small:
  `INBOUND_IMAGE_MAX_BYTES` (512 KB, was 32 KB pre-`v1.2.3`),
  `INBOUND_FILE_MAX_BYTES` (256 KB), `RRC_MAX_RESOURCE_BYTES` (256 KB).
- Net effect: a sender that transmits a multi-MB image (e.g. Sideband
  at higher quality) has it dropped on receive by `extractImageField`;
  an image-only message then renders as a blank bubble.

The receive path does **not** degrade attachments — what's stored is
exactly the sender's bytes. So "get the full-res image" means "stop
capping/dropping large attachments", which the blob-in-row
architecture cannot do.

## 2. Wire formats are unchanged

This is a **local-persistence** refactor only. The Reticulum Resource
layer (`SPEC.md` §10) and the LXMF `FIELD_IMAGE` / `FIELD_FILE_ATTACHMENTS`
wire formats (§5.9) are untouched — inbound parsing already assembles
them correctly. The Resource layer already supports
`MAX_MULTISEGMENT_BYTES` = 8 MB, so transport is not the limiter once
storage is fixed. No wire-compat risk; CLAUDE.md RULE #1 (spec-read
before wire code) does not apply — nothing on the wire changes.

## 3. Design — a shared attachment store

Move attachment bytes off the DB row onto app-private file storage;
the row keeps only a small opaque reference.

### 3.1 `AttachmentStore` — commonMain `expect` / per-platform `actual`

```
expect class AttachmentStore {
    suspend fun put(bytes: ByteArray): String      // returns an opaque token
    suspend fun load(token: String): ByteArray?
    fun pathFor(token: String): String?            // absolute path, for native decoders
    suspend fun delete(token: String)
    suspend fun sweep(liveTokens: Set<String>)     // orphan GC
}
```

- **Android actual** — files under `context.filesDir/attachments/`.
- **iOS actual** — files under Application Support `/attachments/`
  (not Caches — Caches can be purged; mark the dir
  `isExcludedFromBackup`).
- Token = a generated id (UUID hex, or the SHA-256 of the bytes — see
  §5). `pathFor` lets the UI hand a path straight to
  `BitmapFactory.decodeFile` / `UIImage(contentsOfFile:)` without
  pulling the whole blob across the KMP↔Swift bridge.
- Wired into `ReticulumEngine` via the engine factory, alongside the
  existing crypto / storage actuals (`IosEngineFactory`, the Android
  service factory).

### 3.2 `StoredMessage` model — additive token refs

Add nullable columns, keep the old blob columns (see §3.3):

```
imageToken: String?,  imageSize: Int?,
attachmentToken: String?, attachmentSize: Int?
// attachmentName stays; imageBytes / attachmentBytes stay (legacy read)
```

### 3.3 Migration — additive, no data movement

Room and SQLDelight migrations are SQL-only and cannot do file I/O, so
**do not move existing blobs**. Instead:

- One additive migration each — Android Room `MIGRATION_15_16`
  (`@Database version` 15 → 16); iOS SQLDelight `7.sqm`. Both just
  `ALTER TABLE ... ADD COLUMN` the four nullable columns.
- **Dual-read:** the bubble renderer prefers `*Token` (load from file);
  if null it falls back to the legacy `*Bytes` blob. Existing rows
  keep their ≤512 KB blobs — those are already CursorWindow-safe, so
  nothing breaks and they age out naturally.
- **Write path always uses tokens.** The old blob columns are never
  written again; they can be dropped in a much later release or left
  as dead columns (harmless).

This is the lowest-risk migration shape: purely additive, no one-shot
data-move pass, no destructive step.

### 3.4 Receive paths

The three inbound sites — `handleLinkLxmf`, the opportunistic
`handleData` LXMF branch, and the `PropagationClient` drain — currently
do `extractImageField` → `imageBytes` → `messageRepo.save(...)`.
Change to: extract bytes → `attachmentStore.put(bytes)` → token →
`save(StoredMessage(imageToken = token, imageSize = n, ...))`. Same for
`extractFileAttachments`.

### 3.5 Send path

The sender persists its own attachment on its row (so the sender's
bubble shows it). Same change — `put` to the store, save the token.
Outbound images are ≤20 KB today (the `ImageCompress` ladder), but
routing them through the store keeps one code path.

### 3.6 UI — downsampled decode is now mandatory

A 4 MB JPEG full-decodes to a ~40–50 MB bitmap; a conversation list of
them OOMs. Decode **downsampled to the bubble size** for the timeline,
full-res only in the existing zoom / full-screen viewer.

- Android — `BitmapFactory.Options` two-pass (`inJustDecodeBounds`
  then `inSampleSize`), decoding from the file path.
- iOS — `CGImageSourceCreateThumbnailAtIndex` (ImageIO) from the file
  URL; `kCGImageSourceThumbnailMaxPixelSize` at the bubble's max
  dimension.

### 3.7 Delete + orphan GC

`deleteMessagesForDestination` and `deleteDestinationAndMessages` must
`attachmentStore.delete(token)` for every affected row, else files
leak. A startup `sweep(liveTokens)` (collect all tokens still
referenced by a row, delete any file not in the set) is the
belt-and-braces backstop for crashes between row-delete and file-delete.

### 3.8 New cap

With bytes off the DB row, the limiter becomes transport + decode, not
CursorWindow. Replace the three small caps with one
`INBOUND_ATTACHMENT_MAX_BYTES` — proposed **4 MB** (a full-res phone
JPEG is ~2–5 MB; the Resource hard ceiling is 8 MB). Oversize still
logs + drops — the hostile-peer / disk-starvation defense is kept,
just at a realistic threshold. Note: multi-MB transfers are infeasible
over a LoRa link (minutes per image, likely timeout) — full-res is in
practice a TCP-path feature.

## 4. Phasing

- **Phase 1 — plumbing, no behaviour change.** `AttachmentStore`
  `expect`/`actual` + engine-factory wiring + round-trip tests. The
  `StoredMessage` token columns + both additive DB migrations. Repos
  dual-read. Nothing visibly changes yet.
- **Phase 2 — "it works".** Receive + send paths write to the store;
  raise the cap to `INBOUND_ATTACHMENT_MAX_BYTES`; bubble renderers
  load via token (legacy-blob fallback) with downsampled decode. Large
  images now display. Migration tests (Room `room-testing` + the
  exported schema; SQLDelight verify).
- **Phase 3 — polish.** Delete-path file cleanup + startup orphan
  sweep; full-res zoom decodes from the file; retire the dead
  `INBOUND_IMAGE_MAX_BYTES` / `INBOUND_FILE_MAX_BYTES` constants.

## 5. Open questions

1. **Token scheme** — random UUID (simple, one file per message) vs
   content-addressed SHA-256 (dedups identical forwarded images, but
   needs refcounting before delete). Recommend UUID for v1 — simpler,
   and dedup is a marginal win for a messenger.
2. **Outbound bytes** — keep routing the sender's ≤20 KB images
   through the store for one code path, or leave small outbound images
   as blobs? Recommend store, for uniformity.
3. **Drop the legacy blob columns?** — leaving them is harmless;
   dropping needs a second, destructive-ish migration. Recommend leave.
4. **Cap value** — 4 MB proposed; confirm against the largest image
   Sideband / Columba realistically emit at max quality.
