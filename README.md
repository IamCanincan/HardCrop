# HardCrop

一个只做一件事的 Xposed 模块：**把非自适应图标强制裁成圆形**，让它们和系统里被统一形状处理过的自适应图标看起来一致。

- 只处理「非自适应」图标。已经是 `AdaptiveIconDrawable` 的图标交给系统，模块不碰。
- 圆形来自系统的自适应图标 mask（本机上是正圆），不铺底色、不改图标内容大小。
- 不依赖任何图标包。模块本身无需配置（应用内的界面只提供作用域清单、启用步骤与检查更新）。

## 安装

1. 安装 `app-release.apk`（或用源码自行构建）。
2. 在 LSPosed 里启用模块。作用域由模块自己声明（`staticScope=true`），管理器里**只能**勾选
   这 6 个进程，`scope.list` 之外的一个也加不进去 —— 想加别的应用会被框架直接拒绝：

   ```
   android                          系统服务，解析应用信息
   com.android.launcher3            桌面与应用抽屉（效果最直观，必选）
   com.android.systemui             状态栏与最近任务
   com.android.settings             设置里的应用列表
   com.android.intentresolver       分享与打开方式的选择列表
   com.android.permissioncontroller 权限弹窗里的应用图标
   ```

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
PixelLauncher: 2 createBadgedIconBitmap hooked in com.android.launcher3  （仅 launcher3 进程）
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
3. `CircleIconDrawable` 继承 `AdaptiveIconDrawable`：**原图标放在 background 层**，
   foreground 用 `ColorDrawable(TRANSPARENT)` 占位。这里有三个反直觉的点：
   - 内容必须放在 background（或 foreground）层里，因为 launcher 按自适应语义**只取
     `getBackground()` / `getForeground()` 分别绘制**，它根本不会调用我们的 `draw()`。
     把内容画在重写的 `draw()` 里 = launcher 永远看不到（表现就是一个纯黑的圆）。
   - 父类会把每一层的 bounds 设成 `1.5 × view bounds`，mask 只保留中心的 view bounds。
     所以 `CenteredIconDrawable` 要在这个 1.5× 区域里把原图标画到**中心 2/3**，
     正好等于最终 view bounds —— 经 mask 裁切后就是 1:1，既不放大也不留边。
   - 形状直接交给系统 mask（`config_icon_mask`）决定，不自己再裁一次 ——
     双重裁切会把圆角方形之类的系统形状又裁成内切圆。
4. **Android 16+** 额外 hook `BaseIconFactory.createBadgedIconBitmap`（Pixel Launcher），
   把 `IconOptions.drawFullBleed` 设成 `false`，让 launcher 不再加自己的白圆背景板。
   旧版 Android 没有这个开关，hook 自动 no-op。

## API 102（libxposed）合规性

对照 LSPosed 框架的加载逻辑核验过：

- 声明 `minApiVersion=102` / `targetApiVersion=102`。框架只按 `targetApiVersion` 决定加载方式
  （>= 101 即走 `META-INF/xposed/java_init.list` 的现代路径，= 100 已被弃用），
  `minApiVersion` 在框架侧不参与判断。
- 只调用 `io.github.libxposed.api.*`。API 102 会屏蔽 legacy 的 `de.robv.android.xposed.*`，
  本模块没有任何 legacy 调用，dex 内也检索不到该包名；也不依赖 `hiddenapibypass`。
- `staticScope=true`：作用域由 `META-INF/xposed/scope.list` 固定声明，管理器里只能勾选这
  6 个进程。实测往里加别的应用会被框架直接拒绝：

  ```
  Error: com.iamcanincan.hardcrop fixes its scope in module.prop, so
  com.iamcanincan.noticon cannot be added. It claims: android,
  com.android.launcher3, com.android.systemui, com.android.settings,
  com.android.intentresolver, com.android.permissioncontroller.
  ```

  这条报错就是判定静态作用域真正生效的依据。
- `autoHotReload=false`：改动代码后必须重启目标进程，框架不会热加载。
- 框架开启 dex 混淆时只改写框架自己的隐藏包名，不会动
  `com.iamcanincan.hardcrop.XposedMain`，入口类名由 proguard `-keep` 保证不被 R8 改名。
  注意 `META-INF/xposed/java_init.list` 里写的也是全限定名，改包名时三处
  （`namespace`/`applicationId`、proguard `-keep`、`java_init.list`）必须一起改。

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

**Android 16+（BAKLAVA）的 Pixel Launcher 有一个隐藏开关 `IconOptions.drawFullBleed`**
—— `BaseIconFactory.createBadgedIconBitmap` 用它决定要不要在 launcher 内部再给图标加一层
白色背景板并把内容缩到 safe zone。本模块额外 hook 了 `createBadgedIconBitmap`，**把
`drawFullBleed` 强制设成 `false`**：launcher 不再加自己的背景板，按图标原样画 full-bleed，
我们的 `CircleIconDrawable`（圆形 + 内容填满、圆外透明）就直接呈现在桌面。

**怎么验证它生效**：日志里会出现 `PixelLauncher: 2 createBadgedIconBitmap hooked in
com.android.launcher3`（launcher3 进程里有两个 `createBadgedIconBitmap` 重载被挂上）。
之后看抽屉 —— **所有非自适应图标都会变成圆形 + 内容填满、圆外透明**，跟自适应图标观感一致。

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
  403（限流）／404（仓库还没发过 Release，这属于正常状态，不是故障）

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
