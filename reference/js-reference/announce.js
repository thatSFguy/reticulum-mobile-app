// js/announce.js — Reticulum announce build/parse/validate.
//
// Announce data layout (after Reticulum header):
//   public_key(64) + name_hash(10) + random_hash(10) +
//   [ratchet(32) if context_flag] + signature(64) + [app_data]
//
// Signed data = dest_hash + public_key + name_hash + random_hash + [ratchet] + app_data

'use strict';

import { ed25519 } from '@noble/curves/ed25519';
import { decode as msgpackDecode } from '@msgpack/msgpack';
import { Identity, computeDestinationHash, computeNameHash, sha256, truncatedHash } from './identity.js';
import { KEYSIZE, SIGLENGTH, NAME_HASH_LENGTH, TRUNCATED_HASHLENGTH } from './reticulum.js';
import { toHex } from './kiss.js';

// Parse an announce packet's payload (data after Reticulum header).
// Returns { publicKey, nameHash, randomHash, ratchet, signature, appData, identityHash, destHash }
export async function parseAnnounce(payload, contextFlag, destHashFromHeader) {
  if (payload.length < KEYSIZE + NAME_HASH_LENGTH + NAME_HASH_LENGTH + SIGLENGTH) {
    return null;  // too short
  }

  let offset = 0;
  const publicKey = payload.subarray(offset, offset + KEYSIZE); offset += KEYSIZE;
  const nameHash  = payload.subarray(offset, offset + NAME_HASH_LENGTH); offset += NAME_HASH_LENGTH;
  const randomHash = payload.subarray(offset, offset + NAME_HASH_LENGTH); offset += NAME_HASH_LENGTH;

  let ratchet = null;
  if (contextFlag) {
    ratchet = payload.subarray(offset, offset + 32); offset += 32;
  }

  const signature = payload.subarray(offset, offset + SIGLENGTH); offset += SIGLENGTH;
  const appData   = payload.subarray(offset);

  // Compute identity hash from public key
  const identityHash = await truncatedHash(publicKey);

  // For LXMF announces, the dest hash in the header is the authoritative
  // value. We use it directly rather than recomputing.
  const destHash = destHashFromHeader;

  return {
    publicKey, nameHash, randomHash, ratchet, signature, appData,
    identityHash, destHash, appName: 'lxmf.delivery',
  };
}

// Validate an announce's Ed25519 signature
export function validateAnnounce(announce, destHashFromHeader) {
  const { publicKey, nameHash, randomHash, ratchet, signature, appData } = announce;

  // Build signed_data = dest_hash + public_key + name_hash + random_hash + [ratchet] + app_data
  const parts = [destHashFromHeader, publicKey, nameHash, randomHash];
  if (ratchet) parts.push(ratchet);
  parts.push(appData);

  const signedData = concatBytes(parts);
  const sigPubKey = publicKey.subarray(32, 64);  // Ed25519 public key

  try {
    return ed25519.verify(signature, signedData, sigPubKey);
  } catch {
    return false;
  }
}

// Build an announce for our identity.
//
// If `ratchetPub` is supplied, the announce carries the ratchet in
// the Reticulum 0.7+ layout: the 32-byte ratchet public key is
// inserted between `random_hash` and `signature` in the on-wire
// payload, and also included in `signed_data` (in the same
// position) so the initiator-side signature check matches.
//
// Callers that emit a ratchet announce MUST also set the packet
// header's `contextFlag` bit to 1 so receivers know to parse the
// extra 32 bytes. The returned `hasRatchet` flag is a convenience
// for the caller that drives `buildPacket`.
export async function buildAnnounce(identity, appName = 'lxmf.delivery', appData = new Uint8Array(0), ratchetPub = null) {
  const nameHash = await computeNameHash(appName);
  const destHash = await computeDestinationHash(appName, identity.hash);

  // Random hash (10 bytes)
  const randomHash = new Uint8Array(10);
  crypto.getRandomValues(randomHash);

  // signed_data = dest_hash + public_key + name_hash + random_hash + [ratchet] + app_data
  const signedParts = [destHash, identity.publicKey, nameHash, randomHash];
  if (ratchetPub) signedParts.push(ratchetPub);
  signedParts.push(appData);
  const signedData = concatBytes(signedParts);
  const signature = identity.sign(signedData);

  // Wire payload = public_key(64) + name_hash(10) + random_hash(10) + [ratchet(32)] + signature(64) + app_data
  const payloadParts = [identity.publicKey, nameHash, randomHash];
  if (ratchetPub) payloadParts.push(ratchetPub);
  payloadParts.push(signature);
  payloadParts.push(appData);
  const payload = concatBytes(payloadParts);

  return { destHash, payload, hasRatchet: !!ratchetPub };
}

// Extract display name from announce app_data.
// LXMF/Sideband announces app_data as msgpack, typically:
//   [display_name_bytes, stamp_cost]
// or sometimes a raw UTF-8 string. Try msgpack first, fall back to UTF-8.
export function extractDisplayName(appData) {
  if (!appData || appData.length === 0) return null;

  // Try msgpack decode
  try {
    const decoded = msgpackDecode(appData);
    if (Array.isArray(decoded) && decoded.length > 0) {
      // First element is the display name (bytes or string)
      const name = decoded[0];
      if (name instanceof Uint8Array) {
        return new TextDecoder('utf-8', { fatal: false }).decode(name);
      }
      if (typeof name === 'string') return name;
    }
    if (typeof decoded === 'string') return decoded;
    if (decoded instanceof Uint8Array) {
      return new TextDecoder('utf-8', { fatal: false }).decode(decoded);
    }
  } catch {
    // Not valid msgpack — fall through to raw UTF-8
  }

  // Fall back: try raw UTF-8 (strict, returns null on invalid bytes)
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(appData);
  } catch {
    return null;
  }
}

// ---- Helpers --------------------------------------------------------

function concatBytes(arrays) {
  const total = arrays.reduce((sum, a) => sum + a.length, 0);
  const result = new Uint8Array(total);
  let offset = 0;
  for (const a of arrays) {
    result.set(a, offset);
    offset += a.length;
  }
  return result;
}

function arraysEqual(a, b) {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) {
    if (a[i] !== b[i]) return false;
  }
  return true;
}

export { concatBytes, arraysEqual };
