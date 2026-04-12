// js/reticulum.js — Reticulum packet header encode/decode + constants.

'use strict';

// Packet types
export const PACKET_DATA      = 0x00;
export const PACKET_ANNOUNCE  = 0x01;
export const PACKET_LINKREQ   = 0x02;
export const PACKET_PROOF     = 0x03;

// Header types
export const HEADER_1 = 0x00;  // normal
export const HEADER_2 = 0x01;  // transport

// Destination types
export const DEST_SINGLE = 0x00;
export const DEST_GROUP  = 0x01;
export const DEST_PLAIN  = 0x02;
export const DEST_LINK   = 0x03;

// Transport types
export const TRANSPORT_BROADCAST = 0x00;
export const TRANSPORT_TRANSPORT = 0x01;

// Sizes
export const TRUNCATED_HASHLENGTH = 16;  // 128 bits = 16 bytes
export const NAME_HASH_LENGTH     = 10;  // 80 bits = 10 bytes
export const KEYSIZE              = 64;  // 32 X25519 + 32 Ed25519
export const SIGLENGTH            = 64;  // Ed25519 signature
export const MTU                  = 500;
export const HEADER_MINSIZE       = 19;  // flags(1) + hops(1) + dest(16) + context(1)
export const TOKEN_OVERHEAD       = 48;  // 16 IV + 32 HMAC

// Parse a Reticulum packet header
export function parsePacket(data) {
  if (data.length < HEADER_MINSIZE) return null;

  const flags   = data[0];
  const hops    = data[1];

  const headerType    = (flags >> 6) & 0x01;
  const contextFlag   = (flags >> 5) & 0x01;
  const transportType = (flags >> 4) & 0x01;
  const destType      = (flags >> 2) & 0x03;
  const packetType    = flags & 0x03;

  let destHash, transportId, context, payload;

  if (headerType === HEADER_1) {
    destHash  = data.subarray(2, 2 + TRUNCATED_HASHLENGTH);
    context   = data[2 + TRUNCATED_HASHLENGTH];
    payload   = data.subarray(2 + TRUNCATED_HASHLENGTH + 1);
  } else {
    // HEADER_2: transport_id(16) + destination_hash(16)
    transportId = data.subarray(2, 2 + TRUNCATED_HASHLENGTH);
    destHash    = data.subarray(2 + TRUNCATED_HASHLENGTH, 2 + 2 * TRUNCATED_HASHLENGTH);
    context     = data[2 + 2 * TRUNCATED_HASHLENGTH];
    payload     = data.subarray(2 + 2 * TRUNCATED_HASHLENGTH + 1);
  }

  return {
    raw: data, flags, hops, headerType, contextFlag, transportType,
    destType, packetType, destHash, transportId, context, payload,
  };
}

// Build a Reticulum packet
export function buildPacket({ headerType = HEADER_1, contextFlag = 0, transportType = TRANSPORT_BROADCAST,
                              destType = DEST_SINGLE, packetType = PACKET_DATA, hops = 0,
                              destHash, context = 0x00, payload = new Uint8Array(0) }) {
  const flags = ((headerType & 0x01) << 6) |
                ((contextFlag & 0x01) << 5) |
                ((transportType & 0x01) << 4) |
                ((destType & 0x03) << 2) |
                (packetType & 0x03);

  const header = new Uint8Array(2 + TRUNCATED_HASHLENGTH + 1);
  header[0] = flags;
  header[1] = hops;
  header.set(destHash, 2);
  header[2 + TRUNCATED_HASHLENGTH] = context;

  const packet = new Uint8Array(header.length + payload.length);
  packet.set(header);
  packet.set(payload, header.length);
  return packet;
}

// Packet type names for display
export const PACKET_TYPE_NAMES = ['DATA', 'ANNOUNCE', 'LINKREQ', 'PROOF'];
export const DEST_TYPE_NAMES   = ['SINGLE', 'GROUP', 'PLAIN', 'LINK'];
