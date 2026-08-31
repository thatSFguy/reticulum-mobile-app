# Security policy

This is a clean-room Kotlin Multiplatform port of the Reticulum
protocol stack. The protocol layer handles end-to-end encrypted
messaging over LoRa / BLE / TCP — meaning a vulnerability here can
affect anyone who messages anyone using this client.

If you've found something, **please report it privately first.** A
public issue or PR puts every user at risk before a fix is shipped.

## Reporting a vulnerability

Two channels, in preference order:

1. **GitHub Private Vulnerability Reporting** —
   <https://github.com/thatSFguy/reticulum-mobile-app/security/advisories/new>
   Creates a private thread visible only to maintainers; you can
   collaborate on fixes there. Best for technical reports with PoC
   code, and the right channel for almost every report.

2. **Reticulum / LXMF** — if you'd rather avoid the surveilled
   channel above, open a private advisory as in #1 saying only that
   you'd like to move to LXMF, and a Reticulum identity hash will be
   exchanged there for the remainder. No email is involved.

## What to include

A useful report has:

- **Affected version(s)** — preferably the lowest version that
  reproduces. Versions before v0.1.50 had a different protocol
  layer entirely; if you're on something old, please retest against
  current `master` before reporting.
- **Severity self-assessment** — one of:
  - **Critical** — RCE, key disclosure, ability to read other users'
    messages, ability to forge announces or messages from other
    identities.
  - **High** — DoS that affects more than the vulnerable user (e.g.
    a transport node forced to drop traffic for unrelated peers),
    persistent state corruption, identity-hash leakage.
  - **Medium** — single-user DoS (process crash), local data
    leakage requiring physical / OS-level access, vulnerabilities
    that depend on a misconfiguration the user has to actively make.
  - **Low** — hardening / defense-in-depth concerns; unverified or
    speculative findings.
- **Reproducer** — minimum bytes / steps that trigger the bug.
  Test vectors live in `reference/test-vectors.json` if you want
  a starting fixture.
- **Suggested mitigation** — optional but appreciated.

## What I'll do

- Acknowledge receipt within **3 business days**. If you don't hear
  back, the email may have been filtered — please escalate via a
  different channel.
- Triage to a severity level within **7 days**.
- Targeted fix release timeline depending on severity:
  - **Critical** — patch and release within 7 days, immediately
    yank the affected versions from the GitHub release page.
  - **High** — patch and release within 30 days.
  - **Medium / Low** — fix in the next regular release cycle.
- Coordinate disclosure timing with you. The default is **90 days
  from acknowledgement** before any public mention; happy to extend
  if you need more time, or compress if a fix is already public.
- Publish a security advisory at disclosure, crediting you (or
  acknowledging your preference for anonymity).

## What this project ISN'T responsible for

This client implements the Reticulum + LXMF + NomadNet protocols
clean-room. **Vulnerabilities in those protocols themselves**, or in
upstream `markqvist/Reticulum` / `markqvist/NomadNet` /
`markqvist/LXMF` — please report to those projects. Private channels
for upstream:

- Mark Qvist publishes his email at <https://unsigned.io/>.
- For the upstream RNS protocol there's no SECURITY.md at time of
  writing; email is the working channel.

If you're not sure which project to file with, file with us and
we'll triage and forward as appropriate.

## Hall of fame

Reporters who would like to be credited will be listed here after
each disclosure cycle. Anonymous-by-default — credit only with your
explicit say-so.

## See also

- `reference/test-vectors.json` — known-good crypto round-trips you
  can use as a baseline for spotting drift.
- `../reticulum-specifications/SPEC.md` (sibling repo) — wire-format
  documentation. Vulnerabilities discovered while reading the spec
  but not reproducible against this client should be filed against
  the spec repo or upstream RNS.
