/*
 * Copyright 2025 Google LLC
 * Licensed under the Apache License, Version 2.0
 */

package com.pockettalk.app.ui.navigation

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseOutExpo
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.pockettalk.app.customtasks.common.CustomTaskDataForBuiltinTask
import com.pockettalk.app.data.ModelDownloadStatusType
import com.pockettalk.app.data.Task
import com.pockettalk.app.data.isLegacyTasks
import com.pockettalk.app.ui.common.ErrorDialog
import com.pockettalk.app.ui.common.ModelPageAppBar
import com.pockettalk.app.ui.common.chat.ModelDownloadStatusInfoPanel
import com.pockettalk.app.ui.home.HomeScreen
import com.pockettalk.app.ui.modelmanager.ModelInitializationStatusType
import com.pockettalk.app.ui.modelmanager.ModelManager
import com.pockettalk.app.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "PocketTalkNavGraph"
private const val ROUTE_HOMESCREEN = "homepage"
private const val ROUTE_MODEL_LIST = "model_list"
private const val ROUTE_MODEL = "route_model"

@Composable
fun GalleryNavHost(
  navController: NavHostController,
  modifier: Modifier = Modifier,
  modelManagerViewModel: ModelManagerViewModel,
) {
  val lifecycleOwner = LocalLifecycleOwner.current
  var pickedTask by remember { mutableStateOf<Task?>(null) }
  var enableHomeScreenAnimation by remember { mutableStateOf(true) }
  var enableModelListAnimation by remember { mutableStateOf(true) }
  var lastNavigatedModelName = remember { "" }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME ->
          modelManagerViewModel.setAppInForeground(foreground = true)
        Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_PAUSE ->
          modelManagerViewModel.setAppInForeground(foreground = false)
        else -> {}
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  NavHost(
    navController = navController,
    startDestination = ROUTE_HOMESCREEN,
    enterTransition = { EnterTransition.None },
    exitTransition = { ExitTransition.None },
  ) {
    composable(route = ROUTE_HOMESCREEN) {
      HomeScreen(
        modelManagerViewModel = modelManagerViewModel,
        enableAnimation = enableHomeScreenAnimation,
        navigateToTaskScreen = { task ->
          pickedTask = task
          enableModelListAnimation = true
          navController.navigate(ROUTE_MODEL_LIST)
        },
        onModelsClicked = {},
      )
    }

    composable(route = ROUTE_MODEL_LIST) {
      pickedTask?.let {
        ModelManager(
          viewModel = modelManagerViewModel,
          task = it,
          enableAnimation = enableModelListAnimation,
          onModelClicked = { model ->
            navController.navigate("$ROUTE_MODEL/${it.id}/${model.name}")
          },
          onBenchmarkClicked = {},
          navigateUp = {
            enableHomeScreenAnimation = false
            navController.navigateUp()
          },
        )
      }
    }

    composable(
      route = "$ROUTE_MODEL/{taskId}/{modelName}",
      arguments = listOf(
        navArgument("taskId") { type = NavType.StringType },
        navArgument("modelName") { type = NavType.StringType },
      ),
    ) { backStackEntry ->
      val modelName = backStackEntry.arguments?.getString("modelName") ?: ""
      val taskId = backStackEntry.arguments?.getString("taskId") ?: ""
      val scope = rememberCoroutineScope()
      val context = LocalContext.current

      modelManagerViewModel.getModelByName(name = modelName)?.let { initialModel ->
        if (lastNavigatedModelName != modelName) {
          modelManagerViewModel.selectModel(initialModel)
          lastNavigatedModelName = modelName
        }

        val customTask = modelManagerViewModel.getCustomTaskByTaskId(id = taskId)
        if (customTask != null) {
          customTask.MainScreen(
            data = CustomTaskDataForBuiltinTask(
              modelManagerViewModel = modelManagerViewModel,
              onNavUp = {
                enableModelListAnimation = false
                lastNavigatedModelName = ""
                navController.navigateUp()
              },
            )
          )
        }
      }
    }
  }
}

@Composable
private fun CustomTaskScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  disableAppBarControls: Boolean,
  hideTopBar: Boolean,
  useThemeColor: Boolean,
  onNavigateUp: () -> Unit,
  content: @Composable (bottomPadding: Dp) -> Unit,
) {
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val selectedModel = modelManagerUiState.selectedModel
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  var navigatingUp by remember { mutableStateOf(false) }
  var showErrorDialog by remember { mutableStateOf(false) }
  var appBarHeight by remember { mutableIntStateOf(0) }

  val handleNavigateUp = {
    navigatingUp = true
    onNavigateUp()
  }

  BackHandler { handleNavigateUp() }

  val curDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]
  LaunchedEffect(curDownloadStatus, selectedModel.name) {
    if (!navigatingUp && curDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED) {
      modelManagerViewModel.initializeModel(context, task = task, model = selectedModel)
    }
  }

  val modelInitializationStatus = modelManagerUiState.modelInitializationStatus[selectedModel.name]
  LaunchedEffect(modelInitializationStatus) {
    showErrorDialog = modelInitializationStatus?.status == ModelInitializationStatusType.ERROR
  }

  Scaffold(
    topBar = {
      AnimatedVisibility(
        !hideTopBar,
        enter = slideInVertically { -it },
        exit = slideOutVertically { -it },
      ) {
        ModelPageAppBar(
          task = task,
          model = selectedModel,
          modelManagerViewModel = modelManagerViewModel,
          inProgress = disableAppBarControls,
          modelPreparing = disableAppBarControls,
          canShowResetSessionButton = false,
          useThemeColor = useThemeColor,
          modifier = Modifier.onGloballyPositioned { coordinates -> appBarHeight = coordinates.size.height },
          hideModelSelector = task.models.size <= 1,
          onConfigChanged = { _, _ -> },
          onBackClicked = { handleNavigateUp() },
          onModelSelected = { prevModel, newSelectedModel ->
            val instanceToCleanUp = prevModel.instance
            scope.launch(Dispatchers.Default) {
              if (prevModel.name != newSelectedModel.name) {
                modelManagerViewModel.cleanupModel(
                  context = context, task = task, model = prevModel, instanceToCleanUp = instanceToCleanUp,
                )
              }
              modelManagerViewModel.selectModel(model = newSelectedModel)
            }
          },
        )
      }
    }
  ) { innerPadding ->
    val targetPaddingDp =
      if (!hideTopBar && appBarHeight > 0) {
        with(LocalDensity.current) { appBarHeight.toDp() }
      } else {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
      }

    val animatedTopPadding by animateDpAsState(
      targetValue = targetPaddingDp,
      animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
      label = "TopPaddingAnimation",
    )

    Box(
      modifier = Modifier.padding(
        top = if (!hideTopBar) innerPadding.calculateTopPadding() else animatedTopPadding,
        start = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
        end = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
      )
    ) {
      val curModelDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]
      AnimatedContent(
        targetState = curModelDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED
      ) { targetState ->
        when (targetState) {
          true -> content(innerPadding.calculateBottomPadding())
          false -> ModelDownloadStatusInfoPanel(
            model = selectedModel, task = task, modelManagerViewModel = modelManagerViewModel,
          )
        }
      }
    }
  }

  if (showErrorDialog) {
    ErrorDialog(
      error = modelInitializationStatus?.error ?: "",
      onDismiss = {
        showErrorDialog = false
        onNavigateUp()
      },
    )
  }
}
