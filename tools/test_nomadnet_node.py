#!/usr/bin/env python3
"""
Minimal NomadNet-style page node for the NomadNetLiveTest integration
test. Hosts a destination at the `nomadnetwork.node` aspect, registers
a request handler at `/page/index.mu` (the upstream NomadNet default,
matching `Browser.py` `DEFAULT_PATH` and `Node.py` `register_request_handler`),
announces every 5 minutes.

Usage:
    pip install rns
    python tools/test_nomadnet_node.py

Prints the env vars to set on the Kotlin test side:

    NOMADNET_NODE_HASH=<hex>
    NOMADNET_TCP_HOST=rns.chicagonomad.net
    NOMADNET_TCP_PORT=4242
    NOMADNET_PAGE_PATH=/page/index.mu
    NOMADNET_PAGE_NEEDLE=Hello

Then in another shell:
    cd shared
    NOMADNET_NODE_HASH=... ./gradlew testDebugUnitTest \\
        --tests io.github.thatsfguy.reticulum.NomadNetLiveTest

Stop with Ctrl-C.

This is a *minimal* NomadNet — it does not implement the full nomadnet
TUI, just enough to serve micron pages over an RNS Link's
REQUEST/RESPONSE flow, which is exactly what our app's fetchNomadPage
exercises.
"""
import os
import sys
import time

os.environ.setdefault("RNS_LOG_DEST", "stderr")

# Same Windows rename safety as the receiver — RNS's atomic rename
# fails on Windows when the destination exists.
_orig_replace = os.replace
def _safe_rename(src, dst):
    try:
        _orig_replace(src, dst)
    except (FileNotFoundError, PermissionError):
        pass
os.rename = _safe_rename
os.replace = _safe_rename

# Windows: RNS's `Identity.persist_job` thread opens ratchet files for
# write while the OS AV / indexer briefly holds an exclusive read on
# them. The open() call raises PermissionError and kills the persist
# thread, which is fatal because RNS keeps spawning ratchet rotations.
# Wrap builtin open() so writes that hit a transient lock just no-op
# rather than crashing the daemon.
import builtins
_orig_open = builtins.open
def _safe_open(*args, **kwargs):
    try:
        return _orig_open(*args, **kwargs)
    except PermissionError:
        # Return a /dev/null-equivalent so the caller's `with` block
        # exits cleanly. The skipped persist just means the ratchet
        # rotation didn't survive a restart — fine for a test node.
        import io
        return io.BytesIO()
builtins.open = _safe_open

import RNS

IDENTITY_PATH = os.path.expanduser("~/.reticulum-mobile-app-test-nomad-identity")
CONFIG_DIR    = os.path.expanduser("~/.reticulum-mobile-app-test-nomad-config")
DEFAULT_TCP   = "rns.chicagonomad.net:4242"
TCP_TARGET    = os.environ.get("TEST_NOMAD_TCP", DEFAULT_TCP)
DISPLAY_NAME  = "NomadNet Test Node"

# Canned pages so the Kotlin tests can assert on known strings.
PAGE_BODY = (
    "`!Welcome to the NomadNet Test Node`!\n"
    "\n"
    "This page is served by tools/test_nomadnet_node.py for the\n"
    "NomadNetLiveTest integration test.\n"
    "\n"
    "If our Kotlin client fetched this page successfully, the entire\n"
    "Link + REQUEST/RESPONSE protocol stack works end to end on the\n"
    "current TCP transport.\n"
    "\n"
    "Hello from Python RNS.\n"
    "\n"
    ">>Other test pages\n"
    "\n"
    "`[Showcase — every supported micron feature`/page/showcase.mu]\n"
    "`[Echo form handler`/page/echo.mu]\n"
    "`[Cross-node link sample`/page/links.mu]\n"
)

# Page used by the cross-node link follow test (v0.1.56). The link
# points back to this same node — what we exercise on the client side
# is the parseLinkTarget dispatch + resolveOrPrepareDestination round-
# trip, NOT a true two-node hop. A real cross-node test would need a
# second NomadNet running.
LINKS_PAGE_TEMPLATE = (
    "`!Cross-node link sample`!\n"
    "\n"
    "Tap the link below. The Kotlin client should parse it via\n"
    "parseLinkTarget into a CrossNode(<our hex>, /page/index.mu) and\n"
    "navigate by swapping `selected` even though the target is us.\n"
    "\n"
    "`[Visit other node`{node_hex}:/page/index.mu]\n"
)

# v0.1.71 showcase page — exercises EVERY micron feature the Kotlin
# parser/renderer claims to support so we can spot regressions
# visually. Each section is independent; if one renders wrong on the
# phone we know exactly which parser branch to look at.
SHOWCASE_PAGE = """\
#!c=60
#!bg=eeece6
#!fg=222
>Showcase page — all supported micron

This page exercises every feature the Kotlin browser claims to
render. If any section looks wrong, we know exactly which parser
branch to fix.

Source line breaks should be preserved as `\\n` per
MicronParser.py:82-93 — these three lines render on three
separate lines, NOT collapsed to a single space-joined paragraph.

>>Section A — inline formatting

`!Bold`! ordinary `_underlined`_ ordinary `*italic`* ordinary,
`Ff00red text`f, `F0a0green text`f, `B888shaded`b ordinary,
`!`*`_bold-italic-underlined`!`*`_, full reset → `! bold `` and
this part is fully reset to plain.

>>Section B — alignment

`cThis line is centered.
`rThis line is right-aligned.
`lBack to left.

>>Section C — links

`[same-node link → /page/echo.mu`/page/echo.mu]
`[bare-hash link (us, default path)`{node_hex}]
`[cross-node link → /page/index.mu`{node_hex}:/page/index.mu]
`[nnn shorthand`nnn@{node_hex}:/page/links.mu]

>>Section D — form fields

Text input (24-wide):
`<24|message`hello world>

Masked input (16-wide):
`<!16|password`>

Checkboxes (per Browser.py:226-241 — unchecked omits, prechecked):
`<?|opt_in|yes|*`Subscribe to updates>
`<?|terms||`Accept terms>

Radio buttons:
`<^|color|red|*`Red>
`<^|color|green`Green>
`<^|color|blue`Blue>

Submit form (link with named field list):
`[Send`/page/echo.mu`message]

URL-query-style params (var_*):
`[Click with params`/page/echo.mu`tag=showcase|priority=high]

>>Section E — table

`tc60
Header A | Header B | Header C
1 | 2 | 3
foo | bar | baz
`t

>>Section F — horizontal rules

Single dash → default rune:
-
Custom rune (═):
-═
Custom rune (•):
-•

>>Section G — literal block

Inside a literal block, \\`! and \\`* and \\`[link\\] are all preserved
verbatim — no parsing.

`=
#!/usr/bin/env bash
echo "this # comment is preserved inside a literal block"
echo "and `!so are`! these `*backticks`*"
`=

>>Section H — partial (server-side include)

The placeholder below should fetch /page/echo.mu and render the
result inline, replacing the "⧖ Loading…" text:

`{{/page/echo.mu}}

>>Section I — escape and edge cases

\\>This line starts with a backslash so it isn't a heading.
\\#And this one is a literal hash, not a comment.

# This IS a real comment and should be dropped from the render.

>>Section J — anti-features that must NOT render as HRs

These three lines are upstream-literal text per
MicronParser.py:266-273. Pre-v0.1.58 our parser wrongly matched
them as horizontal rules:

---
===
\\=

End of showcase. Tap Reload to retest after a code change.
"""

CONFIG_TEMPLATE = """\
[reticulum]
  enable_transport = False
  share_instance = No
  shared_instance_port = 37448
  instance_control_port = 37449
  panic_on_interface_error = No

[logging]
  loglevel = 4

[interfaces]

  [[Test Nomad TCP]]
    type = TCPClientInterface
    interface_enabled = true
    target_host = {host}
    target_port = {port}
    name = test_nomad_tcp
"""


def main():
    if os.path.exists(IDENTITY_PATH):
        identity = RNS.Identity.from_file(IDENTITY_PATH)
        print(f"[nomad] loaded existing identity from {IDENTITY_PATH}", flush=True)
    else:
        identity = RNS.Identity()
        identity.to_file(IDENTITY_PATH)
        print(f"[nomad] created new identity at {IDENTITY_PATH}", flush=True)

    os.makedirs(CONFIG_DIR, exist_ok=True)
    config_path = os.path.join(CONFIG_DIR, "config")
    if not os.path.exists(config_path):
        if ":" in TCP_TARGET:
            host, port = TCP_TARGET.rsplit(":", 1)
        else:
            host, port = TCP_TARGET, "4242"
        with open(config_path, "w") as f:
            f.write(CONFIG_TEMPLATE.format(host=host, port=port))
        print(f"[nomad] wrote config to {config_path} (TCP: {host}:{port})", flush=True)

    rns = RNS.Reticulum(configdir=CONFIG_DIR, loglevel=int(os.environ.get("NOMAD_LOGLEVEL", "4")))

    # Hosting destination — IN means "we accept inbound traffic to this".
    destination = RNS.Destination(
        identity, RNS.Destination.IN, RNS.Destination.SINGLE,
        "nomadnetwork", "node",
    )
    destination.set_proof_strategy(RNS.Destination.PROVE_ALL)

    def page_handler(path, data, request_id, link_id, remote_identity, requested_at):
        # Returning bytes makes RNS RESPOND with this body inside a single
        # packet (or as a Resource if it exceeds MDU). The Kotlin client's
        # LinkSession.request() unwraps either form to bytes.
        print(f"[nomad] request: path={path!r} link={RNS.prettyhexrep(link_id) if link_id else None} from={RNS.prettyhexrep(remote_identity.hash) if remote_identity else 'anon'}", flush=True)
        return PAGE_BODY.encode("utf-8")

    # v0.1.57 form-handler page. Echoes back whichever value the client
    # sent in `field_message` so the Kotlin live test can assert that
    # form data made it through end-to-end. Pre-v0.1.53 our REQUEST
    # envelope shape was wrong (data was bin not dict) and this handler
    # would have seen `data` as bytes and `field_message` would never
    # be in env_map — the test that catches that regression.
    def echo_handler(path, data, request_id, link_id, remote_identity, requested_at):
        print(f"[nomad] echo request: path={path!r} data={data!r}", flush=True)
        if isinstance(data, dict):
            value = data.get("field_message", "(missing field_message)")
            if isinstance(value, bytes):
                value = value.decode("utf-8", errors="replace")
        else:
            # Pre-v0.1.53 wire shape would land here.
            value = f"(non-dict data: {type(data).__name__})"
        body = f"got message: {value}\n"
        return body.encode("utf-8")

    # v0.1.56 cross-node-link follow test page.
    def links_handler(path, data, request_id, link_id, remote_identity, requested_at):
        print(f"[nomad] links request: path={path!r}", flush=True)
        return LINKS_PAGE_TEMPLATE.format(node_hex=destination.hash.hex()).encode("utf-8")

    # v0.1.71 showcase — every supported micron feature on one page.
    def showcase_handler(path, data, request_id, link_id, remote_identity, requested_at):
        print(f"[nomad] showcase request: path={path!r}", flush=True)
        return SHOWCASE_PAGE.format(node_hex=destination.hash.hex()).encode("utf-8")

    # Upstream NomadNet registers pages as "/page/<name>.mu" — no leading
    # colon. Browser.py:67 DEFAULT_PATH="/page/index.mu", Node.py:62
    # `register_request_handler("/page/index.mu", ...)`. We had a leading
    # `:` here for a while because the micron link syntax `[label]:url`
    # got mistakenly conflated with the URL itself; that drift made our
    # client work only against this test node and silently fail against
    # real NomadNet nodes.
    destination.register_request_handler(
        path="/page/index.mu",
        response_generator=page_handler,
        allow=RNS.Destination.ALLOW_ALL,
    )
    destination.register_request_handler(
        path="/page/echo.mu",
        response_generator=echo_handler,
        allow=RNS.Destination.ALLOW_ALL,
    )
    destination.register_request_handler(
        path="/page/links.mu",
        response_generator=links_handler,
        allow=RNS.Destination.ALLOW_ALL,
    )
    destination.register_request_handler(
        path="/page/showcase.mu",
        response_generator=showcase_handler,
        allow=RNS.Destination.ALLOW_ALL,
    )

    # Print the env vars the Kotlin test needs.
    print()
    print("=" * 64)
    print("NOMADNET TEST NODE READY")
    print("=" * 64)
    print(f"NOMADNET_NODE_HASH={destination.hash.hex()}")
    print(f"NOMADNET_TCP_HOST={TCP_TARGET.split(':')[0]}")
    print(f"NOMADNET_TCP_PORT={TCP_TARGET.split(':')[1] if ':' in TCP_TARGET else '4242'}")
    print(f"NOMADNET_PAGE_PATH=/page/index.mu")
    print(f"NOMADNET_PAGE_NEEDLE=Hello from Python RNS")
    # v0.1.57 form-submission round-trip + cross-node link follow tests.
    print(f"NOMADNET_FORM_PATH=/page/echo.mu")
    print(f"NOMADNET_FORM_FIELD=message")
    print(f"NOMADNET_FORM_VALUE=hello-world")
    print(f"NOMADNET_FORM_NEEDLE=got message: hello-world")
    print(f"NOMADNET_LINKS_PATH=/page/links.mu")
    print(f"NOMADNET_SHOWCASE_PATH=/page/showcase.mu")
    print("=" * 64)
    print()
    print("[nomad] announcing every ~5 min; first announce in 2s")

    next_announce = time.time() + 2
    try:
        while True:
            now = time.time()
            if now >= next_announce:
                destination.announce()
                print(f"[nomad] announce sent", flush=True)
                next_announce = now + 300
            time.sleep(0.5)
    except KeyboardInterrupt:
        print("[nomad] stopping")


if __name__ == "__main__":
    main()
