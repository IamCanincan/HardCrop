package com.iamcanincan.hardcrop

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageItemInfo
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Method

private const val TAG = "HardCrop"

// 应用资源 id 都住在 0x7f 这个 package 里。把图标 id 挪到一个不会有真实资源的 package，
// 它到达 Resources 时就能被认出来；调用原方法之前会换回 0x7f，所以解析逻辑不受影响。
private const val REAL_PACKAGE_ID = 0x7f000000
private const val MARKED_PACKAGE_ID = 0x6e000000

private fun Int.isMarkedIcon() = (this and 0xff000000.toInt()) == MARKED_PACKAGE_ID

private fun Int.marked() = (this and 0x00ffffff) or MARKED_PACKAGE_ID

private fun Int.unmarked() = (this and 0x00ffffff) or REAL_PACKAGE_ID

private fun Int.isAppResource() = (this and 0xff000000.toInt()) == REAL_PACKAGE_ID

fun hookIcons(xposed: XposedInterface, param: XposedModuleInterface.PackageReadyParam) {
  Log.d(TAG, "onPackageReady ${param.packageName}, sdk ${Build.VERSION.SDK_INT}")

  // 应用图标最终都会到 Resources.getDrawableForDensity(int, int, Theme)：
  // Resources.getDrawable(id, theme) 和 PackageItemInfo.loadIcon() 都转调它。
  var hooked = false
  for (method in declaredMethods(Resources::class.java, "getDrawableForDensity")) {
    hooked = hookIconLoader(xposed, method) || hooked
  }
  // 较新的 android 版本改成在 ApplicationPackageManager 里解析 item 图标。
  classOf("android.app.ApplicationPackageManager", param)?.let { clazz ->
    for (method in declaredMethods(clazz, "getDrawableInternal")) {
      hooked = hookIconLoader(xposed, method) || hooked
    }
  }

  // 只有在加载通道挂上了才敢改 res id，否则被改过的 id 会解析失败。
  if (!hooked) {
    Log.w(TAG, "No icon loader is found, nothing is hooked")
    return
  }
  hookPixelLauncher(xposed, param)
  hookIconIds(xposed)
  hookShortcutIcons(xposed)
  Log.d(TAG, "Hooked ${param.packageName}")
}

/**
 * Android 16+ (BAKLAVA) 的 Pixel / AOSP Launcher3 在 `BaseIconFactory.createBadgedIconBitmap`
 * 里读取 `IconOptions.drawFullBleed`：true 时会**在 launcher 内部给图标再加一层白色背景板
 * 并把内容缩到 safe zone**，false 时按图标原样画（full-bleed）。
 *
 * 我们已经把图标处理成"圆形、内容填满、圆外透明"——这时 launcher 再加白圆板 +
 * safe-zone 缩小就会让用户看到"白圆 + 缩小"（即上一版模块的"白边"观感）。
 * 把 `drawFullBleed` 设成 false 让 launcher 不再加它的背景板，我们的圆形就直接呈现在桌面。
 *
 * 旧 Android 版本没有这个开关，本 hook 自动 no-op。
 */
private fun hookPixelLauncher(
  xposed: XposedInterface,
  param: XposedModuleInterface.PackageReadyParam,
) {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return

  // Launcher3 的类在**不同 ROM 上包名不同**：
  // - 类原生 / AOSP（LineageOS、Sony 等）：进程 `com.android.launcher3`，
  //   内部类沿用 `com.android.launcher3.*`
  // - Pixel / Nexus Launcher：进程 `com.google.android.apps.nexuslauncher`，
  //   内部类**可能**被重打包到 `com.google.android.apps.nexuslauncher.*`
  // 两个都试一遍，哪个存在用哪个。
  val launcherPkgs = listOf("com.android.launcher3", "com.google.android.apps.nexuslauncher")
  val baseIconFactoryClass =
    firstClassOf(launcherPkgs.map { "$it.icons.BaseIconFactory" }, param) ?: return
  val iconOptionsClass =
    firstClassOf(
      launcherPkgs.map { pkg -> pkg + ".icons.BaseIconFactory" + '$' + "IconOptions" },
      param,
    ) ?: return
  val drawFullBleedField =
    runCatching {
        iconOptionsClass.getDeclaredField("drawFullBleed").apply { isAccessible = true }
      }
      .getOrNull() ?: return

  var hooked = 0
  for (method in
    baseIconFactoryClass.declaredMethods.filter { it.name == "createBadgedIconBitmap" }) {
    method.isAccessible = true
    runCatching {
      xposed.hook(method).intercept { chain ->
        val args = chain.args
        val iconOptions = args.getOrNull(1)
        if (iconOptions != null && iconOptionsClass.isInstance(iconOptions)) {
          drawFullBleedField.setBoolean(iconOptions, false)
        }
        chain.proceed(args.toTypedArray())
      }
      hooked++
    }
  }
  if (hooked > 0) Log.d(TAG, "PixelLauncher: $hooked createBadgedIconBitmap hooked in ${param.packageName}")
}

private fun hookIconLoader(xposed: XposedInterface, method: Method): Boolean =
  runCatching {
      xposed.hook(method).intercept { chain ->
        val args = chain.args
        // 被标记的图标 id 是唯一认得出来的参数，找它比记住参数下标可靠。
        val index = args.indexOfFirst { (it as? Int)?.isMarkedIcon() == true }
        if (index < 0) return@intercept chain.proceed(args.toTypedArray())
        val restored = args.toMutableList()
        restored[index] = (args[index] as Int).unmarked()
        val icon = chain.proceedWith(chain.thisObject, restored.toTypedArray()) as? Drawable
        if (icon == null) null else clipToCircle(icon)
      }
      true
    }
    .getOrDefault(false)

private fun hookIconIds(xposed: XposedInterface) {
  val itemClasses =
    listOf(
      ApplicationInfo::class.java,
      ActivityInfo::class.java,
      ServiceInfo::class.java,
      ProviderInfo::class.java,
    )
  for (clazz in itemClasses) {
    for (ctor in declaredConstructors(clazz)) {
      runCatching {
        xposed.hook(ctor).intercept { chain ->
          val result = chain.proceed(chain.args.toTypedArray())
          val info = chain.thisObject as? PackageItemInfo ?: return@intercept result
          // 快捷设置磁贴画的是小尺寸单色图形，不是应用图标。
          if (info is ServiceInfo && info.permission == Manifest.permission.BIND_QUICK_SETTINGS_TILE)
            return@intercept result
          val icon = info.icon
          if (icon != 0 && icon.isAppResource()) info.icon = icon.marked()
          result
        }
      }
    }
  }

  for (ctor in declaredConstructors(ResolveInfo::class.java)) {
    runCatching {
      xposed.hook(ctor).intercept { chain ->
        val result = chain.proceed(chain.args.toTypedArray())
        val ri = chain.thisObject as? ResolveInfo ?: return@intercept result
        val icon = ri.componentInfo?.icon?.takeIf { it != 0 } ?: return@intercept result
        ri.icon = icon
        setIntField(ri, "iconResourceId", icon)
        result
      }
    }
  }
}

private fun hookShortcutIcons(xposed: XposedInterface) {
  for (method in declaredMethods(LauncherApps::class.java, "getShortcutIconDrawable")) {
    runCatching {
      xposed.hook(method).intercept { chain ->
        val icon = chain.proceed(chain.args.toTypedArray()) as? Drawable ?: return@intercept null
        clipToCircle(icon)
      }
    }
  }
}

private val ResolveInfo.componentInfo: ComponentInfo?
  get() = activityInfo ?: serviceInfo ?: providerInfo

private fun declaredMethods(clazz: Class<*>, name: String): List<Method> =
  clazz.declaredMethods.filter { it.name == name }.onEach { it.isAccessible = true }

private fun declaredConstructors(clazz: Class<*>): List<Constructor<*>> =
  clazz.declaredConstructors.toList().onEach { it.isAccessible = true }

private fun classOf(name: String, param: XposedModuleInterface.PackageReadyParam): Class<*>? =
  runCatching { Class.forName(name, true, param.classLoader) }.getOrNull()

/**
 * 按候选名依次尝试，返回**第一个存在**的类。
 *
 * Launcher3 的内部类在不同 ROM 上包名不同（AOSP 的 `com.android.launcher3.*` vs
 * Pixel 的 `com.google.android.apps.nexuslauncher.*`），所以类名不能写死一个。
 */
private fun firstClassOf(
  names: List<String>,
  param: XposedModuleInterface.PackageReadyParam,
): Class<*>? = names.firstNotNullOfOrNull { classOf(it, param) }

private fun setIntField(obj: Any, name: String, value: Int) = runCatching {
  obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(obj, value)
}
