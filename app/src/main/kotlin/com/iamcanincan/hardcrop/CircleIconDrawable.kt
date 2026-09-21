package com.iamcanincan.hardcrop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable

/**
 * 父类 `AdaptiveIconDrawable` 把每一层的 bounds 设成 `1.5 × view bounds`
 * （`DEFAULT_VIEW_PORT_SCALE = 1 / 1.5`），只有中心那 `view bounds` 是可见区域。
 * 内容要画到中心 `1 / 1.5` 才是 1:1 呈现。
 */
private const val VIEW_PORT_SCALE = 1f / 1.5f

/**
 * 把非自适应图标裁成圆形，并让 launcher 按自适应图标的方式处理它。
 *
 * ## 为什么必须是 AdaptiveIconDrawable
 * AOSP Launcher3 / Pixel Launcher 对桌面图标有两条完全不同的路径：
 * - `instanceof AdaptiveIconDrawable` → 走 **adaptive 路径**：取 `getBackground()` /
 *   `getForeground()` 分别绘制，再加系统 mask（`config_icon_mask`）裁切，
 *   **不加 launcher 自带的白色背景板、不缩到 safe zone**。
 * - 其他 → 走 wrap 路径：加 launcher 自带的白圆板 + 缩到 safe zone。
 *
 * 要拿到"无白边"只有走 adaptive 路径这一条路。
 *
 * ## 为什么实际内容要放在 background 层
 * launcher 按 adaptive 语义**只取 background / foreground 分别绘制**，它并不会调用外层
 * 的 `draw()`。内容画在外层的 `draw()` 里 = launcher 只画了两个透明层（表现为纯黑）。
 *
 * 所以：**foreground 保持透明（不遮挡），实际内容放 background**。
 *
 * ## 为什么形状由我们自己画，不看系统 mask
 * 早期版本让系统 mask 去裁：`config_icon_mask` 由 ROM 决定，有的圆、有的圆角方、
 * 有的水滴 —— 于是"裁出来是什么形状"完全随设备变。
 *
 * 现在改成 [RoundedIconDrawable] 在离屏位图上用 `DST_IN` 自己画一个正圆。这样无论
 * 系统 mask 是什么形状，看到的都是同一个圆：mask 只能"保留"像素，不能"凭空填出"
 * 圆外的部分，而我们圆外本来就是透明的。
 *
 * 这比去 hook 系统 mask 或依赖某个版本才有的开关稳得多，且对所有 Android 版本、
 * 所有桌面（含第三方）都成立。
 */
class CircleIconDrawable(icon: Drawable) :
  AdaptiveIconDrawable(
    RoundedIconDrawable(icon),
    ColorDrawable(Color.TRANSPARENT),
  ) {

  /**
   * 副本必须是新实例，且仍然要是 [AdaptiveIconDrawable]（否则 launcher 又走 wrap 路径）。
   */
  override fun getConstantState(): ConstantState? {
    val inner = background?.constantState
    // 同样**不能返回 null**：Launcher3 的 FloatingIconView 直接调
    // getConstantState().newDrawable()，null = NPE = 桌面进程崩。
    // RoundedIconDrawable 已保证非 null，这里的兜底只是防止把 launcher 带崩。
    return object : ConstantState() {
      override fun newDrawable(): Drawable =
        CircleIconDrawable(inner?.newDrawable() ?: ColorDrawable(Color.TRANSPARENT))

      override fun getChangingConfigurations(): Int = changingConfigurations
    }
  }
}

/**
 * 把原图标按 cover 方式画到 bounds 的**中心 2/3**，再裁成正圆。
 *
 * 父类会把这一层的 bounds 设成 `1.5 × view bounds`，所以"中心 2/3"正好等于最终可见的
 * view bounds —— 原图标因此 1:1 呈现在圆里，既不放大也不留边。
 */
private class RoundedIconDrawable(private val icon: Drawable) : Drawable() {

  private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
  private val maskMode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
  private var cache: Bitmap? = null

  override fun draw(canvas: Canvas) {
    val width = bounds.width()
    val height = bounds.height()
    if (width <= 0 || height <= 0) return

    // 尺寸没变就复用位图；内容每次重画（图标可能是会动的，比如时钟）。
    val bitmap =
      cache?.takeIf { it.width == width && it.height == height }
        ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { cache = it }
    val layer = Canvas(bitmap)
    bitmap.eraseColor(Color.TRANSPARENT)

    val side = minOf(width, height) * VIEW_PORT_SCALE
    val intrinsicWidth = icon.intrinsicWidth.takeIf { it > 0 } ?: side.toInt()
    val intrinsicHeight = icon.intrinsicHeight.takeIf { it > 0 } ?: side.toInt()

    // cover：填满 side × side，保持宽高比，多出来的居中裁掉
    val scale = maxOf(side / intrinsicWidth, side / intrinsicHeight)
    val scaledWidth = intrinsicWidth * scale
    val scaledHeight = intrinsicHeight * scale

    layer.save()
    layer.translate(width / 2f - scaledWidth / 2f, height / 2f - scaledHeight / 2f)
    layer.scale(scale, scale)
    icon.setBounds(0, 0, intrinsicWidth, intrinsicHeight)
    icon.draw(layer)
    layer.restore()

    // 只保留内切圆以内的像素 —— 形状在这里定死，不经过系统 mask
    paint.xfermode = maskMode
    layer.drawOval(
      width / 2f - side / 2f,
      height / 2f - side / 2f,
      width / 2f + side / 2f,
      height / 2f + side / 2f,
      paint,
    )
    paint.xfermode = null

    canvas.drawBitmap(bitmap, null, bounds, paint)
  }

  override fun setAlpha(alpha: Int) {
    icon.alpha = alpha
    invalidateSelf()
  }

  override fun setColorFilter(colorFilter: ColorFilter?) {
    icon.colorFilter = colorFilter
    invalidateSelf()
  }

  // API 27/28 上 framework 的 Drawable.getOpacity() 还是抽象方法，不能整个删掉。
  @Suppress("OVERRIDE_DEPRECATION")
  override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

  override fun getIntrinsicWidth(): Int = icon.intrinsicWidth

  override fun getIntrinsicHeight(): Int = icon.intrinsicHeight

  /**
   * 必须实现，而且**绝不能返回 null**。
   *
   * `Drawable` 默认实现返回 null，而 Launcher3 的 `FloatingIconView`（点击桌面图标时
   * 那个图标浮起的动画）在 `getIconResult()` 里直接写
   * `icon.getConstantState().newDrawable()` —— 拿到 null 就是 NPE，整个桌面进程崩掉。
   * 上一版正是漏了这里，导致"点一下图标桌面就崩"。
   */
  override fun getConstantState(): ConstantState? {
    val src = icon.constantState
    return if (src != null) {
      object : ConstantState() {
        override fun newDrawable(): Drawable = RoundedIconDrawable(src.newDrawable())
        override fun getChangingConfigurations(): Int = src.changingConfigurations
      }
    } else {
      // 原图标自己也不支持 ConstantState（自定义 Drawable 常见）。
      // 这种没法安全地"重建一个等价副本"，就退回复用同一个原图标 ——
      // 比返回 null 崩掉桌面好得多；FloatingIconView 只是临时画一下这个副本。
      object : ConstantState() {
        override fun newDrawable(): Drawable = RoundedIconDrawable(icon)
        override fun getChangingConfigurations(): Int = changingConfigurations
      }
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
