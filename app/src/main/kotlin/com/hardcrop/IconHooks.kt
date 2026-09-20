package com.hardcrop

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
  hookIconIds(xposed)
  hookShortcutIcons(xposed)
  Log.d(TAG, "Hooked ${param.packageName}")
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

private fun setIntField(obj: Any, name: String, value: Int) = runCatching {
  obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(obj, value)
}
