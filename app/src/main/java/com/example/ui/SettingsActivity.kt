package com.example.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.data.CameraLens
import com.example.data.PrivilegeMode
import com.example.data.VideoQuality
import com.example.ui.theme.CapturaTheme
import com.example.ui.theme.StatusOkGreen

class SettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      CapturaTheme {
        SettingsScreen()
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  val permissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
      viewModel.onRuntimePermissionsResult(results)
    }

  LaunchedEffect(viewModel) {
    viewModel.runtimePermissionRequest.collect { permissions ->
      if (permissions.isNotEmpty()) {
        permissionLauncher.launch(permissions)
      }
    }
  }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    contentWindowInsets = WindowInsets.safeDrawing,
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(
        modifier =
          Modifier
            .widthIn(max = 600.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text = stringResource(R.string.settings_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        // Privilege mode & permission setup card
        Surface(
          shape = MaterialTheme.shapes.large,
          color = MaterialTheme.colorScheme.surfaceContainer,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(
                imageVector = Icons.Outlined.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
              )
              Text(
                text = stringResource(R.string.section_privilege_mode),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
              )
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
              val modes = PrivilegeMode.entries
              modes.forEachIndexed { index, mode ->
                SegmentedButton(
                  selected = uiState.settings.privilegeMode == mode,
                  onClick = { viewModel.selectPrivilegeMode(mode) },
                  shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                  modifier =
                    Modifier.testTag(
                      if (mode == PrivilegeMode.ROOT) "privilege_mode_root"
                      else "privilege_mode_shizuku"
                    ),
                ) {
                  Text(
                    text =
                      if (mode == PrivilegeMode.ROOT) stringResource(R.string.mode_root)
                      else stringResource(R.string.mode_shizuku)
                  )
                }
              }
            }

            Surface(
              shape = MaterialTheme.shapes.medium,
              color =
                if (uiState.isModeOk) StatusOkGreen.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
              modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Icon(
                  imageVector =
                    if (uiState.isModeOk) Icons.Outlined.CheckCircle
                    else Icons.Outlined.ErrorOutline,
                  contentDescription = null,
                  tint =
                    if (uiState.isModeOk) StatusOkGreen
                    else MaterialTheme.colorScheme.error,
                  modifier = Modifier.size(20.dp),
                )
                Text(
                  text = uiState.statusLine,
                  style = MaterialTheme.typography.bodyMedium,
                  fontFamily = FontFamily.Monospace,
                  color =
                    if (uiState.isModeOk) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onErrorContainer,
                  modifier = Modifier.testTag("status_line"),
                )
              }
            }

            Button(
              onClick = { viewModel.applyPermissions() },
              enabled = !uiState.isApplying,
              modifier = Modifier.fillMaxWidth().height(48.dp).testTag("apply_permissions_button"),
            ) {
              Text(
                text =
                  if (uiState.isApplying) stringResource(R.string.btn_applying_permissions)
                  else stringResource(R.string.btn_apply_permissions)
              )
            }
          }
        }

        // Capture defaults card
        Surface(
          shape = MaterialTheme.shapes.large,
          color = MaterialTheme.colorScheme.surfaceContainer,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Text(
              text = stringResource(R.string.section_capture_defaults),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Lens toggle (Back / Front)
            Row(
              modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f),
              ) {
                Icon(
                  imageVector = Icons.Outlined.CameraAlt,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column {
                  Text(
                    text = stringResource(R.string.label_camera_lens),
                    style = MaterialTheme.typography.bodyLarge,
                  )
                  Text(
                    text =
                      if (uiState.settings.lens == CameraLens.FRONT)
                        stringResource(R.string.lens_front)
                      else stringResource(R.string.lens_back),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }

              SingleChoiceSegmentedButtonRow {
                val lenses = CameraLens.entries
                lenses.forEachIndexed { index, lens ->
                  SegmentedButton(
                    selected = uiState.settings.lens == lens,
                    onClick = { viewModel.setLens(lens) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = lenses.size),
                    modifier =
                      Modifier.testTag(
                        if (lens == CameraLens.BACK) "lens_back" else "lens_front"
                      ),
                  ) {
                    Text(lens.label)
                  }
                }
              }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Video quality dropdown (720p / 1080p)
            var qualityExpanded by remember { mutableStateOf(false) }
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Icon(
                imageVector = Icons.Outlined.HighQuality,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              ExposedDropdownMenuBox(
                expanded = qualityExpanded,
                onExpandedChange = { qualityExpanded = it },
                modifier = Modifier.weight(1f),
              ) {
                OutlinedTextField(
                  value = uiState.settings.videoQuality.label,
                  onValueChange = {},
                  readOnly = true,
                  singleLine = true,
                  label = { Text(stringResource(R.string.label_video_quality)) },
                  trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = qualityExpanded)
                  },
                  modifier =
                    Modifier
                      .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                      .fillMaxWidth()
                      .testTag("video_quality_dropdown"),
                )
                ExposedDropdownMenu(
                  expanded = qualityExpanded,
                  onDismissRequest = { qualityExpanded = false },
                ) {
                  VideoQuality.entries.forEach { quality ->
                    DropdownMenuItem(
                      text = { Text(quality.label) },
                      onClick = {
                        viewModel.setVideoQuality(quality)
                        qualityExpanded = false
                      },
                      modifier = Modifier.testTag("quality_option_${quality.label}"),
                    )
                  }
                }
              }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Quick Settings tile switch
            Row(
              modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f).padding(end = 12.dp),
              ) {
                Icon(
                  imageVector = Icons.Outlined.DashboardCustomize,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column {
                  Text(
                    text = stringResource(R.string.label_show_qs_tile),
                    style = MaterialTheme.typography.bodyLarge,
                  )
                  Text(
                    text = stringResource(R.string.desc_show_qs_tile),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }

              Switch(
                checked = uiState.settings.showQuickSettingsTile,
                onCheckedChange = { viewModel.setShowQuickSettingsTile(it) },
                modifier = Modifier.testTag("qs_tile_switch"),
              )
            }
          }
        }
      }
    }
  }
}
