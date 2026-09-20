package com.iamcanincan.hardcrop

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * 模块入口，由 `META-INF/xposed/java_init.list` 指向。
 *
 * ## 元数据落在哪（LibXposed / API 102）
 * | 内容 | 位置 |
 * |---|---|
 * | 模块名 | AndroidManifest 的 `android:label` |
 * | 模块描述 | AndroidManifest 的 `android:description`（管理器读 `ApplicationInfo.descriptionRes`）|
 * | 作用域 | `META-INF/xposed/scope.list` |
 * | 模块配置 | `META-INF/xposed/module.prop` |
 *
 * `module.prop` 里**不要**写 `description=`——那是给 API <= 93 旧框架用的写法，
 * 留着会让管理器把模块当「兼容模式」处理（作用域退化成列出全部已装应用）。
 *
 * ## 为什么 module.prop 一行注释都没有
 * `META-INF/xposed/` 下的文件是**原样打进 APK** 的（AAPT2 不处理非 res 目录），
 * 写在里面的任何文字用户解包就能看到。字段说明一律放在本文件的 KDoc 或 README。
 *
 * ## 各字段的含义
 * - `staticScope=true`：作用域固定为 `scope.list` 里那 7 个进程，管理器拒绝勾选清单外的应用。
 *   模块只替换图标的加载结果，对没声明的进程没有任何作用，勾了只会误导。
 * - `autoHotReload=false`（框架默认值，显式写出来）：更新 APK 不会原地换 hook，
 *   改完代码必须重启目标进程。本模块 hook 的是 launcher / systemui 这类常驻进程，
 *   热加载会让新旧 hook 状态混在一起。
 * - `exceptionMode=protective`（框架默认值，显式写出来）：hook 里抛出的异常被框架吞掉，
 *   不让单个图标加载失败带崩 launcher / systemui。
 */
class XposedMain : XposedModule() {

  override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
    if (!param.isFirstPackage) return
    hookIcons(this, param)
  }
}
