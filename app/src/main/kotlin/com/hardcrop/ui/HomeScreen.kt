package com.hardcrop.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hardcrop.R

private const val REPO_URL = "https://github.com/IamCanincan/HardCrop"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(
            text = stringResource(R.string.appName),
            style = MaterialTheme.typography.headlineMedium,
          )
        },
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          ),
      )
    }
  ) { innerPadding ->
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item { HeroCard() }
      item { ScopeCard() }
      item { StepsCard() }
      item { VerifyCard() }
      item { NotesCard() }
      item { AboutCard() }
    }
  }
}

@Composable
private fun HeroCard() {
  Card(
    colors =
      CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    shape = MaterialTheme.shapes.extraLarge,
  ) {
    Column(modifier = Modifier.padding(24.dp)) {
      Text(
        text = stringResource(R.string.hero_title),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = stringResource(R.string.hero_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScopeCard() {
  val scopes =
    listOf(
      R.string.scope_system to R.string.scope_system_desc,
      R.string.scope_launcher to R.string.scope_launcher_desc,
      R.string.scope_systemui to R.string.scope_systemui_desc,
      R.string.scope_settings to R.string.scope_settings_desc,
      R.string.scope_intentresolver to R.string.scope_intentresolver_desc,
      R.string.scope_permissioncontroller to R.string.scope_permissioncontroller_desc,
    )

  OutlinedCard(shape = MaterialTheme.shapes.extraLarge) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
      Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
          text = stringResource(R.string.scope_title),
          style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = stringResource(R.string.scope_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      HorizontalDivider()
      scopes.forEach { (titleRes, descRes) ->
        ListItem(
          headlineContent = {
            Text(text = stringResource(titleRes), style = MaterialTheme.typography.bodyLarge)
          },
          supportingContent = {
            Text(
              text = stringResource(descRes),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          },
        )
      }
    }
  }
}

@Composable
private fun StepsCard() {
  val steps =
    listOf(R.string.step_1, R.string.step_2, R.string.step_3, R.string.step_4)

  ElevatedCard(shape = MaterialTheme.shapes.extraLarge) {
    Column(modifier = Modifier.padding(20.dp)) {
      Text(text = stringResource(R.string.steps_title), style = MaterialTheme.typography.titleMedium)
      Spacer(modifier = Modifier.height(8.dp))
      steps.forEachIndexed { index, res ->
        Row(modifier = Modifier.padding(vertical = 8.dp)) {
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(28.dp),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
              )
            }
          }
          Spacer(modifier = Modifier.width(12.dp))
          Text(
            text = stringResource(res),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
          )
        }
      }
    }
  }
}

@Composable
private fun VerifyCard() {
  ElevatedCard(shape = MaterialTheme.shapes.extraLarge) {
    Column(modifier = Modifier.padding(20.dp)) {
      Text(text = stringResource(R.string.verify_title), style = MaterialTheme.typography.titleMedium)
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = stringResource(R.string.verify_body),
        style = MaterialTheme.typography.bodyMedium,
      )
      Spacer(modifier = Modifier.height(12.dp))
      Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
      ) {
        Text(
          text = stringResource(R.string.verify_logcat),
          style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
      }
    }
  }
}

@Composable
private fun NotesCard() {
  val notes =
    listOf(
      R.string.note_adaptive,
      R.string.note_tiles,
      R.string.note_boot,
      R.string.note_launcher3,
    )

  ElevatedCard(shape = MaterialTheme.shapes.extraLarge) {
    Column(modifier = Modifier.padding(20.dp)) {
      Text(text = stringResource(R.string.notes_title), style = MaterialTheme.typography.titleMedium)
      Spacer(modifier = Modifier.height(8.dp))
      notes.forEach { res ->
        Row(modifier = Modifier.padding(vertical = 6.dp)) {
          Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(16.dp),
          )
          Text(
            text = stringResource(res),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
          )
        }
      }
    }
  }
}

@Composable
private fun AboutCard() {
  val uriHandler = LocalUriHandler.current

  ElevatedCard(shape = MaterialTheme.shapes.extraLarge) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
      Text(text = stringResource(R.string.about_title), style = MaterialTheme.typography.titleMedium)
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = stringResource(R.string.about_version),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = stringResource(R.string.about_license),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      TextButton(onClick = { uriHandler.openUri(REPO_URL) }) {
        Text(text = stringResource(R.string.about_repo))
      }
    }
  }
}
