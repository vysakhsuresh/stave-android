package com.layerbit.stave.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.layerbit.stave.BuildConfig
import com.layerbit.stave.R
import com.layerbit.stave.brand.BrandLinks
import com.layerbit.stave.ui.components.AmbientBackdrop
import com.layerbit.stave.ui.components.Panel
import com.layerbit.stave.ui.components.PrimaryButton
import com.layerbit.stave.ui.components.SecondaryButton
import com.layerbit.stave.ui.components.rise
import com.layerbit.stave.ui.theme.StaveColors

/**
 * How to reach a person. Every link is handed to another app through an Intent; nothing here
 * talks to the network itself, and nothing about a sound travels with a message.
 */
@Composable
fun ContactScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val subject = "Stave (Layerbit) ${BuildConfig.VERSION_NAME}"
    Box(modifier.fillMaxSize()) {
        AmbientBackdrop(strength = 0.7f)
        LazyColumn(modifier = Modifier.fillMaxWidth().testTag("contactList"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.contact_intro), style = MaterialTheme.typography.bodyMedium, color = StaveColors.Muted) }
            item {
                Panel(modifier = rise(0).fillMaxWidth()) {
                    Text(stringResource(R.string.contact_email_title), style = MaterialTheme.typography.titleMedium, color = StaveColors.Text)
                    SelectionContainer { Text(BrandLinks.SUPPORT_EMAIL, style = MaterialTheme.typography.bodyLarge, color = StaveColors.BandBright, modifier = Modifier.padding(top = 4.dp)) }
                    Text(stringResource(R.string.contact_email_body), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(top = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        PrimaryButton(stringResource(R.string.contact_write), iconRes = R.drawable.ic_contact, onClick = { BrandLinks.sendEmail(context, "$subject - question", "") }, modifier = Modifier.weight(1f), tall = false)
                        SecondaryButton(stringResource(R.string.contact_copy), iconRes = R.drawable.ic_copy, onClick = { clipboard.setText(AnnotatedString(BrandLinks.SUPPORT_EMAIL)) }, modifier = Modifier.weight(1f), tall = false)
                    }
                }
            }
            item {
                Panel(modifier = rise(1).fillMaxWidth()) {
                    Text(stringResource(R.string.contact_whatsapp_title), style = MaterialTheme.typography.titleMedium, color = StaveColors.Text)
                    Text(stringResource(R.string.contact_whatsapp_body), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(top = 4.dp))
                    SecondaryButton("WhatsApp", iconRes = R.drawable.ic_chat, onClick = { BrandLinks.openUrl(context, BrandLinks.WHATSAPP_URL) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), tall = false)
                }
            }
            item {
                Panel(modifier = rise(2).fillMaxWidth()) {
                    Text(stringResource(R.string.contact_bug_title), style = MaterialTheme.typography.titleMedium, color = StaveColors.Text)
                    Text(stringResource(R.string.contact_bug_body), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(top = 4.dp))
                    SecondaryButton(stringResource(R.string.contact_bug_write), iconRes = R.drawable.ic_contact, onClick = {
                        BrandLinks.sendEmail(context, "$subject - something went wrong", context.getString(R.string.contact_bug_template, BuildConfig.VERSION_NAME, android.os.Build.MODEL, android.os.Build.VERSION.RELEASE))
                    }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), tall = false)
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    SecondaryButton(stringResource(R.string.contact_source), iconRes = R.drawable.ic_github, onClick = { BrandLinks.openUrl(context, BrandLinks.SOURCE_URL) }, modifier = Modifier.fillMaxWidth(), tall = false)
                    SecondaryButton(stringResource(R.string.about_rate), iconRes = R.drawable.ic_star, onClick = { BrandLinks.openUrl(context, BrandLinks.PLAY_LISTING) }, modifier = Modifier.fillMaxWidth(), tall = false)
                    SecondaryButton(stringResource(R.string.about_privacy), iconRes = R.drawable.ic_lock, onClick = { BrandLinks.openUrl(context, BrandLinks.PRIVACY_URL) }, modifier = Modifier.fillMaxWidth(), tall = false)
                    TextButton(onClick = { BrandLinks.openUrl(context, BrandLinks.WEBSITE_URL) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.about_made_by, BrandLinks.BRAND_LABEL, BrandLinks.WEBSITE_URL.removePrefix("https://")), color = StaveColors.Dim, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
