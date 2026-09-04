#!/usr/bin/env python3
"""
NomadNet-style test node: renderer fixtures + the live protocol harness.

Two jobs in one script:

  1. It is the peer for `NomadNetLiveTest` (shared/src/androidUnitTest) —
     a real Python RNS node hosting the `nomadnetwork.node` aspect and
     answering REQUESTs over a real Link, so the fetch path is exercised
     against upstream rather than against ourselves.

  2. It serves one fixture page per renderer feature, so a micron change
     can be verified on the phone without hunting the live mesh for a page
     that happens to use the feature. #53 (tables) and #54 (section indent)
     both shipped unverified on device for exactly that reason.

Local by default (issue #59). The node listens on 127.0.0.1 and nothing it
does touches the public mesh:

    python3 tools/test_nomadnet_node.py          # loopback only
    ./tools/phone.sh testnode                    # loopback + adb reverse

`adb reverse tcp:<port> tcp:<port>` makes the listener reachable at
127.0.0.1:<port> from the phone over the USB cable — no LAN, no firewall
rules, nothing announced anywhere. `tools/phone.sh testnode` does the whole
sequence and prints the destination hash ready to paste into "Go to page".

Attaching to a public transport node is opt-in and deliberate:

    TEST_NOMAD_TCP=rns.example.net:4242 python3 tools/test_nomadnet_node.py

The previous version of this script defaulted to a real public hub and
announced every 5 minutes, which published throwaway test destinations onto
the live network at twelve times the cadence this project's own guidance
allows (CLAUDE.md #7 — roughly hourly; transport nodes apply
`announce_rate_penalty` to frequent announcers). Announces are therefore
capped when a public attachment is on: the default there is none at all,
and an explicit interval below one hour is refused.

They stay ON for the loopback default, at a short interval — those packets
reach nothing but the client on the other end of the socket, and
`NomadNetLiveTest` waits for the node's announce (or a path response) before
it can fetch anything.

Environment:

    TEST_NOMAD_PORT=45420           port to listen on (loopback default)
    TEST_NOMAD_BIND=127.0.0.1       bind address; change it and you are
                                    exposing the node to your LAN
    TEST_NOMAD_TCP=host:port        ALSO attach to a public transport node
    TEST_NOMAD_ANNOUNCE_SECS=30     announce interval; 0 disables. Refused
                                    below 3600 when TEST_NOMAD_TCP is set
    TEST_NOMAD_STATE=~/.reticulum-mobile-app-test-nomad
                                    identity, config, files, logs
    NOMAD_LOGLEVEL=4                RNS loglevel

Usage:
    pip install rns
    python3 tools/test_nomadnet_node.py

It prints the env vars the Kotlin side reads; every line matching
`NOMADNET_*=...` can be fed straight into the test environment:

    cd shared
    NOMADNET_NODE_HASH=... NOMADNET_TCP_HOST=127.0.0.1 NOMADNET_TCP_PORT=45420 \\
        ./gradlew testDebugUnitTest --tests io.github.thatsfguy.reticulum.NomadNetLiveTest

Stop with Ctrl-C.

This is a *minimal* NomadNet — it does not implement the nomadnet TUI, just
enough to serve micron pages and files over an RNS Link's REQUEST/RESPONSE
flow, which is exactly what the app's fetchNomadPage / fetchNomadFile
exercise.
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

REPO_ROOT     = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STATE_DIR     = os.path.expanduser(os.environ.get("TEST_NOMAD_STATE", "~/.reticulum-mobile-app-test-nomad"))
IDENTITY_PATH = os.path.join(STATE_DIR, "identity")
CONFIG_DIR    = os.path.join(STATE_DIR, "config")
FILES_DIR     = os.path.join(STATE_DIR, "files")
DISPLAY_NAME  = "NomadNet Test Node"

BIND_ADDR   = os.environ.get("TEST_NOMAD_BIND", "127.0.0.1")
LISTEN_PORT = int(os.environ.get("TEST_NOMAD_PORT", "45420"))
PUBLIC_TCP  = os.environ.get("TEST_NOMAD_TCP")  # opt-in; unset = loopback only

# Announce cadence. Loopback-only announces cost nothing and the live test
# waits for one, so they stay on by default. A public attachment gets none
# unless asked for, and never faster than the hourly guidance in CLAUDE.md #7.
MIN_PUBLIC_ANNOUNCE_SECS = 3600
_announce_env = os.environ.get("TEST_NOMAD_ANNOUNCE_SECS")
if _announce_env is not None:
    ANNOUNCE_SECS = int(_announce_env)
elif PUBLIC_TCP:
    ANNOUNCE_SECS = 0
else:
    ANNOUNCE_SECS = 30

if PUBLIC_TCP and 0 < ANNOUNCE_SECS < MIN_PUBLIC_ANNOUNCE_SECS:
    sys.exit(
        f"refusing to announce every {ANNOUNCE_SECS}s onto {PUBLIC_TCP}.\n"
        f"A transport node applies announce_rate_penalty to destinations that\n"
        f"announce more often than its advertised rate (CLAUDE.md #7), and this\n"
        f"is a throwaway test destination. Use TEST_NOMAD_ANNOUNCE_SECS>={MIN_PUBLIC_ANNOUNCE_SECS},\n"
        f"or 0, or drop TEST_NOMAD_TCP and stay on loopback."
    )

# The client's cache cap (MAX_CACHED_PAGE_BYTES in ReticulumEngine.kt). The
# over-cap fixture has to land on the far side of it.
MAX_CACHED_PAGE_BYTES = 256 * 1024

# Credentials the login fixture accepts. The live test deliberately submits
# the wrong ones — reaching a credential verdict at all is the assertion.
LOGIN_USER = "fixture"
LOGIN_PASS = "fixture-password"

GUIDE_KT = os.path.join(
    REPO_ROOT,
    "shared/src/commonTest/kotlin/io/github/thatsfguy/reticulum/nomad/NomadNetMarkupGuide.kt",
)


def load_guide():
    """NomadNet's own Markup guide, read out of the Kotlin fixture.

    The same document backs the parse-level golden
    (`MicronGuideConformanceTest`) and the on-device render, so the two
    cannot drift — which is the point of serving it from here rather than
    keeping a second copy (#59.2). The Kotlin side holds it as a raw string
    literal; everything between the triple quotes is the guide verbatim.
    """
    try:
        with _orig_open(GUIDE_KT, encoding="utf-8") as f:
            src = f.read()
    except FileNotFoundError:
        return None
    start = src.find('"""')
    end = src.rfind('"""')
    if start < 0 or end <= start:
        return None
    body = src[start + 3:end]
    if "${" in body:
        # A Kotlin raw string interpolates ${...}; if upstream's guide ever
        # contains one, the fixture will have been escaped as ${'$'} and a
        # naive read would serve the escape rather than the character.
        raise SystemExit(
            f"{GUIDE_KT} contains a Kotlin interpolation — teach load_guide() "
            f"how it was escaped before serving it as micron."
        )
    return body.lstrip("\n")  # matches the Kotlin side's .trimStart()
# ---------------------------------------------------------------------------
# Fixtures.
#
# House rule for everything below (issue #59): a fixture is authored from
# UPSTREAM's rules — NomadNet's own Guide.py markup topic, MicronParser.py,
# and MarkdownToMicron.format_table_raw — never from what our parser happens
# to accept. The pre-#53 table fixture here was written against our own
# broken parser (no ":---:" alignment row, because we treated every row as
# data), so it would have passed against the bug and failed against a correct
# renderer. A fixture that comes from the implementation validates the
# implementation against itself.
#
# Two authoring traps, both hit while writing these: a backtick anywhere in
# prose opens a micron tag, and a line starting with #, -, > or < is a
# comment / rule / heading / depth-reset. Fixture prose therefore names tags
# in plain words ("the tc30 form of the table tag"), and the tags themselves
# only ever appear where they are the thing under test.
#
# "{NODE}" is replaced with this node's destination hash at serve time.
# ---------------------------------------------------------------------------

INDEX_PAGE = r"""`!Welcome to the NomadNet Test Node`!

This page is served by tools/test_nomadnet_node.py for the
NomadNetLiveTest integration test.

If our Kotlin client fetched this page successfully, the entire
Link + REQUEST/RESPONSE protocol stack works end to end on the
current TCP transport.

Hello from Python RNS.

>Renderer fixtures

One page per feature, so a screenshot diff is per-feature rather than one
wall of markup. Every page is authored from upstream's rules.

 `[Micron guide, NomadNet's own, verbatim`/page/guide.mu]
 `[Tables: alignment, markup in cells, ragged rows`/page/table.mu]
 `[Sections: depth, indent and reset`/page/sections.mu]
 `[Boxes drawn in paragraph text`/page/box.mu]
 `[Over-wide content`/page/wide.mu]
 `[Colours, page and inline`/page/colors.mu]
 `[Anchors and in-page links`/page/anchors.mu]
 `[Partials and refresh links`/page/partial.mu]
 `[Cache: this page opts out of caching`/page/nocache.mu]
 `[Cache: this page is over the 256 KB cap`/page/big.mu]
 `[Auth: ALLOW_LIST page, needs identify`/page/auth.mu]
 `[File download`/file/fixture.txt]
 `[Login form, the all-fields wildcard`/page/login.mu]

>Protocol fixtures

 `[Showcase: every micron feature on one page`/page/showcase.mu]
 `[Echo form handler`/page/echo.mu]
 `[Cross-node link sample`/page/links.mu]
"""

# Page used by the cross-node link follow test (v0.1.56). The link points
# back to this same node — what we exercise on the client side is the
# parseLinkTarget dispatch + resolveOrPrepareDestination round-trip, NOT a
# true two-node hop. A real cross-node test would need a second NomadNet.
#
# NomadNetLiveTest.crossNodeLinkRoundTripsViaParseAndFetch takes the FIRST
# link on this page and asserts it parses to CrossNode(<our hex>, …), so the
# cross-node link stays first.
LINKS_PAGE = r"""`!Cross-node link sample`!

Tap the link below. The Kotlin client should parse it via
parseLinkTarget into a CrossNode(<our hex>, /page/index.mu) and
navigate by swapping the selected destination, even though the
target is this same node.

`[Visit other node`{NODE}:/page/index.mu]
"""

# --- tables (#53) ----------------------------------------------------------
#
# Source of truth: NomadNet Guide.py TOPIC_MARKUP (the first example is
# upstream's own, verbatim) and MarkdownToMicron.format_table_raw
# (RNS/Utilities/rngit/util.py:530-631) — row 0 header, row 1 the alignment
# spec, rows 2+ data, re-emitted as micron and parsed AGAIN, which is why
# cell contents are live markup.
TABLE_PAGE = r""">Tables

>>Upstream's own example

Verbatim from NomadNet's Markup guide. Apple is green and the 5 is bold,
because cells are re-parsed as micron. The second row is the alignment
spec and must NOT render as a row of dashes.

`t
| Name | Price | Qty |
| ---- | :---: | --: |
| `F3a3Apple`f | Free | `!5`! |
| Orange | Ask, nicely | 3 |
`t

>>Per-column alignment

Three dashes is left, colon-dashes-colon is centre, dashes-colon is right.
All three columns hold the same strings, so a correct renderer shows
column 1 left, column 2 centred and column 3 right.

`t
| Left | Centre | Right |
| --- | :---: | ---: |
| abc | abc | abc |
| a much longer cell | a much longer cell | a much longer cell |
`t

>>Markup and links inside cells

The link cells must be tappable and must dispatch through the ordinary
link handler. Column widths are measured on the rendered text, not on the
markup that produced it.

`t
| Kind | Example |
| --- | --- |
| bold | `!bold`! |
| italic | `*italic`* |
| colour | `F3a3green`f and `B444shaded`b |
| same-node link | `[anchors page`/page/anchors.mu] |
| cross-node link | `[this node, by hash`{NODE}:/page/sections.mu] |
`t

>>Escaped pipe

The first data row's left cell contains one escaped pipe, so it is ONE
cell reading "a | b", not two cells.

`t
| Expression | Meaning |
| --- | --- |
| a \| b | logical or |
| a & b | logical and |
`t

>>Ragged rows

The first data row is short and the second is long. Upstream pads to the
header's column count and drops the overflow, so all three render as three
columns.

`t
| A | B | C |
| --- | --- | --- |
| 1 |
| 1 | 2 | 3 | 4 |
| 1 | 2 | 3 |
`t

>>Width ceiling

The table below opens with the width form of the table tag, asking for 20
columns. Upstream truncates cells to fit its width budget; we treat the
number as a ceiling and wrap instead, because on a phone the screen width
binds first.

`t20
| Key | Value |
| --- | --- |
| short | short |
| a much longer key than fits in twenty columns | a much longer value than fits in twenty columns |
`t

>>Wider than a phone

Eight columns, none of them short. This is the case the table renderer has
to make a decision about — whatever it does, it must not silently drop a
column.

`t
| Node | Hash | Hops | RSSI | SNR | Seen | Kind | Name |
| --- | --- | ---: | ---: | ---: | --- | --- | --- |
| alpha | a1b2c3d4e5f60718 | 1 | -97 | 8.25 | 12:04:11 | nomadnetwork.node | Alpha Repeater North |
| bravo | 0f1e2d3c4b5a6978 | 3 | -114 | -2.50 | 12:03:52 | lxmf.delivery | Bravo Handheld |
`t

>>Table placement

The alignment form of the table tag places the whole table on the page.
Per-table placement and per-column alignment are different things: this
one is centred, and its second column is still right-aligned.

`tc40
| Centred table | Right column |
| --- | ---: |
| a | b |
`t
"""

# --- sections (#54) --------------------------------------------------------
SECTIONS_PAGE = r""">Sections, depth and indent

Each leading angle bracket on a heading line raises the section depth, and
the depth applies to EVERY line after the heading, not just the heading
itself (MicronParser.py:285-318). A single closing bracket on its own line
resets the depth to 0.

Upstream indents both sides: left_indent and right_indent are both
(depth-1) * SECTION_INDENT, with SECTION_INDENT = 2 (MicronParser.py:35,
:418-422). We indent the left only and cap it, because a phone cannot spend
that width twice over. The shape below is what to check.

>Depth 1

This paragraph sits at depth 1. It should be flush with the left margin,
level with its own heading, and it should wrap at that same margin on every
line — which is the part a one-line fixture cannot show, so here is a second
sentence to force a wrap at any phone width.

>>Depth 2

This paragraph sits at depth 2 and should be indented one step from the
paragraph above. Its heading is indented by the same step: a heading and
its body move together, they are not indented relative to each other.

>>>Depth 3

Depth 3, one more step in. A page that nests this far is using structure to
carry meaning, which is exactly why the indent matters: flattened, this
paragraph reads as a sibling of the depth-1 text rather than a grandchild
of it.

>>>>Depth 4

Depth 4, past our three-step cap. This should render at the same indent as
depth 3 rather than marching off the right edge — a deliberate divergence.

<

Back to depth 0 after the reset. This paragraph must be flush left again;
if it is still indented, the reset was dropped.

>>Depth 2 again

Depth rises again straight after a reset, without needing a depth-1 heading
first.

>>Rules and literals inside a section

-

Both the rule above and the literal block below carry the section's indent
too. Upstream indents every block in a section, not only its prose.

`=
literal inside a depth-2 section
`=
"""
# --- boxes in paragraph text (#58) -----------------------------------------
#
# The reported case: NomadNet forums title their threads with a double-line
# frame drawn in ORDINARY PARAGRAPH TEXT, not inside a literal block. In a
# proportional body font those borders wrap onto a second row and the
# verticals are stranded — a staircase of stray bars. looksPreformatted
# (a run of 4+ box-drawing characters) is what has to catch it.
BOX_PAGE = r""">Boxes drawn in paragraph text

Everything below is ordinary paragraph text. None of it is inside a
literal block, which is the whole point: the renderer has to notice the
frame on its own.

╔══════════════════════════════════════════════════════════════╗
║  Thread: LoRa antenna comparison, 33 replies                 ║
║  Last post: 2026-09-03 by kb9xyz                             ║
╚══════════════════════════════════════════════════════════════╝

A single-line frame, which is just as common:

┌──────────────────────────────────────────────────────────────┐
│  Board index                                                 │
├──────────────────────────────────────────────────────────────┤
│  General          14 threads                                 │
│  Radio            41 threads                                 │
└──────────────────────────────────────────────────────────────┘

A heavy frame, and one built from block elements:

┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃  Announcements                                               ┃
┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛

████████████████████████████████████████████████████████████████
▒▒▒  shaded banner  ▒▒▒
████████████████████████████████████████████████████████████████

>>What must NOT be caught

This paragraph mentions a single ─ character in the middle of a sentence,
and one │ here, but it is prose and must stay prose: it has to wrap
normally at the viewport, in the body font, like every other paragraph on
this page. Prose that is forced into a monospace horizontal scroller
because it once said "─" is a worse outcome than the bug being fixed.

An ASCII-fallback frame is deliberately not caught either, because plus,
dash and pipe are ordinary punctuation:

+--------------------------------------------------------------+
|  ASCII frame: expected to wrap, and that is the accepted cost |
+--------------------------------------------------------------+
"""

# --- over-wide content (#58) -----------------------------------------------
WIDE_PAGE = r""">Over-wide content

Micron is written for an 80-column terminal. Everything below is wider
than a phone. The rule is one rule for every over-wide block: clip at the
viewport, monospace, no reflow, its own horizontal scroller. What must NOT
happen is shrink-to-illegible or wrap-into-garbage.

>>Box art in a literal block

`=
 ____      _   _            _
|  _ \ ___| |_(_) ___ _   _| |_   _ _ __ ___
| |_) / _ \ __| |/ __| | | | | | | | '_ ` _ \
|  _ <  __/ |_| | (__| |_| | | |_| | | | | | |
|_| \_\___|\__|_|\___|\__,_|_|\__,_|_| |_| |_|
`=

>>A literal block that is NOT art

Prose and code in a literal block used to get no treatment at all: the
old heuristic only flagged blocks that looked like art, so this one
wrapped at body size and a wrapped monospace block is misaligned by
definition.

`=
2026-09-03 11:58:04 [INFO ] transport: path request for a1b2c3d4e5f60718 answered from cache, 3 hops, next hop 0f1e2d3c4b5a6978
2026-09-03 11:58:05 [DEBUG] link ab12: RESPONSE 24118 bytes as resource, 47 parts, window 4, rtt 1.82s
val response = engine.fetchNomadPage(destinationHash = hashHex, path = "/page/index.mu", data = null, identify = false)
`=

>>A very long single line

`=
one line, no spaces, nothing to wrap on: ................................................................................................................................ end
`=

>>Dividers

A plain divider, which spans the viewport:

-

A custom-rune divider. Upstream repeats the rune to the terminal width;
ours must span the viewport rather than a fixed repeat count that
overflows a narrow screen or falls short of a wide one:

-═

-∿

-•
"""

# --- colours ---------------------------------------------------------------
#
# Page-level colours come from the "#!" header block (Micron.kt:266-278),
# inline ones from the F/B tags in both 3- and 6-hex forms, as the guide's
# Colors topic documents.
COLORS_PAGE = """\
#!bg=222222
#!fg=ddddcc

>Colours

This page sets a page background and a page foreground in its header
block. On a light-themed client the page must honour them, and it must
still keep readable contrast for anything it draws itself: link colour,
the address row, the section rule.

>>Inline foreground

`F3a3Three-hex green`f, `F33aa33Six-hex green`f, `F79dblue-ish`f,
`Fd44red`f, and back to the page foreground.

>>Inline background

`B444shaded background`b, `B333333six-hex shaded`b, then back to the page
background.

>>Both at once, with styles

`F222`Bddd`!dark on light, bold`!`f`b, then reset.

>>Reset

The double-backtick tag resets colour and style together; the text after
it must be exactly the page foreground on the page background, with no
carry-over from above.

`F3a3`!green and bold``back to plain
"""

# --- anchors ---------------------------------------------------------------
ANCHORS_PAGE = r""">Anchors and in-page links

Two kinds of anchor share one namespace per page: the ones a heading
generates from its own text, and the ones the author declares. First
declaration wins (MicronParser.py:308-311).

>>Contents

 `[Jump to a declared anchor`#middle]
 `[Jump to a heading auto-slug`#the-far-section]
 `[Jump to the top`#top]
 `[A missing anchor, which must not navigate anywhere`#no-such-anchor]

`:top The line above declares an anchor named "top" and renders as
ordinary text: an anchor declaration is zero-width, the name is consumed
and nothing is drawn.

>>Filler

Scrolling has to be visible for a jump to be visible, so the next lines
exist purely to push the targets off the first screen.

Filler line 1.
Filler line 2.
Filler line 3.
Filler line 4.
Filler line 5.
Filler line 6.
Filler line 7.
Filler line 8.
Filler line 9.
Filler line 10.
Filler line 11.
Filler line 12.
Filler line 13.
Filler line 14.
Filler line 15.
Filler line 16.
Filler line 17.
Filler line 18.
Filler line 19.
Filler line 20.

`:middle This paragraph is the target of the declared anchor "middle".

More filler.
Filler line 21.
Filler line 22.
Filler line 23.
Filler line 24.
Filler line 25.
Filler line 26.
Filler line 27.
Filler line 28.
Filler line 29.
Filler line 30.

>>The far section

This heading declares no anchor of its own. Its slug comes from its text,
so "the-far-section" reaches it — that is the auto-slug case, and the
reason a page's table of contents can link to headings the author never
tagged.
"""

# --- partials --------------------------------------------------------------
PARTIAL_PAGE = r""">Partials

A partial is a server-side include: the client fetches the inner page and
substitutes the response where the placeholder sits
(MicronParser.py:95-141).

>>Plain include

The line below includes the echo page. It should be replaced by that
page's rendered output, not by its markup and not by a loading placeholder
that never resolves.

`{/page/echo.mu}

>>Include with a refresh interval and an id

The next one names itself "clock" and asks for a refresh every 5 seconds.
The included page prints the node's clock, so a working refresh is visible
without touching anything.

`{/page/clock.mu`5`pid=clock}

>>Refresh links

This link refreshes only the partial above, without reloading the page:

`[Refresh the clock partial`p:clock]

And this one names two ids, only one of which exists on this page:

`[Refresh clock and a missing id`p:clock:nosuchpartial]

>>Refusals

A refresh interval under one second is dropped by upstream, to defend
against a page configured to spam the link. The partial below asks for
0.2s and must therefore be included ONCE and never refreshed:

`{/page/clock.mu`0.2`pid=toofast}
"""

# --- cache -----------------------------------------------------------------
#
# The "#!c=0" header means "do not keep this page", which has to include the
# copy already kept (#52). The client must render this page with no
# "Last pulled" age beside it and must drop any row it already held.
NOCACHE_PAGE = """\
#!c=0

>This page opts out of caching

Its header sets a cache TTL of zero. Fetch it once, leave the browser, come
back: there must be no stored copy and no "last pulled" age, and the
clear-cache affordance must not offer to clear a page that was never kept.

Server clock at render time: {CLOCK}

Fetch it again — if the timestamp above changed, the page came off the
network. If it did not, it came out of a cache that should not exist.
"""

# --- auth ------------------------------------------------------------------
AUTH_PAGE = r""">Authenticated page

You are reading this over a link that identified to the node, and the
node's ALLOW_LIST admitted your identity hash. Without the identify step,
this request is refused before the handler runs.

The client header must say it is identifying to this node whenever the
toggle is on, wherever the toggle itself lives (SPEC §11.6.6).

`[Back to the index`/page/index.mu]
"""

ENROLL_PAGE_HEADER = r""">Enrolment

This page is open to everyone and exists so the auth fixture can be tested
without restarting the node: identify to this node, load this page, and
your identity hash is added to the ALLOW_LIST that guards /page/auth.mu.
"""

# --- login form ------------------------------------------------------------
#
# The all-fields wildcard: real NomadNet forms rarely name their widgets,
# they submit with a trailing `*`. Pre-1.2.114 buildFormSubmitData had no
# "*" case, so the POST carried the link's own variables and nothing the
# user had typed, and every such login silently re-rendered the empty form.
LOGIN_PAGE = r""">Login

A form whose submit link uses the all-fields wildcard rather than naming
its widgets, which is how real node-side applications are written.

Username: `B444`<username`>`b

Password: `B444`<!password`>`b

Remember me: `<?|remember|yes|*`Stay signed in>

`[  Log in  `:/page/login.mu`action=submit|*]

The node answers with a credential verdict when it receives both fields,
and re-renders this blank form when it does not — which is exactly the
symptom the wildcard bug produced.
"""

LOGIN_OK = r""">Login accepted

The node received field_username and field_password, and they matched the
fixture credentials.

`[Back to the login form`/page/login.mu]
"""

LOGIN_BAD = r""">Invalid credentials

The node received field_username = "{USER}" and a password of {PWLEN}
characters, and rejected them.

This page is the pass condition for the all-fields wildcard test: reaching
a credential verdict at all proves the typed fields were submitted. A
re-render of the blank login form would mean they were not.

`[Back to the login form`/page/login.mu]
"""
# --- showcase --------------------------------------------------------------
#
# Kept from v0.1.71: every feature on ONE page, for a quick "did anything
# fall over" pass. The per-feature pages above are what you use to verify a
# specific change; this one is the smoke test.
SHOWCASE_PAGE = r"""#!c=60
#!bg=eeece6
#!fg=222

>Showcase page — all supported micron

This page exercises every feature the Kotlin browser claims to
render. If any section looks wrong, we know exactly which parser
branch to fix.

Source line breaks are preserved per MicronParser.py:82-93 — these
three lines render on three separate lines, NOT collapsed into a
single space-joined paragraph.

>>Section A — inline formatting

`!Bold`! ordinary `_underlined`_ ordinary `*italic`* ordinary,
`Ff00red text`f, `F0a0green text`f, `B888shaded`b ordinary,
`!`*`_bold-italic-underlined`!`*`_, full reset → `! bold ``and
this part is fully reset to plain.

>>Section B — alignment

`cThis line is centered.
`rThis line is right-aligned.
`lBack to left.

>>Section C — links

`[same-node link → /page/echo.mu`/page/echo.mu]
`[bare-hash link (us, default path)`{NODE}]
`[cross-node link → /page/index.mu`{NODE}:/page/index.mu]
`[nnn shorthand`nnn@{NODE}:/page/links.mu]

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

Row 1 is the alignment spec, not data: a correct renderer shows three
columns aligned left / centre / right and never draws a row of dashes.
Before #53 we treated every row as data, and the fixture that used to
live here was written to match that bug.

`t60
| Header A | Header B | Header C |
| --- | :---: | ---: |
| 1 | 2 | 3 |
| foo | `!bar`! | `[baz`/page/index.mu] |
`t

>>Section F — horizontal rules

Single dash → default rune:
-
Custom rune (═):
-═
Custom rune (•):
-•

>>Section G — literal block

Inside a literal block, \`! and \`* and \`[link\] are all preserved
verbatim — no parsing.

`=
#!/usr/bin/env bash
echo "this # comment is preserved inside a literal block"
echo "and `!so are`! these `*backticks`*"
`=

>>Section H — partial (server-side include)

The placeholder below should fetch /page/echo.mu and render the
result inline, replacing the "⧖ Loading…" text:

`{/page/echo.mu}

>>Section I — escape and edge cases

\>This line starts with a backslash so it isn't a heading.
\#And this one is a literal hash, not a comment.

# This IS a real comment and should be dropped from the render.

>>Section J — anti-features that must NOT render as HRs

These three lines are upstream-literal text per
MicronParser.py:266-273. Pre-v0.1.58 our parser wrongly matched
them as horizontal rules:

---
===
\=

End of showcase. Tap Reload to retest after a code change.
"""

# --- generated pages -------------------------------------------------------

def big_page():
    """A page deliberately over the client's cache cap (#52).

    Fetched content must render, and nothing must be written to (or left
    in) the page cache — so the browser must not show a "last pulled" age
    beside it.
    """
    head = (
        ">A page over the cache cap\n"
        "\n"
        f"This page is larger than MAX_CACHED_PAGE_BYTES ({MAX_CACHED_PAGE_BYTES} bytes),\n"
        "so the client renders it but must not keep it — including any copy it\n"
        "already held from an earlier, smaller version of this page.\n"
        "\n"
        "It also arrives as a multi-part Resource rather than a single RESPONSE\n"
        "packet, which is the other half of what it exercises.\n"
        "\n"
        ">>Filler\n"
        "\n"
    )
    line = "Filler line {n}: " + ("x" * 60) + "\n"
    out = [head]
    total = len(head)
    n = 0
    while total < MAX_CACHED_PAGE_BYTES + 4096:
        n += 1
        chunk = line.replace("{n}", str(n))
        out.append(chunk)
        total += len(chunk)
    return "".join(out)


def clock_page():
    """Included by the partial fixture; changes every second so a working
    refresh is visible without touching anything."""
    return (
        "`F3a3Node clock: " + time.strftime("%H:%M:%S") + "`f\n"
    )
# ---------------------------------------------------------------------------
# RNS config.
#
# Rewritten on every start, deliberately: it is derived from the environment
# and a stale file is how the old version kept pointing a "local" run at the
# public hub it was last told about.
# ---------------------------------------------------------------------------

CONFIG_HEAD = """\
[reticulum]
  enable_transport = False
  share_instance = No
  shared_instance_port = 37448
  instance_control_port = 37449
  panic_on_interface_error = No

[logging]
  loglevel = 4

[interfaces]

  [[Test Nomad Local]]
    type = TCPServerInterface
    interface_enabled = true
    listen_ip = {bind}
    listen_port = {port}
"""

CONFIG_PUBLIC = """
  [[Test Nomad Public]]
    type = TCPClientInterface
    interface_enabled = true
    target_host = {host}
    target_port = {port}
    name = test_nomad_public
"""


def write_config():
    os.makedirs(CONFIG_DIR, exist_ok=True)
    body = CONFIG_HEAD.format(bind=BIND_ADDR, port=LISTEN_PORT)
    if PUBLIC_TCP:
        host, _, port = PUBLIC_TCP.rpartition(":")
        if not host:
            host, port = PUBLIC_TCP, "4242"
        body += CONFIG_PUBLIC.format(host=host, port=port)
    path = os.path.join(CONFIG_DIR, "config")
    with _orig_open(path, "w") as f:
        f.write(body)
    return path


def write_fixture_file():
    """The /file/ fixture. Small, text, and self-describing — the download
    path is what is under test, not the payload."""
    os.makedirs(FILES_DIR, exist_ok=True)
    path = os.path.join(FILES_DIR, "fixture.txt")
    with _orig_open(path, "w", encoding="utf-8") as f:
        f.write(
            "Downloaded from the NomadNet test node.\n"
            "\n"
            "If you are reading this in the app's download viewer, the /file/\n"
            "path worked end to end: REQUEST, Resource with a metadata prefix\n"
            "(SPEC §10.2 step 1), name lifted from metadata['name'], body\n"
            "written out with the prefix stripped.\n"
        )
    return path


def main():
    if os.path.exists(IDENTITY_PATH):
        identity = RNS.Identity.from_file(IDENTITY_PATH)
        print(f"[nomad] loaded existing identity from {IDENTITY_PATH}", flush=True)
    else:
        os.makedirs(STATE_DIR, exist_ok=True)
        identity = RNS.Identity()
        identity.to_file(IDENTITY_PATH)
        print(f"[nomad] created new identity at {IDENTITY_PATH}", flush=True)

    config_path = write_config()
    fixture_file = write_fixture_file()
    print(f"[nomad] config: {config_path}", flush=True)
    print(f"[nomad] listening on {BIND_ADDR}:{LISTEN_PORT}"
          + (f", also attached to {PUBLIC_TCP}" if PUBLIC_TCP else " (loopback only)"), flush=True)

    RNS.Reticulum(configdir=CONFIG_DIR, loglevel=int(os.environ.get("NOMAD_LOGLEVEL", "4")))

    # Hosting destination — IN means "we accept inbound traffic to this".
    destination = RNS.Destination(
        identity, RNS.Destination.IN, RNS.Destination.SINGLE,
        "nomadnetwork", "node",
    )
    destination.set_proof_strategy(RNS.Destination.PROVE_ALL)
    node_hex = destination.hash.hex()

    guide = load_guide()
    if guide is None:
        print(f"[nomad] WARNING: {GUIDE_KT} not found — /page/guide.mu will say so", flush=True)

    # Identities admitted to the ALLOW_LIST page. Mutable and passed by
    # reference to register_request_handler, so /page/enroll.mu can add to it
    # while the node runs — the auth fixture is testable without a restart.
    allowed = []

    def render(body):
        return body.replace("{NODE}", node_hex).encode("utf-8")

    def static(body):
        def handler(path, data, request_id, link_id, remote_identity, requested_at):
            log_request(path, data, link_id, remote_identity)
            return render(body)
        return handler

    def log_request(path, data, link_id, remote_identity):
        who = RNS.prettyhexrep(remote_identity.hash) if remote_identity else "anon"
        link = RNS.prettyhexrep(link_id) if link_id else None
        extra = f" data={data!r}" if data else ""
        print(f"[nomad] request: path={path!r} link={link} from={who}{extra}", flush=True)

    # --- dynamic handlers --------------------------------------------------

    def echo_handler(path, data, request_id, link_id, remote_identity, requested_at):
        # v0.1.57. Pre-v0.1.53 our REQUEST envelope shape was wrong (data was
        # bin, not dict) and this handler would have seen bytes, so
        # field_message would never be in env_map — the regression this
        # catches.
        log_request(path, data, link_id, remote_identity)
        if isinstance(data, dict):
            value = data.get("field_message", "(missing field_message)")
            if isinstance(value, bytes):
                value = value.decode("utf-8", errors="replace")
        else:
            value = f"(non-dict data: {type(data).__name__})"
        return f"got message: {value}\n".encode("utf-8")

    def nocache_handler(path, data, request_id, link_id, remote_identity, requested_at):
        log_request(path, data, link_id, remote_identity)
        return NOCACHE_PAGE.replace("{CLOCK}", time.strftime("%H:%M:%S")).encode("utf-8")

    def big_handler(path, data, request_id, link_id, remote_identity, requested_at):
        log_request(path, data, link_id, remote_identity)
        body = big_page().encode("utf-8")
        print(f"[nomad] serving {len(body)} bytes (cap is {MAX_CACHED_PAGE_BYTES})", flush=True)
        return body

    def clock_handler(path, data, request_id, link_id, remote_identity, requested_at):
        log_request(path, data, link_id, remote_identity)
        return clock_page().encode("utf-8")

    def guide_handler(path, data, request_id, link_id, remote_identity, requested_at):
        log_request(path, data, link_id, remote_identity)
        if guide is None:
            return (
                ">Guide fixture missing\n\n"
                "NomadNetMarkupGuide.kt was not found next to this script.\n"
            ).encode("utf-8")
        return guide.encode("utf-8")

    def enroll_handler(path, data, request_id, link_id, remote_identity, requested_at):
        log_request(path, data, link_id, remote_identity)
        if remote_identity is None:
            return (
                ENROLL_PAGE_HEADER
                + "\n`Fd44You did not identify on this link`f, so there is nothing to\n"
                  "enrol. Turn the identify toggle on and load this page again.\n"
            ).encode("utf-8")
        h = remote_identity.hash
        if h not in allowed:
            allowed.append(h)
            print(f"[nomad] enrolled {RNS.prettyhexrep(h)} for /page/auth.mu", flush=True)
        return (
            ENROLL_PAGE_HEADER
            + f"\n`F3a3Enrolled`f {h.hex()}\n\n"
              "`[Open the authenticated page`/page/auth.mu]\n"
        ).encode("utf-8")

    def login_handler(path, data, request_id, link_id, remote_identity, requested_at):
        # v1.2.114 — the `*` (all fields) wildcard. A form that receives no
        # fields must render the blank form again: that re-render IS the
        # symptom the wildcard bug produced, so it has to stay reachable.
        log_request(path, data, link_id, remote_identity)
        fields = data if isinstance(data, dict) else {}

        def field(name):
            v = fields.get("field_" + name)
            if isinstance(v, bytes):
                v = v.decode("utf-8", errors="replace")
            return v

        user, password = field("username"), field("password")
        if not user and not password:
            return render(LOGIN_PAGE)
        if user == LOGIN_USER and password == LOGIN_PASS:
            return render(LOGIN_OK)
        return render(
            LOGIN_BAD
            .replace("{USER}", str(user))
            .replace("{PWLEN}", str(len(password or "")))
        )

    def file_handler(path, data, request_id, link_id, remote_identity, requested_at):
        # Upstream shape, NomadNet Node.py:128-141:
        #   return [open(fdest, "rb"), {"name": os.path.basename(fdest).encode("utf-8")}]
        # RNS wraps that into a Resource whose metadata carries the filename
        # (Link.py:895), which is where the client's DownloadedFile.filename
        # comes from.
        log_request(path, data, link_id, remote_identity)
        return [
            _orig_open(fixture_file, "rb"),
            {"name": os.path.basename(fixture_file).encode("utf-8")},
        ]

    # --- registration ------------------------------------------------------
    #
    # Upstream NomadNet registers pages as "/page/<name>.mu" — no leading
    # colon. Browser.py:67 DEFAULT_PATH="/page/index.mu", Node.py:62
    # `register_request_handler("/page/index.mu", ...)`. We had a leading `:`
    # here for a while because the micron link syntax `[label]:url` got
    # conflated with the URL itself; that drift made our client work only
    # against this test node and fail against real NomadNet nodes.

    pages = {
        "/page/index.mu":    static(INDEX_PAGE),
        "/page/links.mu":    static(LINKS_PAGE),
        "/page/showcase.mu": static(SHOWCASE_PAGE),
        "/page/table.mu":    static(TABLE_PAGE),
        "/page/sections.mu": static(SECTIONS_PAGE),
        "/page/box.mu":      static(BOX_PAGE),
        "/page/wide.mu":     static(WIDE_PAGE),
        "/page/colors.mu":   static(COLORS_PAGE),
        "/page/anchors.mu":  static(ANCHORS_PAGE),
        "/page/partial.mu":  static(PARTIAL_PAGE),
        "/page/guide.mu":    guide_handler,
        "/page/echo.mu":     echo_handler,
        "/page/nocache.mu":  nocache_handler,
        "/page/big.mu":      big_handler,
        "/page/clock.mu":    clock_handler,
        "/page/enroll.mu":   enroll_handler,
        "/page/login.mu":    login_handler,
        "/file/fixture.txt": file_handler,
    }
    for page_path, handler in pages.items():
        destination.register_request_handler(
            path=page_path,
            response_generator=handler,
            allow=RNS.Destination.ALLOW_ALL,
        )

    # The one auth-gated path: ALLOW_LIST refuses the REQUEST outright unless
    # the link identified with an admitted identity (SPEC §11.6.6).
    destination.register_request_handler(
        path="/page/auth.mu",
        response_generator=static(AUTH_PAGE),
        allow=RNS.Destination.ALLOW_LIST,
        allowed_list=allowed,
    )

    # Print the env vars the Kotlin test needs. Every NOMADNET_* line here is
    # machine-read by tools/phone.sh and by the CI fetch check, so keep the
    # `KEY=value` shape.
    print()
    print("=" * 64)
    print("NOMADNET TEST NODE READY")
    print("=" * 64)
    print(f"NOMADNET_NODE_HASH={node_hex}")
    print(f"NOMADNET_TCP_HOST={BIND_ADDR}")
    print(f"NOMADNET_TCP_PORT={LISTEN_PORT}")
    print(f"NOMADNET_PAGE_PATH=/page/index.mu")
    print(f"NOMADNET_PAGE_NEEDLE=Hello from Python RNS")
    # v0.1.57 form-submission round-trip + cross-node link follow tests.
    print(f"NOMADNET_FORM_PATH=/page/echo.mu")
    print(f"NOMADNET_FORM_FIELD=message")
    print(f"NOMADNET_FORM_VALUE=hello-world")
    print(f"NOMADNET_FORM_NEEDLE=got message: hello-world")
    print(f"NOMADNET_LINKS_PATH=/page/links.mu")
    print(f"NOMADNET_SHOWCASE_PATH=/page/showcase.mu")
    # #57 conformance fixture, served from the same source as the golden.
    print(f"NOMADNET_GUIDE_PATH=/page/guide.mu")
    # v1.2.114 all-fields wildcard: the test submits the WRONG credentials
    # on purpose — a credential verdict proves the typed fields arrived.
    print(f"NOMADNET_LOGIN_PATH=/page/login.mu")
    print(f"NOMADNET_LOGIN_NEEDLE=Invalid credentials")
    print("=" * 64)
    print()
    print("Fixture pages: " + ", ".join(sorted(pages)) + ", /page/auth.mu")
    print("Paste the node hash into the app's address row to browse them.")
    if ANNOUNCE_SECS:
        print(f"[nomad] announcing every {ANNOUNCE_SECS}s"
              + (" onto the public attachment" if PUBLIC_TCP else " (loopback only)"))
    else:
        print("[nomad] not announcing — reach it by pasting the hash above")
    print(flush=True)

    next_announce = time.time() + 2 if ANNOUNCE_SECS else None
    try:
        while True:
            if next_announce is not None and time.time() >= next_announce:
                destination.announce()
                print("[nomad] announce sent", flush=True)
                next_announce = time.time() + ANNOUNCE_SECS
            time.sleep(0.5)
    except KeyboardInterrupt:
        print("[nomad] stopping")


if __name__ == "__main__":
    main()
