package dm1sh.hedgehog.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dm1sh.hedgehog.model.AppSettings
import kotlin.math.roundToInt

private val BgClearColor = Color(0xFF2E2E2E)
private val PanelBgColor = Color(0xFF4D4D4D)
private val DarkPanelColor = Color(0xFF222222)
private val ButtonNormalColor = Color(0xFF3A3A3A)
private val ButtonPressedColor = Color(0xFF262626)
private val ButtonActiveBorderColor = Color(0xFFFF991A)
private val IconNormalColor = Color(0xFFB8B8B8)
private val IconBusyColor = Color(0xFFFF991A)
private val StatNormalColor = Color(0xFFA1A1A1)
private val ElapsedRunningColor = Color(0xFF6E6E6E)

@Composable
fun EdgeTtsScreen(
    vm: MainViewModel,
    onRequestSystemFilePicker: (FileDialogPurpose) -> Unit,
    onExitApp: () -> Unit
) {
    val context = LocalContext.current
    val calibri = rememberCalibriFont()
    val settings = vm.settings
    val baseFontSize = settings.fontSize.sp

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(BgClearColor)
            .systemBarsPadding()
            .imePadding()
            .pointerInput(Unit) {
                detectTapGestures {
                    vm.onBackgroundTap()
                }
            }
    ) {
        val isWideLandscape = maxWidth >= 820.dp
        val showEditor = settings.showTextAreaPressed

        // Top Progress Bar (matches ProgressBar in index.tscn)
        if (vm.progressBarVisible) {
            val progress = if (vm.progressBarMax > 0) {
                (vm.progressBarValue.toFloat() / vm.progressBarMax.toFloat()).coerceIn(0f, 1f)
            } else 0f
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .align(Alignment.TopCenter),
                color = IconBusyColor,
                trackColor = Color(0xFF1F1F1F)
            )
        }

        if (showEditor && !isWideLandscape) {
            // Fullscreen Editor on compact screens
            TextAreaEditorPanel(
                vm = vm,
                fontFamily = calibri,
                baseFontSize = baseFontSize,
                modifier = Modifier.fillMaxSize()
            )
        } else if (showEditor && isWideLandscape) {
            // Split screen on wide tablets/landscape
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextAreaEditorPanel(
                    vm = vm,
                    fontFamily = calibri,
                    baseFontSize = baseFontSize,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
                CenterMenuColumn(
                    vm = vm,
                    fontFamily = calibri,
                    baseFontSize = baseFontSize,
                    onOpenStoragePermissions = { vm.repo.openStorageSettings(context) },
                    onExitApp = onExitApp,
                    modifier = Modifier.width(400.dp)
                )
            }
        } else {
            // Centered main panel (CenterMenu + Group5 above + Group3 below)
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CenterMenuColumn(
                    vm = vm,
                    fontFamily = calibri,
                    baseFontSize = baseFontSize,
                    onOpenStoragePermissions = { vm.repo.openStorageSettings(context) },
                    onExitApp = onExitApp,
                    modifier = Modifier
                        .width(400.dp)
                        .padding(horizontal = 12.dp, vertical = 16.dp)
                )
            }
        }

        // DopMenu Modals (Chapter Regex / Glav MP3 & Empty Recycle Bin)
        if (vm.dopModal != DopModal.NONE) {
            DopMenuOverlay(
                vm = vm,
                fontFamily = calibri,
                baseFontSize = baseFontSize
            )
        }

        // Built-in File/Directory Browser Dialog
        if (vm.fileDialogPurpose != FileDialogPurpose.NONE) {
            val purpose = vm.fileDialogPurpose
            val initDir = when (purpose) {
                FileDialogPurpose.SAVE_DIR -> settings.saveDir
                FileDialogPurpose.LOAD_DIR, FileDialogPurpose.OPEN_BOOKS -> settings.loadDir
                FileDialogPurpose.LEXX_FILE -> settings.lexxPath.substringBeforeLast("/", settings.loadDir)
                FileDialogPurpose.GLAV_MP3 -> repoDirOrDownloads(vm)
                FileDialogPurpose.NONE -> ""
            }
            FileBrowserDialog(
                purpose = purpose,
                initialDir = initDir,
                tr = vm::tr,
                fontFamily = calibri,
                onConfirmFiles = { paths ->
                    vm.fileDialogPurpose = FileDialogPurpose.NONE
                    vm.ttsSay(vm.tr("KEY_FD_CONFIRMED"))
                    when (purpose) {
                        FileDialogPurpose.OPEN_BOOKS -> vm.onOpenBooks(paths)
                        FileDialogPurpose.LEXX_FILE -> {
                            val chosen = paths.firstOrNull().orEmpty()
                            vm.updateSettings { it.copy(lexxPath = chosen) }
                            vm.loadLexx()
                        }
                        FileDialogPurpose.GLAV_MP3 -> {
                            vm.tempGlavMp3Path = paths.firstOrNull().orEmpty()
                        }
                        else -> {}
                    }
                },
                onConfirmDir = { dir ->
                    vm.fileDialogPurpose = FileDialogPurpose.NONE
                    vm.ttsSay(vm.tr("KEY_FD_CONFIRMED"))
                    when (purpose) {
                        FileDialogPurpose.SAVE_DIR -> vm.updateSettings { it.copy(saveDir = dir) }
                        FileDialogPurpose.LOAD_DIR -> vm.updateSettings { it.copy(loadDir = dir) }
                        else -> {}
                    }
                },
                onUseSystemPicker = {
                    val p = vm.fileDialogPurpose
                    vm.fileDialogPurpose = FileDialogPurpose.NONE
                    onRequestSystemFilePicker(p)
                },
                onCancel = {
                    vm.fileDialogPurpose = FileDialogPurpose.NONE
                    vm.ttsSay(vm.tr("KEY_FD_CANCELLED"))
                },
                onSpeakItem = { name -> vm.ttsSay(name) }
            )
        }
    }
}

private fun repoDirOrDownloads(vm: MainViewModel): String {
    val p = vm.repo.programDir
    return if (p.exists()) p.absolutePath else vm.repo.defaultDownloadsDir
}

@Composable
private fun CenterMenuColumn(
    vm: MainViewModel,
    fontFamily: FontFamily,
    baseFontSize: TextUnit,
    onOpenStoragePermissions: () -> Unit,
    onExitApp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = vm.settings

    Box(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Group5: Status Info Header (StatInfo on left, ElapsedTime + StatStr on right)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        vm.toggleStatusGridVisible()
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = vm.statInfo,
                    color = if (vm.statInfoDoneHighlight) Color.White else StatNormalColor,
                    fontSize = baseFontSize,
                    fontFamily = fontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (vm.elapsedVisible && vm.elapsedTimeText.isNotEmpty()) {
                        Text(
                            text = vm.elapsedTimeText,
                            color = if (vm.workFinished) Color.White else ElapsedRunningColor,
                            fontSize = baseFontSize,
                            fontFamily = fontFamily
                        )
                    }
                    Text(
                        text = vm.statStr,
                        color = StatNormalColor,
                        fontSize = baseFontSize,
                        fontFamily = fontFamily
                    )
                }
            }

            // CenterMenu Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .background(PanelBgColor, RoundedCornerShape(10.dp))
                    .padding(horizontal = 24.dp, vertical = 20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val showGroup1 = !settings.dopSettingsPressed || vm.activeSettingsTab == SettingsTab.VOICE
                val showGroup2 = settings.dopSettingsPressed && vm.activeSettingsTab == SettingsTab.VOICE
                val showGroup6 = settings.dopSettingsPressed && vm.activeSettingsTab == SettingsTab.BOOK
                val showGroup7 = settings.dopSettingsPressed && vm.activeSettingsTab == SettingsTab.TOOL
                val showGroup8 = settings.dopSettingsPressed && vm.activeSettingsTab == SettingsTab.INTERFACE
                val showGroup4 = settings.group4Visible && vm.partStatuses.isNotEmpty()

                if (showGroup1) {
                    Group1VoiceAndSpeed(
                        vm = vm,
                        fontFamily = fontFamily,
                        baseFontSize = baseFontSize
                    )
                }

                if (showGroup2) {
                    Group2VoiceSettings(
                        vm = vm,
                        fontFamily = fontFamily,
                        baseFontSize = baseFontSize
                    )
                }

                if (showGroup6) {
                    Group6BookSettings(
                        vm = vm,
                        fontFamily = fontFamily,
                        baseFontSize = baseFontSize,
                        onOpenStoragePermissions = onOpenStoragePermissions
                    )
                }

                if (showGroup7) {
                    Group7ToolSettings(
                        vm = vm,
                        fontFamily = fontFamily,
                        baseFontSize = baseFontSize
                    )
                }

                if (showGroup8) {
                    Group8InterfaceSettings(
                        vm = vm,
                        fontFamily = fontFamily,
                        baseFontSize = baseFontSize
                    )
                }

                if (showGroup4) {
                    StatusGridView(
                        statuses = vm.partStatuses,
                        tr = vm::tr,
                        onSpeakTooltip = { vm.ttsSay(it) },
                        fontFamily = fontFamily,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Group3: Bottom Action Bar / Settings Tab Bar
            Group3BottomBar(
                vm = vm,
                fontFamily = fontFamily,
                baseFontSize = baseFontSize,
                onExitApp = onExitApp
            )
        }

        // Dropdown Overlays (VoicesList, LanguagesList, LanguagesUITTSList)
        if (vm.voicesDropdownOpen) {
            DropdownListOverlay(
                items = vm.voicesList,
                selectedIndex = settings.voicesSelect,
                fontFamily = fontFamily,
                fontSize = baseFontSize,
                onSelect = { vm.selectVoice(it) },
                onDismiss = { vm.voicesDropdownOpen = false }
            )
        } else if (vm.languagesDropdownOpen) {
            DropdownListOverlay(
                items = vm.languagesList,
                selectedIndex = settings.languagesSelect,
                fontFamily = fontFamily,
                fontSize = baseFontSize,
                onSelect = { vm.selectLanguage(it) },
                onDismiss = { vm.languagesDropdownOpen = false }
            )
        } else if (vm.uiTtsDropdownOpen) {
            val displayItems = vm.uiTtsList.mapIndexed { idx, raw ->
                if (idx == 0) vm.tr("KEY_OFF") else raw
            }
            DropdownListOverlay(
                items = displayItems,
                selectedIndex = settings.languagesUiTtsSelect,
                fontFamily = fontFamily,
                fontSize = baseFontSize,
                onSelect = { vm.selectUiTts(it) },
                onDismiss = { vm.uiTtsDropdownOpen = false }
            )
        }
    }
}

@Composable
private fun Group1VoiceAndSpeed(
    vm: MainViewModel,
    fontFamily: FontFamily,
    baseFontSize: TextUnit
) {
    val settings = vm.settings
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Voices selector button
        DropdownSelectorButton(
            text = settings.voicesName,
            isOpen = vm.voicesDropdownOpen,
            fontFamily = fontFamily,
            fontSize = baseFontSize,
            onClick = {
                vm.languagesDropdownOpen = false
                vm.uiTtsDropdownOpen = false
                vm.voicesDropdownOpen = !vm.voicesDropdownOpen
                vm.ttsSay(vm.voiceSayText(settings.voicesName))
            }
        )

        // Speed (Rate) slider (-50% .. +100%, step 5)
        GodotSliderWithLabel(
            label = vm.tr("KEY_SPEED"),
            valueText = "${vm.formatSignedValue(settings.rate)}%",
            value = settings.rate.toFloat(),
            valueRange = -50f..100f,
            steps = 29,
            onValueChange = { vm.setRate(it.roundToInt()) },
            fontFamily = fontFamily,
            fontSize = baseFontSize
        )
    }
}

@Composable
private fun Group2VoiceSettings(
    vm: MainViewModel,
    fontFamily: FontFamily,
    baseFontSize: TextUnit
) {
    val settings = vm.settings
    val downloadIcon = rememberAssetIcon("download.png")

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Pitch slider (-50Hz .. +50Hz)
        GodotSliderWithLabel(
            label = vm.tr("KEY_PITCH"),
            valueText = "${vm.formatSignedValue(settings.pitch)}Hz",
            value = settings.pitch.toFloat(),
            valueRange = -50f..50f,
            steps = 99,
            onValueChange = { vm.setPitch(it.roundToInt()) },
            fontFamily = fontFamily,
            fontSize = baseFontSize
        )

        // Low bitrate (48 kbps)
        GodotSwitchRow(
            text = vm.tr("KEY_LOWBITRATE"),
            checked = settings.lowBitrate,
            onCheckedChange = { checked ->
                vm.updateSettings { it.copy(lowBitrate = checked) }
                vm.ttsSay("${vm.tr("KEY_LOWBITRATE")} ${vm.tr(if (checked) "KEY_ON" else "KEY_OFF")}")
            },
            fontFamily = fontFamily,
            fontSize = baseFontSize
        )

        // Preview voice on selection
        GodotSwitchRow(
            text = vm.tr("KEY_VOICE_PREVIEW"),
            checked = settings.voicePreview,
            onCheckedChange = { checked ->
                vm.updateSettings { it.copy(voicePreview = checked) }
                if (!checked && !vm.partPreviewActive) {
                    vm.voicePreviewStop()
                }
                vm.ttsSay("${vm.tr("KEY_VOICE_PREVIEW")} ${vm.tr(if (checked) "KEY_ON" else "KEY_OFF")}")
            },
            fontFamily = fontFamily,
            fontSize = baseFontSize
        )

        // Get online voices
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GodotIconButton(
                icon = downloadIcon,
                contentDescription = vm.tr("KEY_GET_VOICES"),
                iconTint = if (vm.onlineVoicesBusy) IconBusyColor else IconNormalColor,
                onClick = { vm.fetchOnlineVoices() },
                modifier = Modifier.size(40.dp)
            )
            Text(
                text = vm.tr("KEY_GET_VOICES"),
                color = Color.White,
                fontSize = baseFontSize,
                fontFamily = fontFamily,
                modifier = Modifier.weight(1f)
            )
        }

        // Get offline voices
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GodotIconButton(
                icon = downloadIcon,
                contentDescription = vm.tr("KEY_GET_OFFLINE_VOICES"),
                iconTint = if (vm.offlineVoicesBusy) IconBusyColor else IconNormalColor,
                onClick = { vm.fetchOfflineVoices() },
                modifier = Modifier.size(40.dp)
            )
            Text(
                text = vm.tr("KEY_GET_OFFLINE_VOICES"),
                color = Color.White,
                fontSize = baseFontSize,
                fontFamily = fontFamily,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun Group6BookSettings(
    vm: MainViewModel,
    fontFamily: FontFamily,
    baseFontSize: TextUnit,
    onOpenStoragePermissions: () -> Unit
) {
    val settings = vm.settings
    val shieldIcon = rememberAssetIcon("shield.png")
    val chapterIcon = rememberAssetIcon("chapter.png")
    val recycleIcon = rememberAssetIcon("recycle_bin.png")

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Save folder + Storage permission button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GodotButton(
                text = "...",
                onClick = {
                    vm.fileDialogPurpose = FileDialogPurpose.SAVE_DIR
                    vm.ttsSay(vm.tr("KEY_SELECT_SAVE_DIR"))
                },
                fontFamily = fontFamily,
                fontSize = baseFontSize,
                modifier = Modifier.size(width = 40.dp, height = 38.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = vm.tr("KEY_SAVE_DIR"),
                    color = Color.White,
                    fontSize = baseFontSize,
                    fontFamily = fontFamily
                )
                if (settings.saveDir.isNotEmpty()) {
                    Text(
                        text = settings.saveDir,
                        color = Color(0xFFAAAAAA),
                        fontSize = 12.sp,
                        fontFamily = fontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            GodotIconButton(
                icon = shieldIcon,
                contentDescription = vm.tr("KEY_STORAGE_PERMISSION"),
                onClick = {
                    vm.ttsSay(vm.tr("KEY_STORAGE_PERMISSION"))
                    onOpenStoragePermissions()
                },
                modifier = Modifier.size(38.dp)
            )
        }

        // Load folder + ... button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GodotButton(
                text = "...",
                onClick = {
                    vm.fileDialogPurpose = FileDialogPurpose.LOAD_DIR
                    vm.ttsSay(vm.tr("KEY_SELECT_LOAD_DIR"))
                },
                fontFamily = fontFamily,
                fontSize = baseFontSize,
                modifier = Modifier.size(width = 40.dp, height = 38.dp)
            )
            GodotSwitchRow(
                text = vm.tr("KEY_LOAD_DIR"),
                checked = settings.loadDirUse,
                onCheckedChange = { checked ->
                    val actual = if (checked && settings.loadDir.isBlank()) false else checked
                    vm.updateSettings { it.copy(loadDirUse = actual) }
                    vm.ttsSay("${vm.tr("KEY_LOAD_DIR")} ${vm.tr(if (actual) "KEY_ON" else "KEY_OFF")}")
                },
                fontFamily = fontFamily,
                fontSize = baseFontSize,
                modifier = Modifier.weight(1f)
            )
        }

        // Use dictionary (.lexx) + ... button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GodotButton(
                text = "...",
                onClick = {
                    vm.fileDialogPurpose = FileDialogPurpose.LEXX_FILE
                    vm.ttsSay(vm.tr("KEY_SELECT_LEXX"))
                },
                fontFamily = fontFamily,
                fontSize = baseFontSize,
                modifier = Modifier.size(width = 40.dp, height = 38.dp)
            )
            GodotSwitchRow(
                text = vm.tr("KEY_USE_LEXX"),
                checked = settings.lexxUse,
                onCheckedChange = { checked ->
                    val actual = if (checked && settings.lexxPath.isBlank()) false else checked
                    vm.updateSettings { it.copy(lexxUse = actual) }
                    vm.ttsSay("${vm.tr("KEY_USE_LEXX")} ${vm.tr(if (actual) "KEY_ON" else "KEY_OFF")}")
                },
                fontFamily = fontFamily,
                fontSize = baseFontSize,
                modifier = Modifier.weight(1f)
            )
        }

        // Case sensitive dictionary
        GodotSwitchRow(
            text = vm.tr("KEY_LEXX_REGISTER"),
            checked = settings.lexxRegister,
            onCheckedChange = { checked ->
                vm.updateSettings { it.copy(lexxRegister = checked) }
                vm.ttsSay("${vm.tr("KEY_LEXX_REGISTER")} ${vm.tr(if (checked) "KEY_ON" else "KEY_OFF")}")
            },
            fontFamily = fontFamily,
            fontSize = baseFontSize
        )

        // Save to a separate folder
        GodotSwitchRow(
            text = vm.tr("KEY_DIR_FOR_MP3"),
            checked = settings.bookDirCreate,
            onCheckedChange = { checked ->
                vm.updateSettings { it.copy(bookDirCreate = checked) }
                vm.ttsSay("${vm.tr("KEY_DIR_FOR_MP3")} ${vm.tr(if (checked) "KEY_ON" else "KEY_OFF")}")
            },
            fontFamily = fontFamily,
            fontSize = baseFontSize
        )

        // Cut by chapters + Chapter Regex button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GodotIconButton(
                icon = chapterIcon,
                contentDescription = vm.tr("KEY_REGEX_GLAVA"),
                onClick = { vm.openChapterSettingsModal() },
                modifier = Modifier.size(width = 40.dp, height = 38.dp)
            )
            GodotSwitchRow(
                text = vm.tr("KEY_BOOK_CHAPTER"),
                checked = settings.bookChapter,
                onCheckedChange = { checked ->
                    vm.updateSettings { it.copy(bookChapter = checked) }
                    vm.ttsSay("${vm.tr("KEY_BOOK_CHAPTER")} ${vm.tr(if (checked) "KEY_ON" else "KEY_OFF")}")
                },
                fontFamily = fontFamily,
                fontSize = baseFontSize,
                modifier = Modifier.weight(1f)
            )
        }

        // Use Recycle Bin + Empty Trash button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GodotIconButton(
                icon = recycleIcon,
                contentDescription = vm.tr("KEY_CLEAR_BIN"),
                onClick = { vm.openRecycleBinModal() },
                modifier = Modifier.size(width = 40.dp, height = 38.dp)
            )
            GodotSwitchRow(
                text = vm.tr("KEY_RECYCLE_BIN"),
                checked = settings.recycleBinUse,
                onCheckedChange = { checked ->
                    vm.updateSettings { it.copy(recycleBinUse = checked) }
                    vm.ttsSay("${vm.tr("KEY_RECYCLE_BIN")} ${vm.tr(if (checked) "KEY_ON" else "KEY_OFF")}")
                },
                fontFamily = fontFamily,
                fontSize = baseFontSize,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun Group7ToolSettings(
    vm: MainViewModel,
    fontFamily: FontFamily,
    baseFontSize: TextUnit
) {
    val settings = vm.settings
    val rdIndex = AppSettings.restartDelayIndexFromSeconds(settings.restartDelay)
    val rdLabel = if (settings.restartDelay < 0) "∞" else settings.restartDelay.toString()
    val mergeLabel = if (settings.mergeFiles == 100) {
        vm.tr("KEY_ALL")
    } else {
        "${settings.mergeFiles} ${vm.tr("KEY_UNIT")}"
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Number of streams (1..100)
        GodotSliderWithLabel(
            label = vm.tr("KEY_THREADSCOUNT"),
            valueText = settings.maxThreads.toString(),
            value = settings.maxThreads.toFloat(),
            valueRange = 1f..100f,
            steps = 98,
            onValueChange = { vm.setMaxThreads(it.roundToInt()) },
            fontFamily = fontFamily,
            fontSize = baseFontSize
        )

        // Restart Delay (index 0..19)
        GodotSliderWithLabel(
            label = vm.tr("KEY_RESTARTDELAY"),
            valueText = rdLabel,
            value = rdIndex.toFloat(),
            valueRange = 0f..AppSettings.RESTART_DELAY_STEPS.size.toFloat(),
            steps = AppSettings.RESTART_DELAY_STEPS.size - 1,
            onValueChange = { vm.setRestartDelayIndex(it.roundToInt()) },
            fontFamily = fontFamily,
            fontSize = baseFontSize
        )

        // Merge MP3 by (1..100)
        GodotSliderWithLabel(
            label = vm.tr("KEY_MARGE_FILES"),
            valueText = mergeLabel,
            value = settings.mergeFiles.toFloat(),
            valueRange = 1f..100f,
            steps = 98,
            onValueChange = { vm.setMergeFiles(it.roundToInt()) },
            fontFamily = fontFamily,
            fontSize = baseFontSize
        )
    }
}

@Composable
private fun Group8InterfaceSettings(
    vm: MainViewModel,
    fontFamily: FontFamily,
    baseFontSize: TextUnit
) {
    val settings = vm.settings
    val currentLangText = vm.languagesList.getOrElse(settings.languagesSelect) { "AUTO" }
    val currentUiTtsText = if (settings.languagesUiTtsSelect == 0) {
        vm.tr("KEY_OFF")
    } else {
        vm.uiTtsList.getOrElse(settings.languagesUiTtsSelect) { vm.tr("KEY_OFF") }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Interface language
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = vm.tr("KEY_LANGUAGE"),
                color = Color.White,
                fontSize = baseFontSize,
                fontFamily = fontFamily
            )
            DropdownSelectorButton(
                text = currentLangText,
                isOpen = vm.languagesDropdownOpen,
                fontFamily = fontFamily,
                fontSize = baseFontSize,
                onClick = {
                    vm.voicesDropdownOpen = false
                    vm.uiTtsDropdownOpen = false
                    vm.languagesDropdownOpen = !vm.languagesDropdownOpen
                    vm.ttsSay("${vm.tr("KEY_LANGUAGE")} $currentLangText")
                }
            )
        }

        // Screen reader (LanguagesUITTS)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = vm.tr("KEY_UITTS"),
                color = Color.White,
                fontSize = baseFontSize,
                fontFamily = fontFamily
            )
            DropdownSelectorButton(
                text = currentUiTtsText,
                isOpen = vm.uiTtsDropdownOpen,
                fontFamily = fontFamily,
                fontSize = baseFontSize,
                onClick = {
                    vm.voicesDropdownOpen = false
                    vm.languagesDropdownOpen = false
                    vm.uiTtsDropdownOpen = !vm.uiTtsDropdownOpen
                    vm.ttsSay("${vm.tr("KEY_UITTS")} $currentUiTtsText")
                }
            )
        }

        // UI Size (ZoomUI 50%..400%)
        GodotSliderWithLabel(
            label = vm.tr("KEY_ZOOM"),
            valueText = "${(settings.zoomUi * 100).roundToInt()}%",
            value = settings.zoomUi,
            valueRange = 0.5f..4.0f,
            steps = 34,
            onValueChange = { vm.setZoomUi(it) },
            fontFamily = fontFamily,
            fontSize = baseFontSize
        )

        // Font size (16..24)
        GodotSliderWithLabel(
            label = vm.tr("KEY_FONTSIZE"),
            valueText = settings.fontSize.toString(),
            value = settings.fontSize.toFloat(),
            valueRange = 16f..24f,
            steps = 7,
            onValueChange = { vm.setFontSize(it.roundToInt()) },
            fontFamily = fontFamily,
            fontSize = baseFontSize
        )
    }
}

@Composable
private fun Group3BottomBar(
    vm: MainViewModel,
    fontFamily: FontFamily,
    baseFontSize: TextUnit,
    onExitApp: () -> Unit
) {
    val settings = vm.settings
    val dopIcon = rememberAssetIcon("dopmenu.png")
    val pencilIcon = rememberAssetIcon("pencil.png")
    val openIcon = rememberAssetIcon("open.png")
    val pauseIcon = rememberAssetIcon("pause.png")
    val resumeIcon = rememberAssetIcon("resume.png")

    val backIcon = rememberAssetIcon("back.png")
    val voiceIcon = rememberAssetIcon("voice.png")
    val bookIcon = rememberAssetIcon("book.png")
    val toolIcon = rememberAssetIcon("tool.png")
    val interfaceIcon = rememberAssetIcon("interface.png")
    val powerIcon = rememberAssetIcon("power.png")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(82.dp)
            .background(PanelBgColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        if (!settings.dopSettingsPressed) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GodotIconButton(
                    icon = dopIcon,
                    contentDescription = vm.tr("KEY_SETTINGS"),
                    onClick = { vm.toggleDopSettings() },
                    modifier = Modifier.size(50.dp)
                )
                GodotIconButton(
                    icon = pencilIcon,
                    contentDescription = vm.tr("KEY_VIEW_TEXT"),
                    isToggled = settings.showTextAreaPressed,
                    onClick = { vm.toggleTextAreaPanel() },
                    modifier = Modifier.size(50.dp)
                )
                GodotIconButton(
                    icon = openIcon,
                    contentDescription = vm.tr("KEY_OPEN_BOOKS"),
                    isToggled = vm.bookLoaded,
                    onClick = { vm.onFileButtonClicked() },
                    modifier = Modifier.size(50.dp)
                )
                GodotButton(
                    text = vm.tr(if (vm.runWork) "KEY_STOP" else "KEY_SAVE_MP3"),
                    onClick = { vm.onSaveOrStopButtonPressed() },
                    fontFamily = fontFamily,
                    fontSize = baseFontSize,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                )
                if (vm.runWork) {
                    GodotIconButton(
                        icon = if (vm.workPaused) resumeIcon else pauseIcon,
                        contentDescription = vm.tr(if (vm.workPaused) "KEY_RESUME" else "KEY_PAUSE"),
                        onClick = { vm.onPauseOrResumeButtonPressed() },
                        modifier = Modifier.size(50.dp)
                    )
                }
            }
        } else {
            // SettingButtons bar: Back, Voice, Book, Tool, Interface, Exit
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GodotIconButton(
                    icon = backIcon,
                    contentDescription = vm.tr("KEY_SETTINGS_BACK"),
                    onClick = { vm.toggleDopSettings() },
                    modifier = Modifier.size(50.dp)
                )
                GodotIconButton(
                    icon = voiceIcon,
                    contentDescription = vm.tr("KEY_SETTINGS_VOICE"),
                    isToggled = vm.activeSettingsTab == SettingsTab.VOICE,
                    onClick = { vm.selectSettingsTab(SettingsTab.VOICE) },
                    modifier = Modifier.size(50.dp)
                )
                GodotIconButton(
                    icon = bookIcon,
                    contentDescription = vm.tr("KEY_SETTINGS_BOOK"),
                    isToggled = vm.activeSettingsTab == SettingsTab.BOOK,
                    onClick = { vm.selectSettingsTab(SettingsTab.BOOK) },
                    modifier = Modifier.size(50.dp)
                )
                GodotIconButton(
                    icon = toolIcon,
                    contentDescription = vm.tr("KEY_SETTINGS_TOOL"),
                    isToggled = vm.activeSettingsTab == SettingsTab.TOOL,
                    onClick = { vm.selectSettingsTab(SettingsTab.TOOL) },
                    modifier = Modifier.size(50.dp)
                )
                GodotIconButton(
                    icon = interfaceIcon,
                    contentDescription = vm.tr("KEY_SETTINGS_INTERFACE"),
                    isToggled = vm.activeSettingsTab == SettingsTab.INTERFACE,
                    onClick = { vm.selectSettingsTab(SettingsTab.INTERFACE) },
                    modifier = Modifier.size(50.dp)
                )
                GodotIconButton(
                    icon = powerIcon,
                    contentDescription = vm.tr("KEY_EXIT"),
                    onClick = {
                        vm.stopConversion(announce = false)
                        onExitApp()
                    },
                    modifier = Modifier.size(50.dp)
                )
            }
        }
    }
}

@Composable
private fun TextAreaEditorPanel(
    vm: MainViewModel,
    fontFamily: FontFamily,
    baseFontSize: TextUnit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val taFontSize = (vm.settings.fontSize + 4).sp
    val noNav = vm.totalPartsCount <= 1

    Column(
        modifier = modifier
            .background(DarkPanelColor, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        // Top bar: Play/Stop button on left, "Total parts: N" centered
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
        ) {
            if (!vm.runWork) {
                GodotButton(
                    text = if (vm.partPreviewActive) "■" else "▶",
                    onClick = { vm.onEditorPlayPressed() },
                    fontFamily = fontFamily,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .size(40.dp)
                        .align(Alignment.CenterStart)
                )
            }
            Text(
                text = "${vm.tr("KEY_TOTAL_PARTS")}${vm.totalPartsCount}",
                color = Color(0xFFD9D9D9),
                fontSize = 19.sp,
                fontFamily = fontFamily,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Main TextEdit area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF1C1C1C), RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            if (vm.textAreaText.isEmpty()) {
                Text(
                    text = vm.tr("KEY_TEXTAREA"),
                    color = Color(0xFF666666),
                    fontSize = taFontSize,
                    fontFamily = fontFamily
                )
            }
            BasicTextField(
                value = vm.textAreaText,
                onValueChange = { if (!vm.runWork) vm.textAreaText = it },
                readOnly = vm.runWork,
                textStyle = TextStyle(
                    color = Color(0xFFEEEEEE),
                    fontSize = taFontSize,
                    fontFamily = fontFamily
                ),
                cursorBrush = SolidColor(IconBusyColor),
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Bottom Bar: Close (×), <, Part input, >, Save (✓)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            GodotButton(
                text = "×",
                onClick = { vm.onEditorClosePressed() },
                fontFamily = fontFamily,
                fontSize = 26.sp,
                modifier = Modifier.size(60.dp)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GodotButton(
                    text = "<",
                    enabled = !noNav,
                    onClick = { vm.onEditorPrevPressed() },
                    fontFamily = fontFamily,
                    fontSize = 24.sp,
                    modifier = Modifier.size(60.dp)
                )

                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .height(44.dp)
                        .background(Color(0xFF2E2E2E), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    BasicTextField(
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
                        textStyle = TextStyle(
                            color = Color(0xFFF2F2F2),
                            fontSize = 18.sp,
                            fontFamily = fontFamily,
                            textAlign = TextAlign.Center
                        ),
                        cursorBrush = SolidColor(IconBusyColor),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                GodotButton(
                    text = ">",
                    enabled = !noNav,
                    onClick = { vm.onEditorNextPressed() },
                    fontFamily = fontFamily,
                    fontSize = 24.sp,
                    modifier = Modifier.size(60.dp)
                )
            }

            GodotButton(
                text = "✓",
                enabled = !vm.runWork,
                onClick = { vm.onEditorSavePressed() },
                fontFamily = fontFamily,
                fontSize = 26.sp,
                modifier = Modifier.size(60.dp)
            )
        }
    }
}

@Composable
private fun DopMenuOverlay(
    vm: MainViewModel,
    fontFamily: FontFamily,
    baseFontSize: TextUnit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xAA000000))
            .clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center
    ) {
        if (vm.dopModal == DopModal.REGEX_GLAVA) {
            Column(
                modifier = Modifier
                    .width(420.dp)
                    .padding(horizontal = 16.dp)
                    .background(PanelBgColor, RoundedCornerShape(10.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = vm.tr("KEY_REGEX_GLAVA"),
                    color = Color.White,
                    fontSize = 26.sp,
                    fontFamily = fontFamily,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(Color(0xFF262626), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BasicTextField(
                        value = vm.tempRegexGlava,
                        onValueChange = { vm.tempRegexGlava = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = baseFontSize,
                            fontFamily = fontFamily,
                            textAlign = TextAlign.Center
                        ),
                        cursorBrush = SolidColor(IconBusyColor),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Sound at the beginning of chapters + ... button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GodotButton(
                        text = "...",
                        onClick = {
                            vm.fileDialogPurpose = FileDialogPurpose.GLAV_MP3
                            vm.ttsSay(vm.tr("KEY_SELECT_ADDGLAVMP3"))
                        },
                        fontFamily = fontFamily,
                        fontSize = baseFontSize,
                        modifier = Modifier.size(width = 40.dp, height = 38.dp)
                    )
                    GodotSwitchRow(
                        text = vm.tr("KEY_ADDGLAVMP3"),
                        checked = vm.tempGlavMp3Use,
                        onCheckedChange = { vm.tempGlavMp3Use = it },
                        fontFamily = fontFamily,
                        fontSize = baseFontSize,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Chapter name in file name
                GodotSwitchRow(
                    text = vm.tr("KEY_ADDGLAVTEXT"),
                    checked = vm.tempGlavTextUse,
                    onCheckedChange = { vm.tempGlavTextUse = it },
                    fontFamily = fontFamily,
                    fontSize = baseFontSize
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    GodotButton(
                        text = vm.tr("KEY_OK"),
                        onClick = { vm.confirmChapterSettingsModal() },
                        fontFamily = fontFamily,
                        fontSize = 22.sp,
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp)
                    )
                    GodotButton(
                        text = vm.tr("KEY_CANCEL"),
                        onClick = { vm.cancelChapterSettingsModal() },
                        fontFamily = fontFamily,
                        fontSize = 22.sp,
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp)
                    )
                }
            }
        } else if (vm.dopModal == DopModal.RECYCLE_BIN) {
            Column(
                modifier = Modifier
                    .width(420.dp)
                    .padding(horizontal = 16.dp)
                    .background(PanelBgColor, RoundedCornerShape(10.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = vm.tr("KEY_CLEAR_BIN"),
                    color = Color.White,
                    fontSize = 26.sp,
                    fontFamily = fontFamily,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    GodotButton(
                        text = vm.tr("KEY_YES"),
                        onClick = { vm.confirmEmptyRecycleBin() },
                        fontFamily = fontFamily,
                        fontSize = 22.sp,
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp)
                    )
                    GodotButton(
                        text = vm.tr("KEY_NO"),
                        onClick = { vm.cancelEmptyRecycleBin() },
                        fontFamily = fontFamily,
                        fontSize = 22.sp,
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DropdownListOverlay(
    items: List<String>,
    selectedIndex: Int,
    fontFamily: FontFamily,
    fontSize: TextUnit,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex) {
        if (selectedIndex in items.indices) {
            listState.scrollToItem((selectedIndex - 2).coerceAtLeast(0))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .heightIn(max = 420.dp)
                .background(Color(0xFF242424), RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFF444444), RoundedCornerShape(10.dp))
                .padding(vertical = 10.dp, horizontal = 8.dp)
        ) {
            itemsIndexed(items) { index, itemText ->
                val isSelected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .background(
                            if (isSelected) Color(0xFF424242) else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onSelect(index) }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = itemText,
                        color = if (isSelected) Color.White else Color(0xFFD6D6D6),
                        fontSize = fontSize,
                        fontFamily = fontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun DropdownSelectorButton(
    text: String,
    isOpen: Boolean,
    fontFamily: FontFamily,
    fontSize: TextUnit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .background(ButtonNormalColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = fontSize,
            fontFamily = fontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (isOpen) "○" else "●",
            color = Color.White,
            fontSize = fontSize,
            fontFamily = fontFamily
        )
    }
}

@Composable
private fun GodotSliderWithLabel(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    fontFamily: FontFamily,
    fontSize: TextUnit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = fontSize,
                fontFamily = fontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueText,
                color = Color.White,
                fontSize = fontSize,
                fontFamily = fontFamily
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps.coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFDDDDDD),
                activeTrackColor = Color(0xFFAAAAAA),
                inactiveTrackColor = Color(0xFF2B2B2B)
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun GodotSwitchRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    fontFamily: FontFamily,
    fontSize: TextUnit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = fontSize,
            fontFamily = fontFamily,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF6699CC),
                uncheckedThumbColor = Color(0xFFAAAAAA),
                uncheckedTrackColor = Color(0xFF2B2B2B)
            )
        )
    }
}

@Composable
fun GodotButton(
    text: String,
    onClick: () -> Unit,
    fontFamily: FontFamily,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .background(
                if (enabled) ButtonNormalColor else ButtonPressedColor,
                RoundedCornerShape(10.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) Color(0xFFF2F2F2) else Color(0xFF777777),
            fontSize = fontSize,
            fontFamily = fontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun GodotIconButton(
    icon: ImageBitmap?,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isToggled: Boolean = false,
    iconTint: Color = IconNormalColor
) {
    Box(
        modifier = modifier
            .background(
                if (isToggled) ButtonPressedColor else ButtonNormalColor,
                RoundedCornerShape(10.dp)
            )
            .then(
                if (isToggled) Modifier.border(1.5.dp, ButtonActiveBorderColor, RoundedCornerShape(10.dp))
                else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = contentDescription,
                colorFilter = ColorFilter.tint(if (isToggled) Color.White else iconTint),
                modifier = Modifier.size(26.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(iconTint, CircleShape)
            )
        }
    }
}
