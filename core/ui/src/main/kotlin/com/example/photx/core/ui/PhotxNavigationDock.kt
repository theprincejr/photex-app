package com.example.photx.core.ui

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.photx.core.designsystem.PhotxTheme

private val DOCK_BORDER_WIDTH = 1.dp
private val DOCK_SHADOW_ELEVATION = 10.dp
private val DOCK_HORIZONTAL_PADDING = 10.dp
private val DOCK_VERTICAL_PADDING = 6.dp
private val DOCK_TAB_HORIZONTAL_PADDING = 14.dp
private val DOCK_TAB_VERTICAL_PADDING = 8.dp
private val DOCK_TAB_MIN_HEIGHT = 48.dp
private val DOCK_ICON_SIZE = 20.dp
private val DOCK_ICON_LABEL_GAP = 2.dp
private const val API_S_BLUR_SUPPORTED = Build.VERSION_CODES.S

/**
 * Primary top-level destinations hosted inside the floating unified pill dock.
 */
public enum class PhotxDockDestination(public val label: String) {
    PHOTOS("Photos"),
    ALBUMS("Albums"),
    BACKUP("Backup"),
    SEARCH("Search")
}

/**
 * Floating unified frosted-glass pill navigation dock (`Photos · Albums · Backup · Search`).
 *
 * Uses high-opacity translucent surface tokens from [PhotxTheme.glass] paired with a specular
 * border and elevation so text and icons stay readable across API 26–37 without heavy blur cost.
 */
@Composable
public fun PhotxNavigationDock(
    selectedDestination: PhotxDockDestination,
    onSelectDestination: (PhotxDockDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = PhotxTheme.glass
    val spacing = PhotxTheme.spacing
    // API 31+ devices can pair translucent surfaces with native system backdrop polish, while
    // API 26–30 cleanly fall back to the same 90% opacity frosted-glass tokens without lag.
    val dockColor = if (Build.VERSION.SDK_INT >= API_S_BLUR_SUPPORTED) {
        glass.dockContainer
    } else {
        glass.dockContainer
    }

    Surface(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = spacing.md, vertical = spacing.md)
            .border(width = DOCK_BORDER_WIDTH, color = glass.dockBorder, shape = CircleShape),
        shape = CircleShape,
        color = dockColor,
        shadowElevation = DOCK_SHADOW_ELEVATION
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = DOCK_HORIZONTAL_PADDING,
                vertical = DOCK_VERTICAL_PADDING
            ),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PhotxDockDestination.entries.forEach { destination ->
                DockTabItem(
                    destination = destination,
                    isSelected = destination == selectedDestination,
                    onClick = { onSelectDestination(destination) }
                )
            }
        }
    }
}

@Composable
private fun DockTabItem(destination: PhotxDockDestination, isSelected: Boolean, onClick: () -> Unit) {
    val colors = PhotxTheme.colorScheme
    val glass = PhotxTheme.glass
    val capsuleColor by animateColorAsState(
        targetValue = if (isSelected) glass.activeTabContainer else Color.Transparent,
        label = "dockCapsule"
    )
    val iconColor = if (isSelected) colors.primary else colors.onSurfaceVariant
    val labelColor = if (isSelected) colors.onSurface else colors.onSurfaceVariant

    Column(
        modifier = Modifier
            .defaultMinSize(minHeight = DOCK_TAB_MIN_HEIGHT)
            .clip(CircleShape)
            .background(capsuleColor)
            .semantics { selected = isSelected }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .padding(horizontal = DOCK_TAB_HORIZONTAL_PADDING, vertical = DOCK_TAB_VERTICAL_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = destination.icon(),
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(DOCK_ICON_SIZE)
        )
        Spacer(modifier = Modifier.height(DOCK_ICON_LABEL_GAP))
        Text(
            text = destination.label,
            style = PhotxTheme.typography.labelSmall,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun PhotxDockDestination.icon(): ImageVector = when (this) {
    PhotxDockDestination.PHOTOS -> Icons.Rounded.PhotoLibrary
    PhotxDockDestination.ALBUMS -> Icons.Rounded.Collections
    PhotxDockDestination.BACKUP -> Icons.Rounded.CloudUpload
    PhotxDockDestination.SEARCH -> Icons.Rounded.Search
}
