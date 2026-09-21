import com.android.build.api.dsl.ApplicationExtension
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

extensions.configure<ApplicationExtension> {
  namespace = "com.iamcanincan.hardcrop"
  compileSdk = 37

  buildFeatures {
    compose = true
    // AGP 8+ 起默认不生成 BuildConfig；界面的"关于"与"检查更新"要读 VERSION_NAME，
    // 与其在 strings.xml 里再抄一份版本号，不如让构建来生成唯一真相。
    buildConfig = true
  }

  defaultConfig {
    applicationId = "com.iamcanincan.hardcrop"
    minSdk = 27
    targetSdk = 37
    versionCode = 7
    versionName = "1.0.7"
  }

  buildTypes {
    getByName("release") {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Ship a signed apk without maintaining a release keystore.
      signingConfig = signingConfigs.getByName("debug")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  dependenciesInfo {
    includeInApk = false
    includeInBundle = false
  }

  lint {
    // 见 app/lint.xml：项目要求 0 warning，被显式忽略的两条都在文件里写了原因。
    lintConfig = file("lint.xml")
    abortOnError = true
    checkAllWarnings = true
  }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
  // 模块的挂钩逻辑：只在框架注入后运行，编译期不需要打进 APK。
  compileOnly(libs.libxposed.api)

  // 设置界面：Material 3 Expressive（Compose）。版本由 BOM 统一托管。
  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.compose.ui.graphics)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.compose.material3)
  implementation(libs.compose.material.icons.extended)
  implementation(libs.activity.compose)
  implementation(libs.graphics.shapes)
  debugImplementation(libs.compose.ui.tooling)
}
