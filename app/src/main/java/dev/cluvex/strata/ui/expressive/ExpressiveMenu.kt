package dev.cluvex.strata.ui.expressive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

class MenuEntry(
    val text: String,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
    val supporting: String? = null,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveMenu(expanded: Boolean, onDismiss: () -> Unit, groups: List<List<MenuEntry>>, modifier: Modifier = Modifier) {
    DropdownMenuPopup(expanded = expanded, onDismissRequest = onDismiss, modifier = modifier) {
        val shown = groups.filter { it.isNotEmpty() }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            shown.forEachIndexed { gi, group ->
                if (gi > 0) Spacer(Modifier.height(2.dp))
                DropdownMenuGroup(
                    shapes = MenuDefaults.groupShape(gi, shown.size),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    group.forEachIndexed { i, entry ->
                        DropdownMenuItem(
                            onClick = entry.onClick,
                            text = { Text(entry.text) },
                            shape = when {
                                group.size == 1 -> MenuDefaults.standaloneItemShape
                                i == 0 -> MenuDefaults.leadingItemShape
                                i == group.lastIndex -> MenuDefaults.trailingItemShape
                                else -> MenuDefaults.middleItemShape
                            },
                            leadingIcon = entry.icon?.let { icon ->
                                { Icon(icon, contentDescription = null, modifier = Modifier.size(MenuDefaults.LeadingIconSize)) }
                            },
                            supportingText = entry.supporting?.let { s -> { Text(s) } },
                            enabled = entry.enabled,
                        )
                    }
                }
            }
        }
    }
}
