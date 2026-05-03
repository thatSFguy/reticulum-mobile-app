#!/usr/bin/env python3
"""
Bare-socket TCP receiver for the local transport. No RNS, no LXMF —
just opens a TCP connection to host:port and dumps every byte received
with a timestamp. Lets us prove whether the transport is actually
forwarding our app's DATA packet to a connected client.

Pair with our app sending a message; watch for HDLC-framed bytes here.

Usage:
    python tools/rx_socket_sniffer.py 127.0.0.1 7822
"""
import socket
import sys
import time

host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
port = int(sys.argv[2]) if len(sys.argv) > 2 else 7822

s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.settimeout(2.0)
s.connect((host, port))
print(f"connected to {host}:{port}", flush=True)

buf = b""
last_print = 0
while True:
    try:
        chunk = s.recv(4096)
        if not chunk:
            print("remote closed", flush=True)
            break
        ts = time.strftime("%H:%M:%S", time.localtime())
        print(f"[{ts}] rx {len(chunk)}B: {chunk.hex()}", flush=True)
    except socket.timeout:
        # idle tick so we know the script is alive
        if time.time() - last_print > 10:
            print(f"[{time.strftime('%H:%M:%S')}] idle...", flush=True)
            last_print = time.time()
    except KeyboardInterrupt:
        print("ctrl-c", flush=True)
        break
