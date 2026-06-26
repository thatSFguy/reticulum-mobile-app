# Contributing

Short version: **this is a personal, security-first app, and it is closed to feature requests.** It's released in the open so anyone can use it, audit it, or fork it — not as an open-ended community project soliciting features.

## What's welcome

- **Security reports.** This is the one kind of contribution I actively want. Report privately — see **[SECURITY.md](SECURITY.md)**. Do not open a public issue or PR for a vulnerability.
- **Bug reports for existing features.** If something that already ships is broken, a focused, reproducible bug report is welcome. Use the Bug report issue form.
- **Forks.** The app is [AGPL-3.0](LICENSE). If you want it to do something it doesn't do, fork it and build that. That is the intended path for new functionality, and the licence exists precisely to let you.

## What's not accepted

- **Feature requests / "please add X" issues.** They'll be closed. Not a judgement on the idea — adding surface works directly against the security goal of this app (smallest, most static attack vector). Fork instead.
- **Feature PRs.** Unsolicited PRs that add capabilities, dependencies, transports, or new protocols will be closed unmerged. This includes "quality of life" additions.
- **Dependency / CI / build-pipeline changes from outside.** These are the highest-risk supply-chain surface and are not accepted from contributors. (Dependabot-style auto-bumping is intentionally off.)

## Why so closed?

The threat model is a privacy/off-grid messaging tool whose users de-Google their devices on purpose. Every dependency, every parser, every transport, and every merged-on-trust line of code is attack surface. A small app that does a few things and changes rarely is one I can keep secure and reason about end to end. A feature-accreting app is not. Closing the feature firehose is a security decision, not an unfriendly one — and forking is always open to you.

## If you fork

Great — that's encouraged. Please keep the AGPL-3.0 terms, change the application ID and signing identity (don't ship under this project's package name or keys), and make clear to your users that your fork is not this project.
