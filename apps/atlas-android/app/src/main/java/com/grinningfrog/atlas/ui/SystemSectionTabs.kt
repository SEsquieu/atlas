package com.grinningfrog.atlas.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Two columns leave room for full labels on a phone; every tab has the same rectangular bounds. */
@Composable
internal fun SystemSectionTabs(selectedSection: String, onSelected: (String) -> Unit, modifier: Modifier = Modifier) {
    val sections = listOf("Inference" to "Inference", "Data" to "Data & logs", "Intent" to "Intent", "Tools" to "Tools")
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        sections.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (id, label) ->
                    val selected = selectedSection == id
                    Surface(
                        modifier = Modifier.weight(1f).height(56.dp).selectable(selected = selected, role = Role.Tab, onClick = { onSelected(id) }),
                        shape = RoundedCornerShape(4.dp),
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Box(Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
