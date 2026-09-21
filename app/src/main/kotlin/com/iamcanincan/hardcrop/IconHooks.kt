package com.iamcanincan.hardcrop

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageInfo
import android.content.pm.PackageItemInfo
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Parcel
import android.os.Process
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.concurrent.Volatile

const val TAG = "HardCrop"

// 应用资源 id 都住在 0x7f 这个 package 里。把图标 id 挪到一个不会有真实资源的 package，
// 它到达 Resources 时就能被认出来；调用原方法之前会换回 0x7f，所以解析逻辑不受影响。
private const val REAL_PACKAGE_ID = 0x7f000000
private const val MARKED_PACKAGE_ID = 0x6e000000

// 应用图标解析不出来时系统用的那个默认图标，它同样不是圆形的。
private const val DEFAULT_APP_ICON = android.R.drawable.sym_def_app_icon

private fun Int.isMarkedIcon() = (this and 0xff000000.toInt()) == MARKED_PACKAGE_ID

private fun Int.marked() = (this and 0x00ffffff) or MARKED_PACKAGE_ID

private fun Int.unmarked() = (this and 0x00ffffff) or REAL_PACKAGE_ID

private fun Int.isAppResource() = (this and 0xff000000.toInt()) == REAL_PACKAGE_ID

/**
 * 打标记的重入保护。
 *
 * 生成图标信息（构造 / Parcel 反序列化 / PMS 生成）的几条路径会互相嵌套：
 * 外层生成 `PackageInfo` 时内部又会构造 `ApplicationInfo`，而 `PackageInfo` 里装的正是
 * 这些 `ApplicationInfo`。没有这层保护就会出现"打过标记又被当作新 id 再处理一遍"。
 */
private val markingIcons = ThreadLocal.withInitial { false }

/**
 * 图标生成的重入保护。
 *
 * `Resources.getDrawableForDensity()` 和 `ApplicationPackageManager.getDrawableInternal()`
 * 可能出现在同一条调用链上：外层 proceed 之后会跑到内层。只让最外层生成图标，
 * 否则同一个图标会被包装两次（圆套圆、尺寸再缩一次），表现就是"同一个图标
 * 有时大有时小"。
 */
private val replacingIcon = ThreadLocal.withInitial { false }


private inline fun runMarkingIcons(block: () -> Unit) {
  if (markingIcons.get() == true) return
  markingIcons.set(true)
  try {
    block()
  } finally {
    markingIcons.set(false)
  }
}

/** 同一进程里 `onSystemServerStarting` 和 `onPackageReady` 可能都来，别装两遍。 */
@Volatile private var installed = false

fun hookSystemServer(
  xposed: XposedInterface,
  param: XposedModuleInterface.SystemServerStartingParam,
) = install(xposed, param.classLoader, "android")

fun hookIcons(xposed: XposedInterface, param: XposedModuleInterface.PackageReadyParam) =
  install(xposed, param.classLoader, param.packageName)

/**
 * 图标替换分两步：先在**图标信息**上把 icon 资源 id 打标记，再在**图标加载出口**上
 * 认出这个标记并把结果裁成圆形。两步缺一不可，所以加载出口挂不上时就整体放弃 ——
 * 否则被打过标记的 id 会在别的进程里解析失败。
 *
 * ⚠ 「在别的进程里解析失败」的后果比"图标变空白"严重得多：伪造的 `0x6e…` 传到没有
 * 本模块的进程后，`Resources.getDrawable` 会抛 `Resources$NotFoundException`，而
 * `Activity.initWindowDecorActionBar` 这类调用点就在 `Activity.onCreate` 里 —— **直接崩应用**。
 * 所以 [hookParcelWriteRestore] 会在 Parcel 出口统一还原，把这条泄漏掐断。
 */
private fun install(xposed: XposedInterface, classLoader: ClassLoader, packageName: String) {
  if (installed) return
  Log.d(TAG, "install in $packageName, sdk ${Build.VERSION.SDK_INT}")

  if (!hookIconLoaders(xposed, classLoader)) {
    Log.w(TAG, "No icon loader is found, nothing is hooked")
    return
  }
  installed = true

  // 出口还原必须先挂：它是唯一能挡住"标记 id 泄漏到没注入的进程"的闸门。
  hookParcelWriteRestore(xposed)
  hookMarkedIconIds(xposed)
  hookBatchIconIds(xposed, classLoader)
  hookShortcutIcons(xposed)
  hookArchivedAppIcon(xposed, classLoader)
  hookPackageManagerIconGetters(xposed, classLoader)

  if (packageName == "com.android.launcher3" ||
      packageName == "com.google.android.apps.nexuslauncher"
  ) {
    invalidateIconCache(xposed, packageName)
    hookPixelLauncher(xposed, classLoader)
    hookTaskIcons(xposed, classLoader)
  }
  if (packageName == "com.android.systemui") hookSplashScreenIcon(xposed, classLoader)
  if (packageName == "com.android.settings") {
    hookSettingsAdaptiveIcon(xposed, classLoader)
    hookBatteryIcons(xposed, classLoader)
  }

  Log.d(TAG, "Hooked $packageName")
}

/**
 * 模块生效后**自动清掉桌面自己的图标缓存**。
 *
 * launcher 把图标位图存在 `app_icons.db` 里，按**目标 App 的包名 + 版本**存 ——
 * 我们模块升不升级跟它没关系。所以升级之后桌面看到的还是**上一版渲染出来的旧图**，
 * 得手工删这个 DB 才会重新生成（bump 版本号也没用，见 MEMORY）。
 *
 * 这里在模块加载时对比**自己 APK 的戳**：变了就删一次。
 * - 戳用「mtime + 体积」：APK 的 mtime 是安装时间，每次装都会变。
 * - `onPackageReady` 发生在 `Application.attachBaseContext` 阶段，
 *   **比 launcher 打开 IconCache 还早**，这时候删得掉。
 * - 只删 `app_icons.db*`（图标缓存），**绝不碰** `launcher.db` / `launcher_4_by_5.db`
 *   —— 那是桌面布局，删了图标排列就全没了。
 * - 上次的戳记在 launcher 自己数据目录下的标记文件里：我们正以 launcher 的 UID 在跑，
 *   读写它毫无障碍，而且能**跨 launcher 重启存活**（不像 `getRemotePreferences` 在本机
 *   KernelSU + LSPosed 下不落盘）。这样保证"每个模块版本只清一次"，重启不会反复重建 17MB 缓存。
 */
private const val CACHE_STAMP_FILE = "hardcrop_cache_stamp"

private fun invalidateIconCache(xposed: XposedInterface, packageName: String) {
  val base = "/data/user/${Process.myUid() / 100000}/$packageName"
  val stampFile = File("$base/files/$CACHE_STAMP_FILE")
  val stamp =
    runCatching {
        File(xposed.moduleApplicationInfo.sourceDir).let { "${it.lastModified()}_${it.length()}" }
      }
      .getOrNull()
  if (stamp == null) {
    Log.w(TAG, "IconCache: cannot read module apk stamp")
    return
  }

  // 已经清过这个版本了，跳过（避免每次 launcher 重启都重建 17MB 缓存）。
  if (runCatching { stampFile.readText() }.getOrNull() == stamp) return

  // 删缓存（按 launcher 的 UID 直接删它自己数据目录里的文件，不需要任何额外权限）。
  val db = File("$base/databases/app_icons.db")
  if (db.exists()) {
    if (!runCatching { db.delete() }.getOrDefault(false)) {
      Log.w(TAG, "IconCache: cannot delete ${db.path}")
      return
    }
    for (suffix in listOf("-journal", "-wal", "-shm")) {
      runCatching { File(db.path + suffix).delete() }
    }
    Log.d(TAG, "IconCache: invalidated, new stamp $stamp")
  }
  // 无论 DB 是否存在都记下戳，保证同版本只清一次。
  runCatching { stampFile.parentFile?.mkdirs(); stampFile.writeText(stamp) }
}

/**
 * 图标的加载出口。应用图标最终都会到 `Resources.getDrawableForDensity()`：
 * `Resources.getDrawable(id, theme)` 和 `PackageItemInfo.loadIcon()` 都转调它。
 * 较新的安卓版本改成在 `ApplicationPackageManager` 里解析 item 图标。
 */
private fun hookIconLoaders(xposed: XposedInterface, classLoader: ClassLoader): Boolean {
  var hooked = false
  for (method in declaredMethods(Resources::class.java, "getDrawableForDensity")) {
    hooked = hookIconLoader(xposed, method) || hooked
  }
  // deprecated 入口（资源 ID + 主题）：launcher 的快捷面板 / AllApps 数据加载
  // 走的就是这两个重载，没走 `getDrawableForDensity`。
  for (method in declaredMethods(Resources::class.java, "getDrawable")) {
    if (hookIconLoader(xposed, method)) {
      Log.d(TAG, "IconLoaders: Resources.getDrawable (${method.parameterTypes.joinToString { it.simpleName }}) hooked")
      hooked = true
    }
  }
  val packageManager = classOf("android.app.ApplicationPackageManager", classLoader)
  if (packageManager != null) {
    for (method in declaredMethods(packageManager, "getDrawableInternal")) {
      hooked = hookIconLoader(xposed, method) || hooked
    }
    // Settings 等应用拿其它包图标走这个：签名 (String, int, ApplicationInfo) 等。
    for (method in declaredMethods(packageManager, "getDrawable")) {
      if (hookIconLoader(xposed, method)) {
        Log.d(TAG, "IconLoaders: APM.getDrawable (${method.parameterTypes.joinToString { it.simpleName }}) hooked")
        hooked = true
      }
    }
  }
  return hooked
}

private fun hookIconLoader(xposed: XposedInterface, method: Method): Boolean =
  runCatching {
      xposed.hook(method).intercept { chain ->
        val args = chain.args

        // 已经在生成图标了（同一条调用链的内层），交给最外层处理。
        // 参考项目把这道闸放在最前面 —— 默认图标那条分支同样要挡住，
        // 否则嵌套时同一个图标会被裁两次。
        if (replacingIcon.get() == true) return@intercept chain.proceed(args.toTypedArray())

        // 被标记的图标 id 是唯一认得出来的参数，找它比记住参数下标可靠。
        val index = args.indexOfFirst { (it as? Int)?.isMarkedIcon() == true }
        if (index < 0) {
          // 解析不出应用图标时系统会退回这个默认图标，它同样不是圆的。
          if (args.indexOfFirst { (it as? Int) == DEFAULT_APP_ICON } < 0) {
            return@intercept chain.proceed(args.toTypedArray())
          }
          val fallback =
            chain.proceed(args.toTypedArray()) as? Drawable ?: return@intercept null
          return@intercept clipToCircle(fallback)
        }

        replacingIcon.set(true)
        try {
          val restored = args.toMutableList()
          restored[index] = (args[index] as Int).unmarked()
          val icon =
            chain.proceedWith(chain.thisObject, restored.toTypedArray()) as? Drawable
              ?: return@intercept null
          clipToCircle(icon)
        } finally {
          replacingIcon.set(false)
        }
      }
      true
    }
    .getOrDefault(false)

/** 归档应用的图标走的是独立的接口，不经过上面那两个出口。 */
private fun hookArchivedAppIcon(xposed: XposedInterface, classLoader: ClassLoader) {
  val packageManager = classOf("android.app.ApplicationPackageManager", classLoader) ?: return
  var hooked = 0
  for (method in declaredMethods(packageManager, "getArchivedAppIcon")) {
    runCatching {
      xposed.hook(method).intercept { chain ->
        val icon = chain.proceed(chain.args.toTypedArray()) as? Drawable ?: return@intercept null
        clipToCircle(icon)
      }
      hooked++
    }
  }
  if (hooked > 0) Log.d(TAG, "ArchivedAppIcon: $hooked hooked")
}

/**
 * 一些场景（电池页、某些缓存接口）不拿 resId 而是直接拿 Drawable —— 走
 * `getApplicationIcon` / `getActivityIcon` / `getDefaultActivityIcon` 等，
 * 它们直接返回 `Drawable`，没法用"resId 打标记"那套。这条路径用结果包装：
 * 不管原方法返回什么 Drawable，都套成 `CircleIconDrawable`（adaptive 图标
 * 系统自己会画圆，不动；其它一律裁圆）。
 */
private fun hookPackageManagerIconGetters(xposed: XposedInterface, classLoader: ClassLoader) {
  val packageManager = classOf("android.app.ApplicationPackageManager", classLoader) ?: return
  var hooked = 0
  for (name in listOf("getApplicationIcon", "getActivityIcon", "getDefaultActivityIcon", "loadItemIcon")) {
    for (method in declaredMethods(packageManager, name)) {
      runCatching {
        xposed.hook(method).intercept { chain ->
          val icon = chain.proceed(chain.args.toTypedArray()) as? Drawable ?: return@intercept null
          clipToCircle(icon)
        }
        hooked++
      }
    }
  }
  if (hooked > 0) Log.d(TAG, "PM IconGetters: $hooked hooked")
}

/**
 * **跨进程出口还原**：把 icon 的标记换回真实 id 之后再写进 Parcel。
 *
 * 打过标记的 id（`0x6e…`）只有**本进程**的图标加载出口认得。system_server 在 PMS 里
 * 给 `PackageItemInfo` 打标记之后，这些对象会经 Binder 传给任意 app —— 接收方如果
 * 不在作用域里（没有我们的还原 hook），拿到的伪造 package id 在它自己的资源表里
 * 根本不存在，一解析就抛 `Resources$NotFoundException`，而且是**在 `Activity.onCreate`
 * 里抛的，直接把应用带崩**，不是"图标变空白"那么轻。
 *
 * 真机案例：华为应用市场 `MainActivity` 的 ActionBar 默认图标走
 * `Activity.initWindowDecorActionBar` → `PhoneWindow.setDefaultIcon` →
 * `Context.getDrawable(activityInfo.icon)`，拿到 `0x6e…` 连续两次冷启动都崩。
 *
 * 所以写进 Parcel 前必须还原成合法的 `0x7f…`。客户端收到后由 `readTypedList` /
 * `BaseParceledListSlice` / 构造器 hook 重新打标记，已注入进程的裁圆功能不受影响。
 */
private fun hookParcelWriteRestore(xposed: XposedInterface) {
  var hooked = 0

  // 所有 PackageItemInfo 子类（ApplicationInfo / ActivityInfo / ServiceInfo /
  // ProviderInfo）的 writeToParcel 都会调到基类这一层，一处覆盖全部。
  for (method in declaredMethods(PackageItemInfo::class.java, "writeToParcel")) {
    runCatching {
      xposed.hook(method).intercept { chain ->
        val info =
          chain.thisObject as? PackageItemInfo
            ?: return@intercept chain.proceed(chain.args.toTypedArray())
        val saved = info.icon
        if (saved.isMarkedIcon()) info.icon = saved.unmarked()
        try {
          chain.proceed(chain.args.toTypedArray())
        } finally {
          info.icon = saved
        }
      }
      hooked++
    }
  }

  // ResolveInfo 不是 PackageItemInfo 的子类，它自己还带一份 icon / iconResourceId。
  for (method in declaredMethods(ResolveInfo::class.java, "writeToParcel")) {
    runCatching {
      xposed.hook(method).intercept { chain ->
        val info =
          chain.thisObject as? ResolveInfo
            ?: return@intercept chain.proceed(chain.args.toTypedArray())
        val savedIcon = info.icon
        val savedResId = getIntField(info, "iconResourceId")
        if (savedIcon.isMarkedIcon()) {
          info.icon = savedIcon.unmarked()
          setIntField(info, "iconResourceId", savedIcon.unmarked())
        }
        try {
          chain.proceed(chain.args.toTypedArray())
        } finally {
          info.icon = savedIcon
          if (savedResId != null) setIntField(info, "iconResourceId", savedResId)
        }
      }
      hooked++
    }
  }

  if (hooked > 0) Log.d(TAG, "ParcelWrite: $hooked hooked (unmark before Binder)")
}

/**
 * 在图标信息**构造**时打标记。这是最基础的一条路径，覆盖 launcher / systemui /
 * settings 里逐个构造出来的 `ApplicationInfo` / `ActivityInfo` / `ResolveInfo`。
 */
private fun hookMarkedIconIds(xposed: XposedInterface) {
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
          runMarkingIcons { markIcon(info) }
          result
        }
      }
    }
  }

  for (ctor in declaredConstructors(ResolveInfo::class.java)) {
    runCatching {
      xposed.hook(ctor).intercept { chain ->
        val result = chain.proceed(chain.args.toTypedArray())
        runMarkingIcons { markResolveInfo(chain.thisObject as? ResolveInfo) }
        result
      }
    }
  }

  // PackageInfo 里的 applicationInfo / activities 等字段多数是构造之后才填的，
  // 但"构造完立刻用"的场合也有，先打一遍。批量通道里还会再打一次。
  for (ctor in declaredConstructors(PackageInfo::class.java)) {
    runCatching {
      xposed.hook(ctor).intercept { chain ->
        val result = chain.proceed(chain.args.toTypedArray())
        markInfo(chain.thisObject as? PackageInfo)
        result
      }
    }
  }
}

/**
 * 批量 / 跨进程通道 —— **应用列表真正走的是这里**，不是上面那条逐个构造的路径。
 *
 * Settings 的应用列表、分享页、权限页拿到的图标是 PMS 一次性序列化过来的一整包
 * `PackageInfo` / `ResolveInfo`。只 hook 构造的话，这些列表里的图标根本不会经过
 * 我们的加载出口，于是仍然显示原图。
 */
private fun hookBatchIconIds(xposed: XposedInterface, classLoader: ClassLoader) {
  var hooked = 0

  for (method in declaredMethods(Parcel::class.java, "readTypedList")) {
    runCatching {
      xposed.hook(method).intercept { chain ->
        val result = chain.proceed(chain.args.toTypedArray())
        markAll(result as? List<*>)
        result
      }
      hooked++
    }
  }

  for (method in declaredMethods(Parcel::class.java, "createTypedArray")) {
    runCatching {
      xposed.hook(method).intercept { chain ->
        val result = chain.proceed(chain.args.toTypedArray())
        markAll((result as? Array<*>)?.asIterable())
        result
      }
      hooked++
    }
  }

  if (hooked > 0) Log.d(TAG, "Parcel: $hooked hooked")

  hookParceledListSlice(xposed, classLoader)
  hookPackageInfoCommonUtils(xposed, classLoader)
}

/**
 * 跨进程传大列表用的容器。一次性把整张列表打标记，比逐个处理快得多。
 */
private fun hookParceledListSlice(xposed: XposedInterface, classLoader: ClassLoader) {
  val base = classOf("android.content.pm.BaseParceledListSlice", classLoader) ?: return
  val mList = fieldOf(base, "mList") ?: return
  var hooked = 0
  for (ctor in declaredConstructors(base)) {
    runCatching {
      xposed.hook(ctor).intercept { chain ->
        val result = chain.proceed(chain.args.toTypedArray())
        markAll(runCatching { mList.get(chain.thisObject) as? List<*> }.getOrNull())
        result
      }
      hooked++
    }
  }
  if (hooked > 0) Log.d(TAG, "ParceledListSlice: $hooked hooked")
}

/**
 * PMS 生成图标信息的地方（system_server 进程）。在系统侧就打好标记，
 * 客户端拿到的一定是带标记的 id，覆盖面比在客户端补救大得多。
 */
private fun hookPackageInfoCommonUtils(xposed: XposedInterface, classLoader: ClassLoader) {
  val utils = classOf("com.android.internal.pm.parsing.PackageInfoCommonUtils", classLoader)
    ?: return
  val names =
    listOf(
      "generate",
      "generateApplicationInfo",
      "generateActivityInfo",
      "generateServiceInfo",
      "generateProviderInfo",
    )
  var hooked = 0
  for (name in names) {
    for (method in declaredMethods(utils, name)) {
      runCatching {
        xposed.hook(method).intercept { chain ->
          val result = chain.proceed(chain.args.toTypedArray())
          markInfo(result)
          result
        }
        hooked++
      }
    }
  }
  if (hooked > 0) Log.d(TAG, "PackageInfoCommonUtils: $hooked hooked")
}

/**
 * 最近任务 / 概览里的卡片图标。`TaskIconCache` 先把图标读成 `BitmapDrawable`，
 * 再包成 `BitmapInfo`；在这个入口上换成裁好的圆形即可。
 */
private fun hookTaskIcons(xposed: XposedInterface, classLoader: ClassLoader) {
  val cache = classOf("com.android.quickstep.TaskIconCache", classLoader) ?: return
  var hooked = 0
  for (method in declaredMethods(cache, "getBitmapInfo")) {
    runCatching {
      xposed.hook(method).intercept { chain ->
        val args = chain.args
        val index = args.indexOfFirst { it is Drawable }
        if (index < 0) return@intercept chain.proceed(args.toTypedArray())
        val replaced = args.toMutableList()
        replaced[index] = clipToCircle(args[index] as Drawable)
        chain.proceedWith(chain.thisObject, replaced.toTypedArray())
      }
      hooked++
    }
  }
  if (hooked > 0) Log.d(TAG, "TaskIconCache: $hooked hooked")
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
private fun hookPixelLauncher(xposed: XposedInterface, classLoader: ClassLoader) {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return

  // Launcher3 的类在**不同 ROM 上包名不同**：
  // - 类原生 / AOSP（LineageOS、Sony 等）：进程 `com.android.launcher3`，
  //   内部类沿用 `com.android.launcher3.*`
  // - Pixel / Nexus Launcher：进程 `com.google.android.apps.nexuslauncher`，
  //   内部类**可能**被重打包到 `com.google.android.apps.nexuslauncher.*`
  // 两个都试一遍，哪个存在用哪个。
  val launcherPkgs = listOf("com.android.launcher3", "com.google.android.apps.nexuslauncher")
  val baseIconFactoryClass =
    firstClassOf(launcherPkgs.map { "$it.icons.BaseIconFactory" }, classLoader)
      ?: run {
        Log.w(TAG, "Launcher: BaseIconFactory not found")
        return
      }
  val iconOptionsClass =
    firstClassOf(
      launcherPkgs.map { pkg -> pkg + ".icons.BaseIconFactory" + '$' + "IconOptions" },
      classLoader,
    )
      ?: run {
        Log.w(TAG, "Launcher: BaseIconFactory.IconOptions not found")
        return
      }
  val drawFullBleedField =
    fieldOf(iconOptionsClass, "drawFullBleed")
      ?: run {
        // 这个开关是承重的：拿不到它，桌面图标四角会出现黑色方角。留痕。
        Log.w(TAG, "Launcher: IconOptions.drawFullBleed not found")
        return
      }

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
  if (hooked > 0) Log.d(TAG, "PixelLauncher: $hooked createBadgedIconBitmap hooked")
}

/**
 * 冷启动 splash 屏上的那张图标。
 *
 * 系统会分析图标的背景色，背景透明时它判定"图标没有可当背景的部分"，只画不透明区域。
 * 我们把图标裁成了圆（圆外透明），正好落进这个判定。强制标记"背景是复杂的"，
 * 让系统把整张图标画出来。
 *
 * ⚠ ROM 相关：Sony（XQ-DQ72 / Android 16）的 SystemUI 里这套 wm.shell 类被 R8 削成了
 * 空壳 —— `IconColor` 只剩 5 个字段，**连 `<init>` 都没有**（`ctors=0`），所以永远不会被
 * 实例化，这条 hook 在那里是空操作（钩子注册数为 0 是正常的，不是 bug）。
 * 参考项目在这台机器上同样如此。类完整的 ROM 上会正常注册。
 */
private fun hookSplashScreenIcon(xposed: XposedInterface, classLoader: ClassLoader) {
  val iconColor =
    classOf(
      "com.android.wm.shell.startingsurface.SplashscreenContentDrawer\$ColorCache\$IconColor",
      classLoader,
    )
      ?: run {
        Log.w(TAG, "SplashScreen: IconColor class not found")
        return
      }
  val mBgColor = fieldOf(iconColor, "mBgColor")
  val mIsBgComplex = fieldOf(iconColor, "mIsBgComplex")
  // 失败必须留痕：这里曾经静默 return，查了半天才发现是 ROM 侧类被削过。
  if (mBgColor == null || mIsBgComplex == null) {
    Log.w(TAG, "SplashScreen: fields not found, skip")
    return
  }
  val ctors = declaredConstructors(iconColor)
  var hooked = 0
  var lastError: Throwable? = null
  for (ctor in ctors) {
    runCatching {
        xposed.hook(ctor).intercept { chain ->
          val result = chain.proceed(chain.args.toTypedArray())
          runCatching {
            if (mIsBgComplex.getBoolean(chain.thisObject)) return@runCatching
            if (mBgColor.getInt(chain.thisObject) == 0) {
              mIsBgComplex.setBoolean(chain.thisObject, true)
            }
          }
          result
        }
        hooked++
      }
      .onFailure { lastError = it }
  }
  if (hooked > 0) Log.d(TAG, "SplashScreen: $hooked hooked")
  else Log.w(TAG, "SplashScreen: ctors=${ctors.size} hooked=0 err=${lastError?.message}")
}

/**
 * Android 15+ 的设置页会把图标再过一遍 `Utils.getAdaptiveIcon()`：非自适应图标会被它
 * **套上系统自己的形状（含系统自带的背景板）**。
 *
 * 这里在系统处理之前就把图标换成已经是 `AdaptiveIconDrawable` 的形态 —— 系统看到
 * "已经是 adaptive" 就不会再套它自己的那一层，图标按我们给的样子呈现。
 *
 * 必须 `deoptimize()`：这个方法会被内联优化掉，不 deoptimize 的话 hook 根本不会触发
 * （实测加之前日志里一次都没命中）。
 */
private fun hookSettingsAdaptiveIcon(xposed: XposedInterface, classLoader: ClassLoader) {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
  val utils = classOf("com.android.settings.Utils", classLoader) ?: return
  var hooked = 0
  for (method in declaredMethods(utils, "getAdaptiveIcon")) {
    runCatching {
      xposed.deoptimize(method)
      xposed.hook(method).intercept { chain ->
        val args = chain.args
        val sig = args.joinToString { it?.javaClass?.simpleName ?: "null" }
        val index = args.indexOfFirst { it is Drawable }
        if (index < 0) {
          Log.d(TAG, "Settings.getAdaptiveIcon($sig) no-drawable")
          return@intercept chain.proceed(args.toTypedArray())
        }
        val inIcon = args[index] as Drawable
        Log.d(
          TAG,
          "Settings.getAdaptiveIcon($sig) idx=$index in=${inIcon.javaClass.simpleName} adaptive=${inIcon is AdaptiveIconDrawable}",
        )
        val replaced = args.toMutableList()
        replaced[index] = clipToCircle(inIcon)
        chain.proceedWith(chain.thisObject, replaced.toTypedArray())
      }
      hooked++
    }
  }
  if (hooked > 0) Log.d(TAG, "Settings: $hooked getAdaptiveIcon hooked (deoptimized)")
}

/**
 * 电池用量页的图标**不走** `PackageManager`，也不走 `Resources.getDrawable`：
 * 它先把图标装进 `BatteryDiffEntry.mAppIcon`（旧版是 `BatteryEntry.mIcon`），
 * UI 再从 `getAppIcon()` 或者直接从字段里取。整条链上没有任何我们挂过的出口 ——
 * 实测打开电池页时 `clipToCircle` 一次都没被调用（trace 日志 0 条）。
 *
 * 所以这里对着这两个类直接下手：
 * - `getAppIcon()`：包装返回值
 * - `loadLabelAndIcon()` / `loadNameAndIcon()`：跑完之后把字段里的图标也换掉，
 *   这样"直接读字段"的地方同样是圆的
 */
private fun hookBatteryIcons(xposed: XposedInterface, classLoader: ClassLoader) {
  var hooked = 0

  val diffEntry =
    classOf("com.android.settings.fuelgauge.batteryusage.BatteryDiffEntry", classLoader)
  if (diffEntry != null) {
    for (name in listOf("getAppIcon", "getBadgeIconForUser")) {
      for (method in declaredMethods(diffEntry, name)) {
        runCatching {
          runCatching { xposed.deoptimize(method) }
          xposed.hook(method).intercept { chain ->
            val icon = chain.proceed(chain.args.toTypedArray()) as? Drawable
            if (icon == null) null else clipToCircle(icon)
          }
          hooked++
        }
      }
    }
    for (name in listOf("loadLabelAndIcon", "loadNameAndIconForUid")) {
      for (method in declaredMethods(diffEntry, name)) {
        runCatching {
          runCatching { xposed.deoptimize(method) }
          xposed.hook(method).intercept { chain ->
            val result = chain.proceed(chain.args.toTypedArray())
            wrapIconField(chain.thisObject, "mAppIcon")
            result
          }
          hooked++
        }
      }
    }
  }

  val entry = classOf("com.android.settings.fuelgauge.batteryusage.BatteryEntry", classLoader)
  if (entry != null) {
    for (method in declaredMethods(entry, "loadNameAndIcon")) {
      runCatching {
        runCatching { xposed.deoptimize(method) }
        xposed.hook(method).intercept { chain ->
          val result = chain.proceed(chain.args.toTypedArray())
          wrapIconField(chain.thisObject, "mIcon")
          result
        }
        hooked++
      }
    }
  }

  if (hooked > 0) Log.d(TAG, "Battery: $hooked hooked")
}

/**
 * 把 `owner` 里叫 `name` 的 Drawable 字段换成裁圆后的版本。
 *
 * 已经是 `AdaptiveIconDrawable` 的不动 —— 自己包出来的 [CircleIconDrawable] 也是 adaptive，
 * 所以这个判断同时避免了对同一个图标反复包。
 */
private fun wrapIconField(owner: Any?, name: String) {
  if (owner == null) return
  runCatching {
    val field = fieldOf(owner.javaClass, name) ?: return
    val icon = field.get(owner) as? Drawable ?: return
    if (icon !is AdaptiveIconDrawable) field.set(owner, clipToCircle(icon))
  }
}

private fun hookShortcutIcons(xposed: XposedInterface) {
  var hooked = 0
  for (method in declaredMethods(LauncherApps::class.java, "getShortcutIconDrawable")) {
    runCatching {
      xposed.hook(method).intercept { chain ->
        val icon = chain.proceed(chain.args.toTypedArray()) as? Drawable ?: return@intercept null
        clipToCircle(icon)
      }
      hooked++
    }
  }
  if (hooked > 0) Log.d(TAG, "Shortcut: $hooked hooked")
}

private fun markAll(items: Iterable<*>?) {
  val list = items ?: return
  runMarkingIcons {
    for (item in list) markInfo(item)
  }
}

private fun markInfo(info: Any?) {
  when (info) {
    is PackageInfo ->
      runMarkingIcons {
        info.applicationInfo?.let(::markIcon)
        info.activities?.forEach(::markIcon)
        info.services?.forEach(::markIcon)
        info.providers?.forEach(::markIcon)
      }
    is PackageItemInfo -> runMarkingIcons { markIcon(info) }
    is ResolveInfo -> runMarkingIcons { markResolveInfo(info) }
    // 无障碍服务列表：服务信息藏在 resolveInfo 里
    is AccessibilityServiceInfo -> runMarkingIcons { markResolveInfo(info.resolveInfo) }
    else -> {
      if (info == null) return
      runMarkingIcons { markNestedInfo(info) }
    }
  }
}

/**
 * 外层对象本身不是 `PackageItemInfo`、图标信息**藏在字段里**的那些类型。
 *
 * 只按外层类型判断的话，这些列表一个都覆盖不到 —— 最近任务、冷启动 splash
 * 传的正是这类对象：
 * - `TaskInfo.topActivityInfo`：`RunningTaskInfo` / `RecentTaskInfo` 都继承它
 *   （最近任务 / 概览 / 分屏选择器）
 * - `LaunchActivityItem.mInfo`：启动 Activity 时带的那份 `ActivityInfo`，
 *   **冷启动 splash 屏上的图标就是从这里来的**
 * - `LauncherActivityInfoInternal.mActivityInfo`：`LauncherApps` 内部传递用
 *
 * 一律用"类名字符串 + 反射字段"取，不写 `is TaskInfo` 这种直接引用 ——
 * 这些类在旧版本设备上不存在，直接引用会在类加载时炸掉。
 */
private val nestedInfoFields by lazy {
  listOf(
      "android.app.TaskInfo" to "topActivityInfo",
      "android.app.servertransaction.LaunchActivityItem" to "mInfo",
      "android.content.pm.LauncherActivityInfoInternal" to "mActivityInfo",
    )
    .mapNotNull { (className, fieldName) ->
      runCatching {
        val clazz = Class.forName(className)
        clazz to (fieldOf(clazz, fieldName) ?: throw NoSuchFieldException(fieldName))
      }
        .getOrNull()
    }
}

private fun markNestedInfo(info: Any) {
  for ((clazz, field) in nestedInfoFields) {
    if (!clazz.isInstance(info)) continue
    val inner = runCatching { field.get(info) }.getOrNull() ?: continue
    markInfo(inner)
  }
}

private fun markIcon(info: PackageItemInfo) {
  // 快捷设置磁贴画的是小尺寸单色图形，不是应用图标。
  if (info is ServiceInfo && info.permission == Manifest.permission.BIND_QUICK_SETTINGS_TILE) return
  markIconResId(info)
  // 组件自己没声明图标时，系统会回退到 `applicationInfo.icon` —— 那个 id 走的是
  // 同一条解析路径，也得打标记。参考项目的 `componentInfosTransform` 同样把这一层
  // 一起处理（itemInfos + applicationInfo）；漏掉它，这类"图标为 0 的组件"就是方的。
  if (info is ComponentInfo) info.applicationInfo?.let(::markIconResId)
}

private fun markIconResId(info: PackageItemInfo) {
  val icon = info.icon
  if (icon != 0 && icon.isAppResource()) info.icon = icon.marked()
}

private fun markResolveInfo(info: ResolveInfo?) {
  val resolveInfo = info ?: return
  // 组件自己的 icon 在构造时已经打过标记，这里原样继承即可。
  val component =
    resolveInfo.activityInfo ?: resolveInfo.serviceInfo ?: resolveInfo.providerInfo ?: return
  val icon = component.icon
  if (icon == 0) return
  resolveInfo.icon = icon
  setIntField(resolveInfo, "iconResourceId", icon)
}

private fun declaredMethods(clazz: Class<*>, name: String): List<Method> =
  clazz.declaredMethods.filter { it.name == name }.onEach { it.isAccessible = true }

private fun declaredConstructors(clazz: Class<*>): List<Constructor<*>> =
  clazz.declaredConstructors.toList().onEach { it.isAccessible = true }

private fun classOf(name: String, classLoader: ClassLoader): Class<*>? =
  runCatching { Class.forName(name, true, classLoader) }.getOrNull()

/**
 * 按候选名依次尝试，返回**第一个存在**的类。
 *
 * Launcher3 的内部类在不同 ROM 上包名不同（AOSP 的 `com.android.launcher3.*` vs
 * Pixel 的 `com.google.android.apps.nexuslauncher.*`），所以类名不能写死一个。
 */
private fun firstClassOf(names: List<String>, classLoader: ClassLoader): Class<*>? =
  names.firstNotNullOfOrNull { classOf(it, classLoader) }

/**
 * 按名字找字段，**沿父类链往上找**（与参考项目的 `field()` 一致）。
 *
 * ROM 侧的类是会被 R8 改写的：字段可能被挪到父类、方法可能被内联。只查本类
 * `getDeclaredField` 会漏掉这些情况，而且漏掉时是**静默**的 —— 不报错、不打日志。
 */
private fun fieldOf(clazz: Class<*>, name: String): Field? {
  var current: Class<*>? = clazz
  while (current != null && current != Any::class.java) {
    val found = runCatching { current!!.getDeclaredField(name) }.getOrNull()
    if (found != null) return found.apply { isAccessible = true }
    current = current.superclass
  }
  return null
}

private fun setIntField(obj: Any, name: String, value: Int) = runCatching {
  obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(obj, value)
}

/** 读 int 字段；字段在旧版本 / 被 R8 改过的类上可能不存在，取不到就返回 null。 */
private fun getIntField(obj: Any, name: String): Int? = runCatching {
  obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(obj) as Int
}
  .getOrNull()
