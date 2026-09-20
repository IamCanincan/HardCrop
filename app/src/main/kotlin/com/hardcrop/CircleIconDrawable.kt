package com.hardcrop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable

/**
 * 把 [icon] 裁成圆形，圆外部分保持透明。
 *
 * 继承 InsetDrawable（inset 为 0）来复用系统自带的 wrapper：alpha、colorFilter、state、level
 * 都会自动转发给被包装的 drawable，矢量图标也仍然按最终尺寸绘制，不会被提前栅格化。
 */
class CircleIconDrawable(private val icon: Drawable) : InsetDrawable(icon, 0) {

  private val paint =
    Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)

  // DST_IN 只保留圆内的像素，圆形路径带抗锯齿，边缘是软的而不是硬锯齿。
  private val maskPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

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
    // 被包装的 drawable 按真实 bounds 布局，这里把它平移到离屏缓冲的原点。
    offscreen.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
    super.draw(offscreen)
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
    val cs = icon.constantState ?: return null
    return CircleState(cs)
  }

  private class CircleState(private val cs: ConstantState) : ConstantState() {
    override fun newDrawable(): Drawable = CircleIconDrawable(cs.newDrawable())

    override fun getChangingConfigurations(): Int = cs.changingConfigurations
  }
}

/** 自适应图标由系统按统一形状处理，已经裁过的也不重复处理，除此之外的一律裁圆。 */
fun clipToCircle(icon: Drawable): Drawable =
  when (icon) {
    is AdaptiveIconDrawable -> icon
    is CircleIconDrawable -> icon
    else -> CircleIconDrawable(icon)
  }
