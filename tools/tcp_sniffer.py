#!/usr/bin/env python3
"""
Transparent TCP proxy that logs every HDLC-framed Reticulum packet
in both directions. Used to wire-diff our app's outbound packets
against another reference Reticulum client (e.g. Sideband) by pointing
both clients at the same proxy endpoint.

Usage:
    python tools/tcp_sniffer.py [--listen-port 7822]
                                 [--upstream rns.chicagonomad.net:4242]

Then on the phone (or wherever the clients run):
    adb reverse tcp:7822 tcp:7822
And configure both clients to use TCP host 127.0.0.1 / port 7822.
The proxy will accept multiple concurrent client connections, give each
a short label (c1, c2, ...), and log every Reticulum packet it sees in
each direction.

Output goes to test-shots/sniffer.log and to stderr.
"""
import argparse
import os
import socket
import sys
import threading
import time

FLAG = 0x7E   # HDLC flag
ESC  = 0x7D
ESC_MASK = 0x20

LOG_DIR = os.path.join(os.path.dirname(__file__), "..", "test-shots")
LOG_PATH = os.path.join(LOG_DIR, "sniffer.log")

_log_lock = threading.Lock()
_log_file = None

def log(msg):
    """Thread-safe log: prints to stderr AND appends to sniffer.log."""
    line = f"{time.strftime('%H:%M:%S')}  {msg}"
    with _log_lock:
        print(line, flush=True, file=sys.stderr)
        if _log_file is not None:
            _log_file.write(line + "\n")
            _log_file.flush()

def hdlc_unframe(buf, frames_out):
    """
    Greedy HDLC frame extractor. Consumes bytes from `buf`, emits each
    complete frame (de-escaped) into `frames_out`. Returns the
    leftover bytes for the next call.
    """
    out = bytearray()
    i = 0
    in_frame = False
    cur = bytearray()
    while i < len(buf):
        b = buf[i]
        if b == FLAG:
            if in_frame:
                # End of frame.
                if len(cur) > 0:
                    frames_out.append(bytes(cur))
                cur = bytearray()
                in_frame = False
            else:
                in_frame = True
        elif in_frame:
            if b == ESC:
                i += 1
                if i >= len(buf):
                    # Incomplete escape; rewind to ESC and stop.
                    return bytes(buf[i - 1:])
                cur.append(buf[i] ^ ESC_MASK)
            else:
                cur.append(b)
        # Bytes outside a frame are ignored.
        i += 1
    # If we're mid-frame at the end of the buffer, save the unconsumed
    # tail starting at the last FLAG so the next call can finish it.
    if in_frame:
        # find last FLAG at-or-before end (we know buf has at least one)
        last_flag = buf.rfind(bytes([FLAG]))
        return bytes(buf[last_flag:])
    return b""

def decode_packet(pkt):
    """
    Decode a Reticulum packet header into a one-line summary.
    Returns (summary, full_hex).
    """
    if len(pkt) < 19:
        return ("(too short)", pkt.hex())
    flags = pkt[0]
    hops = pkt[1]
    header_type = (flags >> 6) & 1
    context_flag = (flags >> 5) & 1
    transport_type = (flags >> 4) & 1
    dest_type = (flags >> 2) & 0x3
    packet_type = flags & 0x3
    pt = ["DATA", "ANNC", "LREQ", "PROOF"][packet_type]
    dt = ["SINGLE", "GROUP", "PLAIN", "LINK"][dest_type]
    if header_type == 0:
        dest = pkt[2:18].hex()
        ctx = pkt[18]
        rest_off = 19
    else:
        if len(pkt) < 35:
            return ("(HEADER_2 truncated)", pkt.hex())
        tid = pkt[2:18].hex()
        dest = pkt[18:34].hex()
        ctx = pkt[34]
        rest_off = 35
    extra = ""
    if header_type == 1:
        extra = f" tid={tid[:8]}…"
    summary = (f"H{header_type+1} {pt} {dt} hops={hops} ctxF={context_flag} "
               f"dest={dest} ctx={ctx:#04x}{extra} +{len(pkt)-rest_off}B")
    return (summary, pkt.hex())

def pump(name, src, dst, label, full_hex):
    """
    Read from src, write to dst, log each complete HDLC frame.
    """
    leftover = b""
    try:
        while True:
            chunk = src.recv(8192)
            if not chunk:
                log(f"{label} {name}: EOF")
                break
            dst.sendall(chunk)
            buf = leftover + chunk
            frames = []
            leftover = hdlc_unframe(buf, frames)
            for f in frames:
                summary, hexstr = decode_packet(f)
                if full_hex:
                    log(f"{label} {name}  {summary}  hex={hexstr}")
                else:
                    log(f"{label} {name}  {summary}")
    except Exception as e:
        log(f"{label} {name}: error {e!r}")
    finally:
        try: dst.shutdown(socket.SHUT_WR)
        except OSError: pass

def handle_client(client_sock, client_addr, upstream_host, upstream_port,
                  label, full_hex):
    log(f"{label} <- accepted {client_addr}, dialing {upstream_host}:{upstream_port}")
    try:
        upstream = socket.create_connection((upstream_host, upstream_port), timeout=15)
        upstream.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        upstream.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
    except Exception as e:
        log(f"{label} ! upstream dial failed: {e!r}")
        client_sock.close()
        return
    log(f"{label} == bridged {client_addr} <-> {upstream_host}:{upstream_port}")
    t1 = threading.Thread(target=pump, args=("c->s", client_sock, upstream, label, full_hex), daemon=True)
    t2 = threading.Thread(target=pump, args=("s->c", upstream, client_sock, label, full_hex), daemon=True)
    t1.start(); t2.start()
    t1.join(); t2.join()
    try: client_sock.close()
    except OSError: pass
    try: upstream.close()
    except OSError: pass
    log(f"{label} XX closed {client_addr}")

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--listen-host", default="0.0.0.0")
    p.add_argument("--listen-port", default=7822, type=int)
    p.add_argument("--upstream", default="rns.chicagonomad.net:4242",
                   help="host:port of the real rnsd to forward to")
    p.add_argument("--full-hex", action="store_true",
                   help="log full hex of every frame (default: header summary only)")
    args = p.parse_args()

    if ":" not in args.upstream:
        print("--upstream must be host:port", file=sys.stderr)
        sys.exit(2)
    up_host, up_port_s = args.upstream.rsplit(":", 1)
    up_port = int(up_port_s)

    os.makedirs(LOG_DIR, exist_ok=True)
    global _log_file
    _log_file = open(LOG_PATH, "w", encoding="utf-8")

    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind((args.listen_host, args.listen_port))
    s.listen(8)
    log(f"== sniffer listening on {args.listen_host}:{args.listen_port} -> {up_host}:{up_port}")
    log(f"== full_hex={args.full_hex}; log file: {LOG_PATH}")

    next_label = [1]
    try:
        while True:
            client, addr = s.accept()
            client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            client.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
            label = f"c{next_label[0]}"
            next_label[0] += 1
            t = threading.Thread(target=handle_client,
                                 args=(client, addr, up_host, up_port, label, args.full_hex),
                                 daemon=True)
            t.start()
    except KeyboardInterrupt:
        log("== shutting down (Ctrl-C)")
    finally:
        s.close()
        if _log_file is not None:
            _log_file.close()

if __name__ == "__main__":
    main()
