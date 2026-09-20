# HardCrop

一个只做一件事的 Xposed 模块：**把非自适应图标强制裁成圆形**，让它们和系统里被统一形状处理过的自适应图标看起来一致。

- 只处理「非自适应」图标。已经是 `AdaptiveIconDrawable` 的图标交给系统，模块不碰。
- 圆外部分保持透明（`DST_IN` 圆形遮罩 + 抗锯齿边缘），不铺底色、不改图标内容大小。
- 不依赖任何图标包，也不需要任何配置界面，装上启用即生效。

## 安装

1. 安装 `app-release.apk`（或用源码自行构建）。
2. 在 LSPosed 里勾选模块作用域，建议按 `scope.list` 全选：
   `system`、`com.android.systemui`、`com.android.settings`、`com.android.launcher3`、
   `com.google.android.apps.nexuslauncher`、`com.android.intentresolver`、
   `com.android.permissioncontroller`、`com.google.android.settings.intelligence`、
   `com.google.android.apps.wellbeing`
3. **重启**（LSPosed 不会热加载模块）。

> 作用域要覆盖所有会解析图标的进程。模块会给应用图标的资源 id 打标记，只有被标记的进程
> 才认得这个标记；漏掉的进程会拿到无法解析的 id。

## 验证

```bash
adb logcat -s HardCrop
```

正常时每个作用域进程会打印：

```
onPackageReady com.android.settings, sdk 37
Hooked com.android.settings
```

如果只看到 `No icon loader is found, nothing is hooked`，说明当前系统里两个加载通道
（`Resources.getDrawableForDensity` 与 `ApplicationPackageManager.getDrawableInternal`）都没挂上，
模块会主动放弃打标记，此时不会有任何图标被改动（也不会弄坏图标）。

## 原理

1. 在 `ApplicationInfo` / `ActivityInfo` / `ServiceInfo` / `ProviderInfo` / `ResolveInfo` 构造完成后，
   把图标资源 id 从 `0x7f……` 挪到 `0x6e……`（一个不会有真实资源的 package id）作为标记。
2. 在 `Resources.getDrawableForDensity()`（以及新版本上的 `ApplicationPackageManager.getDrawableInternal()`）
   出口认出这个标记，换回原始 id 调用原方法拿到图标，再包成 `CircleIconDrawable` 返回。
3. `CircleIconDrawable` 继承 `InsetDrawable`（inset 为 0），把原图标画进离屏位图，
   用圆形路径以 `DST_IN` 裁掉圆外像素，最后把位图画回画布。

## API 102（libxposed）合规性

对照 LSPosed 框架的加载逻辑核验过：

- 声明 `minApiVersion=102` / `targetApiVersion=102`。框架只按 `targetApiVersion` 决定加载方式
  （>= 101 即走 `META-INF/xposed/java_init.list` 的现代路径，= 100 已被弃用），
  `minApiVersion` 在框架侧不参与判断。
- 只调用 `io.github.libxposed.api.*`。API 102 会屏蔽 legacy 的 `de.robv.android.xposed.*`，
  本模块没有任何 legacy 调用，dex 内也检索不到该包名；也不依赖 `hiddenapibypass`。
- `staticScope=false`：作用域不写死，可在管理器里自由勾选（若为 true，框架会强制限定在
  `scope.list` 内的应用上）。
- `autoHotReload=false`：改动代码后必须重启目标进程，框架不会热加载。
- 框架开启 dex 混淆时只改写框架自己的隐藏包名，不会动 `com.hardcrop.XposedMain`，
  入口类名由 proguard `-keep` 保证不被 R8 改名。

## 已知取舍

- 快捷设置磁贴（`BIND_QUICK_SETTINGS_TILE`）画的是小尺寸单色图形，被排除在外。
- 从 `ConstantState.newDrawable()` 恢复出来的副本仍然是圆的（模块自己实现了 `ConstantState`）。
- 通知栏小图标、快捷方式以外的小图标走的是别的资源，不受影响。
- **生效进程**：模块挂钩应用图标的资源加载通道，已在 launcher 桌面与应用抽屉上
  实测可见（所有非自适应图标都变成正圆）。**设置页/分享菜单里的应用列表项走的是
  `system_server` 里 PMS 缓存的 Bitmap 路径**，而 `system_server` 在 boot 时启动往往
  早于 LSPosed daemon，未被注入，这一处看到的还是原图 —— 这是 LSPosed/Zygisk 启动时机的限制。
  重启一次让 `system_server` 也能进入注入、或者只关心 launcher 桌面/抽屉的话，效果完整。

## 关于 AOSP Launcher3（Pixel / Quickstep 自带桌面）

**实测发现**：在 Android 12+ 的 AOSP Launcher3 上，launcher 会**对所有桌面图标**（包括自适应图标
如 Chrome / Gmail / 系统应用等）**强制应用 launcher 自带的白圆背景板 + safe zone 缩小**——
这是 launcher 自身的"统一图标形状"渲染流程，发生在 launcher 拿到 Drawable 之后，与模块无关。

直接验证方式：
1. 禁用本模块（`/data/adb/lspd/cli modules disable com.hardcrop`），重启 launcher3，打开抽屉
2. 启用本模块，重启 launcher3，打开抽屉
3. 两张截图对比 —— 如果两张图看起来一模一样、且所有图标（包括自适应图标）都是"白圆 + 缩小"，
   就说明白圆是 launcher 加的，不是模块加的。

这就是说：
- 在 AOSP Launcher3 上，**所有图标**看起来都是"白圆 + 缩小"，自适应图标也不例外；
  本模块让非自适应图标**和自适应图标一模一样**地呈现。
- 但用户常说的"和自适应图标一样"如果指的是 adaptive 图标**本身**那种"图标填满圆形、无白边"的观感，
  **那在 AOSP Launcher3 上没有任何桌面图标能呈现**——AOSP launcher 给所有图标都加了同一块白圆板。

要看到模块让"非自适应图标填满圆形、无白边"的真正效果，**换用第三方桌面**：
Lawnchair / Nova Launcher / Action Launcher / Niagara / Smart Launcher / Microsoft Launcher 等
不强制"统一图标形状"背景板的桌面都可以。在这些桌面上，模块的圆形裁切会原样生效，
图标填满圆形、圆外透明，与自适应图标的视觉表现完全一致。

如果坚持 AOSP Launcher3，可以试关闭系统的"Themed icons"（部分 OEM 在壁纸与样式里有开关）——
但 AOSP Launcher3 的白圆板**仍会**显示，所以"无白边"仍然做不到。

## 构建

```bash
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
./gradlew lintDebug
```

需要 Android SDK 37（在 `local.properties` 里配好 `sdk.dir`）与 JDK 17+。

## 许可

MIT —— 见 [LICENSE](LICENSE)。
