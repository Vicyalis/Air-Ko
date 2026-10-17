package me.rerere.rikkahub.ui.components.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.SidebarBackgroundType
import java.io.File

/**
 * 侧边栏（会话抽屉）背景。
 *
 * 一律先铺一层主题 surface 再叠加颜色/渐变/图片：抽屉里全是文字，直接铺满图片会完全没法读，
 * 所以图片按不透明度淡入，文字和卡片始终压在背景之上。
 */
@Composable
fun SidebarBackground(
    displaySetting: DisplaySetting,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surface),
    ) {
        when (displaySetting.sidebarBackgroundType) {
            SidebarBackgroundType.THEME -> Unit
            SidebarBackgroundType.SOLID -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(resolveSidebarColor(displaySetting.sidebarBackgroundColor)),
            )

            SidebarBackgroundType.GRADIENT -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.secondaryContainer,
                                MaterialTheme.colorScheme.surfaceVariant,
                            )
                        )
                    ),
            )

            SidebarBackgroundType.IMAGE -> SidebarBackgroundImage(displaySetting)
        }
        content()
    }
}

@Composable
private fun SidebarBackgroundImage(displaySetting: DisplaySetting) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        displaySetting.sidebarBackgroundImagePath,
    ) {
        value = withContext(Dispatchers.IO) {
            val path = displaySetting.sidebarBackgroundImagePath
            if (path.isBlank()) return@withContext null
            runCatching {
                val file = File(context.filesDir, path)
                if (file.isFile) decodeSidebarBitmap(file) else null
            }.getOrNull()
        }
    }

    val image = bitmap ?: return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = displaySetting.sidebarBackgroundImageOpacity.coerceIn(0.05f, 1f)
            },
    ) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 没设置过颜色就用主色容器色，跟着主题走 */
fun resolveSidebarColor(argb: Int): Color =
    if (argb == 0) Color.Unspecified else Color(argb)

/** 纯色背景的候选色；都挑的浅色容器色，深色文字仍可读 */
val SidebarPresetColors: List<Int> = listOf(
    0xFFEADDFF.toInt(),
    0xFFD0BCFF.toInt(),
    0xFFFFD8E4.toInt(),
    0xFFFFE0B2.toInt(),
    0xFFFFF3B0.toInt(),
    0xFFB8F0C8.toInt(),
    0xFFB3E5FC.toInt(),
    0xFFCFD8DC.toInt(),
)

@Composable
fun SidebarColorSwatches(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SidebarPresetColors.forEach { argb ->
            val isSelected = argb == selected
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape,
                    )
                    .clickable { onSelect(argb) },
            )
        }
    }
}

/** 背景图按最长边 1024 采样，避免手机拍的图直接解成几千万像素 */
private fun decodeSidebarBitmap(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= 1024 && bounds.outHeight / (sampleSize * 2) >= 1024) {
        sampleSize *= 2
    }
    return BitmapFactory.decodeFile(
        file.path,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    )
}
