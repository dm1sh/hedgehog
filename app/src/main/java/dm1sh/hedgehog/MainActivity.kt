package dm1sh.hedgehog

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import dm1sh.hedgehog.service.HedgehogTtsService
import dm1sh.hedgehog.service.PlayerNotificationService
import dm1sh.hedgehog.ui.DopModal
import dm1sh.hedgehog.ui.EdgeTtsScreen
import dm1sh.hedgehog.ui.MainTab
import dm1sh.hedgehog.ui.MainViewModel
import dm1sh.hedgehog.ui.NativePickerType
import dm1sh.hedgehog.ui.theme.HedgehogTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var pendingPickerType: NativePickerType? = null
    private var lastBackPressTime = 0L

    private val openMultipleDocumentsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.ttsSay(viewModel.tr("KEY_FD_CONFIRMED"))
            viewModel.onOpenBookUris(uris)
        } else {
            viewModel.ttsSay(viewModel.tr("KEY_FD_CANCELLED"))
        }
    }

    private val openSingleDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val path = viewModel.repo.resolveAndroidUriToPath(uri)
            viewModel.ttsSay(viewModel.tr("KEY_FD_CONFIRMED"))
            when (pendingPickerType) {
                NativePickerType.LEXX_FILE -> {
                    viewModel.updateSettings { it.copy(lexxPath = path, lexxUse = path.isNotBlank()) }
                    viewModel.loadLexx()
                }
                NativePickerType.GLAV_MP3 -> {
                    viewModel.tempGlavMp3Path = path
                    if (path.isNotBlank()) {
                        viewModel.tempGlavMp3Use = true
                    }
                }
                else -> {}
            }
        } else {
            viewModel.ttsSay(viewModel.tr("KEY_FD_CANCELLED"))
        }
    }

    private val openDocumentTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val dirPath = viewModel.repo.resolveAndroidTreeUriToPath(uri)
            viewModel.ttsSay(viewModel.tr("KEY_FD_CONFIRMED"))
            when (pendingPickerType) {
                NativePickerType.SAVE_DIR -> {
                    viewModel.updateSettings { it.copy(saveDir = dirPath) }
                }
                NativePickerType.LOAD_DIR -> {
                    viewModel.updateSettings { it.copy(loadDir = dirPath, loadDirUse = dirPath.isNotBlank()) }
                }
                else -> {}
            }
        } else {
            viewModel.ttsSay(viewModel.tr("KEY_FD_CANCELLED"))
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshLoadDirFiles()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshLoadDirFiles()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        requestNeededPermissions()
        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    viewModel.dopModal != DopModal.NONE -> {
                        viewModel.dopModal = DopModal.NONE
                    }
                    viewModel.voicesDropdownOpen || viewModel.offlineVoicesDropdownOpen || viewModel.languagesDropdownOpen || viewModel.uiTtsDropdownOpen -> {
                        viewModel.closeAllDropdowns()
                    }
                    viewModel.activeMainTab != MainTab.BOOK -> {
                        viewModel.selectMainTab(MainTab.BOOK)
                    }
                    else -> {
                        val now = System.currentTimeMillis()
                        if (now - lastBackPressTime < 1000L) {
                            if (!viewModel.playerIsPlaying) {
                                viewModel.stopPlayerPlayback(clearCache = false)
                            }
                            finish()
                        } else {
                            lastBackPressTime = now
                        }
                    }
                }
            }
        })

        setContent {
            val baseDensity = LocalDensity.current
            val zoom = viewModel.settings.zoomUi.coerceIn(0.5f, 2.0f)
            val scaledDensity = Density(
                density = baseDensity.density * zoom,
                fontScale = baseDensity.fontScale
            )
            HedgehogTheme(dynamicColor = true) {
                CompositionLocalProvider(LocalDensity provides scaledDensity) {
                    EdgeTtsScreen(
                        vm = viewModel,
                        onRequestNativePicker = { pickerType ->
                            pendingPickerType = pickerType
                            when (pickerType) {
                                NativePickerType.OPEN_BOOKS -> {
                                    openMultipleDocumentsLauncher.launch(arrayOf("*/*"))
                                }
                                NativePickerType.LEXX_FILE -> {
                                    openSingleDocumentLauncher.launch(arrayOf("*/*"))
                                }
                                NativePickerType.GLAV_MP3 -> {
                                    openSingleDocumentLauncher.launch(arrayOf("audio/*"))
                                }
                                NativePickerType.SAVE_DIR, NativePickerType.LOAD_DIR -> {
                                    openDocumentTreeLauncher.launch(null)
                                }
                            }
                        },
                        onExitApp = {
                            finish()
                        }
                    )
                }
            }
        }
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (perms.isNotEmpty()) {
            permissionLauncher.launch(perms.toTypedArray())
        }
        if (!viewModel.repo.hasAllFilesAccess()) {
            viewModel.repo.requestAllFilesAccess(this)
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(PlayerNotificationService.EXTRA_OPEN_PLAYER_TAB, false) == true) {
            intent.removeExtra(PlayerNotificationService.EXTRA_OPEN_PLAYER_TAB)
            viewModel.selectMainTab(MainTab.PLAYER)
            return
        }
        if (intent?.getBooleanExtra(HedgehogTtsService.EXTRA_OPEN_SETTINGS_TAB, false) == true) {
            intent.removeExtra(HedgehogTtsService.EXTRA_OPEN_SETTINGS_TAB)
            viewModel.selectMainTab(MainTab.SETTINGS)
            return
        }
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_SEND -> {
                val streamUri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?: intent.clipData?.let { clip ->
                        if (clip.itemCount > 0) clip.getItemAt(0)?.uri else null
                    }
                if (streamUri != null) {
                    viewModel.onSharedUris(listOf(streamUri))
                    return
                }
                val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                    ?: intent.clipData?.let { clip ->
                        if (clip.itemCount > 0) clip.getItemAt(0)?.text?.toString() else null
                    }
                if (!sharedText.isNullOrBlank()) {
                    val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                        ?: intent.getStringExtra(Intent.EXTRA_TITLE)
                    viewModel.onSharedPlainText(sharedText, subject)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = mutableListOf<Uri>()
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { uris.addAll(it) }
                if (uris.isEmpty()) {
                    intent.clipData?.let { clip ->
                        for (i in 0 until clip.itemCount) {
                            clip.getItemAt(i)?.uri?.let { uris.add(it) }
                        }
                    }
                }
                if (uris.isNotEmpty()) {
                    viewModel.onSharedUris(uris)
                }
            }
        }
    }
}
