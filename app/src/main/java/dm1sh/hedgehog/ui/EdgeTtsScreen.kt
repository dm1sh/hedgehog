package dm1sh.hedgehog.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dm1sh.hedgehog.model.AppSettings
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EdgeTtsScreen(
    vm: MainViewModel,
    onRequestNativePicker: (NativePickerType) -> Unit,
    onExitApp: () -> Unit
) {
    val context = LocalContext.current
    val settings = vm.settings

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .pointerInput(Unit) {
                detectTapGestures {
                    vm.onBackgroundTap()
                }
            },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Hedgehog TTS",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (vm.bookLoaded || vm.totalPartsCount > 0) {
                            Text(
                                text = "${vm.tr("KEY_TOTAL_PARTS")}${vm.totalPartsCount}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                },
                actions = {
                    FilledTonalIconButton(
                        onClick = { vm.toggleTextAreaPanel() },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (settings.showTextAreaPressed) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            contentColor = if (settings.showTextAreaPressed) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.EditNote,
                            contentDescription = vm.tr("KEY_VIEW_TEXT")
                        )
                    }
                    IconButton(
                        onClick = {
                            vm.stopConversion(announce = false)
                            onExitApp()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PowerSettingsNew,
                            contentDescription = vm.tr("KEY_EXIT")
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ConversionActionDock(
                    vm = vm,
                    onRequestNativePicker = onRequestNativePicker
                )
                NavigationBar {
                    NavigationBarItem(
                        selected = vm.activeSettingsTab == SettingsTab.VOICE && !settings.showTextAreaPressed,
                        onClick = {
                            if (settings.showTextAreaPressed) vm.onEditorClosePressed()
                            vm.selectSettingsTab(SettingsTab.VOICE)
                        },
                        icon = {
                            Icon(
                                imageVector = if (vm.activeSettingsTab == SettingsTab.VOICE) {
                                    Icons.Filled.RecordVoiceOver
                                } else {
                                    Icons.Outlined.RecordVoiceOver
                                },
                                contentDescription = vm.tr("KEY_SETTINGS_VOICE")
                            )
                        },
                        label = {
                            Text(
                                text = vm.tr("KEY_SETTINGS_VOICE"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                    NavigationBarItem(
                        selected = vm.activeSettingsTab == SettingsTab.BOOK && !settings.showTextAreaPressed,
                        onClick = {
                            if (settings.showTextAreaPressed) vm.onEditorClosePressed()
                            vm.selectSettingsTab(SettingsTab.BOOK)
                        },
                        icon = {
                            Icon(
                                imageVector = if (vm.activeSettingsTab == SettingsTab.BOOK) {
                                    Icons.Filled.MenuBook
                                } else {
                                    Icons.AutoMirrored.Outlined.MenuBook
                                },
                                contentDescription = vm.tr("KEY_SETTINGS_BOOK")
                            )
                        },
                        label = {
                            Text(
                                text = vm.tr("KEY_SETTINGS_BOOK"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                    NavigationBarItem(
                        selected = vm.activeSettingsTab == SettingsTab.TOOL && !settings.showTextAreaPressed,
                        onClick = {
                            if (settings.showTextAreaPressed) vm.onEditorClosePressed()
                            vm.selectSettingsTab(SettingsTab.TOOL)
                        },
                        icon = {
                            Icon(
                                imageVector = if (vm.activeSettingsTab == SettingsTab.TOOL) {
                                    Icons.Filled.Tune
                                } else {
                                    Icons.Outlined.Tune
                                },
                                contentDescription = vm.tr("KEY_SETTINGS_TOOL")
                            )
                        },
                        label = {
                            Text(
                                text = vm.tr("KEY_SETTINGS_TOOL"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                    NavigationBarItem(
                        selected = vm.activeSettingsTab == SettingsTab.INTERFACE && !settings.showTextAreaPressed,
                        onClick = {
                            if (settings.showTextAreaPressed) vm.onEditorClosePressed()
                            vm.selectSettingsTab(SettingsTab.INTERFACE)
                        },
                        icon = {
                            Icon(
                                imageVector = if (vm.activeSettingsTab == SettingsTab.INTERFACE) {
                                    Icons.Filled.Palette
                                } else {
                                    Icons.Outlined.Palette
                                },
                                contentDescription = vm.tr("KEY_SETTINGS_INTERFACE")
                            )
                        },
                        label = {
                            Text(
                                text = vm.tr("KEY_SETTINGS_INTERFACE"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val isWideScreen = maxWidth >= 840.dp
            val showEditor = settings.showTextAreaPressed

            if (showEditor && !isWideScreen) {
                TextAreaEditorPanel(
                    vm = vm,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            } else if (showEditor && isWideScreen) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    TextAreaEditorPanel(
                        vm = vm,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                    MainSettingsPane(
                        vm = vm,
                        onRequestNativePicker = onRequestNativePicker,
                        onOpenStoragePermissions = { vm.repo.openStorageSettings(context) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            } else {
                MainSettingsPane(
                    vm = vm,
                    onRequestNativePicker = onRequestNativePicker,
                    onOpenStoragePermissions = { vm.repo.openStorageSettings(context) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                )
            }
        }
    }

    // Voices Selection ModalBottomSheet
    if (vm.voicesDropdownOpen) {
        VoicesPickerBottomSheet(
            vm = vm,
            onDismiss = { vm.voicesDropdownOpen = false }
        )
    }

    // Native Material 3 AlertDialogs for Chapter Settings & Empty Recycle Bin
    if (vm.dopModal == DopModal.REGEX_GLAVA) {
        ChapterSettingsDialog(
            vm = vm,
            onRequestNativePicker = onRequestNativePicker
        )
    } else if (vm.dopModal == DopModal.RECYCLE_BIN) {
        EmptyRecycleBinDialog(vm = vm)
    }
}

@Composable
private fun MainSettingsPane(
    vm: MainViewModel,
    onRequestNativePicker: (NativePickerType) -> Unit,
    onOpenStoragePermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        ConversionStatusCard(vm = vm)

        when (vm.activeSettingsTab) {
            SettingsTab.VOICE -> VoiceSettingsSection(vm = vm)
            SettingsTab.BOOK -> BookSettingsSection(
                vm = vm,
                onRequestNativePicker = onRequestNativePicker,
                onOpenStoragePermissions = onOpenStoragePermissions
            )
            SettingsTab.TOOL -> ToolSettingsSection(vm = vm)
            SettingsTab.INTERFACE -> InterfaceSettingsSection(vm = vm)
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun ConversionStatusCard(vm: MainViewModel) {
    val settings = vm.settings
    val hasParts = vm.partStatuses.isNotEmpty()
    val statusText = when {
        vm.statInfo.isNotBlank() -> vm.statInfo
        vm.bookLoaded -> vm.tr("KEY_BOOK_LOADED")
        else -> vm.tr("KEY_OPEN_BOOKS")
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (vm.statInfoDoneHighlight) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = hasParts) {
                    vm.toggleStatusGridVisible()
                }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (vm.statInfoDoneHighlight) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (vm.statInfoDoneHighlight) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (vm.elapsedVisible && vm.elapsedTimeText.isNotEmpty()) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Timer,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = vm.elapsedTimeText,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }

                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Text(
                            text = vm.statStr,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    if (hasParts) {
                        Icon(
                            imageVector = if (settings.group4Visible) {
                                Icons.Default.ExpandLess
                            } else {
                                Icons.Default.ExpandMore
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (vm.progressBarVisible) {
                val progress = if (vm.progressBarMax > 0) {
                    (vm.progressBarValue.toFloat() / vm.progressBarMax.toFloat()).coerceIn(0f, 1f)
                } else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            }

            AnimatedVisibility(visible = settings.group4Visible && hasParts) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider()
                    StatusGridView(
                        statuses = vm.partStatuses,
                        tr = vm::tr,
                        onSpeakTooltip = { vm.ttsSay(it) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversionActionDock(
    vm: MainViewModel,
    onRequestNativePicker: (NativePickerType) -> Unit
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (vm.bookLoaded) {
                OutlinedButton(
                    onClick = {
                        vm.onFileButtonClicked {
                            onRequestNativePicker(NativePickerType.OPEN_BOOKS)
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = vm.tr("KEY_BOOK_CLEAR"),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = vm.tr("KEY_OPEN_BOOKS"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                FilledTonalButton(
                    onClick = {
                        vm.onFileButtonClicked {
                            onRequestNativePicker(NativePickerType.OPEN_BOOKS)
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FolderOpen,
                        contentDescription = vm.tr("KEY_OPEN_BOOKS"),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = vm.tr("KEY_OPEN_BOOKS"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Button(
                onClick = { vm.onSaveOrStopButtonPressed() },
                colors = if (vm.runWork) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (vm.runWork) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = vm.tr(if (vm.runWork) "KEY_STOP" else "KEY_SAVE_MP3"),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (vm.runWork) {
                FilledTonalIconButton(
                    onClick = { vm.onPauseOrResumeButtonPressed() },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (vm.workPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = vm.tr(if (vm.workPaused) "KEY_RESUME" else "KEY_PAUSE")
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSettingsSection(vm: MainViewModel) {
    val settings = vm.settings
    val isOffline = vm.repo.isVoiceOffline(settings.voicesName)

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Native Material 3 Voice Field opening ModalBottomSheet
            ExposedDropdownMenuBox(
                expanded = vm.voicesDropdownOpen,
                onExpandedChange = {
                    vm.languagesDropdownOpen = false
                    vm.uiTtsDropdownOpen = false
                    vm.voicesDropdownOpen = !vm.voicesDropdownOpen
                    vm.ttsSay(vm.voiceSayText(settings.voicesName))
                }
            ) {
                OutlinedTextField(
                    value = settings.voicesName,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text(vm.tr("KEY_SETTINGS_VOICE")) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.RecordVoiceOver,
                            contentDescription = null
                        )
                    },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = vm.voicesDropdownOpen)
                    },
                    supportingText = if (isOffline) {
                        { Text(vm.tr("KEY_VOICE_OFFLINE")) }
                    } else null,
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )
            }

            // Speed Slider (-50% .. +100%, step 5)
            M3SliderSetting(
                label = vm.tr("KEY_SPEED"),
                valueText = "${vm.formatSignedValue(settings.rate)}%",
                value = settings.rate.toFloat(),
                valueRange = -50f..100f,
                steps = 29,
                onValueChange = { vm.setRate(it.roundToInt()) }
            )

            // Pitch Slider (-50Hz .. +50Hz)
            M3SliderSetting(
                label = vm.tr("KEY_PITCH"),
                valueText = "${vm.formatSignedValue(settings.pitch)}Hz",
                value = settings.pitch.toFloat(),
                valueRange = -50f..50f,
                steps = 99,
                onValueChange = { vm.setPitch(it.roundToInt()) }
            )
        }
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            M3SwitchListItem(
                headline = vm.tr("KEY_LOWBITRATE"),
                checked = settings.lowBitrate,
                onCheckedChange = { checked ->
                    vm.updateSettings { it.copy(lowBitrate = checked) }
                    vm.ttsSay("${vm.tr("KEY_LOWBITRATE")} ${vm.tr(if (checked) "KEY_ON" else "KEY_OFF")}")
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            M3SwitchListItem(
                headline = vm.tr("KEY_VOICE_PREVIEW"),
                checked = settings.voicePreview,
                onCheckedChange = { checked ->
                    vm.updateSettings { it.copy(voicePreview = checked) }
                    if (!checked && !vm.partPreviewActive) {
                        vm.voicePreviewStop()
                    }
                    vm.ttsSay("${vm.tr("KEY_VOICE_PREVIEW")} ${vm.tr(if (checked) "KEY_ON" else "KEY_OFF")}")
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalButton(
                    onClick = { vm.fetchOnlineVoices() },
                    enabled = !vm.onlineVoicesBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    if (vm.onlineVoicesBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = vm.tr("KEY_GET_VOICES"),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                FilledTonalButton(
                    onClick = { vm.fetchOfflineVoices() },
                    enabled = !vm.offlineVoicesBusy && !vm.runWork,
                    modifier = Modifier.weight(1f)
                ) {
                    if (vm.offlineVoicesBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.DownloadForOffline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = vm.tr("KEY_GET_OFFLINE_VOICES"),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun BookSettingsSection(
    vm: MainViewModel,
    onRequestNativePicker: (NativePickerType) -> Unit,
    onOpenStoragePermissions: () -> Unit
) {
    val settings = vm.settings

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Save Directory + Storage Permission
            ListItem(
                headlineContent = { Text(vm.tr("KEY_SAVE_DIR")) },
                supportingContent = if (settings.saveDir.isNotBlank()) {
                    {
                        Text(
                            text = settings.saveDir,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else null,
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = null
                    )
                },
                trailingContent = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalIconButton(
                            onClick = {
                                vm.ttsSay(vm.tr("KEY_SELECT_SAVE_DIR"))
                                onRequestNativePicker(NativePickerType.SAVE_DIR)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FolderOpen,
                                contentDescription = vm.tr("KEY_SELECT_SAVE_DIR")
                            )
                        }
                        OutlinedIconButton(
                            onClick = {
                                vm.ttsSay(vm.tr("KEY_STORAGE_PERMISSION"))
                                onOpenStoragePermissions()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Security,
                                contentDescription = vm.tr("KEY_STORAGE_PERMISSION")
                            )
                        }
                    }
                },
                modifier = Modifier.clickable {
                    vm.ttsSay(vm.tr("KEY_SELECT_SAVE_DIR"))
                    onRequestNativePicker(NativePickerType.SAVE_DIR)
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Load Directory + Switch
            ListItem(
                headlineContent = { Text(vm.tr("KEY_LOAD_DIR")) },
                supportingContent = if (settings.loadDir.isNotBlank()) {
                    {
                        Text(
                            text = settings.loadDir,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else null,
                leadingContent = {
                    FilledTonalIconButton(
                        onClick = {
                            vm.ttsSay(vm.tr("KEY_SELECT_LOAD_DIR"))
                            onRequestNativePicker(NativePickerType.LOAD_DIR)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FolderOpen,
                            contentDescription = vm.tr("KEY_SELECT_LOAD_DIR")
                        )
                    }
                },
                trailingContent = {
                    M3Switch(
                        checked = settings.loadDirUse,
                        onCheckedChange = { checked ->
                            if (checked && settings.loadDir.isBlank()) {
                                onRequestNativePicker(NativePickerType.LOAD_DIR)
                            } else {
                                vm.updateSettings { it.copy(loadDirUse = checked) }
                                vm.ttsSay("${vm.tr("KEY_LOAD_DIR")} ${vm.tr(if (checked) "KEY_ON" else "KEY_OFF")}")
                            }
                        }
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Separate folder per book
            M3SwitchListItem(
                headline = vm.tr("KEY_DIR_FOR_MP3"),
                checked = settings.bookDirCreate,
                onCheckedChange = { checked ->
                    vm.updateSettings { it.copy(bookDirCreate = checked) }
                    vm.ttsSay("${vm.tr("KEY_DIR_FOR_MP3")} ${vm.tr(if (checked) "KEY_ON" else "KEY_OFF")}")
                }
            )
        }
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Use Dictionary (.lexx)
            ListItem(
                headlineContent = { Text(vm.tr("KEY_USE_LEXX")) },
                supportingContent = if (settings.lexxPath.isNotBlank()) {
                    {
                        Text(
                            text = File(settings.lexxPath).name.ifEmpty { settings.lexxPath },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else null,
                leadingContent = {
                    FilledTonalIconButton(
                        onClick = {
                            vm.ttsSay(vm.tr("KEY_SELECT_LEXX"))
                            onRequestNativePicker(NativePickerType.LEXX_FILE)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FileOpen,
                            contentDescription = vm.tr("KEY_SELECT_LEXX")
                        )
                    }
                },
                trailingContent = {
                    M3Switch(
                        checked = settings.lexxUse,
                        onCheckedChange = { checked ->
                            if (checked && settings.lexxPath.isBlank()) {
                                onRequestNativePicker(NativePickerType.LEXX_FILE)
                            } else {
                                vm.updateSettings { it.copy(lexxUse = checked) }
                                vm.ttsSay("${vm.tr("KEY_USE_LEXX")} ${vm.tr(if (checked) "KEY_ON" else "KEY_OFF")}")
                            }
                        }
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Case-sensitive dictionary
            M3SwitchListItem(
                headline = vm.tr("KEY_LEXX_REGISTER"),
                checked = settings.lexxRegister,
                onCheckedChange = { checked ->
                    vm.updateSettings { it.copy(lexxRegister = checked) }
                    vm.ttsSay("${vm.tr("KEY_LEXX_REGISTER")} ${vm.tr(if (checked) "KEY_ON" else "KEY_OFF")}")
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Cut by chapters + Chapter Regex Modal Button
            ListItem(
                headlineContent = { Text(vm.tr("KEY_BOOK_CHAPTER")) },
                supportingContent = {
                    Text(
                        text = settings.regexGlavaCompile,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingContent = {
                    FilledTonalIconButton(
                        onClick = { vm.openChapterSettingsModal() }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = vm.tr("KEY_REGEX_GLAVA")
                        )
                    }
                },
                trailingContent = {
                    M3Switch(
                        checked = settings.bookChapter,
                        onCheckedChange = { checked ->
                            vm.updateSettings { it.copy(bookChapter = checked) }
                            vm.ttsSay("${vm.tr("KEY_BOOK_CHAPTER")} ${vm.tr(if (checked) "KEY_ON" else "KEY_OFF")}")
                        }
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Use Recycle Bin + Empty Trash Modal Button
            ListItem(
                headlineContent = { Text(vm.tr("KEY_RECYCLE_BIN")) },
                leadingContent = {
                    FilledTonalIconButton(
                        onClick = { vm.openRecycleBinModal() }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteSweep,
                            contentDescription = vm.tr("KEY_CLEAR_BIN")
                        )
                    }
                },
                trailingContent = {
                    M3Switch(
                        checked = settings.recycleBinUse,
                        onCheckedChange = { checked ->
                            vm.updateSettings { it.copy(recycleBinUse = checked) }
                            vm.ttsSay("${vm.tr("KEY_RECYCLE_BIN")} ${vm.tr(if (checked) "KEY_ON" else "KEY_OFF")}")
                        }
                    )
                }
            )
        }
    }
}

@Composable
private fun ToolSettingsSection(vm: MainViewModel) {
    val settings = vm.settings
    val rdIndex = AppSettings.restartDelayIndexFromSeconds(settings.restartDelay)
    val rdLabel = if (settings.restartDelay < 0) "∞" else "${settings.restartDelay}s"
    val mergeLabel = if (settings.mergeFiles == 100) {
        vm.tr("KEY_ALL")
    } else {
        "${settings.mergeFiles} ${vm.tr("KEY_UNIT")}"
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            M3SliderSetting(
                label = vm.tr("KEY_THREADSCOUNT"),
                valueText = settings.maxThreads.toString(),
                value = settings.maxThreads.toFloat(),
                valueRange = 1f..100f,
                steps = 98,
                onValueChange = { vm.setMaxThreads(it.roundToInt()) }
            )

            M3SliderSetting(
                label = vm.tr("KEY_RESTARTDELAY"),
                valueText = rdLabel,
                value = rdIndex.toFloat(),
                valueRange = 0f..AppSettings.RESTART_DELAY_STEPS.size.toFloat(),
                steps = AppSettings.RESTART_DELAY_STEPS.size - 1,
                onValueChange = { vm.setRestartDelayIndex(it.roundToInt()) }
            )

            M3SliderSetting(
                label = vm.tr("KEY_MARGE_FILES"),
                valueText = mergeLabel,
                value = settings.mergeFiles.toFloat(),
                valueRange = 1f..100f,
                steps = 98,
                onValueChange = { vm.setMergeFiles(it.roundToInt()) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InterfaceSettingsSection(vm: MainViewModel) {
    val settings = vm.settings
    val currentLangText = vm.languagesList.getOrElse(settings.languagesSelect) { "AUTO" }
    val currentUiTtsText = if (settings.languagesUiTtsSelect == 0) {
        vm.tr("KEY_OFF")
    } else {
        vm.uiTtsList.getOrElse(settings.languagesUiTtsSelect) { vm.tr("KEY_OFF") }
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Native ExposedDropdownMenuBox for Interface Language
            ExposedDropdownMenuBox(
                expanded = vm.languagesDropdownOpen,
                onExpandedChange = {
                    vm.voicesDropdownOpen = false
                    vm.uiTtsDropdownOpen = false
                    vm.languagesDropdownOpen = !vm.languagesDropdownOpen
                    vm.ttsSay("${vm.tr("KEY_LANGUAGE")} $currentLangText")
                }
            ) {
                OutlinedTextField(
                    value = currentLangText,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text(vm.tr("KEY_LANGUAGE")) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Language,
                            contentDescription = null
                        )
                    },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = vm.languagesDropdownOpen)
                    },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )

                ExposedDropdownMenu(
                    expanded = vm.languagesDropdownOpen,
                    onDismissRequest = { vm.languagesDropdownOpen = false }
                ) {
                    vm.languagesList.forEachIndexed { index, langItem ->
                        DropdownMenuItem(
                            text = { Text(langItem) },
                            trailingIcon = if (index == settings.languagesSelect) {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else null,
                            onClick = { vm.selectLanguage(index) },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                        )
                    }
                }
            }

            // Native ExposedDropdownMenuBox for UI Screen Reader
            ExposedDropdownMenuBox(
                expanded = vm.uiTtsDropdownOpen,
                onExpandedChange = {
                    vm.voicesDropdownOpen = false
                    vm.languagesDropdownOpen = false
                    vm.uiTtsDropdownOpen = !vm.uiTtsDropdownOpen
                    vm.ttsSay("${vm.tr("KEY_UITTS")} $currentUiTtsText")
                }
            ) {
                OutlinedTextField(
                    value = currentUiTtsText,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text(vm.tr("KEY_UITTS")) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                            contentDescription = null
                        )
                    },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = vm.uiTtsDropdownOpen)
                    },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )

                ExposedDropdownMenu(
                    expanded = vm.uiTtsDropdownOpen,
                    onDismissRequest = { vm.uiTtsDropdownOpen = false }
                ) {
                    vm.uiTtsList.forEachIndexed { index, raw ->
                        val label = if (index == 0) vm.tr("KEY_OFF") else raw
                        DropdownMenuItem(
                            text = { Text(label) },
                            trailingIcon = if (index == settings.languagesUiTtsSelect) {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else null,
                            onClick = { vm.selectUiTts(index) },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                        )
                    }
                }
            }
        }
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            M3SliderSetting(
                label = vm.tr("KEY_ZOOM"),
                valueText = "${(settings.zoomUi * 100).roundToInt()}%",
                value = settings.zoomUi.coerceIn(0.5f, 2.0f),
                valueRange = 0.5f..2.0f,
                steps = 14,
                onValueChange = { vm.setZoomUi(it) }
            )

            M3SliderSetting(
                label = vm.tr("KEY_FONTSIZE"),
                valueText = "${settings.fontSize} sp",
                value = settings.fontSize.toFloat(),
                valueRange = 16f..24f,
                steps = 7,
                onValueChange = { vm.setFontSize(it.roundToInt()) }
            )
        }
    }
}

@Composable
private fun TextAreaEditorPanel(
    vm: MainViewModel,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val taFontSize = (vm.settings.fontSize + 2).sp
    val noNav = vm.totalPartsCount <= 1

    OutlinedCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header bar: Preview Play/Stop, Part Counter, Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!vm.runWork) {
                        FilledTonalIconButton(
                            onClick = { vm.onEditorPlayPressed() }
                        ) {
                            Icon(
                                imageVector = if (vm.partPreviewActive) {
                                    Icons.Default.Stop
                                } else {
                                    Icons.Default.PlayArrow
                                },
                                contentDescription = null
                            )
                        }
                    }
                    Text(
                        text = "${vm.tr("KEY_TOTAL_PARTS")}${vm.totalPartsCount}",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                IconButton(onClick = { vm.onEditorClosePressed() }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = vm.tr("KEY_CANCEL")
                    )
                }
            }

            // Native Material 3 OutlinedTextField for editing part text
            OutlinedTextField(
                value = vm.textAreaText,
                onValueChange = { if (!vm.runWork) vm.textAreaText = it },
                readOnly = vm.runWork,
                placeholder = { Text(vm.tr("KEY_TEXTAREA")) },
                textStyle = TextStyle(fontSize = taFontSize),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            // Bottom Navigation & Save Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalIconButton(
                        onClick = { vm.onEditorPrevPressed() },
                        enabled = !noNav
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = null
                        )
                    }

                    OutlinedTextField(
                        value = vm.editorPartFieldText,
                        onValueChange = { vm.editorPartFieldText = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                vm.onEditorPartNumberSubmitted(vm.editorPartFieldText)
                            }
                        ),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            textAlign = TextAlign.Center
                        ),
                        modifier = Modifier.width(88.dp)
                    )

                    FilledTonalIconButton(
                        onClick = { vm.onEditorNextPressed() },
                        enabled = !noNav
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null
                        )
                    }
                }

                Button(
                    onClick = { vm.onEditorSavePressed() },
                    enabled = !vm.runWork
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(vm.tr("KEY_OK"))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicesPickerBottomSheet(
    vm: MainViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }

    val filteredVoices = remember(vm.voicesList.size, searchQuery) {
        if (searchQuery.isBlank()) {
            vm.voicesList.mapIndexed { idx, name -> idx to name }
        } else {
            val q = searchQuery.trim().lowercase()
            vm.voicesList.mapIndexedNotNull { idx, name ->
                if (name.lowercase().contains(q)) idx to name else null
            }
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        val currentIdx = vm.settings.voicesSelect
        if (currentIdx in vm.voicesList.indices && searchQuery.isBlank()) {
            listState.scrollToItem((currentIdx - 2).coerceAtLeast(0))
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = vm.tr("KEY_SETTINGS_VOICE"),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                singleLine = true,
                placeholder = { Text("Search voices...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null
                    )
                },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null
                            )
                        }
                    }
                } else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(
                    items = filteredVoices,
                    key = { _, pair -> pair.first }
                ) { _, (originalIndex, voiceName) ->
                    val isSelected = originalIndex == vm.settings.voicesSelect
                    val isOffline = vm.repo.isVoiceOffline(voiceName)

                    ListItem(
                        headlineContent = {
                            Text(
                                text = voiceName,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        },
                        supportingContent = if (isOffline) {
                            { Text(vm.tr("KEY_VOICE_OFFLINE")) }
                        } else null,
                        trailingContent = if (isSelected) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else null,
                        colors = ListItemDefaults.colors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                            } else {
                                Color.Transparent
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.selectVoice(originalIndex)
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterSettingsDialog(
    vm: MainViewModel,
    onRequestNativePicker: (NativePickerType) -> Unit
) {
    AlertDialog(
        onDismissRequest = { vm.cancelChapterSettingsModal() },
        icon = {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = null
            )
        },
        title = {
            Text(text = vm.tr("KEY_REGEX_GLAVA"))
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = vm.tempRegexGlava,
                    onValueChange = { vm.tempRegexGlava = it },
                    singleLine = true,
                    label = { Text(vm.tr("KEY_REGEX_GLAVA")) },
                    modifier = Modifier.fillMaxWidth()
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        ) {
                            Text(
                                text = vm.tr("KEY_ADDGLAVMP3"),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (vm.tempGlavMp3Path.isNotBlank()) {
                                Text(
                                    text = File(vm.tempGlavMp3Path).name.ifEmpty { vm.tempGlavMp3Path },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        M3Switch(
                            checked = vm.tempGlavMp3Use,
                            onCheckedChange = { vm.tempGlavMp3Use = it }
                        )
                    }

                    AssistChip(
                        onClick = {
                            vm.ttsSay(vm.tr("KEY_SELECT_ADDGLAVMP3"))
                            onRequestNativePicker(NativePickerType.GLAV_MP3)
                        },
                        label = {
                            Text(
                                text = if (vm.tempGlavMp3Path.isBlank()) {
                                    vm.tr("KEY_SELECT_ADDGLAVMP3")
                                } else {
                                    File(vm.tempGlavMp3Path).name.ifEmpty { vm.tempGlavMp3Path }
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.AudioFile,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = vm.tr("KEY_ADDGLAVTEXT"),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    )
                    M3Switch(
                        checked = vm.tempGlavTextUse,
                        onCheckedChange = { vm.tempGlavTextUse = it }
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { vm.confirmChapterSettingsModal() }) {
                Text(vm.tr("KEY_OK"))
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.cancelChapterSettingsModal() }) {
                Text(vm.tr("KEY_CANCEL"))
            }
        }
    )
}

@Composable
private fun EmptyRecycleBinDialog(vm: MainViewModel) {
    AlertDialog(
        onDismissRequest = { vm.cancelEmptyRecycleBin() },
        icon = {
            Icon(
                imageVector = Icons.Outlined.DeleteForever,
                contentDescription = null
            )
        },
        title = {
            Text(text = vm.tr("KEY_CLEAR_BIN"))
        },
        confirmButton = {
            Button(
                onClick = { vm.confirmEmptyRecycleBin() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text(vm.tr("KEY_YES"))
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.cancelEmptyRecycleBin() }) {
                Text(vm.tr("KEY_NO"))
            }
        }
    )
}

@Composable
private fun M3SliderSetting(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps.coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun M3SwitchListItem(
    headline: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(headline) },
        trailingContent = {
            M3Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        },
        modifier = Modifier.clickable { onCheckedChange(!checked) }
    )
}

@Composable
private fun M3Switch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        thumbContent = if (checked) {
            {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize)
                )
            }
        } else null
    )
}
