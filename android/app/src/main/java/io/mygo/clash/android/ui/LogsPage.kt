package io.mygo.clash.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mygo.clash.android.LogLine
import io.mygo.clash.android.Logs
import io.mygo.clash.android.R
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

/** The core's messages, newest first. */
@Composable
fun LogsPage(behavior: ScrollBehavior, padding: PaddingValues) {
    val lines by Logs.lines.collectAsState()
    PageList(behavior, padding) {
        item {
            Spacer(Modifier.height(12.dp))
            if (lines.isEmpty()) {
                Hint(stringResource(R.string.no_logs))
            } else {
                TextButton(text = stringResource(R.string.clear), onClick = Logs::clear, modifier = Modifier.padding(bottom = 12.dp))
            }
        }
        items(lines.asReversed(), key = { it.id }) { LogRow(it) }
    }
}

@Composable
private fun LogRow(l: LogLine) {
    val color = when (l.level) {
        "error" -> MiuixTheme.colorScheme.error
        "warning" -> Color(0xFFE8A33D)
        "debug" -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        else -> MiuixTheme.colorScheme.primary
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(text = timeFormat.format(Date(l.time)) + "  " + l.level.uppercase(), color = color, fontSize = 12.sp)
        Text(text = l.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}
