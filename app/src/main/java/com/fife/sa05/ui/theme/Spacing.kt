package com.fife.sa05.ui.theme

import androidx.compose.ui.unit.dp

/**
 * A 4 dp grid, because Material lays out on one and the app had drifted off it: paddings of
 * 12, 14, 16, 18, 20 and 28 dp appeared across the screens with no rule behind which was used
 * where, so nothing lined up between one card and the next.
 *
 * Named by intent rather than by size, so a later change to a step moves everything that meant
 * the same thing.
 */
object Space {
    /** Between a label and the thing it labels. */
    val Hairline = 4.dp

    /** Between tightly related items inside one block. */
    val Tight = 8.dp

    /** Default gap between items in a list or column. */
    val Item = 12.dp

    /** Inside a card, and along the screen's edges. */
    val Content = 16.dp

    /** Between distinct groups of content. */
    val Group = 24.dp

    /** Trailing room so the last item clears the navigation bar. */
    val ScrollBottom = 32.dp
}
