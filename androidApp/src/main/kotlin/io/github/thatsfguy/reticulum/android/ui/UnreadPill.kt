package io.github.thatsfguy.reticulum.android.ui

import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.thatsfguy.reticulum.android.storage.UnreadTally

/**
 * The one unread badge in the app — Messages rows, Rooms hub/room rows,
 * and both bottom-nav tabs.
 *
 * It exists to keep a single colour rule in one place, because the rule
 * is the whole point:
 *
 *  - **Red means somebody singled you out.** An `@nick` / `@hashprefix`
 *    mention in a group room, or a direct message from a contact you
 *    starred or a conversation you pinned.
 *  - **Everything else is a muted pill** — the inverse of the surface
 *    beneath it, which lands near-white on the true-black dark theme
 *    and dark grey on the beige light one. Legible in both, alarming in
 *    neither.
 *
 * Red stops meaning anything the moment ordinary traffic can turn it
 * red, which is why an unread count on its own never does.
 *
 * Renders nothing at all when there is nothing waiting, so callers can
 * place it unconditionally.
 */
@Composable
fun UnreadPill(unread: UnreadTally, modifier: Modifier = Modifier) {
    if (unread.total <= 0) return
    Badge(
        modifier = modifier,
        containerColor = if (unread.hasMention)
            MaterialTheme.colorScheme.error
        else
            MaterialTheme.colorScheme.onSurfaceVariant,
        contentColor = if (unread.hasMention)
            MaterialTheme.colorScheme.onError
        else
            MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(if (unread.total > 99) "99+" else "${unread.total}")
    }
}
