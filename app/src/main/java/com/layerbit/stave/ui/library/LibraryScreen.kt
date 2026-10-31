package com.layerbit.stave.ui.library

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.layerbit.stave.R
import com.layerbit.stave.StaveApplication
import com.layerbit.stave.engine.export.AudioFormat
import com.layerbit.stave.ui.components.AmbientBackdrop
import com.layerbit.stave.ui.components.IconTile
import com.layerbit.stave.ui.components.Panel
import com.layerbit.stave.ui.components.Pill
import com.layerbit.stave.ui.components.RoundIconButton
import com.layerbit.stave.ui.components.formatBytes
import com.layerbit.stave.ui.components.rise
import com.layerbit.stave.ui.theme.StaveColors
import com.layerbit.stave.ui.theme.ToolAccents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Every export of every project: the private copies under files/projects/<id>/exports, newest first. */
@Composable
fun LibraryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as StaveApplication
    data class Export(val file: File, val project: String)
    var items by remember { mutableStateOf<List<Export>>(emptyList()) }
    var stamp by remember { mutableStateOf(0L) }
    var removing by remember { mutableStateOf<Export?>(null) }
    LaunchedEffect(stamp) {
        items = withContext(Dispatchers.IO) {
            app.projects.list().flatMap { p -> File(p.dir, "exports").listFiles()?.filter { it.isFile }?.map { Export(it, p.name.ifBlank { context.getString(R.string.home_project_untitled) }) } ?: emptyList() }
                .sortedByDescending { it.file.lastModified() }
        }
    }
    Box(modifier.fillMaxSize()) {
        AmbientBackdrop(strength = 0.6f)
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (items.isEmpty()) item { Text(stringResource(R.string.library_empty), style = MaterialTheme.typography.bodyMedium, color = StaveColors.Muted) }
            items.forEachIndexed { i, e ->
                item(key = e.file.path) {
                    val mime = AudioFormat.all.firstOrNull { e.file.extension.equals(it.extension, true) }?.mime ?: "audio/*"
                    Panel(modifier = rise(i).fillMaxWidth(), inset = 12.dp, onClick = { open(context, e.file, mime) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconTile(iconRes = R.drawable.ic_finish, accent = ToolAccents.Finish)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(e.file.name, style = MaterialTheme.typography.titleMedium, color = StaveColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.library_from, e.project) + " · " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(e.file.lastModified())), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Pill(formatBytes(e.file.length()), StaveColors.Muted, numeric = true)
                                }
                            }
                            RoundIconButton(iconRes = R.drawable.ic_contact, contentDescription = stringResource(R.string.app_share), onClick = { share(context, e.file, mime) }, size = 36.dp)
                            RoundIconButton(iconRes = R.drawable.ic_plus, contentDescription = stringResource(R.string.app_delete), onClick = { removing = e }, size = 36.dp)
                        }
                    }
                }
            }
        }
    }
    removing?.let { e ->
        AlertDialog(
            onDismissRequest = { removing = null }, containerColor = StaveColors.Surface,
            title = { Text(stringResource(R.string.library_delete_title), color = StaveColors.Text) },
            text = { Text(stringResource(R.string.library_delete_body), color = StaveColors.Muted) },
            confirmButton = { TextButton(onClick = { e.file.delete(); removing = null; stamp = System.nanoTime() }) { Text(stringResource(R.string.app_delete), color = StaveColors.Failed) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.app_cancel), color = StaveColors.Muted) } }
        )
    }
}

private fun share(context: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
}

private fun open(context: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
}
