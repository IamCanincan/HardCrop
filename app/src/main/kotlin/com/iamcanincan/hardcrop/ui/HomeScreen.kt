package com.iamcanincan.hardcrop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iamcanincan.hardcrop.BuildConfig
import com.iamcanincan.hardcrop.R
import com.iamcanincan.hardcrop.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val REPO_URL = "https://github.com/IamCanincan/HardCrop"

@Composable
fun HomeScreen() {
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val uriHandler = LocalUriHandler.current
  val version = BuildConfig.VERSION_NAME
  val context = LocalContext.current
  var update by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

  Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item { HeroHeader() }
      item { StatusCard() }

      item { SectionLabel(text = stringResource(R.string.section_scope)) }
      item { ScopeGroup() }

      item { SectionLabel(text = stringResource(R.string.section_steps)) }
      item { StepsCard() }

      item { SectionLabel(text = stringResource(R.string.section_verify)) }
      item { VerifyCard(snackbarHostState) }

      item { SectionLabel(text = stringResource(R.string.section_notes)) }
      item { NotesCard() }

      item { SectionLabel(text = stringResource(R.string.section_update)) }
      item {
        UpdateCard(
          version = version,
          state = update,
          onCheck = {
            update = UpdateState.Checking
            scope.launch {
              // 联网不能跑在主线程，扔到 IO 再回来更新界面状态。
              val result = withContext(Dispatchers.IO) { UpdateChecker.check(version) }
              update = result.toState(context, version)
            }
          },
          onOpenPage = { url -> uriHandler.openUri(url) },
        )
      }

      item { SectionLabel(text = stringResource(R.string.section_about)) }
      item { AboutCard() }
    }
  }
}

/**
 * 顶栏：App 图标 + 名称 + 副说明，整块用 `primaryContainer` 色块。
 * 让第一屏有视觉重量（Material You Expressive 倾向），同时让 hero 不再和下面的卡片"齐平"。
 */
@Composable
private fun HeroHeader() {
  Surface(
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.primaryContainer,
    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
  ) {
    Column(modifier = Modifier.padding(20.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        // 在 primaryContainer 背景上，「HC」方块用 onPrimaryContainer 反色，更突出。
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f),
          modifier = Modifier.size(52.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Text(
              text = "HC",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = stringResource(R.string.appName),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = stringResource(R.string.hero_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
          )
        }
      }
      Spacer(modifier = Modifier.height(14.dp))
      // 第二行：版本 + MIT（同一行 chip，留出与正文区隔，又不显得空）。
      VersionChip()
    }
  }
}

/** Hero 内部的版本 chip：在 primaryContainer 上用 onPrimaryContainer 的低 alpha。 */
@Composable
private fun VersionChip() {
  Surface(
    shape = RoundedCornerShape(50),
    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f),
    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
  ) {
    Text(
      text = stringResource(R.string.hero_version_chip, BuildConfig.VERSION_NAME),
      style = MaterialTheme.typography.labelSmall,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
    )
  }
}

/**
 * 状态卡。模块本身是后台模块，没有 API 能读自己在 LSPosed 里的启用状态，
 * 所以这里只说明"去哪里确认"，不假装能显示开关状态。
 */
@Composable
private fun StatusCard() {
  Card(
    colors =
      CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    shape = MaterialTheme.shapes.large,
  ) {
    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.size(40.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.Default.Power,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(22.dp),
          )
        }
      }
      Spacer(modifier = Modifier.width(14.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = stringResource(R.string.status_title),
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = stringResource(R.string.status_body),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
      }
    }
  }
}

/** 分组小标题（Noticon 风格：小字、次要色、带左内边距）。 */
@Composable
private fun SectionLabel(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 4.dp),
  )
}

/** 作用域清单：一张卡片里 6 行，每行 = 图标方块 + 标题 + 描述 + 标签。 */
@Composable
private fun ScopeGroup() {
  val scopes =
    listOf(
      ScopeItem(
        Icons.Default.Android,
        R.string.scope_system_title,
        R.string.scope_system_desc,
        ScopeTag.RECOMMENDED,
      ),
      ScopeItem(
        Icons.Default.Apps,
        R.string.scope_launcher_title,
        R.string.scope_launcher_desc,
        ScopeTag.REQUIRED,
      ),
      ScopeItem(
        Icons.Default.Dashboard,
        R.string.scope_systemui_title,
        R.string.scope_systemui_desc,
        ScopeTag.RECOMMENDED,
      ),
      ScopeItem(
        Icons.Default.Settings,
        R.string.scope_settings_title,
        R.string.scope_settings_desc,
        ScopeTag.RECOMMENDED,
      ),
      ScopeItem(
        Icons.Default.Share,
        R.string.scope_intentresolver_title,
        R.string.scope_intentresolver_desc,
        ScopeTag.OPTIONAL,
      ),
      ScopeItem(
        Icons.Default.Security,
        R.string.scope_permissioncontroller_title,
        R.string.scope_permissioncontroller_desc,
        ScopeTag.OPTIONAL,
      ),
    )

  ElevatedCard(shape = MaterialTheme.shapes.large) {
    Column {
      Text(
        text = stringResource(R.string.scope_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
      )
      HorizontalDivider()
      scopes.forEachIndexed { index, item ->
        if (index > 0) HorizontalDivider()
        ScopeRow(item)
      }
    }
  }
}

/**
 * 作用域的档位。多个作用域共享同一档位 —— 每种标签只在 strings.xml 里定义一次，
 * 不重复（否则 lint 的 DuplicateStrings 会报）。
 */
private enum class ScopeTag(val labelRes: Int, val highlighted: Boolean) {
  REQUIRED(R.string.scope_tag_required, highlighted = true),
  RECOMMENDED(R.string.scope_tag_recommended, highlighted = false),
  OPTIONAL(R.string.scope_tag_optional, highlighted = false),
}

private data class ScopeItem(
  val icon: ImageVector,
  val titleRes: Int,
  val descRes: Int,
  val tag: ScopeTag,
)

@Composable
private fun ScopeRow(item: ScopeItem) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconBadge(
      icon = item.icon,
      // "必选"用主色底强调，其余用中性 surface 底
      containerColor =
        if (item.tag.highlighted) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainerHighest,
      contentColor =
        if (item.tag.highlighted) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.width(14.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = stringResource(item.titleRes),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = stringResource(item.descRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Spacer(modifier = Modifier.width(8.dp))
    Surface(
      shape = MaterialTheme.shapes.extraSmall,
      color =
        if (item.tag.highlighted) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
      Text(
        text = stringResource(item.tag.labelRes),
        style = MaterialTheme.typography.labelSmall,
        color =
          if (item.tag.highlighted) MaterialTheme.colorScheme.onPrimaryContainer
          else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      )
    }
  }
}

/** 卡片左侧的图标方块：圆角方块 + 居中图标。 */
@Composable
private fun IconBadge(
  icon: ImageVector,
  containerColor: androidx.compose.ui.graphics.Color,
  contentColor: androidx.compose.ui.graphics.Color,
) {
  Surface(shape = RoundedCornerShape(12.dp), color = containerColor, modifier = Modifier.size(40.dp)) {
    Box(contentAlignment = Alignment.Center) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = contentColor,
        modifier = Modifier.size(22.dp),
      )
    }
  }
}

/** 启用步骤：标题行 + 4 个步骤（序号 + 主文案 + 小字提示）。 */
@Composable
private fun StepsCard() {
  val steps =
    listOf(
      R.string.step_1 to R.string.step_1_hint,
      R.string.step_2 to R.string.step_2_hint,
      R.string.step_3 to R.string.step_3_hint,
      R.string.step_4 to R.string.step_4_hint,
    )

  ElevatedCard(shape = MaterialTheme.shapes.large) {
    Column {
      // 「启用步骤」已经在外层 SectionLabel 里写了，卡内不再重复。
      steps.forEachIndexed { index, (titleRes, hintRes) ->
        if (index > 0) HorizontalDivider()
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
          Surface(
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(28.dp),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          Spacer(modifier = Modifier.width(12.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = stringResource(titleRes),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
              text = stringResource(hintRes),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

/** 验证卡：等宽字体的 logcat 命令 + 一键复制。 */
@Composable
private fun VerifyCard(snackbarHostState: SnackbarHostState) {
  val command = stringResource(R.string.verify_logcat)
  val copiedMessage = stringResource(R.string.verify_copied)
  val clipboard = LocalClipboardManager.current
  val scope = rememberCoroutineScope()

  ElevatedCard(shape = MaterialTheme.shapes.large) {
    Column(modifier = Modifier.padding(16.dp)) {
      // 「验证是否生效」已经在外层 SectionLabel 里写了。
      Text(
        text = stringResource(R.string.verify_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.height(12.dp))
      Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = command,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
          )
          IconButton(
            onClick = {
              clipboard.setText(AnnotatedString(command))
              scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
            }
          ) {
            Icon(
              imageVector = Icons.Default.ContentCopy,
              contentDescription = stringResource(R.string.verify_copy),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(20.dp),
            )
          }
        }
      }
    }
  }
}

/** 说明要点：带圆点的列表。 */
@Composable
private fun NotesCard() {
  val notes =
    listOf(
      R.string.note_adaptive,
      R.string.note_tiles,
      R.string.note_boot,
      R.string.note_launcher3,
      R.string.note_themed_icon,
    )

  ElevatedCard(shape = MaterialTheme.shapes.large) {
    Column(modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp)) {
      // 「说明」已经在外层 SectionLabel 里写了。
      notes.forEach { res ->
        Row(modifier = Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
          // Surface 小圆点比 "•" 字符精致：在浅色下是一个清晰可见的小点，
          // 在深色下用 onSurfaceVariant 的 alpha 自动变得柔和。
          Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.padding(top = 7.dp, end = 12.dp).size(6.dp),
          ) {}
          Text(
            text = stringResource(res),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
          )
        }
      }
    }
  }
}

/** 关于：版本/许可 + 跳转 GitHub 的按钮。 */
@Composable
private fun AboutCard() {
  val uriHandler = LocalUriHandler.current

  ElevatedCard(shape = MaterialTheme.shapes.large) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconBadge(
          icon = Icons.Default.Info,
          containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
          contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = stringResource(R.string.about_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      Spacer(modifier = Modifier.height(12.dp))
      Button(
        onClick = { uriHandler.openUri(REPO_URL) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
          ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
          ),
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.OpenInNew,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = stringResource(R.string.action_open_repo))
      }
    }
  }
}
private sealed interface UpdateState {
  data object Idle : UpdateState

  data object Checking : UpdateState

  data class Done(val message: String, val url: String? = null, val ok: Boolean = true) :
    UpdateState
}

private fun UpdateChecker.Result.toState(ctx: android.content.Context, current: String): UpdateState =
  when (this) {
    is UpdateChecker.Result.Newer ->
      UpdateState.Done(ctx.getString(R.string.update_result_newer, version, current), url)
    is UpdateChecker.Result.UpToDate ->
      if (version == current) UpdateState.Done(ctx.getString(R.string.update_result_uptodate, current))
      else UpdateState.Done(ctx.getString(R.string.update_result_uptodate_ahead, current, version))

    // 仓库还没发过 Release：正常状态，不是故障，所以 ok=true（走中性配色）
    UpdateChecker.Result.NoRelease ->
      UpdateState.Done(ctx.getString(R.string.update_result_no_release))

    is UpdateChecker.Result.Failed ->
      UpdateState.Done(ctx.getString(R.string.update_result_failed, reason), ok = false)
  }

/**
 * 更新卡片：与 Noticon 的 UpdateCard 同型。
 *
 * 结果做成**常驻**的一块 chip，而不是一闪而过的 Snackbar —— 用户点完要是走神了，
 * 回头还能看见结论；失败时也能分清是没网、被限流还是仓库没发过 Release。
 */
@Composable
private fun UpdateCard(
  version: String,
  state: UpdateState,
  onCheck: () -> Unit,
  onOpenPage: (String) -> Unit,
) {
  ElevatedCard(shape = MaterialTheme.shapes.large) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconBadge(
          icon = Icons.Default.Update,
          containerColor = MaterialTheme.colorScheme.primary,
          contentColor = MaterialTheme.colorScheme.onPrimary,
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column {
          Text(
            text = stringResource(R.string.update_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = stringResource(R.string.update_current, version),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))
      Text(
        text = stringResource(R.string.update_disclaimer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      val done = state as? UpdateState.Done
      if (done != null) {
        Spacer(modifier = Modifier.height(12.dp))
        ResultChip(done)
      }

      Spacer(modifier = Modifier.height(16.dp))
      val url = done?.url
      Row(verticalAlignment = Alignment.CenterVertically) {
        val checking = state is UpdateState.Checking
        // 单按钮时填满（与 AboutCard 一致）；有「打开发布页」时主按钮按 weight 占满剩余宽度。
        Button(
          onClick = onCheck,
          enabled = !checking,
          modifier =
            if (url == null) Modifier.fillMaxWidth() else Modifier.weight(1f),
        ) {
          if (checking) {
            CircularProgressIndicator(
              modifier = Modifier.size(16.dp),
              strokeWidth = 2.dp,
              color = LocalContentColor.current,
            )
            Spacer(modifier = Modifier.width(8.dp))
          }
          Text(
            text =
              if (checking) stringResource(R.string.update_checking)
              else stringResource(R.string.update_title)
          )
        }
        if (url != null) {
          Spacer(modifier = Modifier.width(10.dp))
          OutlinedButton(onClick = { onOpenPage(url) }) {
            Text(text = stringResource(R.string.update_go_download))
          }
        }
      }
    }
  }
}

/** 检查结果：成功走 secondaryContainer，失败走 errorContainer，一眼能分清 */
@Composable
private fun ResultChip(done: UpdateState.Done) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.medium,
    color =
      if (done.ok) MaterialTheme.colorScheme.secondaryContainer
      else MaterialTheme.colorScheme.errorContainer,
    contentColor =
      if (done.ok) MaterialTheme.colorScheme.onSecondaryContainer
      else MaterialTheme.colorScheme.onErrorContainer,
  ) {
    Text(
      text = done.message,
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
    )
  }
}
