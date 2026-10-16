package dm1sh.hedgehog

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import dm1sh.hedgehog.ui.DopModal
import dm1sh.hedgehog.ui.EdgeTtsScreen
import dm1sh.hedgehog.ui.FileDialogPurpose
import dm1sh.hedgehog.ui.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var pendingPickerPurpose: FileDialogPurpose = FileDialogPurpose.NONE
    private var lastBackPressTime = 0L

    private val openMultipleDocumentsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val paths = uris.map { viewModel.repo.resolveAndroidUriToPath(it) }
            viewModel.onOpenBooks(paths)
        }
    }

    private val openSingleDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val path = viewModel.repo.resolveAndroidUriToPath(uri)
            when (pendingPickerPurpose) {
                FileDialogPurpose.LEXX_FILE -> {
                    viewModel.updateSettings { it.copy(lexxPath = path) }
                    viewModel.loadLexx()
                }
                FileDialogPurpose.GLAV_MP3 -> {
                    viewModel.tempGlavMp3Path = path
                }
                else -> {}
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNeededPermissions()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    viewModel.fileDialogPurpose != FileDialogPurpose.NONE -> {
                        viewModel.fileDialogPurpose = FileDialogPurpose.NONE
                    }
                    viewModel.dopModal != DopModal.NONE -> {
                        viewModel.dopModal = DopModal.NONE
                    }
                    viewModel.voicesDropdownOpen || viewModel.languagesDropdownOpen || viewModel.uiTtsDropdownOpen -> {
                        viewModel.closeAllDropdowns()
                    }
                    viewModel.settings.showTextAreaPressed -> {
                        viewModel.onEditorClosePressed()
                    }
                    viewModel.settings.dopSettingsPressed -> {
                        viewModel.toggleDopSettings()
                    }
                    else -> {
                        val now = System.currentTimeMillis()
                        if (now - lastBackPressTime < 1000L) {
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
            CompositionLocalProvider(LocalDensity provides scaledDensity) {
                EdgeTtsScreen(
                    vm = viewModel,
                    onRequestSystemFilePicker = { purpose ->
                        pendingPickerPurpose = purpose
                        when (purpose) {
                            FileDialogPurpose.OPEN_BOOKS -> {
                                openMultipleDocumentsLauncher.launch(arrayOf("*/*"))
                            }
                            FileDialogPurpose.LEXX_FILE -> {
                                openSingleDocumentLauncher.launch(arrayOf("*/*"))
                            }
                            FileDialogPurpose.GLAV_MP3 -> {
                                openSingleDocumentLauncher.launch(arrayOf("audio/*"))
                            }
                            else -> {}
                        }
                    },
                    onExitApp = {
                        finish()
                    }
                )
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
}
