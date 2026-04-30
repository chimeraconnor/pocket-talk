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

package com.pockettalk.app

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.pockettalk.app.ui.modelmanager.ModelManagerViewModel
import com.pockettalk.app.ui.theme.GalleryTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

  private val modelManagerViewModel: ModelManagerViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(null)

    modelManagerViewModel.loadModelAllowlist()

    setContent {
      GalleryTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          GalleryApp(modelManagerViewModel = modelManagerViewModel)

          var startMaskFadeout by remember { mutableStateOf(false) }
          LaunchedEffect(Unit) { startMaskFadeout = true }
          AnimatedVisibility(
            !startMaskFadeout,
            enter = androidx.compose.animation.fadeIn(
              animationSpec = androidx.compose.animation.core.snap(0)
            ),
            exit = fadeOut(
              animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
            ),
          ) {
            Box(
              modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            )
          }
        }
      }
    }

    enableEdgeToEdge()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      window.isNavigationBarContrastEnforced = false
    }
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }
}
