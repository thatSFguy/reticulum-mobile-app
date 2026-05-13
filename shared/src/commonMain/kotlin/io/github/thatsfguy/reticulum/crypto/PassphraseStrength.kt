package io.github.thatsfguy.reticulum.crypto

/**
 * Strength bands surfaced to the UI for the identity-export passphrase
 * picker. The export wire format ([IdentityArchive]) is PBKDF2-HMAC-
 * SHA256 at 600k iterations + AES-CBC + HMAC, which is solid — but the
 * crypto only buys time proportional to the search space the passphrase
 * actually occupies. A 6-character lowercase passphrase falls in hours
 * of GPU work; a dictionary word falls in seconds. The bands and
 * thresholds below are the minimum bar `IdentityArchive.pack` enforces
 * and the UI surfaces as a meter.
 */
enum class PassphraseStrength {
    /** Reject. Too short or too narrow a character set. */
    TooWeak,

    /** Accepted, but the user is at the floor of safety. */
    Acceptable,

    /** Long enough OR mixed-class enough to make offline cracking
     *  meaningfully expensive. */
    Strong,
}

/**
 * Outcome of [assessPassphrase]: the strength band, an [acceptable]
 * shortcut for UI gating, an optional explanation [reason] suitable
 * for direct display, and a 0..4 [score] for a strength meter.
 *
 * Passphrases below [acceptable] should never reach `pack()` — the
 * underlying [IdentityArchive.pack] call also re-checks this so a
 * caller can't bypass the UI gate.
 */
data class PassphraseAssessment(
    val strength: PassphraseStrength,
    val acceptable: Boolean,
    val score: Int,
    val reason: String?,
)

/**
 * Assess [passphrase] against a length + character-class policy that
 * matches OWASP 2023 password guidance for high-value secrets:
 *
 *   - ≥ 20 characters of any kind → Strong (sentence / diceware style).
 *   - ≥ 12 characters with ≥ 3 of {lower, upper, digit, symbol} → Strong.
 *   - ≥ 12 characters with 2 of those classes → Acceptable.
 *   - anything shorter, or fewer than 2 classes → TooWeak.
 *
 * The two-route shape (length OR mixed classes) keeps two valid mental
 * models open for the user: pick a long memorable phrase, OR pick a
 * shorter random mix from a password manager. Either gets to Strong.
 *
 * This is *not* zxcvbn — it doesn't catch dictionary words or common
 * leet substitutions. Recommend zxcvbn-kmp if a future iteration wants
 * to harden against `Password123!`-class submissions. For now the
 * length floor at 12 raises the effort meaningfully and the UI copy
 * tells the user the file + passphrase together are an impersonation
 * key for life — which is the right framing whether or not zxcvbn is
 * in the loop.
 */
fun assessPassphrase(passphrase: String): PassphraseAssessment {
    val len = passphrase.length
    val hasLower  = passphrase.any { it in 'a'..'z' }
    val hasUpper  = passphrase.any { it in 'A'..'Z' }
    val hasDigit  = passphrase.any { it in '0'..'9' }
    val hasSymbol = passphrase.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' }
    val classes = listOf(hasLower, hasUpper, hasDigit, hasSymbol).count { it }

    if (len == 0) {
        return PassphraseAssessment(
            strength = PassphraseStrength.TooWeak,
            acceptable = false,
            score = 0,
            reason = "Passphrase required.",
        )
    }

    if (len >= 20) {
        return PassphraseAssessment(
            strength = PassphraseStrength.Strong,
            acceptable = true,
            score = 4,
            reason = null,
        )
    }

    if (len >= 12 && classes >= 3) {
        return PassphraseAssessment(
            strength = PassphraseStrength.Strong,
            acceptable = true,
            score = 4,
            reason = null,
        )
    }

    if (len >= 12 && classes >= 2) {
        return PassphraseAssessment(
            strength = PassphraseStrength.Acceptable,
            acceptable = true,
            score = 2,
            reason = "OK — but consider longer (≥20 chars) or 3+ character classes for stronger protection.",
        )
    }

    return PassphraseAssessment(
        strength = PassphraseStrength.TooWeak,
        acceptable = false,
        score = if (len >= 12) 1 else 0,
        reason = if (len < 12) {
            "Passphrase too short. Use ≥12 characters with mixed character classes, OR ≥20 characters of any kind."
        } else {
            "Passphrase too narrow. Include at least 2 of: lowercase, uppercase, digits, symbols. (Or use ≥20 characters of any kind.)"
        },
    )
}
