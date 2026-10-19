package dm1sh.hedgehog.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dm1sh.hedgehog.model.AppSettings
import dm1sh.hedgehog.model.PartStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EdgeTtsScreen(
    vm: MainViewModel,
    onRequestNativePicker: (NativePickerType) -> Unit,
    onExitApp: () -> Unit
) {
    val context = LocalContext.current

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
            Column(modifier = Modifier.fillMaxWidth()) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = context.getString(dm1sh.hedgehog.R.string.app_name),
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            TopBarSubtitleStatus(vm = vm)
                        }
                    },
                    actions = {
                        val isOnlineActive = if (vm.activeMainTab == MainTab.PLAYER) {
                            vm.settings.useOnlineVoicesPlayer && !vm.repo.isVoiceOffline(vm.settings.voicesName)
                        } else {
                            vm.settings.useOnlineVoices && !vm.repo.isVoiceOffline(vm.settings.voicesName)
                        }
                        val rawVoiceName = if (isOnlineActive) {
                            vm.settings.voicesName
                        } else {
                            vm.settings.offlineVoicesName.ifBlank { vm.tr("KEY_OFFLINE_UNAVAILABLE") }
                        }
                        val chipLabel = if (rawVoiceName == vm.tr("KEY_OFFLINE_UNAVAILABLE")) {
                            rawVoiceName
                        } else {
                            formatShortVoiceName(rawVoiceName)
                        }

                        Surface(
                            onClick = { vm.openVoiceSettings() },
                            shape = CircleShape,
                            color = if (isOnlineActive) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            contentColor = if (isOnlineActive) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            border = BorderStroke(
                                1.dp,
                                if (isOnlineActive) {
                                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                }
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (isOnlineActive) {
                                        Icons.Outlined.Cloud
                                    } else {
                                        Icons.Outlined.OfflinePin
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (isOnlineActive) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                Text(
                                    text = chipLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                vm.stopConversion(announce = false)
                                vm.stopPlayerPlayback(clearCache = true)
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
                TopBarProgressStrip(vm = vm)
            }
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ConversionActionDock(
                    vm = vm,
                    onRequestNativePicker = onRequestNativePicker
                )
                NavigationBar {
                    NavigationBarItem(
                        selected = vm.activeMainTab == MainTab.BOOK,
                        onClick = { vm.selectMainTab(MainTab.BOOK) },
                        icon = {
                            Icon(
                                imageVector = if (vm.activeMainTab == MainTab.BOOK) {
                                    Icons.Filled.MenuBook
                                } else {
                                    Icons.AutoMirrored.Outlined.MenuBook
                                },
                                contentDescription = vm.tr("KEY_BOOK")
                            )
                        },
                        label = {
                            Text(
                                text = vm.tr("KEY_BOOK"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                    NavigationBarItem(
                        selected = vm.activeMainTab == MainTab.PARTS,
                        onClick = { vm.selectMainTab(MainTab.PARTS) },
                        icon = {
                            Icon(
                                imageVector = if (vm.activeMainTab == MainTab.PARTS) {
                                    Icons.Filled.EditNote
                                } else {
                                    Icons.Outlined.EditNote
                                },
                                contentDescription = vm.tr("KEY_VIEW_TEXT")
                            )
                        },
                        label = {
                            Text(
                                text = vm.tr("KEY_VIEW_TEXT"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                    NavigationBarItem(
                        selected = vm.activeMainTab == MainTab.PLAYER,
                        onClick = { vm.selectMainTab(MainTab.PLAYER) },
                        icon = {
                            Icon(
                                imageVector = if (vm.activeMainTab == MainTab.PLAYER) {
                                    Icons.Filled.PlayCircleFilled
                                } else {
                                    Icons.Outlined.PlayCircleOutline
                                },
                                contentDescription = vm.tr("KEY_PLAYER")
                            )
                        },
                        label = {
                            Text(
                                text = vm.tr("KEY_PLAYER"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                    NavigationBarItem(
                        selected = vm.activeMainTab == MainTab.SETTINGS,
                        onClick = { vm.selectMainTab(MainTab.SETTINGS) },
                        icon = {
                            Icon(
                                imageVector = if (vm.activeMainTab == MainTab.SETTINGS) {
                                    Icons.Filled.Settings
                                } else {
                                    Icons.Outlined.Settings
                                },
                                contentDescription = vm.tr("KEY_SETTINGS")
                            )
                        },
                        label = {
                            Text(
                                text = vm.tr("KEY_SETTINGS"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (vm.activeMainTab) {
                MainTab.BOOK -> {
                    BookTabContent(
                        vm = vm,
                        onRequestNativePicker = onRequestNativePicker,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                    )
                }
                MainTab.PARTS -> {
                    TextAreaEditorPanel(
                        vm = vm,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                MainTab.PLAYER -> {
                    PlayerTabContent(
                        vm = vm,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                MainTab.SETTINGS -> {
                    SettingsTabContent(
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
    }

    // Online & Offline Voices Selection ModalBottomSheets
    if (vm.voicesDropdownOpen) {
        VoicesPickerBottomSheet(
            title = vm.tr("KEY_ONLINE_VOICE"),
            voices = vm.voicesList,
            selectedIndex = vm.settings.voicesSelect,
            isOfflinePicker = false,
            offlineStatusText = vm.tr("KEY_VOICE_OFFLINE"),
            emptyText = vm.tr("KEY_GET_VOICES"),
            onSelectVoice = { vm.selectVoice(it) },
            onDismiss = { vm.voicesDropdownOpen = false }
        )
    }
    if (vm.offlineVoicesDropdownOpen) {
        VoicesPickerBottomSheet(
            title = vm.tr("KEY_OFFLINE_VOICE"),
            voices = vm.offlineVoicesList,
            selectedIndex = vm.settings.offlineVoicesSelect,
            isOfflinePicker = true,
            offlineStatusText = vm.tr("KEY_VOICE_OFFLINE"),
            emptyText = vm.tr("KEY_OFFLINE_UNAVAILABLE"),
            onSelectVoice = { vm.selectOfflineVoice(it) },
            onDismiss = { vm.offlineVoicesDropdownOpen = false }
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
private fun BookTabContent(
    vm: MainViewModel,
    onRequestNativePicker: (NativePickerType) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    var bookTitlesHeightPx by remember { mutableIntStateOf(0) }
    var statusHeaderHeightPx by remember { mutableIntStateOf(0) }

    val minGridHeightDp = 214.dp // 10 rows: 10 * 16.dp + 9 * 6.dp
    val maxGridHeightDp = if (viewportHeightPx > 0) {
        val viewportDp = with(density) { viewportHeightPx.toDp() }
        val titlesDp = if (vm.bookTitles.isNotEmpty()) with(density) { bookTitlesHeightPx.toDp() } else 0.dp
        val statusHeaderDp = if (statusHeaderHeightPx > 0) with(density) { statusHeaderHeightPx.toDp() } else 48.dp
        val outerGapsDp = if (vm.bookTitles.isNotEmpty()) 46.dp else 34.dp
        val cardInnerChromeDp = 44.dp // 16.dp top + 16.dp bottom padding + 12.dp spacing above grid
        val available = viewportDp - titlesDp - statusHeaderDp - outerGapsDp - cardInnerChromeDp
        maxOf(minGridHeightDp, available)
    } else {
        minGridHeightDp
    }

    Box(
        modifier = modifier.onSizeChanged { viewportHeightPx = it.height }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(2.dp))

            // Dynamic Area: If a book is loading, show loading indicator; if loaded, show StatusGrid; otherwise show list of files in Load folder
            when {
                vm.isBookLoading -> {
                    BookLoadingCard(
                        vm = vm,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp)
                    )
                }
                vm.bookLoaded && vm.partStatuses.isNotEmpty() -> {
                    BookStatusGridCard(
                        vm = vm,
                        maxGridHeightDp = maxGridHeightDp,
                        onHeaderHeightChanged = { statusHeaderHeightPx = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (vm.bookTitles.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onSizeChanged { bookTitlesHeightPx = it.height },
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val multiBooks = vm.bookTitles.size > 1
                            vm.bookTitles.forEachIndexed { idx, title ->
                                OutlinedTextField(
                                    value = title,
                                    onValueChange = { if (!vm.runWork) vm.updateBookTitle(idx, it) },
                                    readOnly = vm.runWork,
                                    singleLine = true,
                                    label = {
                                        Text(
                                            text = if (multiBooks) {
                                                "${vm.tr("KEY_BOOK")} ${idx + 1}"
                                            } else {
                                                vm.tr("KEY_BOOK")
                                            }
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
                else -> {
                    LoadFolderFilesCard(
                        vm = vm,
                        onRequestNativePicker = onRequestNativePicker,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun BookLoadingCard(
    vm: MainViewModel,
    modifier: Modifier = Modifier
) {
    OutlinedCard(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                CircularProgressIndicator()
                Text(
                    text = vm.tr("KEY_LOAD"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun BookStatusGridCard(
    vm: MainViewModel,
    maxGridHeightDp: Dp,
    onHeaderHeightChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val statusText = when {
        vm.statInfo.isNotBlank() -> vm.statInfo
        vm.bookLoaded -> vm.bookLoadedCaption()
        else -> vm.tr("KEY_OPEN_BOOKS")
    }

    ElevatedCard(
        modifier = modifier,
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { onHeaderHeightChanged(it.height) },
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    BookStatusHeaderBadges(vm = vm)
                }

                BookStatusProgressBar(vm = vm)

                HorizontalDivider()
            }

            StatusGridView(
                statuses = vm.partStatuses,
                tr = vm::tr,
                onSpeakTooltip = { vm.ttsSay(it) },
                offlineFallbackMap = vm.partOfflineFallbackMap,
                maxHeightDp = maxGridHeightDp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun TopBarSubtitleStatus(vm: MainViewModel) {
    val topBarStatus = when {
        vm.statInfo.isNotBlank() -> {
            if (vm.runWork && vm.totalPartsCount > 0) {
                val elapsedSuffix = if (vm.elapsedVisible && vm.elapsedTimeText.isNotEmpty()) {
                    val etaPart = if (vm.etaTimeText.isNotEmpty()) " (~${vm.etaTimeText})" else ""
                    " • ${vm.elapsedTimeText}$etaPart"
                } else {
                    ""
                }
                "${vm.statInfo}: ${vm.statStr} • ${vm.chunksStatStr}$elapsedSuffix"
            } else if (vm.statInfoDoneHighlight && vm.totalPartsCount > 0) {
                "${vm.statInfo} • ${vm.statStr} • ${vm.chunksStatStr}"
            } else {
                vm.statInfo
            }
        }
        vm.bookLoaded || vm.totalPartsCount > 0 -> {
            "${vm.bookLoadedCaption()} • ${vm.statStr} • ${vm.chunksStatStr}"
        }
        else -> ""
    }
    if (topBarStatus.isNotEmpty()) {
        Text(
            text = topBarStatus,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (vm.statInfo.isNotBlank()) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                vm.statInfoDoneHighlight -> MaterialTheme.colorScheme.primary
                vm.statInfo.isNotBlank() -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TopBarProgressStrip(vm: MainViewModel) {
    if (vm.progressBarVisible) {
        val progress = if (vm.progressBarMax > 0) {
            (vm.progressBarValue.toFloat() / vm.progressBarMax.toFloat()).coerceIn(0f, 1f)
        } else 0f
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    } else if (vm.offlineVoicesBusy || vm.onlineVoicesBusy || vm.isBookLoading) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    }
}

@Composable
private fun BookStatusHeaderBadges(vm: MainViewModel) {
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
                        text = if (vm.etaTimeText.isNotEmpty()) {
                            "${vm.elapsedTimeText} • ~${vm.etaTimeText}"
                        } else {
                            vm.elapsedTimeText
                        },
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
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.EditNote,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = vm.statStr,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = vm.chunksStatStr,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun BookStatusProgressBar(vm: MainViewModel) {
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
}

@Composable
private fun LoadFolderFilesCard(
    vm: MainViewModel,
    onRequestNativePicker: (NativePickerType) -> Unit,
    modifier: Modifier = Modifier
) {
    val loadDirPath = vm.settings.loadDir.ifBlank { vm.repo.defaultDownloadsDir }
    val files = vm.loadDirFiles

    OutlinedCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Card Header: Load Folder Title, Path, Refresh & Change Folder Actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = vm.tr("KEY_LOAD_DIR"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = loadDirPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (files.size > 1) {
                        TextButton(
                            onClick = {
                                vm.onOpenBooks(files.map { it.absolutePath })
                            }
                        ) {
                            Text(vm.tr("KEY_ALL"))
                        }
                    }
                    IconButton(onClick = { vm.refreshLoadDirFiles() }) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null
                        )
                    }
                    IconButton(
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
                }
            }

            HorizontalDivider()

            if (files.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = vm.tr("KEY_NO_BOOKS_IN_FOLDER"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilledTonalButton(
                                onClick = { onRequestNativePicker(NativePickerType.OPEN_BOOKS) }
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.FileOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(vm.tr("KEY_OPEN_BOOKS"))
                            }
                            OutlinedButton(
                                onClick = { onRequestNativePicker(NativePickerType.LOAD_DIR) }
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(vm.tr("KEY_LOAD_DIR"))
                            }
                        }
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    files.forEachIndexed { idx, file ->
                        if (idx > 0) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                        val extBadge = file.extension.uppercase(Locale.ROOT)
                        val sizeText = formatFileSize(file.length())
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = file.name,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            supportingContent = {
                                Text(text = "$extBadge • $sizeText")
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.ttsSay(file.name)
                                    vm.onOpenBooks(listOf(file.absolutePath))
                                }
                        )
                    }
                }
            }
        }
    }
}

private fun formatShortVoiceName(voice: String): String {
    val trimmed = voice.trim()
    if (trimmed.contains(",")) {
        val parts = trimmed.split(",", limit = 2).map { it.trim() }
        if (parts.size == 2) {
            val lang = parts[0]
            val name = parts[1].removeSuffix("MultilingualNeural").removeSuffix("Neural")
            return "$lang • $name"
        }
    }
    val dashMatch = Regex("^([a-zA-Z]{2,3}-[a-zA-Z]{2,4})[-_\\s]+(.+)$").find(trimmed)
    if (dashMatch != null) {
        val lang = dashMatch.groupValues[1]
        val name = dashMatch.groupValues[2].removeSuffix("MultilingualNeural").removeSuffix("Neural")
        return "$lang • $name"
    }
    return trimmed.removeSuffix("MultilingualNeural").removeSuffix("Neural")
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "${bytes / 1024L} KB"
        else -> "$bytes B"
    }
}

@Composable
private fun SettingsTabContent(
    vm: MainViewModel,
    onRequestNativePicker: (NativePickerType) -> Unit,
    onOpenStoragePermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(vm.settingsScrollTrigger) {
        if (vm.settingsScrollTrigger > 0) {
            scrollState.animateScrollTo(0)
        }
    }

    Column(
        modifier = modifier.verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        // Group 1 + 2: Voice Settings (grouped as in original app)
        SettingsGroupCard(
            title = vm.tr("KEY_SETTINGS_VOICE"),
            icon = Icons.Outlined.RecordVoiceOver
        ) {
            VoiceSettingsGroupContent(vm = vm)
        }

        // Group 6: Book Settings (grouped as in original app)
        SettingsGroupCard(
            title = vm.tr("KEY_SETTINGS_BOOK"),
            icon = Icons.AutoMirrored.Outlined.MenuBook
        ) {
            BookSettingsGroupContent(
                vm = vm,
                onRequestNativePicker = onRequestNativePicker,
                onOpenStoragePermissions = onOpenStoragePermissions
            )
        }

        // Group 7: Tool Settings (grouped as in original app)
        SettingsGroupCard(
            title = vm.tr("KEY_SETTINGS_TOOL"),
            icon = Icons.Outlined.Tune
        ) {
            ToolSettingsGroupContent(vm = vm)
        }

        // Group 8: Interface Settings (grouped as in original app)
        SettingsGroupCard(
            title = vm.tr("KEY_SETTINGS_INTERFACE"),
            icon = Icons.Outlined.Palette
        ) {
            InterfaceSettingsGroupContent(vm = vm)
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun SettingsGroupCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            HorizontalDivider()
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSettingsGroupContent(vm: MainViewModel) {
    val settings = vm.settings
    val offlineDisplay = settings.offlineVoicesName.ifBlank { vm.tr("KEY_OFFLINE_UNAVAILABLE") }

    Column(modifier = Modifier.fillMaxWidth()) {
        M3SwitchListItem(
            headline = vm.tr("KEY_USE_ONLINE_VOICES"),
            checked = settings.useOnlineVoices,
            onCheckedChange = { checked ->
                vm.updateSettings { it.copy(useOnlineVoices = checked) }
                vm.ttsSay("${vm.tr("KEY_USE_ONLINE_VOICES")} ${vm.tr(if (checked) "KEY_ON" else "KEY_OFF")}")
            }
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        M3SwitchListItem(
            headline = vm.tr("KEY_USE_ONLINE_VOICES_PLAYER"),
            checked = settings.useOnlineVoicesPlayer,
            onCheckedChange = { checked ->
                vm.updateSettings { it.copy(useOnlineVoicesPlayer = checked) }
                vm.invalidatePlayerCache()
                vm.ttsSay("${vm.tr("KEY_USE_ONLINE_VOICES_PLAYER")} ${vm.tr(if (checked) "KEY_ON" else "KEY_OFF")}")
            }
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        M3SwitchListItem(
            headline = vm.tr("KEY_USE_ONLINE_VOICES_SYSTEM_TTS"),
            checked = settings.useOnlineVoicesSystemTts,
            onCheckedChange = { checked ->
                vm.updateSettings { it.copy(useOnlineVoicesSystemTts = checked) }
                vm.ttsSay("${vm.tr("KEY_USE_ONLINE_VOICES_SYSTEM_TTS")} ${vm.tr(if (checked) "KEY_ON" else "KEY_OFF")}")
            }
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Online Voice Selector
            ExposedDropdownMenuBox(
                expanded = vm.voicesDropdownOpen,
                onExpandedChange = {
                    vm.languagesDropdownOpen = false
                    vm.uiTtsDropdownOpen = false
                    vm.offlineVoicesDropdownOpen = false
                    vm.voicesDropdownOpen = !vm.voicesDropdownOpen
                    vm.ttsSay(settings.voicesName)
                }
            ) {
                OutlinedTextField(
                    value = settings.voicesName,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text(vm.tr("KEY_ONLINE_VOICE")) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.RecordVoiceOver,
                            contentDescription = null
                        )
                    },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = vm.voicesDropdownOpen)
                    },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )
            }

            // Offline Voice Selector
            ExposedDropdownMenuBox(
                expanded = vm.offlineVoicesDropdownOpen,
                onExpandedChange = {
                    vm.languagesDropdownOpen = false
                    vm.uiTtsDropdownOpen = false
                    vm.voicesDropdownOpen = false
                    vm.offlineVoicesDropdownOpen = !vm.offlineVoicesDropdownOpen
                    vm.ttsSay(vm.voiceSayText(offlineDisplay))
                }
            ) {
                OutlinedTextField(
                    value = offlineDisplay,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text(vm.tr("KEY_OFFLINE_VOICE")) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.DownloadForOffline,
                            contentDescription = null
                        )
                    },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = vm.offlineVoicesDropdownOpen)
                    },
                    supportingText = if (settings.offlineVoicesName.isNotBlank()) {
                        { Text(vm.tr("KEY_VOICE_OFFLINE")) }
                    } else null,
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )
            }

            M3SliderSetting(
                label = vm.tr("KEY_SPEED"),
                valueText = "${vm.formatSignedValue(settings.rate)}%",
                value = settings.rate.toFloat(),
                valueRange = -50f..100f,
                steps = 29,
                onValueChange = { vm.setRate(it.roundToInt()) }
            )

            M3SliderSetting(
                label = vm.tr("KEY_PITCH"),
                valueText = "${vm.formatSignedValue(settings.pitch)}Hz",
                value = settings.pitch.toFloat(),
                valueRange = -50f..50f,
                steps = 99,
                onValueChange = { vm.setPitch(it.roundToInt()) }
            )
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

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
            headline = vm.tr("KEY_OPUS_OUTPUT"),
            checked = settings.opusOutput,
            onCheckedChange = { checked ->
                vm.updateSettings { it.copy(opusOutput = checked) }
                vm.ttsSay("${vm.tr("KEY_OPUS_OUTPUT")} ${vm.tr(if (checked) "KEY_ON" else "KEY_OFF")}")
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
                .padding(16.dp),
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

@Composable
private fun BookSettingsGroupContent(
    vm: MainViewModel,
    onRequestNativePicker: (NativePickerType) -> Unit,
    onOpenStoragePermissions: () -> Unit
) {
    val settings = vm.settings

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
            },
            trailingContent = {
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
                            vm.reprocessBookForCurrentSettings()
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
                vm.reprocessBookForCurrentSettings()
                vm.ttsSay("${vm.tr("KEY_LEXX_REGISTER")} ${vm.tr(if (checked) "KEY_ON" else "KEY_OFF")}")
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

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        // Inline footnotes (FB2 / EPUB)
        M3SwitchListItem(
            headline = vm.tr("KEY_INLINE_NOTES"),
            checked = settings.inlineNotes,
            onCheckedChange = { checked ->
                vm.updateSettings { it.copy(inlineNotes = checked) }
                vm.reprocessBookForCurrentSettings()
                vm.ttsSay("${vm.tr("KEY_INLINE_NOTES")} ${vm.tr(if (checked) "KEY_ON" else "KEY_OFF")}")
            }
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        // Embed cover and metadata into audio files
        M3SwitchListItem(
            headline = vm.tr("KEY_EMBED_METADATA"),
            checked = settings.embedMetadata,
            onCheckedChange = { checked ->
                vm.updateSettings { it.copy(embedMetadata = checked) }
                vm.ttsSay("${vm.tr("KEY_EMBED_METADATA")} ${vm.tr(if (checked) "KEY_ON" else "KEY_OFF")}")
            }
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        // Filter out emojis
        M3SwitchListItem(
            headline = vm.tr("KEY_FILTER_EMOJIS"),
            checked = settings.filterEmojis,
            onCheckedChange = { checked ->
                vm.updateSettings { it.copy(filterEmojis = checked) }
                vm.reprocessBookForCurrentSettings()
                vm.ttsSay("${vm.tr("KEY_FILTER_EMOJIS")} ${vm.tr(if (checked) "KEY_ON" else "KEY_OFF")}")
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
                        vm.reprocessBookForCurrentSettings()
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

@Composable
private fun ToolSettingsGroupContent(vm: MainViewModel) {
    val settings = vm.settings
    val rdIndex = AppSettings.restartDelayIndexFromSeconds(settings.restartDelay)
    val rdLabel = if (settings.restartDelay < 0) "∞" else "${settings.restartDelay}s"
    val mergeLabel = if (settings.mergeFiles == 100) {
        vm.tr("KEY_ALL")
    } else {
        "${settings.mergeFiles} ${vm.tr("KEY_UNIT")}"
    }

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InterfaceSettingsGroupContent(vm: MainViewModel) {
    val settings = vm.settings
    val currentLangText = vm.languagesList.getOrElse(settings.languagesSelect) { "AUTO" }
    val currentUiTtsText = if (settings.languagesUiTtsSelect == 0) {
        vm.tr("KEY_OFF")
    } else {
        vm.uiTtsList.getOrElse(settings.languagesUiTtsSelect) { vm.tr("KEY_OFF") }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
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
                val closeLabel = vm.tr(if (vm.loadedBooksCount <= 1) "KEY_CLOSE_BOOK" else "KEY_CLOSE_BOOKS")
                Button(
                    onClick = {
                        vm.onFileButtonClicked {
                            onRequestNativePicker(NativePickerType.OPEN_BOOKS)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = vm.tr("KEY_BOOK_CLEAR"),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = closeLabel,
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
                        imageVector = Icons.Outlined.FileOpen,
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

            val saveCaptionKey = when {
                vm.runWork -> "KEY_STOP"
                vm.settings.opusOutput -> "KEY_SAVE_OPUS"
                else -> "KEY_SAVE_MP3"
            }
            Button(
                onClick = { vm.onSaveOrStopButtonPressed() },
                enabled = vm.runWork || (vm.bookLoaded && vm.totalPartsCount > 0),
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
                    text = vm.tr(saveCaptionKey),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerTabContent(
    vm: MainViewModel,
    modifier: Modifier = Modifier
) {
    val sentences = vm.playerSentences
    val statuses = vm.playerSentenceStatuses
    val selectedIdx = vm.playerSelectedIndex
    val isPlaying = vm.playerIsPlaying
    val bookmarks = vm.playerBookmarks
    val hasBookKey = vm.playerBookKey != null
    val isSharedText = vm.playerIsSharedText
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = selectedIdx.coerceAtLeast(0)
    )
    val cardFontSize = (vm.settings.fontSize + 1).sp
    var showJumpDialog by remember { mutableStateOf(false) }
    var jumpInputText by remember { mutableStateOf("") }
    var showBookmarksDialog by remember { mutableStateOf(false) }
    var showPartialCacheWarnDialog by remember { mutableStateOf(false) }
    var showNoCacheWarnDialog by remember { mutableStateOf(false) }
    var cachedCountForWarn by remember { mutableIntStateOf(0) }

    val createAudioDocumentLauncher = rememberLauncherForActivityResult(
        contract = remember {
            object : ActivityResultContract<Pair<String, String>, Uri?>() {
                override fun createIntent(context: Context, input: Pair<String, String>): Intent {
                    return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = input.first
                        putExtra(Intent.EXTRA_TITLE, input.second)
                    }
                }

                override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
                    return if (resultCode == Activity.RESULT_OK) intent?.data else null
                }
            }
        }
    ) { targetUri: Uri? ->
        if (targetUri != null) {
            vm.saveCachedPlayerChunksToUri(targetUri)
        }
    }

    val launchSharedSavePicker = {
        val mime = if (vm.settings.opusOutput) "audio/ogg" else "audio/mpeg"
        val suggestedName = vm.defaultSharedPlayerSaveFileName()
        createAudioDocumentLauncher.launch(mime to suggestedName)
    }

    if (showNoCacheWarnDialog) {
        AlertDialog(
            onDismissRequest = { showNoCacheWarnDialog = false },
            title = {
                Text(text = vm.tr(if (vm.settings.opusOutput) "KEY_SAVE_OPUS" else "KEY_SAVE_MP3"))
            },
            text = {
                Text(text = vm.tr("KEY_NO_CACHED_CHUNKS"))
            },
            confirmButton = {
                TextButton(onClick = { showNoCacheWarnDialog = false }) {
                    Text(text = vm.tr("KEY_OK"))
                }
            }
        )
    }

    if (showPartialCacheWarnDialog) {
        AlertDialog(
            onDismissRequest = { showPartialCacheWarnDialog = false },
            title = {
                Text(text = "$cachedCountForWarn / ${sentences.size}")
            },
            text = {
                Text(text = vm.tr("KEY_WARN_NOT_ALL_CACHED"))
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPartialCacheWarnDialog = false
                        launchSharedSavePicker()
                    }
                ) {
                    Text(text = vm.tr("KEY_OK"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPartialCacheWarnDialog = false }) {
                    Text(text = vm.tr("KEY_CANCEL"))
                }
            }
        )
    }

    if (showBookmarksDialog) {
        AlertDialog(
            onDismissRequest = { showBookmarksDialog = false },
            title = {
                Text(text = vm.tr("KEY_BOOKMARKS"))
            },
            text = {
                if (bookmarks.isEmpty()) {
                    Text(
                        text = vm.tr("KEY_NO_BOOKMARKS"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(
                            items = bookmarks.toList(),
                            key = { _, bmIdx -> bmIdx }
                        ) { _, bmIdx ->
                            val bmText = sentences.getOrNull(bmIdx)?.text.orEmpty()
                            OutlinedCard(
                                onClick = {
                                    showBookmarksDialog = false
                                    vm.onPlayerCardClicked(bmIdx)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = "${bmIdx + 1}",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = bmText,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    IconButton(
                                        onClick = { vm.onRemovePlayerBookmark(bmIdx) }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBookmarksDialog = false }) {
                    Text(text = vm.tr("KEY_CANCEL"))
                }
            }
        )
    }

    if (showJumpDialog && sentences.isNotEmpty()) {
        val commitJump = {
            val entered = jumpInputText.trim().toIntOrNull()
            if (entered != null) {
                val targetIdx = (entered - 1).coerceIn(0, sentences.lastIndex)
                showJumpDialog = false
                vm.onPlayerCardClicked(targetIdx)
            }
        }
        AlertDialog(
            onDismissRequest = { showJumpDialog = false },
            title = {
                Text(text = "${selectedIdx + 1} / ${sentences.size}")
            },
            text = {
                OutlinedTextField(
                    value = jumpInputText,
                    onValueChange = { value ->
                        jumpInputText = value.filter { it.isDigit() }
                    },
                    singleLine = true,
                    label = { Text("1 .. ${sentences.size}") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { commitJump() }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = { commitJump() },
                    enabled = jumpInputText.trim().toIntOrNull() != null
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = vm.tr("KEY_RESUME"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showJumpDialog = false }) {
                    Text(text = vm.tr("KEY_CANCEL"))
                }
            }
        )
    }

    LaunchedEffect(selectedIdx, sentences.size, vm.playerBookKey) {
        if (selectedIdx in sentences.indices) {
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) {
                listState.scrollToItem(selectedIdx)
            } else if (visibleItems.none { it.index == selectedIdx }) {
                val firstVisible = listState.firstVisibleItemIndex
                if (kotlin.math.abs(selectedIdx - firstVisible) > 15) {
                    listState.scrollToItem(selectedIdx)
                } else {
                    listState.animateScrollToItem(selectedIdx)
                }
            }
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (vm.isBookLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (sentences.isEmpty()) {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = vm.tr("KEY_NO_BOOK_LOADED"),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            val coroutineScope = rememberCoroutineScope()
            val density = LocalDensity.current
            var trackHeightPx by remember { mutableIntStateOf(0) }
            var isDraggingScrollbar by remember { mutableStateOf(false) }
            var dragFraction by remember { mutableFloatStateOf(0f) }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = if (sentences.size > 1) 20.dp else 0.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    itemsIndexed(
                        items = sentences,
                        key = { idx, _ -> idx }
                    ) { idx, sentenceItem ->
                        val isSelected = idx == selectedIdx
                        val status = statuses[idx] ?: PartStatus.OPENED
                        val cardContainerColor = when {
                            isSelected -> MaterialTheme.colorScheme.primaryContainer
                            status == PartStatus.SAVED -> MaterialTheme.colorScheme.surfaceContainerHigh
                            else -> MaterialTheme.colorScheme.surfaceContainerLow
                        }
                        val cardContentColor = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                        val borderStroke = if (isSelected) {
                            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                        } else {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }

                        val topParagraphPadding = if (idx > 0 && sentenceItem.startsNewParagraph) 8.dp else 0.dp

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = topParagraphPadding),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (sentenceItem.startsNewChapter && !sentenceItem.chapterTitle.isNullOrBlank()) {
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.MenuBook,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        Text(
                                            text = sentenceItem.chapterTitle,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                            }

                            OutlinedCard(
                                onClick = { vm.onPlayerCardClicked(idx) },
                                shape = MaterialTheme.shapes.medium,
                                colors = CardDefaults.outlinedCardColors(
                                    containerColor = cardContainerColor,
                                    contentColor = cardContentColor
                                ),
                                border = borderStroke,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "${idx + 1}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                        if (idx in bookmarks) {
                                            Icon(
                                                imageVector = Icons.Filled.Bookmark,
                                                contentDescription = vm.tr("KEY_BOOKMARKS"),
                                                modifier = Modifier.size(15.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                        if (status != PartStatus.OPENED) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                if (status == PartStatus.SAVED) {
                                                    val usedOnline = vm.playerSentenceOnlineMap[idx]
                                                    if (usedOnline != null) {
                                                        Icon(
                                                            imageVector = if (usedOnline) {
                                                                Icons.Outlined.CloudDone
                                                            } else {
                                                                Icons.Outlined.OfflinePin
                                                            },
                                                            contentDescription = null,
                                                            modifier = Modifier.size(14.dp),
                                                            tint = if (isSelected) {
                                                                MaterialTheme.colorScheme.onPrimaryContainer
                                                            } else if (usedOnline) {
                                                                MaterialTheme.colorScheme.primary
                                                            } else {
                                                                MaterialTheme.colorScheme.onSurfaceVariant
                                                            }
                                                        )
                                                    }
                                                }
                                                Surface(
                                                    shape = MaterialTheme.shapes.extraSmall,
                                                    color = status.color.copy(alpha = 0.2f)
                                                ) {
                                                    Text(
                                                        text = vm.tr(status.trKey),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = if (isSelected) {
                                                            MaterialTheme.colorScheme.onPrimaryContainer
                                                        } else {
                                                            MaterialTheme.colorScheme.onSurfaceVariant
                                                        },
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Text(
                                        text = sentenceItem.text,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontSize = cardFontSize,
                                            lineHeight = (vm.settings.fontSize + 8).sp
                                        ),
                                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }

                if (sentences.size > 1) {
                    val thumbHeightDp = 40.dp
                    val thumbHeightPx = with(density) { thumbHeightDp.roundToPx() }
                    val maxIndex = (sentences.size - 1).coerceAtLeast(1)
                    val layoutInfo = listState.layoutInfo
                    val visibleItems = layoutInfo.visibleItemsInfo
                    val listFraction = when {
                        !listState.canScrollBackward -> 0f
                        !listState.canScrollForward -> 1f
                        visibleItems.isEmpty() -> 0f
                        else -> {
                            val firstItem = visibleItems.first()
                            val itemFraction = (
                                listState.firstVisibleItemScrollOffset.toFloat() /
                                    firstItem.size.coerceAtLeast(1).toFloat()
                                ).coerceIn(0f, 1f)
                            val exactFirst = firstItem.index.toFloat() + itemFraction
                            val maxFirstIndex = (sentences.size - visibleItems.size).coerceAtLeast(1).toFloat()
                            (exactFirst / maxFirstIndex).coerceIn(0f, 1f)
                        }
                    }
                    val activeFraction = if (isDraggingScrollbar) dragFraction else listFraction
                    val availableScrollPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(0)
                    val thumbOffsetY = (activeFraction * availableScrollPx).roundToInt()

                    fun scrubToY(yPx: Float) {
                        if (trackHeightPx <= 0) return
                        val f = ((yPx - thumbHeightPx / 2f) / availableScrollPx.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                        dragFraction = f
                        val targetIdx = (f * maxIndex).roundToInt().coerceIn(0, maxIndex)
                        coroutineScope.launch {
                            listState.scrollToItem(targetIdx)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(20.dp)
                            .padding(vertical = 4.dp)
                            .onSizeChanged { trackHeightPx = it.height }
                            .pointerInput(sentences.size) {
                                detectTapGestures(
                                    onPress = { offset ->
                                        isDraggingScrollbar = true
                                        scrubToY(offset.y)
                                        val released = tryAwaitRelease()
                                        if (released) {
                                            isDraggingScrollbar = false
                                        }
                                    }
                                )
                            }
                            .pointerInput(sentences.size) {
                                detectVerticalDragGestures(
                                    onDragStart = { offset ->
                                        isDraggingScrollbar = true
                                        scrubToY(offset.y)
                                    },
                                    onDragEnd = {
                                        isDraggingScrollbar = false
                                    },
                                    onDragCancel = {
                                        isDraggingScrollbar = false
                                    },
                                    onVerticalDrag = { change, _ ->
                                        change.consume()
                                        scrubToY(change.position.y)
                                    }
                                )
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxHeight()
                                .width(4.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    shape = CircleShape
                                )
                        )
                        Box(
                            modifier = Modifier
                                .offset { IntOffset(0, thumbOffsetY) }
                                .align(Alignment.TopCenter)
                                .width(if (isDraggingScrollbar) 10.dp else 8.dp)
                                .height(thumbHeightDp)
                                .background(
                                    color = if (isDraggingScrollbar) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                                    },
                                    shape = CircleShape
                                )
                        )
                    }

                    if (isDraggingScrollbar) {
                        val previewIdx = (dragFraction * maxIndex).roundToInt().coerceIn(0, maxIndex)
                        val previewChapter = sentences.getOrNull(previewIdx)?.chapterTitle?.takeIf { it.isNotBlank() }
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.inverseSurface,
                            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                            tonalElevation = 6.dp,
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(start = 16.dp, end = 28.dp)
                                .offset { IntOffset(0, thumbOffsetY) }
                        ) {
                            Column(
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                if (previewChapter != null) {
                                    Text(
                                        text = previewChapter,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = "${previewIdx + 1} / ${sentences.size}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom player controls: Previous, Play/Pause, Next
        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Surface(
                        onClick = {
                            jumpInputText = (selectedIdx + 1).toString()
                            showJumpDialog = true
                        },
                        enabled = sentences.isNotEmpty(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.defaultMinSize(minWidth = 56.dp)
                    ) {
                        Text(
                            text = if (sentences.isEmpty()) "0 / 0" else "${selectedIdx + 1} / ${sentences.size}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalIconButton(
                        onClick = { vm.onPlayerPrevPressed() },
                        enabled = sentences.isNotEmpty() && selectedIdx > 0,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = vm.tr("KEY_EDITOR_PREV")
                        )
                    }

                    Button(
                        onClick = { vm.onPlayerPlayPausePressed() },
                        enabled = sentences.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = vm.tr(if (isPlaying) "KEY_PAUSE" else "KEY_RESUME"),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    FilledTonalIconButton(
                        onClick = { vm.onPlayerNextPressed() },
                        enabled = sentences.isNotEmpty() && selectedIdx < sentences.lastIndex,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = vm.tr("KEY_EDITOR_NEXT")
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    if (isSharedText) {
                        val saveCaptionKey = if (vm.settings.opusOutput) "KEY_SAVE_OPUS" else "KEY_SAVE_MP3"
                        FilledTonalIconButton(
                            onClick = {
                                val cachedCount = vm.getCachedPlayerChunkCount()
                                when {
                                    cachedCount <= 0 -> {
                                        showNoCacheWarnDialog = true
                                    }
                                    cachedCount < sentences.size -> {
                                        cachedCountForWarn = cachedCount
                                        showPartialCacheWarnDialog = true
                                    }
                                    else -> {
                                        launchSharedSavePicker()
                                    }
                                }
                            },
                            enabled = sentences.isNotEmpty(),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DownloadForOffline,
                                contentDescription = vm.tr(saveCaptionKey)
                            )
                        }
                    } else if (hasBookKey) {
                        val isCurrentBookmarked = selectedIdx in bookmarks
                        Surface(
                            shape = CircleShape,
                            color = if (isCurrentBookmarked) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .combinedClickable(
                                    enabled = sentences.isNotEmpty(),
                                    onClick = { vm.onTogglePlayerBookmark(selectedIdx) },
                                    onLongClick = { showBookmarksDialog = true }
                                )
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isCurrentBookmarked) {
                                        Icons.Filled.Bookmark
                                    } else {
                                        Icons.Outlined.BookmarkBorder
                                    },
                                    contentDescription = vm.tr("KEY_BOOKMARKS"),
                                    tint = if (isCurrentBookmarked) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }
            }
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

    Column(
        modifier = modifier.padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header bar: Preview Play/Stop & Part Counter
        Row(
            modifier = Modifier.fillMaxWidth(),
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

        // Chapter navigation & Chapter title editor
        if (vm.editorHasChapters) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RepeatingFilledTonalIconButton(
                    onAction = { vm.onEditorPrevChapterPressed() },
                    enabled = vm.editorHasPrevChapter
                ) {
                    Icon(
                        imageVector = Icons.Default.ChevronLeft,
                        contentDescription = null
                    )
                }

                OutlinedTextField(
                    value = vm.editorChapterTitleText,
                    onValueChange = { if (!vm.runWork) vm.editorChapterTitleText = it },
                    readOnly = vm.runWork,
                    singleLine = true,
                    placeholder = { Text(vm.tr("KEY_CHAPTER_TITLE")) },
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            vm.onEditorChapterTitleSubmitted()
                        }
                    ),
                    modifier = Modifier.weight(1f)
                )

                RepeatingFilledTonalIconButton(
                    onAction = { vm.onEditorNextChapterPressed() },
                    enabled = vm.editorHasNextChapter
                ) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null
                    )
                }
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
                RepeatingFilledTonalIconButton(
                    onAction = { vm.onEditorPrevPressed() },
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

                RepeatingFilledTonalIconButton(
                    onAction = { vm.onEditorNextPressed() },
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

@Composable
private fun RepeatingFilledTonalIconButton(
    onAction: () -> Unit,
    enabled: Boolean = true,
    initialDelayMillis: Long = 350L,
    repeatIntervalMillis: Long = 80L,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val currentOnAction by rememberUpdatedState(onAction)
    val isPressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(isPressed, enabled) {
        if (isPressed && enabled) {
            currentOnAction()
            delay(initialDelayMillis)
            while (isPressed && enabled) {
                currentOnAction()
                delay(repeatIntervalMillis)
            }
        }
    }

    FilledTonalIconButton(
        onClick = {},
        enabled = enabled,
        interactionSource = interactionSource,
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicesPickerBottomSheet(
    title: String,
    voices: List<String>,
    selectedIndex: Int,
    isOfflinePicker: Boolean,
    offlineStatusText: String,
    emptyText: String,
    onSelectVoice: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }

    val filteredVoices = remember(voices.size, searchQuery) {
        if (searchQuery.isBlank()) {
            voices.mapIndexed { idx, name -> idx to name }
        } else {
            val q = searchQuery.trim().lowercase()
            voices.mapIndexedNotNull { idx, name ->
                if (name.lowercase().contains(q)) idx to name else null
            }
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        if (selectedIndex in voices.indices && searchQuery.isBlank()) {
            listState.scrollToItem((selectedIndex - 2).coerceAtLeast(0))
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
                text = title,
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

            if (filteredVoices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = emptyText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    itemsIndexed(
                        items = filteredVoices,
                        key = { _, pair -> pair.first }
                    ) { _, (originalIndex, voiceName) ->
                        val isSelected = originalIndex == selectedIndex

                        ListItem(
                            headlineContent = {
                                Text(
                                    text = voiceName,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            },
                            supportingContent = if (isOfflinePicker) {
                                { Text(offlineStatusText) }
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
                                    onSelectVoice(originalIndex)
                                }
                        )
                    }
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
