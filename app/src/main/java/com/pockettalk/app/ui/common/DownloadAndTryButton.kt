/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pockettalk.app.ui.common

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pockettalk.app.R
import com.pockettalk.app.data.Model
import com.pockettalk.app.data.ModelDownloadStatusType
import com.pockettalk.app.data.RuntimeType
import com.pockettalk.app.data.Task
import com.pockettalk.app.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.launch

private const val TAG = "AGDownloadAndTryButton"

@Composable
fun DownloadAndTryButton(
  task: Task?,
  model: Model,
  enabled: Boolean,
  downloadStatus: ModelDownloadStatusType?,
  downloadProgress: Float,
  modelManagerViewModel: ModelManagerViewModel,
  onClicked: () -> Unit,
  modifier: Modifier = Modifier,
  modifierWhenExpanded: Modifier = Modifier,
  compact: Boolean = false,
  canShowTryIt: Boolean = true,
  downloadButtonBackgroundColor: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  var showMemoryWarning by remember { mutableStateOf(false) }
  var downloadStarted by remember { mutableStateOf(false) }

  val needToDownloadFirst =
    (downloadStatus == ModelDownloadStatusType.NOT_DOWNLOADED ||
      downloadStatus == ModelDownloadStatusType.FAILED) &&
      model.localFileRelativeDirPathOverride.isEmpty() &&
      model.runtimeType != RuntimeType.AICORE
  val inProgress = downloadStatus == ModelDownloadStatusType.IN_PROGRESS
  val downloadSucceeded = downloadStatus == ModelDownloadStatusType.SUCCEEDED
  val isPartiallyDownloaded = downloadStatus == ModelDownloadStatusType.PARTIALLY_DOWNLOADED
  val showDownloadProgress =
    !downloadSucceeded && (downloadStarted || inProgress || isPartiallyDownloaded)

  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      modelManagerViewModel.downloadModel(task = task, model = model)
    }

  val startDownload = {
    checkNotificationPermissionAndStartDownload(
      context = context,
      launcher = permissionLauncher,
      modelManagerViewModel = modelManagerViewModel,
      task = task,
      model = model,
    )
  }

  val handleClickButton = {
    scope.launch {
      if (needToDownloadFirst) {
        downloadStarted = true
        Log.d(TAG, "Downloading model '${model.name}'")
        startDownload()
      } else {
        onClicked()
      }
    }
  }

  val checkMemoryAndClickDownloadButton = {
    if (isMemoryLow(context = context, model = model)) {
      showMemoryWarning = true
    } else {
      handleClickButton()
    }
  }

  if (!showDownloadProgress) {
    var buttonModifier: Modifier = modifier.height(42.dp)
    if (!compact) {
      buttonModifier = buttonModifier.then(modifierWhenExpanded)
    }
    Button(
      modifier = buttonModifier,
      colors =
        ButtonDefaults.buttonColors(
          containerColor =
            if (
              (!downloadSucceeded || !canShowTryIt) &&
                model.localFileRelativeDirPathOverride.isEmpty()
            ) {
              downloadButtonBackgroundColor
            } else if (task != null) {
              getTaskBgGradientColors(task = task)[1]
            } else {
              MaterialTheme.colorScheme.primary
            }
        ),
      contentPadding = PaddingValues(horizontal = 12.dp),
      onClick = {
        if (!enabled) return@Button
        checkMemoryAndClickDownloadButton()
      },
    ) {
      val textColor =
        if (!enabled) {
          MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        } else if (!downloadSucceeded && model.localFileRelativeDirPathOverride.isEmpty()) {
          MaterialTheme.colorScheme.onSurface
        } else if (task != null) {
          Color.White
        } else {
          MaterialTheme.colorScheme.onPrimary
        }
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          if (needToDownloadFirst) {
            Icons.Outlined.FileDownload
          } else {
            Icons.AutoMirrored.Rounded.ArrowForward
          },
          contentDescription = null,
          tint = textColor,
        )

        if (!compact) {
          if (needToDownloadFirst) {
            Text(
              stringResource(R.string.download),
              color = textColor,
              style = MaterialTheme.typography.titleMedium,
            )
          } else if (canShowTryIt) {
            Text(
              stringResource(R.string.try_it),
              color = textColor,
              style = MaterialTheme.typography.titleMedium,
              maxLines = 1,
              autoSize =
                TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 16.sp, stepSize = 1.sp),
            )
          }
        }
      }
    }
  } else {
    val animatedProgress = remember { Animatable(0f) }

    var downloadProgressModifier: Modifier = modifier
    if (!compact) {
      downloadProgressModifier = downloadProgressModifier.fillMaxWidth()
    }
    downloadProgressModifier =
      downloadProgressModifier
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceContainer)
        .padding(horizontal = 8.dp)
        .height(42.dp)
    Row(modifier = downloadProgressModifier, verticalAlignment = Alignment.CenterVertically) {
      Text(
        "${(downloadProgress * 100).toInt()}%",
        style =
          MaterialTheme.typography.bodyMedium.copy(
            fontFeatureSettings = "tnum"
          ),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 12.dp).width(if (compact) 32.dp else 44.dp),
      )
      if (!compact) {
        val color =
          if (task != null) getTaskBgGradientColors(task = task)[1]
          else MaterialTheme.colorScheme.primary
        LinearProgressIndicator(
          modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
          progress = { animatedProgress.value },
          color = color,
          trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
      }
      val cbStop = stringResource(R.string.cd_stop_icon)
      IconButton(
        onClick = {
          downloadStarted = false
          modelManagerViewModel.cancelDownloadModel(model = model)
        },
        colors =
          IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
          ),
        modifier = Modifier.semantics { contentDescription = cbStop },
      ) {
        Icon(
          Icons.Outlined.Close,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurface,
        )
      }
    }
    LaunchedEffect(downloadProgress) {
      animatedProgress.animateTo(downloadProgress, animationSpec = tween(150))
    }
  }

  if (showMemoryWarning) {
    MemoryWarningAlert(
      onProceeded = {
        handleClickButton()
        showMemoryWarning = false
      },
      onDismissed = { showMemoryWarning = false },
    )
  }
}
