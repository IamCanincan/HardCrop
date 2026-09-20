package com.hardcrop.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hardcrop.ui.theme.HardCropTheme

/**
 * 设置界面。这个 Activity 同时承担两件事：
 * 1. 给用户看模块的作用域、启用步骤与验证方式；
 * 2. 让模块有 MAIN + LAUNCHER 入口 —— 否则它是纯后台模块，桌面上根本不会出现图标。
 */
class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent { HardCropTheme { HomeScreen() } }
  }
}
