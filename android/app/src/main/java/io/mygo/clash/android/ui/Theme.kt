package io.mygo.clash.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** MyGO!!!!!'s blue, as on the desktop app. */
val MygoBlue = Color(0xFF0B88BB)

/** The flash yellow of MyGO!!!!!, for accents. */
val MygoYellow = Color(0xFFFDFE88)

@Composable
fun MygoTheme(content: @Composable () -> Unit) {
    val controller = remember {
        ThemeController(
            colorSchemeMode = ColorSchemeMode.System,
            lightColors = lightColorScheme(primary = MygoBlue, primaryVariant = MygoBlue),
            darkColors = darkColorScheme(primary = Color(0xFF2FA3D6), primaryVariant = Color(0xFF2FA3D6)),
        )
    }
    MiuixTheme(controller = controller, content = content)
}
