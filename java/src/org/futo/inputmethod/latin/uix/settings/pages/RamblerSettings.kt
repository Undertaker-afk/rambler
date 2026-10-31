package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.RAMBLER_ENABLED
import org.futo.inputmethod.latin.uix.settings.DropDownPickerSettingItem
import org.futo.inputmethod.latin.uix.settings.SettingItem
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.userSettingToggleDataStore
import org.futo.inputmethod.rambler.ModelManager
import org.futo.inputmethod.rambler.ModelSpec
import org.futo.inputmethod.rambler.Models

// Rambler additions to the voice input settings page.

/** UI state shared by the Rambler settings rows. Downloads outlive the settings screen. */
private object RamblerUi {
    var selectedId by mutableStateOf<String?>(null)
    var downloadingId by mutableStateOf<String?>(null)
    var downloadingName by mutableStateOf("")
    var progress by mutableIntStateOf(0)
    var error by mutableStateOf<String?>(null)
    var filesVersion by mutableIntStateOf(0)   // bumped whenever files on disk change
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

val RamblerEnabledSetting = userSettingToggleDataStore(
    title = R.string.rambler_enabled_title,
    subtitle = R.string.rambler_enabled_subtitle,
    setting = RAMBLER_ENABLED
)

@androidx.compose.runtime.Composable
private fun currentSpec(): ModelSpec {
    val context = LocalContext.current
    // Reading selectedId makes the rows recompose when the selection changes.
    val id = RamblerUi.selectedId
    return if (id != null) Models.byId(id) else ModelManager.selected(context)
}

val RamblerModelPickerSetting = UserSetting(
    name = R.string.rambler_model_picker_title,
    subtitle = R.string.rambler_model_picker_subtitle,
) {
    val context = LocalContext.current
    val current = currentSpec()
    val optionFormat = stringResource(R.string.rambler_model_option, "%1\$s", "%2\$s")
    DropDownPickerSettingItem(
        label = stringResource(R.string.rambler_model_picker_title),
        options = Models.all,
        selection = current,
        onSet = {
            ModelManager.select(context, it)
            RamblerUi.selectedId = it.id
            RamblerUi.error = null
        },
        getDisplayName = { optionFormat.replace("%1\$s", it.name).replace("%2\$s", it.sizeLabel) },
    )
}

val RamblerModelDownloadSetting = UserSetting(
    name = R.string.rambler_model_download_title,
) {
    val context = LocalContext.current
    val spec = currentSpec()
    @Suppress("UNUSED_VARIABLE") val version = RamblerUi.filesVersion   // recompose after downloads/deletes
    val downloaded = ModelManager.isDownloaded(context, spec)
    val busyHere = RamblerUi.downloadingId == spec.id
    val busyElsewhere = RamblerUi.downloadingId != null && !busyHere

    val subtitle = when {
        busyHere -> stringResource(R.string.rambler_model_downloading, spec.name, RamblerUi.progress)
        busyElsewhere -> stringResource(R.string.rambler_model_busy)
        RamblerUi.error != null -> stringResource(R.string.rambler_model_failed, RamblerUi.error!!)
        downloaded -> stringResource(R.string.rambler_model_installed, spec.note)
        else -> stringResource(R.string.rambler_model_not_installed, spec.note, spec.sizeLabel)
    }

    SettingItem(
        title = stringResource(R.string.rambler_model_download_title),
        subtitle = subtitle,
        onClick = if (!downloaded && RamblerUi.downloadingId == null) {
            {
                RamblerUi.error = null
                RamblerUi.progress = 0
                RamblerUi.downloadingId = spec.id
                RamblerUi.downloadingName = spec.name
                RamblerUi.scope.launch {
                    try {
                        ModelManager.download(context.applicationContext, spec) { RamblerUi.progress = it }
                    } catch (e: Exception) {
                        RamblerUi.error = e.message ?: e.javaClass.simpleName
                    } finally {
                        RamblerUi.downloadingId = null
                        RamblerUi.filesVersion++
                    }
                }
            }
        } else null
    ) { }
}

val RamblerModelCleanupSetting = UserSetting(
    name = R.string.rambler_model_cleanup_title,
) {
    val context = LocalContext.current
    val spec = currentSpec()
    @Suppress("UNUSED_VARIABLE") val version = RamblerUi.filesVersion
    val selectedBytes = ModelManager.modelFile(context, spec).let { if (it.exists()) it.length() else 0L }
    val partialBytes = java.io.File(ModelManager.modelFile(context, spec).path + ".part").let { if (it.exists()) it.length() else 0L }
    val others = ModelManager.diskUsage(context) - selectedBytes - partialBytes
    if (others > 1_000_000L && RamblerUi.downloadingId == null) {
        SettingItem(
            title = stringResource(R.string.rambler_model_cleanup_title),
            subtitle = stringResource(R.string.rambler_model_cleanup_subtitle, "%.1f GB".format(others / 1e9)),
            onClick = {
                ModelManager.deleteOthers(context)
                RamblerUi.filesVersion++
            },
        ) { }
    }
}
