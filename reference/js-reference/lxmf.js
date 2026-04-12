// js/lxmf.js — LXMF message pack/unpack.
//
// LXMF packed format (before encryption):
//   destination_hash(16) + source_hash(16) + signature(64) + msgpack(payload)
//
// For single-packet opportunistic delivery, destination_hash is stripped
// (inferred from the RNS packet header). So on-wire LXMF payload is:
//   source_hash(16) + signature(64) + msgpack(payload)
//
// Payload msgpack array: [timestamp, title_bytes, content_bytes, fields_dict]
// Optional 5th element: stamp (proof-of-work, ignored for now)

'use strict';

import { encode as msgpackEncode, decode as msgpackDecode } from '@msgpack/msgpack';
import { Identity, sha256 } from './identity.js';
import { concatBytes } from './announce.js';
import { TRUNCATED_HASHLENGTH, SIGLENGTH } from './reticulum.js';

const DESTINATION_LENGTH = 16;
const SIGNATURE_LENGTH   = 64;

// ---- Unpack an LXMF message (after decryption) -----------------------

// Unpack from the on-wire format (destination_hash already known from RNS header):
//   source_hash(16) + signature(64) + msgpack(payload)
export async function unpackMessage(data, destHash) {
  if (data.length < TRUNCATED_HASHLENGTH + SIGNATURE_LENGTH + 1) {
    throw new Error('LXMF message too short');
  }

  const sourceHash = data.subarray(0, TRUNCATED_HASHLENGTH);
  const signature  = data.subarray(TRUNCATED_HASHLENGTH, TRUNCATED_HASHLENGTH + SIGNATURE_LENGTH);
  const msgpackData = data.subarray(TRUNCATED_HASHLENGTH + SIGNATURE_LENGTH);

  // Decode msgpack payload
  const payload = msgpackDecode(msgpackData);

  if (!Array.isArray(payload) || payload.length < 4) {
    throw new Error('Invalid LXMF payload structure');
  }

  const timestamp = payload[0];
  const title     = decodeField(payload[1]);
  const content   = decodeField(payload[2]);
  const fields    = payload[3] || {};
  const stamp     = payload.length > 4 ? payload[4] : null;

  // Upstream LXMF computes the signature hash over a re-encoded msgpack
  // containing only the first four elements of the payload (the stamp,
  // if present, is explicitly excluded). We build two candidate hashes:
  // one over a re-encoded stamp-stripped payload, and one over the raw
  // on-wire msgpack bytes as a fallback. verifyMessageSignature will
  // try both because some upstream builds append the stamp as a
  // trailing blob AFTER the msgpack instead of as a 5th array element,
  // and the byte-level re-encode can also drift if our msgpack encoder
  // chooses a different numeric width than the sender's.
  const strippedMsgpack = new Uint8Array(msgpackEncode([timestamp, payload[1], payload[2], fields]));
  const hashedStripped = concatBytes([destHash, sourceHash, strippedMsgpack]);
  const hashStripped   = await sha256(hashedStripped);
  const hashedOriginal = concatBytes([destHash, sourceHash, msgpackData]);
  const hashOriginal   = await sha256(hashedOriginal);

  return {
    sourceHash,
    signature,
    timestamp,
    title,
    content,
    fields,
    stamp,
    destHash,
    msgpackData,
    // Primary hashedPart / messageHash preserve the old field names so
    // verifyMessageSignature's first-pass call still works.
    hashedPart: hashedStripped,
    messageHash: hashStripped,
    msgpackForHash: strippedMsgpack,
    // Fallback view for the "no stamp stripping" variant.
    hashedPartOriginal: hashedOriginal,
    messageHashOriginal: hashOriginal,
    payloadElementCount: payload.length,
  };
}

// Unpack an LXMF message received over an established Link.
//
// Unlike opportunistic delivery (which strips the destination hash because
// it is inferred from the RNS packet header), link-delivered LXMF includes
// the full container:
//   destination_hash(16) + source_hash(16) + signature(64) + msgpack(payload)
// The leading destination hash comes from inside the link ciphertext, not
// from the outer packet header (which carries the link_id instead).
export async function unpackLinkMessage(data) {
  if (data.length < 2 * TRUNCATED_HASHLENGTH + SIGNATURE_LENGTH + 1) {
    throw new Error('LXMF link message too short');
  }
  const destHash = data.subarray(0, TRUNCATED_HASHLENGTH);
  const inner = data.subarray(TRUNCATED_HASHLENGTH);
  return unpackMessage(inner, destHash);
}

// Verify LXMF message signature using the sender's public key.
// Tries the stamp-stripped-and-re-encoded view first (upstream LXMF
// spec behavior), and if that fails falls back to signing over the
// raw on-wire msgpack bytes. Returns an object describing which
// variant matched, or {ok: false} if neither did.
export function verifyMessageSignature(message, senderIdentity) {
  const strippedSigned = concatBytes([message.hashedPart, message.messageHash]);
  if (senderIdentity.verify(message.signature, strippedSigned)) {
    return { ok: true, variant: 'stripped' };
  }
  if (message.hashedPartOriginal && message.messageHashOriginal) {
    const originalSigned = concatBytes([message.hashedPartOriginal, message.messageHashOriginal]);
    if (senderIdentity.verify(message.signature, originalSigned)) {
      return { ok: true, variant: 'original' };
    }
  }
  return { ok: false };
}

// ---- Pack an outbound LXMF message -----------------------------------

export async function packMessage(sourceIdentity, destHash, sourceHash, title, content, fields = {}) {
  const titleBytes   = new TextEncoder().encode(title || '');
  const contentBytes = new TextEncoder().encode(content || '');
  const timestamp    = Date.now() / 1000;  // float seconds

  // Msgpack encode payload
  const msgpackData = new Uint8Array(msgpackEncode([timestamp, titleBytes, contentBytes, fields]));

  // Compute message hash
  const hashedPart = concatBytes([destHash, sourceHash, msgpackData]);
  const messageHash = await sha256(hashedPart);

  // Sign: signed_data = hashed_part + message_hash
  const signedData = concatBytes([hashedPart, messageHash]);
  const signature = sourceIdentity.sign(signedData);

  // On-wire format (destination stripped for opportunistic single-packet):
  //   source_hash(16) + signature(64) + msgpack(payload)
  return concatBytes([sourceHash, signature, msgpackData]);
}

// ---- Helpers ---------------------------------------------------------

function decodeField(val) {
  if (val instanceof Uint8Array || val instanceof ArrayBuffer) {
    return new TextDecoder().decode(val);
  }
  if (typeof val === 'string') return val;
  if (val === null || val === undefined) return '';
  return String(val);
}
