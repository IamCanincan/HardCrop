package com.iamcanincan.hardcrop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable

/**
 * 自适应图标多出来的那一圈。`AdaptiveIconDrawable` 把每一层的 bounds 设成
 * `(1 + 2 × extraInset) × view bounds`，只有中心的 view bounds 是可见区域。
 *
 * 这个值**随安卓版本变**（老版本是 0.25 → 1/1.5），所以一律动态取，不写死。
 */
private val EXTRA_INSET_FRACTION = AdaptiveIconDrawable.getExtraInsetFraction()

/** 内容要缩到这个比例，才能在自适应图标里 1:1 呈现。 */
private val VIEW_PORT_SCALE = 1f / (1f + 2f * EXTRA_INSET_FRACTION)

/**
 * 把非自适应图标**裁成圆形**，并让系统按自适应图标的方式对待它 —— 也就是让非自适应
 * 图标的表现和自适应图标一致。
 *
 * ## 为什么必须是 AdaptiveIconDrawable
 * Launcher3 / Pixel Launcher 对桌面图标有两条路径：
 * - `instanceof AdaptiveIconDrawable` → adaptive 路径：取 `getBackground()` /
 *   `getForeground()` 分别绘制，再加系统 mask 裁切。
 * - 其他 → wrap 路径：加 launcher 自带的背景板 + 缩到 safe zone。
 *
 * 要"和自适应图标一样"，必须走 adaptive 路径。
 *
 * ## 为什么实际内容要放在 background 层（这是关键，之前几次都栽在这）
 * launcher 按 adaptive 语义**只取 background / foreground 分别绘制**，它不会调用外层
 * 的 `draw()`。如果内容放在外层 `draw()` 里，launcher 只会画两个透明层 → 圆看不见。
 * 也不能放在 foreground：foreground 是透明层，且部分页面（如 Settings 应用列表）直绘
 * 整个 drawable 时只把"非透明层"渲染出来，放 foreground 等于没有圆。
 *
 * 所以：**内容放 background 层**（[RoundedIconDrawable]），foreground 保持透明。
 *
 * ## 为什么形状由我们自己画，不看系统 mask
 * 实测这台机的 Settings 应用列表**不套系统 mask**，直绘整个 drawable —— 靠系统 mask
 * 去裁的话，这一页永远是原图方形。所以在 [RoundedIconDrawable] 里用 `DST_IN` 自己画一个
 * 正圆：无论系统 mask 是什么形状，看到的都是同一个圆（mask 只能"保留"像素、不能凭空
 * 填出圆外部分，而我们圆外本来就是透明的）。
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
 * 把原图标按 cover 方式画到 bounds 的**中心可见区**，再裁成正圆。
 *
 * 父类会把这一层的 bounds 设成 `(1 + 2 × extraInset) × view bounds`，所以"中心
 * `1 / (1 + 2 × extraInset)`"正好等于最终可见的 view bounds —— 原图标因此 1:1 呈现在圆里，
 * 既不放大也不留边。
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
      // 退回复用同一个原图标比返回 null 崩掉桌面好得多。
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
