package net.qmindtech.tmap.ui.auth

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// Mirrors the desktop role="alert" banner: assertive for errors, polite for the
// transient network notice. Error uses the danger role; network uses the warning tone.
@Composable
fun AuthBanner(text: String, isError: Boolean) {
    val container = if (isError) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val onContainer = if (isError) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = container,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .semantics { liveRegion = if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = onContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
