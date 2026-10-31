package com.layerbit.stave.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.layerbit.stave.R
import com.layerbit.stave.ui.theme.StaveColors

/** The bar every screen wears: a round back button, the title, and round buttons for actions. */
@Composable
fun StaveTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    iconRes: Int? = null,
    accent: Color? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier.fillMaxWidth().background(StaveColors.Background).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (onBack != null) RoundIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.app_back), onClick = onBack)
        if (iconRes != null) IconTile(iconRes = iconRes, accent = accent ?: StaveColors.Band, size = 36.dp)
        Column(modifier = Modifier.weight(1f).padding(start = if (onBack == null && iconRes == null) 8.dp else 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = StaveColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}
