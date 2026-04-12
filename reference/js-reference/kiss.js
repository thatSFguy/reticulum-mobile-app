// js/kiss.js — KISS frame encode/decode for BLE byte stream.
//
// Accumulates bytes across BLE notifications and emits complete
// KISS frames on FEND boundaries. Adapted from the rnode webflasher.

'use strict';

export const FEND  = 0xC0;
export const FESC  = 0xDB;
export const TFEND = 0xDC;
export const TFESC = 0xDD;

// RNode command bytes
export const CMD_DATA        = 0x00;
export const CMD_FREQUENCY   = 0x01;
export const CMD_BANDWIDTH   = 0x02;
export const CMD_TXPOWER     = 0x03;
export const CMD_SF          = 0x04;
export const CMD_CR          = 0x05;
export const CMD_RADIO_STATE = 0x06;
export const CMD_DETECT      = 0x08;
export const CMD_READY       = 0x0F;
export const CMD_STAT_RSSI   = 0x23;
export const CMD_STAT_SNR    = 0x24;
export const CMD_STAT_BAT    = 0x27;
export const CMD_BLINK       = 0x30;
export const CMD_RANDOM      = 0x40;
export const CMD_BOARD       = 0x47;
export const CMD_PLATFORM    = 0x48;
export const CMD_MCU         = 0x49;
export const CMD_FW_VERSION  = 0x50;
export const CMD_RESET       = 0x55;
export const CMD_ERROR       = 0x90;

export const DETECT_REQ  = 0x73;
export const DETECT_RESP = 0x46;

// Build a KISS frame: FEND + CMD + escaped(data) + FEND
export function buildFrame(cmd, data = new Uint8Array(0)) {
  const out = [FEND, cmd];
  for (const b of data) {
    if (b === FEND)      { out.push(FESC, TFEND); }
    else if (b === FESC) { out.push(FESC, TFESC); }
    else                 { out.push(b); }
  }
  out.push(FEND);
  return new Uint8Array(out);
}

// KISS frame parser. Feed it bytes (possibly split across BLE
// notifications) and it calls onFrame(cmd, payload) for each
// complete frame.
export class KissParser {
  constructor(onFrame) {
    this.onFrame = onFrame;
    this._buf = [];
    this._inFrame = false;
    this._escape = false;
  }

  // Feed a chunk of bytes (Uint8Array) from a BLE notification.
  feed(bytes) {
    for (const b of bytes) {
      if (b === FEND) {
        if (this._inFrame && this._buf.length > 0) {
          const cmd = this._buf[0];
          const payload = new Uint8Array(this._buf.slice(1));
          this.onFrame(cmd, payload);
        }
        this._inFrame = true;
        this._buf = [];
        this._escape = false;
        continue;
      }

      if (!this._inFrame) continue;

      if (this._escape) {
        this._escape = false;
        if (b === TFEND) this._buf.push(FEND);
        else if (b === TFESC) this._buf.push(FESC);
        else this._buf.push(b);
      } else if (b === FESC) {
        this._escape = true;
      } else {
        this._buf.push(b);
      }
    }
  }

  reset() {
    this._buf = [];
    this._inFrame = false;
    this._escape = false;
  }
}

// Helpers
export function uint32ToBytes(val) {
  return new Uint8Array([
    (val >> 24) & 0xFF, (val >> 16) & 0xFF,
    (val >>  8) & 0xFF, (val >>  0) & 0xFF,
  ]);
}

export function bytesToUint32(bytes, offset = 0) {
  return ((bytes[offset] << 24) | (bytes[offset+1] << 16) |
          (bytes[offset+2] << 8) | bytes[offset+3]) >>> 0;
}

export function toHex(bytes) {
  return Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
}
