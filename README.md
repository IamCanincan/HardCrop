# HardCrop

一个只做一件事的 Xposed 模块：**把非自适应图标强制裁成圆形**，让它们和系统里被统一形状处理过的自适应图标看起来一致。

- 只处理「非自适应」图标。已经是 `AdaptiveIconDrawable` 的图标交给系统，模块不碰。
- 圆形来自系统的自适应图标 mask（本机上是正圆），不铺底色、不改图标内容大小。
- 不依赖任何图标包。模块本身无需配置（应用内的界面只提供作用域清单、启用步骤与检查更新）。

## 安装

1. 从 [Releases](https://github.com/IamCanincan/HardCrop/releases) 下载最新 APK 并安装
   （或按下面的「构建」自行编译）。
2. 在 LSPosed 里启用模块。作用域由模块自己声明（`staticScope=true`），管理器里**只能**勾选
   这 7 个进程，`scope.list` 之外的一个也加不进去 —— 想加别的应用会被框架直接拒绝：

   ```
   android                                 系统服务，解析应用信息
   com.android.launcher3                   桌面与应用抽屉（类原生 ROM，必选）
   com.google.android.apps.nexuslauncher   桌面与应用抽屉（Pixel / Nexus，必选）
   com.android.systemui                    状态栏与最近任务
   com.android.settings                    设置里的应用列表
   com.android.intentresolver              分享与打开方式的选择列表
   com.android.permissioncontroller        权限弹窗里的应用图标
   ```

   两个桌面进程按 ROM 二选一即可：类原生 / AOSP 用 `com.android.launcher3`，
   Pixel / Nexus 用 `com.google.android.apps.nexuslauncher`。勾选不存在的那个
   不会报错，只是没有进程会加载它。

3. **重启**（LSPosed 不会热加载模块）。

> 作用域要覆盖所有会解析图标的进程。模块会给应用图标的资源 id 打标记，只有被标记的进程
> 才认得这个标记；漏掉的进程会拿到无法解析的 id。
>
> 之所以把作用域写死：模块只替换图标的加载结果，对清单外的进程没有任何作用，
> 让用户能勾选反而是一种误导。

## 验证

```bash
adb logcat -s HardCrop
```

正常时每个作用域进程会打印：

```
onPackageReady com.android.settings, sdk 36
PixelLauncher: 2 createBadgedIconBitmap hooked in com.android.launcher3  （仅桌面进程；
Pixel 上进程名是 com.google.android.apps.nexuslauncher）
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
   `android.R.drawable.sym_def_app_icon`（解析不出应用图标时用的兜底）和
   `getArchivedAppIcon`（归档应用）也会被同样裁圆。
3. **批量通道**：上面两步只能盖到"逐个拿图标"的代码路径。Settings 应用列表、分享页、
   权限页拿到的图标是 PMS 一次性序列化过来的一整包 `PackageInfo` / `ResolveInfo`，
   走的是完全不同的路径：`Parcel.readTypedList` / `createTypedArray`、
   `BaseParceledListSlice` 构造、`PackageInfoCommonUtils.generate*Info`。只 hook 第 1 步
   的构造器的话，这些列表里的图标根本不会经过第 2 步的加载出口。
   这几条也都挂上同样的"打标记"逻辑，加上 `markingIcons` ThreadLocal 防重入，
   覆盖才完整。
4. **system_server 提前注入**：`XposedMain.onSystemServerStarting` 让模块在 PMS 启动前
   就装上，客户端拿到的 id 从头就是带标记的。`onPackageReady` 的"android"包名也走同一套
   装机函数，`installed` flag 防重复。
5. **最近任务 / 概览**：`com.android.quickstep.TaskIconCache.getBitmapInfo` 在把
   `BitmapDrawable` 包成 `BitmapInfo` 之前，先替换成 `clipToCircle` 的结果。
6. **冷启动 splash**：`SplashscreenContentDrawer$ColorCache$IconColor` 构造时如果
   发现背景是透明的，强制把 `mIsBgComplex` 标成 `true`，让系统把整张图标画出来
   而不是只画不透明区域（圆形图标圆外透明正好落进这个判定）。
7. **设置页自适应包装**（Android 15+）：`com.android.settings.Utils.getAdaptiveIcon`
   会把非自适应图标自己套一层形状，先把入参换成裁好的，它就原样返回。
8. **`CircleIconDrawable` 继承 `AdaptiveIconDrawable`，形状自己画圆，不依赖系统 mask**。
   `RoundedIconDrawable`（background 层）在自己 bounds（1.5× view）内把原图标 cover 到
   中心 2/3（= 最终 view bounds 大小），再用 `DST_IN` 在离屏位图上裁一个内切圆：
   - launcher 按 adaptive 语义只取 background / foreground 分别绘制，**不调我们的 `draw()`**，
     所以圆形必须在 background 的 `draw()` 里就画好。
   - 父类把 layer bounds 设成 `1.5 × view bounds`，里面画到中心 2/3 = 最终 view bounds
     大小，1:1 不放大。
   - 形状由我们定（正圆），不再看 `config_icon_mask`：mask 只能"保留"不能"凭空填出"
     圆外部分，而我们圆外本来透明 → 无论 ROM 的 mask 是圆是方是水滴，看到的都是圆。
9. **Android 16+**（BAKLAVA）额外 hook `BaseIconFactory.createBadgedIconBitmap`（Pixel / AOSP Launcher3），
   把 `IconOptions.drawFullBleed` 设成 `false`，让 launcher 不再加自己的白圆背景板。
   旧版 Android 没有这个开关，hook 自动 no-op。
10. **防重入**：`markingIcons`（打标记时用，防止生成 Info 的几条路径互相嵌套重复打）
    和 `replacingIcon`（图标加载时用，防止 `Resources` / `APM` 在同一条链上双层包装）
    两个 ThreadLocal，与参考实现的做法一致。

## API 102（libxposed）合规性

对照 LSPosed 框架的加载逻辑核验过：

- 声明 `minApiVersion=102` / `targetApiVersion=102`。框架只按 `targetApiVersion` 决定加载方式
  （>= 101 即走 `META-INF/xposed/java_init.list` 的现代路径，= 100 已被弃用），
  `minApiVersion` 在框架侧不参与判断。
- 只调用 `io.github.libxposed.api.*`。API 102 会屏蔽 legacy 的 `de.robv.android.xposed.*`，
  本模块没有任何 legacy 调用，dex 内也检索不到该包名；也不依赖 `hiddenapibypass`。
- `staticScope=true`：作用域由 `META-INF/xposed/scope.list` 固定声明，管理器里只能勾选这
  7 个进程。实测往里加别的应用会被框架直接拒绝：

  ```
  Error: com.iamcanincan.hardcrop fixes its scope in module.prop, so
  com.iamcanincan.noticon cannot be added. It claims: android,
  com.android.launcher3, com.google.android.apps.nexuslauncher,
  com.android.systemui, com.android.settings, com.android.intentresolver,
  com.android.permissioncontroller.
  ```

  这条报错就是判定静态作用域真正生效的依据。
- `autoHotReload=false`（框架默认值，显式写出）：改动代码后必须重启目标进程，框架不会热加载。
- `exceptionMode=protective`（框架默认值，显式写出）：hook 里抛出的异常由框架吞掉，
  不让单个图标加载失败带崩 launcher / systemui —— 这两个都是常驻关键进程。
- 框架开启 dex 混淆时只改写框架自己的隐藏包名，不会动
  `com.iamcanincan.hardcrop.XposedMain`，入口类名由 proguard `-keep` 保证不被 R8 改名。
  注意 `META-INF/xposed/java_init.list` 里写的也是全限定名，改包名时三处
  （`namespace`/`applicationId`、proguard `-keep`、`java_init.list`）必须一起改。

### 模块元数据分别落在哪

| 内容 | 位置 |
|---|---|
| 模块名 | `AndroidManifest` 的 `android:label`（`@string/appName`）|
| **模块描述** | `AndroidManifest` 的 `android:description`（`@string/xposed_description`）|
| 作用域 | `META-INF/xposed/scope.list` |
| 模块配置 | `META-INF/xposed/module.prop` |
| Java 入口 | `META-INF/xposed/java_init.list` |

管理器读的是 `ApplicationInfo.descriptionRes`，**不是** `module.prop` 里的 `description=`。
后者是 API <= 93 的旧写法，混着写会让管理器把模块当「兼容模式」处理，
作用域列表会退化成列出全部已装应用。Manifest 里也不放任何 `xposed*` meta-data。

另：`META-INF/xposed/` 下的文件是**原样打进 APK** 的（AAPT2 不处理非 res 目录），
所以 `module.prop` 里一行注释都没有 —— 写什么用户解包就能看到。字段说明放在
`XposedMain` 的 KDoc 和本文档。

## 已知取舍

- 快捷设置磁贴（`BIND_QUICK_SETTINGS_TILE`）画的是小尺寸单色图形，被排除在外。
- 通知栏小图标、快捷方式以外的小图标走的是别的资源，不受影响。
- **桌面图标 & 点击过渡动画**：`CircleIconDrawable.getConstantState()` 实现非 null，
  `FloatingIconView.getIconResult()` 取 `newDrawable()` 时不会 NPE，点击不崩（v1.0.2 → v1.0.3）。
- **设置页 / 分享菜单**：靠 `Parcel` / `BaseParceledListSlice` / `PackageInfoCommonUtils`
  这几条批量通道把 PMS 传来的整包列表打上标记。
- **system_server 注入时机**：boot 时 LSPosed daemon 经常晚于 `system_server`，
  此时 `/proc/<system_server pid>/maps` 里没有模块 → `onSystemServerStarting` 没机会执行。
  实际效果看 daemon 何时起来：本机 Sony 上 boot 时 system_server 没注入，但 Settings
  应用列表已经是圆形（说明客户端 `onPackageReady` 里的批量通道够用了）。
  重启设备或调整 LSPosed 启动时机可以改善。

## 关于 AOSP Launcher3（Pixel / Quickstep 自带桌面）

**Android 16+（BAKLAVA）的 Pixel Launcher 有一个隐藏开关 `IconOptions.drawFullBleed`**
—— `BaseIconFactory.createBadgedIconBitmap` 用它决定要不要在 launcher 内部再给图标加一层
白色背景板并把内容缩到 safe zone。本模块额外 hook 了 `createBadgedIconBitmap`，**把
`drawFullBleed` 强制设成 `false`**：launcher 不再加自己的背景板，按图标原样画 full-bleed，
我们的 `CircleIconDrawable`（圆形 + 内容填满、圆外透明）就直接呈现在桌面。

**怎么验证它生效**：日志里会出现 `PixelLauncher: 2 createBadgedIconBitmap hooked in
com.android.launcher3`（桌面进程里有两个 `createBadgedIconBitmap` 重载被挂上；
Pixel 上进程名显示为 `com.google.android.apps.nexuslauncher`）。
之后看抽屉 —— **所有非自适应图标都会变成圆形 + 内容填满、圆外透明**，跟自适应图标观感一致。

> Launcher3 的内部类（`BaseIconFactory` / `IconOptions`）在不同 ROM 上包名可能不同：
> 类原生 / AOSP 是 `com.android.launcher3.*`，Pixel / Nexus 可能被重打包到
> `com.google.android.apps.nexuslauncher.*`。模块会依次尝试这两个包名，命中哪个用哪个。

**旧版 Android（< 16）**：没有这个开关，本 hook 自动 no-op；模块只让 `CircleIconDrawable`
作为圆形 drawable 返回，是否能看到"无白边"取决于桌面：AOSP Launcher3 上旧行为（白圆 +
缩小）仍会出现；第三方桌面（Lawnchair / Nova / Niagara / Action / Smart / Microsoft）会按
图标原样显示，圆形 + 无白边。

## 应用界面

模块带一个 `MainActivity`（`MAIN` + `LAUNCHER`）—— 没有它模块就是纯后台的，桌面上根本
不会出现图标。界面本身只是说明性的：作用域清单、启用步骤、验证命令，以及检查更新。

## 检查更新

界面里的「检查更新」是**本应用唯一的联网行为**，而且只在点了按钮之后才发起，没有后台
轮询、没有统计上报；挂钩代码（跑在被注入进程里的部分）完全不联网。

请求按顺序试两个地址，第一个拿到有效响应就用它：

1. `https://api.github.com/repos/IamCanincan/HardCrop/releases/latest`
2. `https://gh-proxy.com/https://api.github.com/...`（GitHub 公共加速镜像）

第二条是给解析不到 `api.github.com` 的网络准备的回退。全部失败时报**第一条**（直连）的
失败原因 —— 那才是主通道的真实状况。结果分三种，因为对应的下一步动作完全不同：

- **有新版本** → 旁边多出一个「打开发布页」按钮（地址取响应里的 `html_url`，不自己拼）
- **已是最新** → 远端比本机旧也算这一类，说明装的是还没发布出去的构建，
  不该提示去「升级」到一个更老的版本
- **失败** → 区分「解析不了域名」「连接超时」「TLS 握手失败」，以及 GitHub 的
  403（限流）／404（仓库当前没有 Release，发版后不该再出现；保留这个分支是为了
  把「没发过版」和「网络故障」分开，不当成红色故障显示）

> 排查提示：如果 App 报「解析不了域名」，而 `adb shell` 里 `curl` 同一个地址是通的，
> 那不是 DNS 问题 —— 是 `netpolicy` 里这个 uid 的陈旧记录把它设成了 `REJECT_ALL`
> （记录是在 App 还没有 `INTERNET` 权限时建立的，`adb install -r` 不会刷新它）。
> `dumpsys netpolicy | grep <uid>` 看 `allowed` 里有没有 `RESTRICTED_MODE_PERMISSIONS`；
> **卸载后全新安装**即可重建，重启无效。

## 主题图标

应用图标提供了 `monochrome` 层（只留 HC 字形、不带底板）。Android 13+ 的启动器打开
「主题图标」后，会拿这一层按壁纸取色染色 —— 也就是说图标颜色会跟着壁纸走。
底板是固定的淡粉色 `#FCE4EC`（Android 不支持底板随壁纸变化）。

## 构建

```bash
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
./gradlew lintDebug
```

需要 Android SDK 37（在 `local.properties` 里配好 `sdk.dir`）与 JDK 17+。

## 许可

MIT —— 见 [LICENSE](LICENSE)。
