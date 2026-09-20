package com.iamcanincan.hardcrop

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable

/**
 * 把非自适应图标裁成圆形，并让 launcher 按自适应图标的方式处理它。
 *
 * ## 为什么必须是 AdaptiveIconDrawable
 * AOSP Launcher3 / Pixel Launcher 对桌面图标有两条完全不同的路径：
 * - `instanceof AdaptiveIconDrawable` → 走 **adaptive 路径**：取 `getBackground()` /
 *   `getForeground()` 分别绘制，再用系统 mask（`config_icon_mask`，设备上是圆形）裁切，
 *   **不加 launcher 自带的白色背景板、不缩到 safe zone**。
 * - 其他 → 走 wrap 路径：加 launcher 自带的白圆板 + 缩到 safe zone。
 *
 * 要拿到"无白边"只有走 adaptive 路径这一条路。
 *
 * ## 为什么实际内容要放在 background 层
 * launcher 按 adaptive 语义**只取 background / foreground 分别绘制**，它并不会调用我们的
 * `draw()`。上一版把内容画在重写的 `draw()` 里、bg/fg 只放透明占位 —— 结果是 launcher
 * 只画了两个透明层，看到的就是一个"透明圆"（表现为纯黑）。
 *
 * 所以：**foreground 保持透明（不遮挡），实际内容放 background**。
 *
 * ## 为什么 background 要把内容画到中心 2/3
 * 父类 `AdaptiveIconDrawable` 会把每一层的 bounds 设成 `1.5 × view bounds`
 * （`DEFAULT_VIEW_PORT_SCALE = 1/1.5`），mask 只保留中心 `view bounds` 区域。
 * 如果 background 铺满这 1.5 × 区域，最终看到的就是"内容被放大 1.5 倍、四周被裁掉"。
 * 因此 [CenteredIconDrawable] 在自己的 bounds（= 1.5 × view）内把原图标画到**中心 2/3**
 * （正好等于 view bounds），这样经 mask 裁切后原图标就是 1:1 铺满最终圆形。
 *
 * ## 关于形状
 * 形状由父类的 mask（系统 `config_icon_mask`）决定，不再自己重写 `draw` 再裁一次 ——
 * 双重裁切会让圆角方形之类的系统形状被裁成内切圆，反而与"和自适应图标一致"矛盾。
 * 设备上的系统形状是圆形，因此最终就是正圆。
 */
class CircleIconDrawable(icon: Drawable) :
  AdaptiveIconDrawable(
    CenteredIconDrawable(icon),
    ColorDrawable(Color.TRANSPARENT),
  ) {

  /**
   * 副本必须是新实例，且仍然要是 [AdaptiveIconDrawable]（否则 launcher 又走 wrap 路径）。
   */
  override fun getConstantState(): ConstantState? {
    val inner = background?.constantState
    // 同样**不能返回 null**：Launcher3 的 FloatingIconView 直接调
    // getConstantState().newDrawable()，null = NPE = 桌面进程崩。
    // CenteredIconDrawable 已保证非 null，这里的兜底只是防止把 launcher 带崩。
    return object : ConstantState() {
      override fun newDrawable(): Drawable =
        CircleIconDrawable(inner?.newDrawable() ?: ColorDrawable(Color.TRANSPARENT))

      override fun getChangingConfigurations(): Int = changingConfigurations
    }
  }
}

/**
 * 把原图标画到 bounds 的**中心 2/3**（cover 方式填满该区域、居中裁切）。
 *
 * 父类会把这一层的 bounds 设成 1.5 × view bounds，所以"中心 2/3"正好等于最终的
 * view bounds —— 原图标因此 1:1 呈现在圆形里，既不放大也不留边。
 */
private class CenteredIconDrawable(private val icon: Drawable) : Drawable() {

  override fun draw(canvas: Canvas) {
    val bounds = bounds
    if (bounds.isEmpty) return

    // 1.5 × view bounds 中的中心 2/3 = 最终可见的 view bounds
    val side = minOf(bounds.width(), bounds.height()) * (1f / 1.5f)
    val intrinsicWidth = icon.intrinsicWidth.takeIf { it > 0 } ?: side.toInt()
    val intrinsicHeight = icon.intrinsicHeight.takeIf { it > 0 } ?: side.toInt()

    // cover：填满 side × side，保持宽高比，多出来的居中裁掉
    val scale = maxOf(side / intrinsicWidth, side / intrinsicHeight)
    val scaledWidth = intrinsicWidth * scale
    val scaledHeight = intrinsicHeight * scale
    val left = bounds.exactCenterX() - scaledWidth / 2f
    val top = bounds.exactCenterY() - scaledHeight / 2f

    canvas.save()
    canvas.translate(left, top)
    canvas.scale(scale, scale)
    icon.setBounds(0, 0, intrinsicWidth, intrinsicHeight)
    icon.draw(canvas)
    canvas.restore()
  }

  override fun setAlpha(alpha: Int) {
    icon.alpha = alpha
  }

  override fun setColorFilter(colorFilter: ColorFilter?) {
    icon.colorFilter = colorFilter
  }

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
        override fun newDrawable(): Drawable = CenteredIconDrawable(src.newDrawable())
        override fun getChangingConfigurations(): Int = src.changingConfigurations
      }
    } else {
      // 原图标自己也不支持 ConstantState（自定义 Drawable 常见）。
      // 这种没法安全地"重建一个等价副本"，就退回复用同一个原图标 ——
      // 比返回 null 崩掉桌面好得多；FloatingIconView 只是临时画一下这个副本。
      object : ConstantState() {
        override fun newDrawable(): Drawable = CenteredIconDrawable(icon)
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
