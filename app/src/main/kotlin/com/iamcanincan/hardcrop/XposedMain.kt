package com.iamcanincan.hardcrop

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class XposedMain : XposedModule() {

  override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
    if (!param.isFirstPackage) return
    hookIcons(this, param)
  }
}
