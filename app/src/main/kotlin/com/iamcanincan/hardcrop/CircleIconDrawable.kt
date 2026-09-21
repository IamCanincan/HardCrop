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
 * ## 为什么两层都要放圆（这是关键，之前几次都栽在这）
 * launcher 按 adaptive 语义**只取 background / foreground 分别绘制**，它不会调用外层
 * 的 `draw()`。如果内容放在外层 `draw()` 里，launcher 只会画两个透明层 → 圆看不见。
 *
 * 而放哪一层取决于页面：
 * - 只放 foreground（背景透明）→ Settings 应用列表空白（v2.5 实测）；
 * - 只放 background（前景透明）→ 只读 `getForeground()` 的页面什么都看不到。
 *
 * 实测不同页面取的层不一样，所以**两层都放同一个圆**最稳（详见下一节）。
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
    RoundedIconDrawable(icon),
  ) {

  /**
   * 圆**同时放进 background 和 foreground 两层**。
   *
   * 原因：参考项目把图标放 foreground（背景透明），而我们实测这台机的 Settings
   * 应用列表必须放 background 才显示 —— 说明不同页面取的层不一样：
   * 只读 `getForeground()` 的页面拿到透明前景就什么都看不到，反之亦然。
   * 两层放同一个圆后，无论页面读哪一层、还是调 `draw()`，拿到的都是圆。
   *
   * 两层画的是**同一个圆**（同心、同尺寸），所以叠在一起仍然是同一个圆；
   * 图标本身不透明时完全没有差别。
   */
  private val sourceIcon: Drawable = icon

  private val sourceState: ConstantState? = icon.constantState

  /**
   * **不调用 `super.draw()`** —— 这是能否在所有页面都显示成圆的关键。
   *
   * `AdaptiveIconDrawable` 自带的 `draw()` 会把两层合成后再套一次**系统 mask**
   * （`config_icon_mask`）。于是最终形状变成 ROM 决定的那个形状（圆角方 / 水滴 / 方），
   * 而不是我们裁好的圆 —— 凡是走 `draw()` 渲染的页面都会是这个结果。
   *
   * 参考项目为此专门写了 `UnClipAdaptiveIconDrawable`：重写 `draw()` 用「全幅矩形」路径
   * 作画，绕开 mask。它靠反射 `mLayersBitmap` / `mLayersShader` / `mCanvas` / `mPaint`
   * 这几个私有字段实现。我们不反射（版本一变就断），改用更直接的办法：
   * 圆已经在 [RoundedIconDrawable] 里裁好了，直接把它按**自己的 bounds** 画出来即可 ——
   * 父类给这一层设的 bounds 正是 `(1 + 2 × extraInset)` 的全幅，圆因此正好落在可见区，
   * 不经过任何系统 mask。
   */
  override fun draw(canvas: Canvas) {
    val bg = background
    if (bg == null) super.draw(canvas) else bg.draw(canvas)
  }

  /**
   * 副本必须是新实例，且仍然要是 [AdaptiveIconDrawable]（否则 launcher 又走 wrap 路径）。
   */
  override fun getConstantState(): ConstantState? {
    val src = sourceState
    // 同样**不能返回 null**：Launcher3 的 FloatingIconView 直接调
    // getConstantState().newDrawable()，null = NPE = 桌面进程崩。
    // 拿不到 ConstantState 时退回复用同一个原图标，比返回 null 崩掉桌面好。
    return object : ConstantState() {
      override fun newDrawable(): Drawable = CircleIconDrawable(src?.newDrawable() ?: sourceIcon)

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
