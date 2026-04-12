// js/link.js — Reticulum Link protocol (both responder and initiator).
//
// Responder path: accept a LINKREQUEST from a peer, derive a session
// key, emit the LRPROOF, and encrypt/decrypt packets on the link.
//
// Initiator path: generate a fresh LINKREQUEST for a known destination,
// wait for the LRPROOF, verify it against the destination's long-term
// sig pub (learned earlier from the destination's announce), derive
// the session key, emit the LRRTT, and mark the link ACTIVE.
//
// AES256_CBC only.
//
// Scope reference: reticulum-lora-repeater/.pio/libdeps/Faketec/
// microReticulum/src/Link.cpp validate_request / handshake / prove and
// the upstream Python source comments in that file.

'use strict';

import { encode as msgpackEncode, decode as msgpackDecode } from '@msgpack/msgpack';
import { ed25519, x25519 } from '@noble/curves/ed25519';
import { sha256 } from './identity.js';
import { hkdfDerive, tokenEncrypt, tokenDecrypt } from './crypto.js';
import { HEADER_1, TRUNCATED_HASHLENGTH } from './reticulum.js';
import { concatBytes } from './announce.js';

export const ECPUBSIZE      = 64;   // 32 X25519 + 32 Ed25519
export const SIGLENGTH      = 64;   // Ed25519 signature
export const LINK_MTU_SIZE  = 3;
export const LINK_KEYSIZE   = 64;   // AES256_CBC derives 64 bytes (32 HMAC + 32 AES)
export const MODE_AES256_CBC = 0x01;

// Link status values mirroring upstream Link::status.
export const LINK_PENDING   = 0x00;
export const LINK_HANDSHAKE = 0x01;
export const LINK_ACTIVE    = 0x02;
export const LINK_CLOSED    = 0x04;

// Encode a 3-byte signalling field carrying mtu (low 21 bits) and mode
// (top 3 bits). Matches upstream Link.signalling_bytes():
//   val = (mtu & 0x1FFFFF) | ((mode & 0x07) << 21)
//   bytes = big-endian (val >> 16) (val >> 8) (val)
export function encodeSignalling(mtu, mode) {
  const val = (mtu & 0x1FFFFF) | ((mode & 0x07) << 21);
  return new Uint8Array([(val >> 16) & 0xFF, (val >> 8) & 0xFF, val & 0xFF]);
}

export function decodeSignalling(bytes) {
  const val = (bytes[0] << 16) | (bytes[1] << 8) | bytes[2];
  return { mtu: val & 0x1FFFFF, mode: (val >> 21) & 0x07 };
}

// Compute the link_id from an inbound LINKREQUEST packet.
//
// Upstream Link.link_id_from_lr_packet:
//   hashable_part = (flags & 0x0F) || raw[2:]            (HEADER_1)
//   hashable_part = (flags & 0x0F) || raw[18:]           (HEADER_2)
//   if data.size > ECPUBSIZE:
//       hashable_part = hashable_part[:-(data.size - ECPUBSIZE)]
//   link_id = SHA256(hashable_part)[:16]
export async function computeLinkId(pkt) {
  const flagsLow = pkt.flags & 0x0F;
  const skipBytes = pkt.headerType === HEADER_1 ? 2 : 2 + TRUNCATED_HASHLENGTH;
  const tail = pkt.raw.subarray(skipBytes);

  const hp = new Uint8Array(1 + tail.length);
  hp[0] = flagsLow;
  hp.set(tail, 1);

  let hashable = hp;
  if (pkt.payload.length > ECPUBSIZE) {
    const diff = pkt.payload.length - ECPUBSIZE;
    hashable = hp.subarray(0, hp.length - diff);
  }

  const digest = await sha256(hashable);
  return digest.subarray(0, 16);
}

// Compute the full-size (32 byte) SHA-256 packet hash over a received
// packet's hashable_part. Upstream Packet::get_hash() uses the full
// hash (not the truncated one used for link_id) so that RNS packet
// receipts address the exact packet they acknowledge. The hashable
// part is (flags & 0x0F) followed by raw[2:] for HEADER_1 or raw[18:]
// for HEADER_2; unlike computeLinkId, no signalling-byte stripping
// applies because this is called for generic data packets, not for
// the LINKREQUEST special case.
export async function computePacketFullHash(pkt) {
  const flagsLow = pkt.flags & 0x0F;
  const skipBytes = pkt.headerType === HEADER_1 ? 2 : 2 + TRUNCATED_HASHLENGTH;
  const tail = pkt.raw.subarray(skipBytes);

  const hp = new Uint8Array(1 + tail.length);
  hp[0] = flagsLow;
  hp.set(tail, 1);

  return sha256(hp);   // full 32-byte SHA-256
}

export class Link {
  constructor() {
    this.linkId           = null;            // Uint8Array(16)
    this.isInitiator      = false;
    this.ourX25519Priv    = null;            // ephemeral on both sides
    this.ourX25519Pub     = null;
    // On the responder side ourSigPriv/Pub are the long-term identity
    // signing keys. On the initiator side they are an ephemeral pair
    // we generate fresh for this link and throw away afterwards.
    this.ourSigPriv       = null;
    this.ourSigPub        = null;
    this.peerX25519Pub    = null;
    this.peerEd25519Pub   = null;
    this.peerLongTermSigPub = null;          // responder's long-term sig pub (initiator only)
    this.derivedKey       = null;            // Uint8Array(64) — 32 HMAC + 32 AES
    this.mtu              = 500;
    this.mode             = MODE_AES256_CBC;
    this.signallingBytes  = null;
    this.status           = LINK_PENDING;
    this.cachedProofData  = null;            // for responder-side LRPROOF dedup / retransmit
    this.cachedLRRTTPacket = null;           // for initiator-side LRRTT (not currently resent)
    this.ownerDestHash    = null;            // destination this link targets
    this.createdAt        = 0;
    this.establishedAt    = 0;
    this.rtt              = 0;               // measured in seconds (initiator only)
  }

  encrypt(plaintext) {
    return tokenEncrypt(this.derivedKey, plaintext);
  }

  decrypt(ciphertext) {
    return tokenDecrypt(this.derivedKey, ciphertext);
  }

  // Accept an inbound LINKREQUEST packet and derive all link state.
  //
  // `pkt` is the parsed packet object from parsePacket().
  // `ourIdentity` is the Identity instance that owns the destination the
  // request was addressed to; its long-term Ed25519 private key signs the
  // LRPROOF so the initiator can verify we are the intended destination.
  //
  // Returns { link, proofData } on success where proofData is the raw
  // payload bytes to put into an LRPROOF packet. Throws on invalid input.
  static async validateRequest(pkt, ourIdentity) {
    const data = pkt.payload;
    if (data.length !== ECPUBSIZE && data.length !== ECPUBSIZE + LINK_MTU_SIZE) {
      throw new Error(`Invalid LINKREQUEST payload size ${data.length}`);
    }

    const peerX25519Pub  = data.subarray(0, 32);
    const peerEd25519Pub = data.subarray(32, 64);

    let mtu  = 500;
    let mode = MODE_AES256_CBC;
    if (data.length === ECPUBSIZE + LINK_MTU_SIZE) {
      const sig = decodeSignalling(data.subarray(ECPUBSIZE, ECPUBSIZE + LINK_MTU_SIZE));
      if (sig.mtu > 0) mtu = sig.mtu;
      mode = sig.mode;
    }
    if (mode !== MODE_AES256_CBC) {
      throw new Error(`Unsupported link mode 0x${mode.toString(16)}`);
    }

    const linkId = await computeLinkId(pkt);

    const link = new Link();
    link.linkId         = linkId;
    link.ourX25519Priv  = x25519.utils.randomPrivateKey();
    link.ourX25519Pub   = x25519.getPublicKey(link.ourX25519Priv);
    link.ourSigPriv     = ourIdentity.sigPrivKey;
    link.ourSigPub      = ourIdentity.sigPubKey;
    link.peerX25519Pub  = new Uint8Array(peerX25519Pub);
    link.peerEd25519Pub = new Uint8Array(peerEd25519Pub);
    link.mtu            = mtu;
    link.mode           = mode;
    link.ownerDestHash  = new Uint8Array(pkt.destHash);

    // ECDH + HKDF. Upstream Link::handshake uses salt=link_id, info=empty.
    const shared = x25519.getSharedSecret(link.ourX25519Priv, link.peerX25519Pub);
    link.derivedKey = await hkdfDerive(shared, link.linkId, new Uint8Array(0), LINK_KEYSIZE);
    link.status = LINK_HANDSHAKE;

    // Build the LRPROOF payload.
    //
    // signed_data = link_id + our_x25519_pub + our_long_term_sig_pub + signalling
    // signature   = Ed25519(our long-term sig priv, signed_data)
    // proof_data  = signature + our_x25519_pub + signalling
    //
    // The initiator already knows our long-term Ed25519 pubkey from our
    // announce and looks it up by destination hash; that is why the sig
    // pub is in the SIGNED data but NOT in the wire proof_data payload.
    link.signallingBytes = encodeSignalling(mtu, mode);
    const signedData = concatBytes([
      link.linkId,
      link.ourX25519Pub,
      link.ourSigPub,
      link.signallingBytes,
    ]);
    const signature = ed25519.sign(signedData, link.ourSigPriv);
    const proofData = concatBytes([signature, link.ourX25519Pub, link.signallingBytes]);
    link.cachedProofData = proofData;
    link.signedData      = signedData;
    link.signatureBytes  = signature;

    return { link, proofData };
  }

  // ---- Initiator path ----------------------------------------------------

  // Create an outbound Link aimed at the given responder identity.
  //
  // `peerLongTermSigPub` is the 32-byte Ed25519 public key the responder
  // advertised in its announce — we need it here because the LRPROOF we
  // are about to receive is signed with the corresponding private key,
  // and we will verify that signature before trusting the derived key.
  //
  // `peerDestHash` is the responder's 16-byte LXMF delivery destination
  // hash — it goes in the destination slot of the LINKREQUEST packet
  // header.
  //
  // Returns {link, requestData}. The caller wraps requestData in a
  // PACKET_LINKREQ and transmits it, then feeds any incoming LRPROOF
  // back to link.validateProof().
  static createInitiator(peerLongTermSigPub, peerDestHash) {
    const link = new Link();
    link.isInitiator = true;
    link.peerLongTermSigPub = new Uint8Array(peerLongTermSigPub);
    link.ownerDestHash = new Uint8Array(peerDestHash);
    link.createdAt = Date.now();

    // Ephemeral X25519 AND ephemeral Ed25519 on the initiator side.
    // The Ed25519 pub goes into the LINKREQUEST body as bytes 32..63
    // but the initiator's identity is NOT authenticated to the
    // responder by this — it's just a fresh key per session.
    link.ourX25519Priv = x25519.utils.randomPrivateKey();
    link.ourX25519Pub  = x25519.getPublicKey(link.ourX25519Priv);
    link.ourSigPriv    = ed25519.utils.randomPrivateKey();
    link.ourSigPub     = ed25519.getPublicKey(link.ourSigPriv);

    link.signallingBytes = encodeSignalling(link.mtu, link.mode);

    const requestData = concatBytes([
      link.ourX25519Pub,
      link.ourSigPub,
      link.signallingBytes,
    ]);

    return { link, requestData };
  }

  // Tell the initiator link what its link_id is, computed from the
  // parsed LINKREQUEST packet after the caller has packed and
  // transmitted it. Both sides must agree on this 16-byte id.
  setLinkIdFromPacket(pkt) {
    // computeLinkId returns a subarray view; copy so we own the bytes.
    return computeLinkId(pkt).then((id) => {
      this.linkId = new Uint8Array(id);
      return this.linkId;
    });
  }

  // Handle an inbound LRPROOF packet on this pending initiator link.
  //
  // Verifies the signature in the proof_data against the responder's
  // long-term sig pub (which the caller supplied at createInitiator
  // time), derives the session key via ECDH+HKDF, and — on success —
  // builds the LRRTT packet data the caller should transmit back.
  //
  // Returns { ok: true, rttData } on success, { ok: false, reason } on
  // failure. The caller decides whether to retry or fall back to
  // opportunistic.
  async validateProof(pkt) {
    if (!this.isInitiator) {
      return { ok: false, reason: 'validateProof called on responder link' };
    }
    if (this.status !== LINK_PENDING) {
      return { ok: false, reason: `link state is ${this.status}, expected PENDING` };
    }

    // Proof payload layout (from upstream prove() / validate_proof):
    //   signature(64) || responder_ephemeral_x25519_pub(32) || [signalling(3)]
    const data = pkt.payload;
    if (data.length !== SIGLENGTH + 32 && data.length !== SIGLENGTH + 32 + LINK_MTU_SIZE) {
      return { ok: false, reason: `LRPROOF payload size ${data.length} not 96 or 99` };
    }
    const signature = data.subarray(0, SIGLENGTH);
    const peerX25519Pub = data.subarray(SIGLENGTH, SIGLENGTH + 32);

    let mtu = this.mtu;
    let mode = this.mode;
    let signallingFromProof = this.signallingBytes;
    if (data.length === SIGLENGTH + 32 + LINK_MTU_SIZE) {
      const sigBytes = data.subarray(SIGLENGTH + 32, SIGLENGTH + 32 + LINK_MTU_SIZE);
      const decoded = decodeSignalling(sigBytes);
      if (decoded.mode !== this.mode) {
        return { ok: false, reason: `LRPROOF mode 0x${decoded.mode.toString(16)} does not match requested` };
      }
      if (decoded.mtu > 0) mtu = decoded.mtu;
      signallingFromProof = new Uint8Array(sigBytes);
    }

    // Reconstruct the signed_data the responder hashed:
    //   link_id || responder_ephemeral_x25519_pub || responder_long_term_sig_pub || signalling
    const signedData = concatBytes([
      this.linkId,
      peerX25519Pub,
      this.peerLongTermSigPub,
      signallingFromProof,
    ]);

    // Verify the Ed25519 signature against the responder's long-term
    // sig pub (supplied at createInitiator() time from the responder's
    // announce). Any mismatch here means either the bytes drifted or
    // someone else tried to answer our LINKREQUEST.
    let sigValid = false;
    try {
      sigValid = ed25519.verify(signature, signedData, this.peerLongTermSigPub);
    } catch {
      sigValid = false;
    }
    if (!sigValid) {
      return { ok: false, reason: 'LRPROOF signature verification failed' };
    }

    // Signature is good. Derive the session key the same way the
    // responder did: our ephemeral X25519 priv + their ephemeral X25519
    // pub, HKDF with salt=link_id.
    this.peerX25519Pub = new Uint8Array(peerX25519Pub);
    this.signallingBytes = signallingFromProof;
    this.mtu = mtu;
    const shared = x25519.getSharedSecret(this.ourX25519Priv, this.peerX25519Pub);
    this.derivedKey = await hkdfDerive(shared, this.linkId, new Uint8Array(0), LINK_KEYSIZE);
    this.status = LINK_ACTIVE;
    this.establishedAt = Date.now();
    this.rtt = (this.establishedAt - this.createdAt) / 1000;

    // Build the LRRTT packet data: Token-encrypted msgpack of the RTT.
    // Caller wraps this in a PACKET_DATA with context=LRRTT addressed
    // to link_id. Upstream sends this as confirmation that we verified
    // the proof; the responder uses the decrypt success to transition
    // its own status to ACTIVE.
    const rttMsgpack = msgpackEncode(this.rtt);
    const rttEncrypted = await tokenEncrypt(this.derivedKey, rttMsgpack);

    return { ok: true, rttData: rttEncrypted, rtt: this.rtt };
  }
}
