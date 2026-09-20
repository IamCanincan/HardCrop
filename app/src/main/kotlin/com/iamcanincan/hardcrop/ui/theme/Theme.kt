package com.iamcanincan.hardcrop.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Material 3 + 一点 Expressive 倾向：
 * - 圆角介于标准 Material 3（12dp large）和 Expressive（32dp）之间 —— 20dp，
 *   既不太死板（贴近 Noticon），又比 Noticon 稍软一点点；
 * - 其它档位按 Material 3 默认缩放派生。
 */
private val HardCropShapes =
  Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
  )

/**
 * 主题：优先用 Android 12+ 的动态取色（颜色从壁纸来，这是 Material 3 的标志性特性），
 * 老版本退回标准配色。
 */
@Composable
fun HardCropTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = true,
  content: @Composable () -> Unit,
) {
  val context = LocalContext.current
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      darkTheme -> darkColorScheme()
      else -> lightColorScheme()
    }

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography(),
    shapes = HardCropShapes,
    content = content,
  )
}