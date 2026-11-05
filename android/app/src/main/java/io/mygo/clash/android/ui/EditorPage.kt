package io.mygo.clash.android.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mygo.clash.android.Core
import io.mygo.clash.android.R
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Edits an extension: the YAML of a merge or the JavaScript of a script.
 * Saving checks it first; the running core applies it.
 */
@Composable
fun EditorPage(uid: String, behavior: ScrollBehavior, padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember(uid) { mutableStateOf<String?>(null) }
    var error by remember(uid) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val saved = stringResource(R.string.saved)

    LaunchedEffect(uid) {
        text = runCatching { Core.content(uid) }.getOrElse {
            error = it.message
            ""
        }
    }

    PageList(behavior, padding) {
        item {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                TextButton(
                    text = if (saving) stringResource(R.string.working) else stringResource(R.string.save),
                    enabled = text != null && !saving,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        saving = true
                        error = null
                        scope.launch {
                            runCatching { Core.setContent(uid, text.orEmpty()) }
                                .onSuccess { Toast.makeText(context, saved, Toast.LENGTH_SHORT).show() }
                                .onFailure { error = it.message ?: it.toString() }
                            saving = false
                        }
                    },
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MiuixTheme.colorScheme.error)
                }
                Spacer(Modifier.height(12.dp))
                text?.let { t ->
                    TextField(
                        value = t,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 400.dp),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    )
                }
            }
        }
    }
}
