package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.RAMBLER_ENABLED
import org.futo.inputmethod.latin.uix.settings.SettingItem
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.userSettingToggleDataStore
import org.futo.inputmethod.rambler.ModelManager

// Rambler additions to the voice input settings page.

val RamblerEnabledSetting = userSettingToggleDataStore(
    title = R.string.rambler_enabled_title,
    subtitle = R.string.rambler_enabled_subtitle,
    setting = RAMBLER_ENABLED
)

val RamblerModelSetting = UserSetting(
    name = R.string.rambler_model_title,
    subtitle = R.string.rambler_model_subtitle,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloaded by remember { mutableStateOf(ModelManager.isDownloaded(context)) }
    var progress by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val subtitle = when {
        progress != null -> stringResource(R.string.rambler_model_downloading, progress!!)
        error != null -> stringResource(R.string.rambler_model_failed, error!!)
        downloaded -> stringResource(R.string.rambler_model_installed)
        else -> stringResource(R.string.rambler_model_subtitle)
    }

    SettingItem(
        title = stringResource(R.string.rambler_model_title),
        subtitle = subtitle,
        onClick = if (!downloaded && progress == null) {
            {
                error = null
                progress = 0
                scope.launch(Dispatchers.IO) {
                    try {
                        ModelManager.download(context) { progress = it }
                        downloaded = true
                    } catch (e: Exception) {
                        error = e.message ?: e.javaClass.simpleName
                    } finally {
                        progress = null
                    }
                }
            }
        } else null
    ) { }
}
