package com.hardcrop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable

/**
 * 把非自适应图标裁成圆形。
 *
 * ## 工作方式
 * 1. 继承 [AdaptiveIconDrawable] 让 `drawable instanceof AdaptiveIconDrawable` 为真。
 *    **第三方桌面**（Lawnchair / Nova / Action Launcher / Niagara 等不强制"统一图标形状"
 *    的桌面）、widget 列表、通知应用图标、recent task 缩略图看到是 `AdaptiveIconDrawable`
 *    时，会走 adaptive 路径不再缩放 + 不再加白色垫底。
 * 2. 重写 [draw] 把原图标按 1:1 绘到离屏位图、用圆形 Path 以 `DST_IN` 裁切后再输出。
 *    父类默认会把每一层画到 `1.5 × bounds`（`DEFAULT_VIEW_PORT_SCALE = 1/1.5`）后再由 mask
 *    裁切 —— 那会让图标被放大且四角被裁掉，不符合"原图标 1:1 显示在圆里"的诉求。
 * 3. 父类的背景/前景/monochrome 都传 [TRANSPARENT_PLACEHOLDER]（`ColorDrawable(TRANSPARENT)`），
 *    既保证 launcher 拿到的 background/foreground 不为 null（避免 `setColorFilter`、
 *    badge 叠加等调用 NPE），又不会画任何东西；真正的内容由我们重写的 [draw] 控制。
 *
 * ## 关于 AOSP Launcher3（Pixel/Quickstep/Material You 自带桌面）
 * Android 12+ 的 AOSP Launcher3 对**所有**桌面图标（含 adaptive 图标）强制应用 launcher
 * 自带的"统一图标形状"—— 一个 launcher 自带的白色圆形背景板 + safe zone 内的图标内容。
 * **这个处理发生在 launcher 拿到 Drawable 之后，与模块无关**，所以即使 Drawable 已经圆，
 * 在 launcher3 home/allapps/hotseat 上仍会显示为"白圆板 + 缩小的图标"。
 *
 * 验证方式：禁用本模块后看 AOSP Launcher3 自适应图标（Chrome / Gmail / 系统应用等），
 * 它们的外观与启用模块后看到的"非自适应图标"完全一样 —— 因为 launcher 给所有图标都
 * 加同一块白圆板。如果想要"图标填满圆形、无白边"的观感：
 *   - 换用第三方桌面（Lawnchair / Nova / Action Launcher / Niagara 等），它们不强制
 *     统一形状背景板，模块的圆形裁切会原样生效。
 *   - 或关闭 Android 系统的"Themed icons"（系统设置 → 壁纸与样式 → 主题图标，部分 OEM
 *     的设置路径不同）；这关掉的是"图标按主色染色"，但 AOSP Launcher3 的白圆板**仍会**显示。
 *
 * ## 关于 mask
 * 父类的 `mMask` 来自系统 `config_icon_mask`，设备上是圆形。它不参与我们的绘制；
 * 但 `getOutline()` / `getIconMask()` / `getTransparentRegion()` 仍返回父类的值，
 * 用于 launcher 的 hit-test 与排序透明区域计算，行为正确。
 */
class CircleIconDrawable(private val icon: Drawable) :
  AdaptiveIconDrawable(
    TRANSPARENT_PLACEHOLDER,
    TRANSPARENT_PLACEHOLDER,
  ) {

  private val paint =
    Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)

  private val maskPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

  private val circle = Path()

  private var buffer: Bitmap? = null
  private var bufferWidth = 0
  private var bufferHeight = 0

  override fun draw(canvas: Canvas) {
    val bounds = bounds
    val width = bounds.width()
    val height = bounds.height()
    if (width <= 0 || height <= 0) return

    val bitmap = buffer(width, height)
    bitmap.eraseColor(Color.TRANSPARENT)

    val offscreen = Canvas(bitmap)
    offscreen.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
    icon.draw(offscreen)
    offscreen.translate(bounds.left.toFloat(), bounds.top.toFloat())

    circle.rewind()
    circle.addCircle(width / 2f, height / 2f, minOf(width, height) / 2f, Path.Direction.CW)
    offscreen.drawPath(circle, maskPaint)

    canvas.drawBitmap(bitmap, null, bounds, paint)
  }

  private fun buffer(width: Int, height: Int): Bitmap {
    val current = buffer
    if (current != null && bufferWidth == width && bufferHeight == height) return current
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
      buffer = it
      bufferWidth = width
      bufferHeight = height
    }
  }

  override fun getConstantState(): ConstantState? {
    val inner = icon.constantState
    return object : ConstantState() {
      override fun newDrawable(): Drawable =
        CircleIconDrawable(inner?.newDrawable() ?: icon)
      override fun getChangingConfigurations(): Int = changingConfigurations
    }
  }
}

/**
 * 自适应图标已带形状；圆形图标已做；这两种不再处理。其余一律包成 [CircleIconDrawable]。
 */
fun clipToCircle(icon: Drawable): Drawable =
  when (icon) {
    is AdaptiveIconDrawable -> icon
    is CircleIconDrawable -> icon
    else -> CircleIconDrawable(icon)
  }

// 占位 drawable：让父类有非 null 的 bg/fg，避免 launcher / 系统调 setColorFilter、
// badge 叠加时 NPE。视觉透明，不参与我们实际渲染的内容。
private val TRANSPARENT_PLACEHOLDER: Drawable = ColorDrawable(Color.TRANSPARENT)