// js/hdlc.js — HDLC frame encode/decode for Reticulum's TCP interface.
//
// rnsd's TCPClientInterface / TCPServerInterface frames every raw
// Reticulum packet with HDLC before writing it to the socket, and
// unframes it on the receive side. Wire format is:
//
//   FLAG (0x7E) || escaped(packet_bytes) || FLAG (0x7E)
//
// where escaping replaces any in-band 0x7D with 0x7D 0x5D and any
// in-band 0x7E with 0x7D 0x5E. The reverse applies on unescape.
//
// This is a separate module from kiss.js because:
//   * framing bytes are different (0x7E / 0x7D vs 0xC0 / 0xDB)
//   * HDLC has no command byte prefix — the frame IS the packet
//   * there is no RSSI/SNR metadata framing like KISS has
//
// Source: RNS/Interfaces/TCPInterface.py class HDLC.

'use strict';

export const FLAG     = 0x7E;
export const ESC      = 0x7D;
export const ESC_MASK = 0x20;

// Wrap one complete Reticulum packet into an HDLC frame. Returns
// FLAG || escaped(data) || FLAG.
export function encodeFrame(data) {
  const out = [FLAG];
  for (const b of data) {
    if (b === ESC || b === FLAG) {
      out.push(ESC, b ^ ESC_MASK);
    } else {
      out.push(b);
    }
  }
  out.push(FLAG);
  return new Uint8Array(out);
}

// Streaming HDLC parser. Feed it bytes as they arrive on the
// transport (WebSocket messages, TCP chunks, whatever) and it calls
// onFrame(bytes) for each complete frame boundary it sees. Buffers
// partial frames across feeds so input chunk sizes do not matter.
export class HdlcParser {
  constructor(onFrame) {
    this.onFrame = onFrame;
    this._buf = [];
    this._inFrame = false;
    this._escape = false;
  }

  feed(bytes) {
    for (const b of bytes) {
      if (b === FLAG) {
        // A FLAG terminates the current frame (if any) and starts
        // the next. Empty frames (two FLAGs back to back) are
        // silently dropped — rnsd uses FLAG as both delimiter and
        // keepalive.
        if (this._inFrame && this._buf.length > 0) {
          this.onFrame(new Uint8Array(this._buf));
        }
        this._buf = [];
        this._inFrame = true;
        this._escape = false;
        continue;
      }

      if (!this._inFrame) continue;

      if (this._escape) {
        this._escape = false;
        this._buf.push(b ^ ESC_MASK);
      } else if (b === ESC) {
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
