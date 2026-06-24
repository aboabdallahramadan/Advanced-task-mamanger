package net.qmindtech.tmap.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.ui.components.parseProjectColor
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapType

/** Midnight Calm project palette — spec §4.1 defaults plus a warm-clay extra. */
val PROJECT_PALETTE: List<String> = listOf(
    "#6EA8FE", // Work – blue
    "#38D39F", // Personal – teal
    "#F0A868", // Health – orange
    "#C9A0FF", // Ideas / Side – lavender
    "#F0A0A0", // Learning – rose
    "#E8A87C", // Extra – clay (accent)
)

val PROJECT_EMOJIS: List<String> = listOf(
    "📁", "💼", "🚀", "🎯", "📚", "💡", "🔧", "🎨", "🏠", "💻", "⚡", "🌟",
)

/**
 * Midnight Calm project create/edit/delete dialog.
 *
 * Signature is unchanged from v1 — call sites in [ProjectsScreen] and any future
 * screen pass through transparently.
 *
 * @param initial   null → create mode; non-null → edit mode (pre-fills fields).
 * @param onDismiss Called on cancel or backdrop tap.
 * @param onSave    Called with validated (name, color-hex, emoji) on Create/Update tap.
 * @param onDelete  When non-null, a danger-colored Delete button is shown (edit mode only).
 */
@Composable
fun ProjectEditDialog(
    initial: ProjectEntity?,
    onDismiss: () -> Unit,
    onSave: (name: String, color: String, emoji: String) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val colors = LocalTmapColors.current
    val type = LocalTmapType.current

    var name by remember { mutableStateOf(initial?.name ?: "") }
    var color by remember { mutableStateOf(initial?.color ?: PROJECT_PALETTE.first()) }
    var emoji by remember { mutableStateOf(initial?.emoji ?: PROJECT_EMOJIS.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceRaised,
        title = {
            Text(
                text = if (initial == null) "New Project" else "Edit Project",
                style = type.heading,
                color = colors.textPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // ── Name field ─────────────────────────────────────────────────
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Project name", color = colors.textSecondary) },
                    singleLine = true,
                )

                // ── Emoji picker ───────────────────────────────────────────────
                Text(
                    text = "Icon",
                    style = type.label,
                    color = colors.textSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PROJECT_EMOJIS.take(8).forEach { e ->
                        Text(
                            text = e,
                            fontWeight = if (emoji == e) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .clickable { emoji = e }
                                .semantics { contentDescription = "Select icon $e" },
                        )
                    }
                }

                // ── Color palette ──────────────────────────────────────────────
                Text(
                    text = "Color",
                    style = type.label,
                    color = colors.textSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PROJECT_PALETTE.forEach { c ->
                        val argb = parseProjectColor(c) ?: 0xFFE8A87CL
                        val isSelected = color == c
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color(argb), CircleShape)
                                .then(
                                    if (isSelected) {
                                        Modifier.border(2.dp, colors.textPrimary, CircleShape)
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable { color = c }
                                .semantics { contentDescription = "Select color $c" },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), color, emoji) },
                enabled = name.isNotBlank(),
            ) {
                Text(
                    text = if (initial == null) "Create" else "Update",
                    color = colors.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            Row {
                if (initial != null && onDelete != null) {
                    TextButton(
                        onClick = onDelete,
                        modifier = Modifier.semantics { contentDescription = "Delete project" },
                    ) {
                        Text(
                            text = "Delete",
                            color = colors.danger,
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "Cancel",
                        color = colors.textSecondary,
                    )
                }
            }
        },
    )
}
