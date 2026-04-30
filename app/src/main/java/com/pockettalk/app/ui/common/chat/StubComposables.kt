package com.pockettalk.app.ui.common.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun ZoomableImage(
  imagePath: String = "",
  bitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
  pagerState: androidx.compose.foundation.pager.PagerState? = null,
  modifier: Modifier = Modifier,
) {}

@Composable
fun MessageBodyConfigUpdate(message: ChatMessage) {}

@Composable
fun MessageBodyImage(
  message: ChatMessage,
  onImageClicked: (List<android.graphics.Bitmap>, Int) -> Unit = { _, _ -> },
) {}

@Composable
fun MessageBodyImageWithHistory(
  message: ChatMessage,
  imageHistoryCurIndex: Any = 0,
) {}

@Composable
fun MessageBodyAudioClip(message: ChatMessage) {}

@Composable
fun MessageBodyClassification(
  message: ChatMessage,
  modifier: Modifier = Modifier,
) {}

@Composable
fun MessageBodyBenchmark(message: ChatMessage) {}

@Composable
fun MessageBodyBenchmarkLlm(
  message: ChatMessage,
  modifier: Modifier = Modifier,
) {}

@Composable
fun MessageBodyWebview(message: ChatMessage) {}

@Composable
fun MessageBodyCollapsableProgressPanel(message: ChatMessage) {}

@Composable
fun BenchmarkConfigDialog(
  onDismissed: () -> Unit,
  messageToBenchmark: ChatMessage?,
  onBenchmarkClicked: (ChatMessage, Int, Int) -> Unit,
) {}

val CLASSIFICATION_BAR_MAX_WIDTH: Dp = 200.dp

@Composable
fun AudioPlaybackPanel(
  audioData: ByteArray,
  sampleRate: Int,
  isRecording: Boolean,
  modifier: Modifier = Modifier,
) {}

@Composable
fun AudioRecorderPanel(
  task: com.pockettalk.app.data.Task? = null,
  onSendAudioClip: (ByteArray) -> Unit = {},
  onAmplitudeChanged: (Int) -> Unit = {},
  onClose: () -> Unit = {},
  modifier: Modifier = Modifier,
) {}

@Composable
fun AICoreAccessPanel() {}
