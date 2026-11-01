package com.layerbit.stave.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.layerbit.stave.BuildConfig
import com.layerbit.stave.R
import com.layerbit.stave.brand.BrandLinks
import com.layerbit.stave.ui.components.AmbientBackdrop
import com.layerbit.stave.ui.components.IconTile
import com.layerbit.stave.ui.components.Panel
import com.layerbit.stave.ui.components.Pill
import com.layerbit.stave.ui.components.SecondaryButton
import com.layerbit.stave.ui.components.rise
import com.layerbit.stave.ui.theme.StaveColors

/** What the app is, what it refuses to do, and how to reach a person. */
@Composable
fun AboutScreen(onGuide: () -> Unit = {}, onContact: () -> Unit = {}, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(modifier.fillMaxSize()) {
        AmbientBackdrop(strength = 0.8f)
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = rise(0)) {
                    // The launcher tile itself: the mark is ink on lime, so it is never drawn alone on
                    // the dark page. The layers are 108dp with a 72dp visible centre; 96dp in a 64dp
                    // clip shows the same two thirds.
                    Box(Modifier.padding(end = 10.dp).size(64.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = Modifier.requiredSize(96.dp))
                        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize(96.dp))
                    }
                    Column(Modifier.padding(start = 4.dp)) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, color = StaveColors.Text)
                        Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                            Pill(stringResource(R.string.app_pill_free), StaveColors.Good)
                            Pill(stringResource(R.string.app_pill_no_internet), StaveColors.Band)
                        }
                    }
                }
            }
            item { Fact(R.drawable.ic_lock, StaveColors.Good, R.string.about_offline_title, R.string.about_offline_body, rise(1), filled = true) }
            item { Fact(R.drawable.ic_wave, StaveColors.Band, R.string.about_free_title, R.string.about_free_body, rise(2)) }
            item { Fact(R.drawable.ic_github, StaveColors.Muted, R.string.about_open_title, R.string.about_open_body, rise(3)) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = rise(4).fillMaxWidth()) {
                    SecondaryButton(stringResource(R.string.guide_title), iconRes = R.drawable.ic_guide, onClick = onGuide, modifier = Modifier.fillMaxWidth(), tall = false)
                    SecondaryButton(stringResource(R.string.contact_title), iconRes = R.drawable.ic_contact, onClick = onContact, modifier = Modifier.fillMaxWidth(), tall = false)
                    SecondaryButton(stringResource(R.string.contact_source), iconRes = R.drawable.ic_github, onClick = { BrandLinks.openUrl(context, BrandLinks.SOURCE_URL) }, modifier = Modifier.fillMaxWidth(), tall = false)
                    SecondaryButton(stringResource(R.string.about_licences), onClick = { BrandLinks.openUrl(context, BrandLinks.SOURCE_URL + "/blob/main/THIRD_PARTY_NOTICES.md") }, modifier = Modifier.fillMaxWidth(), tall = false)
                }
            }
            item { Text(stringResource(R.string.about_family), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

@Composable
private fun Fact(icon: Int, accent: androidx.compose.ui.graphics.Color, title: Int, body: Int, modifier: Modifier, filled: Boolean = false) {
    Panel(modifier = modifier.fillMaxWidth(), tint = if (filled) accent else null) {
        Row(verticalAlignment = Alignment.Top) {
            IconTile(iconRes = icon, accent = accent, filled = filled)
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = StaveColors.Text)
                Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = StaveColors.Muted, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
