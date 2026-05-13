# Export compliance

Apple asks every App Store submission a series of yes/no questions about whether the binary contains cryptography subject to U.S. Export Administration Regulations (EAR). This file documents the correct answer set and the rationale so the volunteer doesn't have to derive it cold under a submission deadline.

## App Store Connect questionnaire — answers

Q: **Does your app use encryption?**
A: **Yes.**

Reticulum Mobile performs end-to-end encryption of LXMF messages (Curve25519 ECDH + HKDF-SHA256 + AES-256-CBC + HMAC-SHA256), digital signatures (Ed25519), and password-based identity backups (PBKDF2-HMAC-SHA256). The app also signs and verifies link-protocol handshakes per the Reticulum SPEC.

Q: **Does your app qualify for any of the exemptions provided in Category 5, Part 2 of the U.S. Export Administration Regulations?**
A: **Yes.**

Specifically:

- The app uses **only encryption that is available within iOS** — every cryptographic primitive is implemented via Apple's `CryptoKit` (Curve25519 / Ed25519) or `CommonCrypto` (AES-CBC / HMAC-SHA256 / PBKDF2). The app does not bundle its own cryptographic library; no implementations are statically linked. This places the app under the `5D002` Note 4 exemption (encryption is **ancillary** to the primary function and uses commodity hardware/OS-provided crypto).

- The app's primary function is **messaging over a radio mesh**, not cryptography. Encryption is a means to deliver the messaging functionality, not the marketed feature.

- The app is **mass-market, royalty-free, and distributed without restriction** (this App Store submission, plus the public GitHub repository under MIT license).

Q: **Does your app implement any encryption algorithms that are proprietary or not accepted as standard by international standard bodies (IEEE, IETF, ITU, etc.)?**
A: **No.**

Every algorithm used is an open international standard:
- Curve25519 / Ed25519 — RFC 7748 / RFC 8032
- HKDF — RFC 5869
- AES — FIPS 197
- HMAC-SHA256 — RFC 2104 + FIPS 180-4
- PBKDF2 — RFC 2898

Q: **Does your app implement encryption for purposes other than user authentication or digital rights management?**
A: **Yes** — message confidentiality (point-to-point encryption of LXMF payloads).

This is the question that funnels the submission into the **5D002 Note 4 exemption** path. Apple's questionnaire applies the exemption automatically once you affirm that the app uses standard algorithms (above question) and that encryption is implemented via iOS-provided APIs.

## Conclusion

The app qualifies for the standard "uses-encryption-available-in-iOS" exemption. **No annual self-classification report (CCATS / ERN / ENC) needs to be filed with the U.S. Bureau of Industry and Security** for this app, because Note 4 of Category 5 Part 2 exempts it from the licensing requirement entirely.

In App Store Connect's *Encryption Documentation* section, the prompt will offer a checkbox-style summary ("My app uses encryption", "My app qualifies for an exemption", "My app does not require export documentation"). Tick:

- "My app uses encryption" — **YES**
- "My app's encryption qualifies for an exemption" — **YES**
- "I confirm that I will provide annual self-classification reports to the U.S. government if required" — **NO** (not required for this exemption category)

## References for the volunteer

- Apple's official guidance: <https://developer.apple.com/documentation/security/complying_with_encryption_export_regulations>
- BIS Note 4 to Category 5 Part 2 (the exemption): <https://www.bis.doc.gov/index.php/regulations/export-administration-regulations-ear>
- The relevant Apple legal page (search for "exemption" within): <https://help.apple.com/app-store-connect/#/dev88f5c7bf9>

If U.S. export-compliance regulations change after the date below and the existing answer set no longer applies, update this file before re-submitting a new tag's build.

**Document last reviewed:** 2026-05-13 (Phase 3 image attachments commit). The crypto stack hasn't changed materially since v1.0.x; only the receiver-side fields[6] extraction and image storage shipped in v1.1.15.
