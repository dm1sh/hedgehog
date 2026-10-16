package dm1sh.hedgehog.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily

object AssetCache {
    private val bitmapCache = mutableMapOf<String, ImageBitmap>()
    private var calibriFontFamily: FontFamily? = null

    fun getIcon(context: Context, name: String): ImageBitmap? {
        bitmapCache[name]?.let { return it }
        return try {
            context.assets.open("img/$name").use { input ->
                val bmp = BitmapFactory.decodeStream(input)?.asImageBitmap()
                if (bmp != null) {
                    bitmapCache[name] = bmp
                }
                bmp
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getCalibriFont(context: Context): FontFamily {
        calibriFontFamily?.let { return it }
        return try {
            val tf = Typeface.createFromAsset(context.assets, "calibri.ttf")
            FontFamily(tf).also { calibriFontFamily = it }
        } catch (_: Exception) {
            FontFamily.Default
        }
    }
}

@Composable
fun rememberAssetIcon(name: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(name) { AssetCache.getIcon(context, name) }
}

@Composable
fun rememberCalibriFont(): FontFamily {
    val context = LocalContext.current
    return remember { AssetCache.getCalibriFont(context) }
}
