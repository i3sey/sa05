package com.fife.sa05.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.fife.sa05.R
import com.fife.sa05.ui.theme.clickableScale
import com.fife.sa05.ui.theme.fadeTransform
import com.fife.sa05.ui.theme.motionEnabled

@Composable
internal fun DashboardRow(
    title: String,
    subtitle: String,
    trailingFlag: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickableScale(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            val subtitleMotion = motionEnabled()
            AnimatedContent(
                targetState = subtitle,
                transitionSpec = { fadeTransform(subtitleMotion) },
                label = "rowSubtitle"
            ) { s ->
                Text(
                    s,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailingFlag?.let { flag ->
            FlagBadge(flag)
            Spacer(Modifier.width(12.dp))
        }
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null
        )
    }
}

@Composable
internal fun FlagBadge(flag: String) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(6.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        // An emoji flag is a glyph, not prose: it takes a scale role rather than a loose size.
        Text(flag, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
internal fun SectionTitle(title: String) {
    // A list subheader labels the group below it; at titleMedium it competed with the card
    // titles it was meant to introduce.
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}
