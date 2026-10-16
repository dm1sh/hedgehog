package dm1sh.hedgehog.ui

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dm1sh.hedgehog.model.AppSettings
import java.io.File

@Composable
fun FileBrowserDialog(
    purpose: FileDialogPurpose,
    initialDir: String,
    tr: (String) -> String,
    fontFamily: FontFamily,
    onConfirmFiles: (List<String>) -> Unit,
    onConfirmDir: (String) -> Unit,
    onUseSystemPicker: () -> Unit,
    onCancel: () -> Unit,
    onSpeakItem: (String) -> Unit
) {
    val defaultRoot = Environment.getExternalStorageDirectory().absolutePath
    val startDir = remember(purpose, initialDir) {
        val f = File(initialDir.ifBlank { defaultRoot })
        if (f.exists() && f.isDirectory) f else File(defaultRoot)
    }

    var currentDir by remember(purpose) { mutableStateOf(startDir) }
    val selectedFiles = remember(purpose, currentDir) { mutableStateListOf<String>() }

    val isDirMode = purpose == FileDialogPurpose.SAVE_DIR || purpose == FileDialogPurpose.LOAD_DIR
    val allowedExtensions: Set<String> = when (purpose) {
        FileDialogPurpose.OPEN_BOOKS -> AppSettings.BOOK_EXTENSIONS
        FileDialogPurpose.LEXX_FILE -> setOf("lexx", "txt")
        FileDialogPurpose.GLAV_MP3 -> setOf("mp3")
        else -> emptySet()
    }

    val entries = remember(currentDir, purpose) {
        val list = currentDir.listFiles()?.toList().orEmpty()
        list.filter { file ->
            if (file.name.startsWith(".")) return@filter false
            if (file.isDirectory) true
            else if (isDirMode) false
            else allowedExtensions.isEmpty() || allowedExtensions.contains(file.extension.lowercase())
        }.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    val title = when (purpose) {
        FileDialogPurpose.OPEN_BOOKS -> tr("KEY_OPEN_BOOKS")
        FileDialogPurpose.SAVE_DIR -> tr("KEY_SAVE_DIR")
        FileDialogPurpose.LOAD_DIR -> tr("KEY_LOAD_DIR")
        FileDialogPurpose.LEXX_FILE -> tr("KEY_USE_LEXX")
        FileDialogPurpose.GLAV_MP3 -> tr("KEY_ADDGLAVMP3")
        FileDialogPurpose.NONE -> ""
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f)
                .background(Color(0xFF262626), RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFF4D4D4D), RoundedCornerShape(10.dp))
                .padding(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = Color(0xFFEEEEEE),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = fontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (!isDirMode) {
                    GodotButton(
                        text = "Android Picker",
                        onClick = onUseSystemPicker,
                        fontFamily = fontFamily,
                        fontSize = 14.sp,
                        modifier = Modifier.height(38.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Path bar + Up button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GodotButton(
                    text = "↑ ..",
                    onClick = {
                        currentDir.parentFile?.let { parent ->
                            if (parent.canRead()) {
                                currentDir = parent
                                onSpeakItem(parent.name.ifEmpty { "/" })
                            }
                        }
                    },
                    fontFamily = fontFamily,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .width(64.dp)
                        .height(42.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .background(Color(0xFF1C1C1C), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = currentDir.absolutePath,
                        color = Color(0xFFCCCCCC),
                        fontSize = 14.sp,
                        fontFamily = fontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Directory & file list
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
                    .padding(6.dp)
            ) {
                items(entries, key = { it.absolutePath }) { item ->
                    val isSelected = selectedFiles.contains(item.absolutePath)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .background(
                                if (isSelected) Color(0xFF555555) else Color.Transparent,
                                RoundedCornerShape(6.dp)
                            )
                            .clickable {
                                onSpeakItem(item.name)
                                if (item.isDirectory) {
                                    if (item.canRead()) {
                                        currentDir = item
                                    }
                                } else {
                                    if (purpose == FileDialogPurpose.OPEN_BOOKS) {
                                        if (isSelected) {
                                            selectedFiles.remove(item.absolutePath)
                                        } else {
                                            selectedFiles.add(item.absolutePath)
                                        }
                                    } else {
                                        selectedFiles.clear()
                                        selectedFiles.add(item.absolutePath)
                                    }
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (item.isDirectory) "📁 ${item.name}" else "📄 ${item.name}",
                            color = if (item.isDirectory) Color(0xFFFFCC66) else Color(0xFFEEEEEE),
                            fontSize = 18.sp,
                            fontFamily = fontFamily,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Bottom OK / Cancel buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GodotButton(
                    text = if (isDirMode) tr("Select Current Folder") else tr("KEY_OK"),
                    onClick = {
                        if (isDirMode) {
                            onConfirmDir(currentDir.absolutePath)
                        } else if (selectedFiles.isNotEmpty()) {
                            onConfirmFiles(selectedFiles.toList())
                        }
                    },
                    enabled = isDirMode || selectedFiles.isNotEmpty(),
                    fontFamily = fontFamily,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                )
                GodotButton(
                    text = tr("KEY_CANCEL"),
                    onClick = onCancel,
                    fontFamily = fontFamily,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                )
            }
        }
    }
}
