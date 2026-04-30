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

package com.pockettalk.app.ui.modelmanager

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettalk.app.AppLifecycleProvider
import com.pockettalk.app.BuildConfig
import com.pockettalk.app.R
import com.pockettalk.app.common.getJsonResponse
import com.pockettalk.app.customtasks.common.CustomTask
import com.pockettalk.app.data.Accelerator
import com.pockettalk.app.data.BuiltInTaskId
import com.pockettalk.app.data.Category
import com.pockettalk.app.data.CategoryInfo
import com.pockettalk.app.data.Config
import com.pockettalk.app.data.ConfigKeys
import com.pockettalk.app.data.DataStoreRepository
import com.pockettalk.app.data.DownloadRepository
import com.pockettalk.app.data.EMPTY_MODEL
import com.pockettalk.app.data.IMPORTS_DIR
import com.pockettalk.app.data.Model
import com.pockettalk.app.data.ModelAllowlist
import com.pockettalk.app.data.ModelCapability
import com.pockettalk.app.data.ModelDownloadStatus
import com.pockettalk.app.data.ModelDownloadStatusType
import com.pockettalk.app.data.NumberSliderConfig
import com.pockettalk.app.data.SOC
import com.pockettalk.app.data.TMP_FILE_EXT
import com.pockettalk.app.data.Task
import com.pockettalk.app.data.ValueType
import com.pockettalk.app.data.createLlmChatConfigs
import com.pockettalk.app.proto.ImportedModel
import com.pockettalk.app.proto.Theme
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AGModelManagerViewModel"
private const val TEXT_INPUT_HISTORY_MAX_SIZE = 50
private const val MODEL_ALLOWLIST_FILENAME = "model_allowlist.json"
private const val MODEL_ALLOWLIST_TEST_FILENAME = "model_allowlist_test.json"

private const val ALLOWLIST_BASE_URL =
  "https://raw.githubusercontent.com/google-ai-edge/gallery/refs/heads/main/model_allowlists"

private const val TEST_MODEL_ALLOW_LIST = """{"models":[{"name":"Gemma-4-E2B-it","modelId":"litert-community/gemma-4-E2B-it-litert-lm","modelFile":"gemma-4-E2B-it.litertlm","description":"Gemma 4 E2B for on-device inference via LiteRT-LM. Supports multi-modality input with up to 32K context length.","sizeInBytes":2583085056,"minDeviceMemoryInGb":8,"commitHash":"7fa1d78473894f7e736a21d920c3aa80f950c0db","llmSupportImage":false,"llmSupportAudio":false,"defaultConfig":{"topK":64,"topP":0.95,"temperature":1.0,"maxContextLength":32000,"maxTokens":4000,"accelerators":"gpu,cpu","visionAccelerator":"gpu"},"taskTypes":["llm_chat","llm_prompt_lab"],"bestForTaskTypes":["llm_chat","llm_prompt_lab"]},{"name":"Gemma-4-E2B-it-Abliterated","modelId":"krsnalyst/andro","modelFile":"model3.litertlm","description":"Gemma 4 E2B Abliterated (v3, per-layer embedder).","sizeInBytes":2556493824,"minDeviceMemoryInGb":8,"commitHash":"a421cf3d51f278d7d3c8120dc17fd77ff9ec5956","llmSupportImage":false,"llmSupportAudio":false,"defaultConfig":{"topK":64,"topP":0.95,"temperature":1.0,"maxContextLength":32000,"maxTokens":4000,"accelerators":"gpu,cpu","visionAccelerator":"gpu"},"taskTypes":["llm_chat","llm_prompt_lab"],"bestForTaskTypes":["llm_chat","llm_prompt_lab"]}]}"""

data class ModelInitializationStatus(
  val status: ModelInitializationStatusType,
  var error: String = "",
  var initializedBackends: Set<String> = setOf(),
) {
  fun isFirstInitialization(model: Model): Boolean {
    val backend =
      model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)
    return !initializedBackends.contains(backend)
  }
}

enum class ModelInitializationStatusType {
  NOT_INITIALIZED,
  INITIALIZING,
  INITIALIZED,
  ERROR,
}

data class ModelManagerUiState(
  val tasks: List<Task>,
  val tasksByCategory: Map<String, List<Task>>,
  val modelDownloadStatus: Map<String, ModelDownloadStatus>,
  val modelInitializationStatus: Map<String, ModelInitializationStatus>,
  val loadingModelAllowlist: Boolean = true,
  val loadingModelAllowlistError: String = "",
  val selectedModel: Model = EMPTY_MODEL,
  val textInputHistory: List<String> = listOf(),
  val configValuesUpdateTrigger: Long = 0L,
  val modelImportingUpdateTrigger: Long = 0L,
) {
  fun isModelInitialized(model: Model): Boolean {
    return modelInitializationStatus[model.name]?.status ==
      ModelInitializationStatusType.INITIALIZED
  }

  fun isModelInitializing(model: Model): Boolean {
    return modelInitializationStatus[model.name]?.status ==
      ModelInitializationStatusType.INITIALIZING
  }
}

private val RESET_CONVERSATION_TURN_COUNT_CONFIG =
  NumberSliderConfig(
    key = ConfigKeys.RESET_CONVERSATION_TURN_COUNT,
    sliderMin = 1f,
    sliderMax = 30f,
    defaultValue = 3f,
    valueType = ValueType.INT,
  )

@HiltViewModel
open class ModelManagerViewModel
@Inject
constructor(
  private val downloadRepository: DownloadRepository,
  val dataStoreRepository: DataStoreRepository,
  private val lifecycleProvider: AppLifecycleProvider,
  private val customTasks: Set<@JvmSuppressWildcards CustomTask>,
  @ApplicationContext private val context: Context,
) : ViewModel() {
  private val externalFilesDir = context.getExternalFilesDir(null)
  protected val _uiState = MutableStateFlow(createEmptyUiState())
  open val uiState = _uiState.asStateFlow()

  private var _allowlistModels: MutableList<Model> = mutableListOf()
  val allowlistModels: List<Model>
    get() = _allowlistModels

  fun getTaskById(id: String): Task? {
    return uiState.value.tasks.find { it.id == id }
  }

  fun getTasksByIds(ids: Set<String>): List<Task> {
    return uiState.value.tasks.filter { ids.contains(it.id) }
  }

  fun getCustomTaskByTaskId(id: String): CustomTask? {
    return getActiveCustomTasks().find { it.task.id == id }
  }

  fun getActiveCustomTasks(): List<CustomTask> {
    return customTasks.toList()
  }

  fun getSelectedModel(): Model? {
    return uiState.value.selectedModel
  }

  fun getModelByName(name: String): Model? {
    for (task in uiState.value.tasks) {
      for (model in task.models) {
        if (model.name == name) {
          return model
        }
      }
    }
    return null
  }

  fun getAllModels(): List<Model> {
    val allModels = mutableSetOf<Model>()
    for (task in uiState.value.tasks) {
      for (model in task.models) {
        allModels.add(model)
      }
    }
    return allModels.toList().sortedBy { it.displayName.ifEmpty { it.name } }
  }

  fun getAllDownloadedModels(): List<Model> {
    return getAllModels().filter {
      uiState.value.modelDownloadStatus[it.name]?.status == ModelDownloadStatusType.SUCCEEDED &&
        it.isLlm
    }
  }

  fun processTasks() {
    val curTasks = getActiveCustomTasks().map { it.task }
    for (task in curTasks) {
      for (model in task.models) {
        model.preProcess()
      }
      val bestModel = task.models.find { it.bestForTaskIds.contains(task.id) }
      if (bestModel != null) {
        task.models.remove(bestModel)
        task.models.add(0, bestModel)
      }
    }
  }

  fun updateConfigValuesUpdateTrigger() {
    _uiState.update { _uiState.value.copy(configValuesUpdateTrigger = System.currentTimeMillis()) }
  }

  fun selectModel(model: Model) {
    if (_uiState.value.selectedModel.name != model.name) {
      _uiState.update { _uiState.value.copy(selectedModel = model) }
    }
  }

  open fun downloadModel(task: Task?, model: Model) {
    setDownloadStatus(
      curModel = model,
      status = ModelDownloadStatus(status = ModelDownloadStatusType.IN_PROGRESS),
    )

    deleteModel(model = model)

    downloadRepository.downloadModel(
      task = task,
      model = model,
      onStatusUpdated = this::setDownloadStatus,
    )
  }

  fun cancelDownloadModel(model: Model) {
    downloadRepository.cancelDownloadModel(model)
    deleteModel(model = model)
  }

  fun deleteModel(model: Model) {
    if (model.updatable) {
      model.updatable = false
      model.latestModelFile?.let {
        model.version = it.commitHash
        model.downloadFileName = it.fileName
      }
    }

    if (model.imported) {
      deleteFilesFromImportDir(model.downloadFileName)
    } else {
      deleteDirFromExternalFilesDir(model.normalizedName)
    }

    val curModelDownloadStatus = uiState.value.modelDownloadStatus.toMutableMap()
    curModelDownloadStatus[model.name] =
      ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED)

    if (model.imported) {
      for (curTask in uiState.value.tasks) {
        val index = curTask.models.indexOf(model)
        if (index >= 0) {
          curTask.models.removeAt(index)
        }
        curTask.updateTrigger.value = System.currentTimeMillis()
      }
      curModelDownloadStatus.remove(model.name)

      val importedModels = dataStoreRepository.readImportedModels().toMutableList()
      val importedModelIndex = importedModels.indexOfFirst { it.fileName == model.name }
      if (importedModelIndex >= 0) {
        importedModels.removeAt(importedModelIndex)
      }
      dataStoreRepository.saveImportedModels(importedModels = importedModels)
    }
    val newUiState =
      uiState.value.copy(
        modelDownloadStatus = curModelDownloadStatus,
        tasks = uiState.value.tasks.toList(),
        modelImportingUpdateTrigger = System.currentTimeMillis(),
      )
    _uiState.update { newUiState }
  }

  fun initializeModel(
    context: Context,
    task: Task,
    model: Model,
    force: Boolean = false,
    onDone: () -> Unit = {},
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      if (
        !force &&
          uiState.value.modelInitializationStatus[model.name]?.status ==
            ModelInitializationStatusType.INITIALIZED
      ) {
        Log.d(TAG, "Model '${model.name}' has been initialized. Skipping.")
        return@launch
      }

      if (model.initializing) {
        model.cleanUpAfterInit = false
        Log.d(TAG, "Model '${model.name}' is being initialized. Skipping.")
        return@launch
      }

      cleanupModel(context = context, task = task, model = model)

      Log.d(TAG, "Initializing model '${model.name}'...")
      model.initializing = true
      updateModelInitializationStatus(
        model = model,
        status = ModelInitializationStatusType.INITIALIZING,
      )

      val onDoneFn: (error: String) -> Unit = { error ->
        model.initializing = false
        if (model.instance != null) {
          Log.d(TAG, "Model '${model.name}' initialized successfully")
          updateModelInitializationStatus(
            model = model,
            status = ModelInitializationStatusType.INITIALIZED,
          )
          if (model.cleanUpAfterInit) {
            Log.d(TAG, "Model '${model.name}' needs cleaning up after init.")
            cleanupModel(context = context, task = task, model = model)
          }
          onDone()
        } else if (error.isNotEmpty()) {
          Log.d(TAG, "Model '${model.name}' failed to initialize")
          updateModelInitializationStatus(
            model = model,
            status = ModelInitializationStatusType.ERROR,
            error = error,
          )
        }
      }

      getCustomTaskByTaskId(id = task.id)
        ?.initializeModelFn(
          context = context,
          coroutineScope = viewModelScope,
          model = model,
          onDone = onDoneFn,
        )
    }
  }

  fun cleanupModel(
    context: Context,
    task: Task,
    model: Model,
    instanceToCleanUp: Any? = model.instance,
    onDone: () -> Unit = {},
  ) {
    if (instanceToCleanUp != null && instanceToCleanUp !== model.instance) {
      Log.d(TAG, "Stale cleanup request for ${model.name}. Aborting.")
      onDone()
      return
    }

    if (model.instance != null) {
      model.cleanUpAfterInit = false
      Log.d(TAG, "Cleaning up model '${model.name}'...")
      val onDoneFn: () -> Unit = {
        model.instance = null
        model.initializing = false
        updateModelInitializationStatus(
          model = model,
          status = ModelInitializationStatusType.NOT_INITIALIZED,
        )
        Log.d(TAG, "Clean up model '${model.name}' done")
        onDone()
      }
      getCustomTaskByTaskId(id = task.id)
        ?.cleanUpModelFn(
          context = context,
          coroutineScope = viewModelScope,
          model = model,
          onDone = onDoneFn,
        )
    } else {
      if (model.initializing) {
        Log.d(
          TAG,
          "Model '${model.name}' is still initializing.. Will clean up after it is done initializing",
        )
        model.cleanUpAfterInit = true
      }
    }
  }

  fun setDownloadStatus(curModel: Model, status: ModelDownloadStatus) {
    val curModelDownloadStatus = uiState.value.modelDownloadStatus.toMutableMap()
    curModelDownloadStatus[curModel.name] = status
    val newUiState = uiState.value.copy(modelDownloadStatus = curModelDownloadStatus)

    if (
      status.status == ModelDownloadStatusType.FAILED ||
        status.status == ModelDownloadStatusType.NOT_DOWNLOADED
    ) {
      deleteFileFromExternalFilesDir(curModel.downloadFileName)
    }

    _uiState.update { newUiState }
  }

  fun setInitializationStatus(model: Model, status: ModelInitializationStatus) {
    val curStatus = uiState.value.modelInitializationStatus.toMutableMap()
    if (curStatus.containsKey(model.name)) {
      val initializedBackends = curStatus[model.name]?.initializedBackends ?: setOf()
      val backend =
        model.getStringConfigValue(
          key = ConfigKeys.ACCELERATOR,
          defaultValue = Accelerator.GPU.label,
        )
      val newInitializedBackends =
        if (status.status == ModelInitializationStatusType.INITIALIZED) {
          initializedBackends + backend
        } else {
          initializedBackends
        }
      curStatus[model.name] = status.copy(initializedBackends = newInitializedBackends)
      _uiState.update { _uiState.value.copy(modelInitializationStatus = curStatus) }
    }
  }

  fun addTextInputHistory(text: String) {
    if (uiState.value.textInputHistory.indexOf(text) < 0) {
      val newHistory = uiState.value.textInputHistory.toMutableList()
      newHistory.add(0, text)
      if (newHistory.size > TEXT_INPUT_HISTORY_MAX_SIZE) {
        newHistory.removeAt(newHistory.size - 1)
      }
      _uiState.update { _uiState.value.copy(textInputHistory = newHistory) }
      dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory)
    } else {
      promoteTextInputHistoryItem(text)
    }
  }

  fun promoteTextInputHistoryItem(text: String) {
    val index = uiState.value.textInputHistory.indexOf(text)
    if (index >= 0) {
      val newHistory = uiState.value.textInputHistory.toMutableList()
      newHistory.removeAt(index)
      newHistory.add(0, text)
      _uiState.update { _uiState.value.copy(textInputHistory = newHistory) }
      dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory)
    }
  }

  fun deleteTextInputHistory(text: String) {
    val index = uiState.value.textInputHistory.indexOf(text)
    if (index >= 0) {
      val newHistory = uiState.value.textInputHistory.toMutableList()
      newHistory.removeAt(index)
      _uiState.update { _uiState.value.copy(textInputHistory = newHistory) }
      dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory)
    }
  }

  fun clearTextInputHistory() {
    _uiState.update { _uiState.value.copy(textInputHistory = mutableListOf()) }
    dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory)
  }

  fun readThemeOverride(): Theme {
    return dataStoreRepository.readTheme()
  }

  fun saveThemeOverride(theme: Theme) {
    dataStoreRepository.saveTheme(theme = theme)
  }

  fun getModelUrlResponse(model: Model, accessToken: String? = null): Int {
    try {
      val url = URL(model.url)
      val connection = url.openConnection() as HttpURLConnection
      if (accessToken != null) {
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
      }
      connection.connect()
      return connection.responseCode
    } catch (e: Exception) {
      Log.e(TAG, "$e")
      return -1
    }
  }

  fun addImportedLlmModel(info: ImportedModel) {
    Log.d(TAG, "adding imported llm model: $info")

    val model = createModelFromImportedModelInfo(info = info)

    val setOfTasks = mutableSetOf(BuiltInTaskId.LLM_CHAT)
    for (task in getTasksByIds(ids = setOfTasks)) {
      val modelIndex = task.models.indexOfFirst { info.fileName == it.name && it.imported }
      if (modelIndex >= 0) {
        Log.d(TAG, "duplicated imported model found in task. Removing it first")
        task.models.removeAt(modelIndex)
      }
      task.models.add(model)
      task.updateTrigger.value = System.currentTimeMillis()
    }

    val modelDownloadStatus = uiState.value.modelDownloadStatus.toMutableMap()
    val modelInstances = uiState.value.modelInitializationStatus.toMutableMap()
    modelDownloadStatus[model.name] =
      ModelDownloadStatus(
        status = ModelDownloadStatusType.SUCCEEDED,
        receivedBytes = info.fileSize,
        totalBytes = info.fileSize,
      )
    modelInstances[model.name] =
      ModelInitializationStatus(status = ModelInitializationStatusType.NOT_INITIALIZED)

    _uiState.update {
      uiState.value.copy(
        tasks = uiState.value.tasks.toList(),
        modelDownloadStatus = modelDownloadStatus,
        modelInitializationStatus = modelInstances,
        modelImportingUpdateTrigger = System.currentTimeMillis(),
      )
    }

    val importedModels = dataStoreRepository.readImportedModels().toMutableList()
    val importedModelIndex = importedModels.indexOfFirst { info.fileName == it.fileName }
    if (importedModelIndex >= 0) {
      Log.d(TAG, "duplicated imported model found in data store. Removing it first")
      importedModels.removeAt(importedModelIndex)
    }
    importedModels.add(info)
    dataStoreRepository.saveImportedModels(importedModels = importedModels)
  }

  private fun processPendingDownloads() {
    downloadRepository.cancelAll {
      Log.d(TAG, "All workers are cancelled.")

      viewModelScope.launch(Dispatchers.Main) {
        val checkedModelNames = mutableSetOf<String>()
        for (task in uiState.value.tasks) {
          for (model in task.models) {
            if (checkedModelNames.contains(model.name)) {
              continue
            }

            val downloadStatus = uiState.value.modelDownloadStatus[model.name]?.status
            if (downloadStatus == ModelDownloadStatusType.PARTIALLY_DOWNLOADED) {
              Log.d(TAG, "Sending a new download request for '${model.name}'")
              downloadRepository.downloadModel(
                task = task,
                model = model,
                onStatusUpdated = this@ModelManagerViewModel::setDownloadStatus,
              )
            }

            checkedModelNames.add(model.name)
          }
        }
      }
    }
  }

  fun loadModelAllowlist() {
    _uiState.update {
      uiState.value.copy(loadingModelAllowlist = true, loadingModelAllowlistError = "")
    }

    viewModelScope.launch(Dispatchers.IO) {
      try {
        _allowlistModels.clear()

        var modelAllowlist: ModelAllowlist? = null

        Log.d(TAG, "Loading test model allowlist.")
        modelAllowlist = readModelAllowlistFromDisk(fileName = MODEL_ALLOWLIST_TEST_FILENAME)

        if (TEST_MODEL_ALLOW_LIST.isNotEmpty()) {
          Log.d(TAG, "Loading local model allowlist for testing.")
          val gson = Gson()
          try {
            modelAllowlist = gson.fromJson(TEST_MODEL_ALLOW_LIST, ModelAllowlist::class.java)
          } catch (e: JsonSyntaxException) {
            Log.e(TAG, "Failed to parse local test json", e)
          }
        }

        if (modelAllowlist == null) {
          var version = BuildConfig.VERSION_NAME.replace(".", "_")
          val url = getAllowlistUrl(version)
          Log.d(TAG, "Loading model allowlist from internet. Url: $url")
          val data = getJsonResponse<ModelAllowlist>(url = url)
          modelAllowlist = data?.jsonObj

          if (modelAllowlist == null) {
            Log.w(TAG, "Failed to load model allowlist from internet. Trying to load it from disk")
            modelAllowlist = readModelAllowlistFromDisk()
          } else {
            Log.d(TAG, "Done: loading model allowlist from internet")
            saveModelAllowlistToDisk(modelAllowlistContent = data?.textContent ?: "{}")
          }
        }

        if (modelAllowlist == null) {
          _uiState.update {
            uiState.value.copy(loadingModelAllowlistError = "Failed to load model list")
          }
          return@launch
        }

        Log.d(TAG, "Allowlist: $modelAllowlist")

        val curTasks = getActiveCustomTasks().map { it.task }
        val nameToModel = mutableMapOf<String, Model>()
        for (allowedModel in modelAllowlist.models) {
          if (allowedModel.disabled == true) {
            continue
          }

          if (allowedModel.runtimeType == com.pockettalk.app.data.RuntimeType.AICORE) {
            continue
          }

          val accelerators = allowedModel.defaultConfig.accelerators ?: ""
          val acceleratorList = accelerators.split(",").map { it.trim() }.filter { it.isNotEmpty() }
          if (acceleratorList.size == 1 && acceleratorList[0] == "npu") {
            val socToModelFiles = allowedModel.socToModelFiles
            if (socToModelFiles != null && !socToModelFiles.containsKey(SOC)) {
              Log.d(
                TAG,
                "Ignoring model '${allowedModel.name}' because it's NPU-only and not supported on SOC: $SOC",
              )
              continue
            }
          }

          val model = allowedModel.toModel()
          _allowlistModels.add(model)
          nameToModel.put(model.name, model)
          for (taskType in allowedModel.taskTypes) {
            val task = curTasks.find { it.id == taskType }
            task?.models?.add(model)
          }
        }

        for (task in curTasks) {
          if (task.modelNames.isNotEmpty()) {
            for (modelName in task.modelNames) {
              val model = nameToModel[modelName]
              if (model == null) {
                Log.w(TAG, "Model '${modelName}' in task '${task.label}' not found in allowlist.")
                continue
              }
              task.models.add(model)
            }
          }
        }

        processTasks()

        _uiState.update {
          createUiState()
            .copy(
              loadingModelAllowlist = false,
              tasks = curTasks,
              tasksByCategory = groupTasksByCategory(),
            )
        }

        processPendingDownloads()
      } catch (e: Exception) {
        e.printStackTrace()
      }
    }
  }

  fun clearLoadModelAllowlistError() {
    val curTasks = getActiveCustomTasks().map { it.task }
    processTasks()
    _uiState.update {
      createUiState()
        .copy(
          loadingModelAllowlist = false,
          tasks = curTasks,
          loadingModelAllowlistError = "",
          tasksByCategory = groupTasksByCategory(),
        )
    }
  }

  fun setAppInForeground(foreground: Boolean) {
    lifecycleProvider.isAppInForeground = foreground
  }

  private fun saveModelAllowlistToDisk(modelAllowlistContent: String) {
    try {
      Log.d(TAG, "Saving model allowlist to disk...")
      val file = File(externalFilesDir, MODEL_ALLOWLIST_FILENAME)
      file.writeText(modelAllowlistContent)
      Log.d(TAG, "Done: saving model allowlist to disk.")
    } catch (e: Exception) {
      Log.e(TAG, "failed to write model allowlist to disk", e)
    }
  }

  private fun readModelAllowlistFromDisk(
    fileName: String = MODEL_ALLOWLIST_FILENAME
  ): ModelAllowlist? {
    try {
      Log.d(TAG, "Reading model allowlist from disk: $fileName")
      val baseDir =
        if (fileName == MODEL_ALLOWLIST_TEST_FILENAME) File("/data/local/tmp") else externalFilesDir
      val file = File(baseDir, fileName)
      if (file.exists()) {
        val content = file.readText()
        Log.d(TAG, "Model allowlist content from local file: $content")

        val gson = Gson()
        return gson.fromJson(content, ModelAllowlist::class.java)
      }
    } catch (e: Exception) {
      Log.e(TAG, "failed to read model allowlist from disk", e)
      return null
    }

    return null
  }

  private fun isModelPartiallyDownloaded(model: Model): Boolean {
    if (model.localModelFilePathOverride.isNotEmpty()) {
      return false
    }

    val tmpFilePath =
      model.getPath(context = context, fileName = "${model.downloadFileName}.$TMP_FILE_EXT")
    return File(tmpFilePath).exists()
  }

  private fun createEmptyUiState(): ModelManagerUiState {
    return ModelManagerUiState(
      tasks = listOf(),
      tasksByCategory = mapOf(),
      modelDownloadStatus = mapOf(),
      modelInitializationStatus = mapOf(),
    )
  }

  private fun createUiState(): ModelManagerUiState {
    val modelDownloadStatus: MutableMap<String, ModelDownloadStatus> = mutableMapOf()
    val modelInstances: MutableMap<String, ModelInitializationStatus> = mutableMapOf()
    val tasks: MutableMap<String, Task> = mutableMapOf()
    val checkedModelNames = mutableSetOf<String>()
    for (customTask in getActiveCustomTasks()) {
      val task = customTask.task
      tasks.put(key = task.id, value = task)
      for (model in task.models) {
        if (checkedModelNames.contains(model.name)) {
          continue
        }
        modelDownloadStatus[model.name] = getModelDownloadStatus(model = model)
        modelInstances[model.name] =
          ModelInitializationStatus(status = ModelInitializationStatusType.NOT_INITIALIZED)
        checkedModelNames.add(model.name)
      }
    }

    for (importedModel in dataStoreRepository.readImportedModels()) {
      Log.d(TAG, "stored imported model: $importedModel")
      val model = createModelFromImportedModelInfo(info = importedModel)

      tasks.get(key = BuiltInTaskId.LLM_CHAT)?.models?.add(model)

      modelDownloadStatus[model.name] =
        ModelDownloadStatus(
          status = ModelDownloadStatusType.SUCCEEDED,
          receivedBytes = importedModel.fileSize,
          totalBytes = importedModel.fileSize,
        )
    }

    val textInputHistory = dataStoreRepository.readTextInputHistory()
    Log.d(TAG, "text input history: $textInputHistory")

    Log.d(TAG, "model download status: $modelDownloadStatus")
    return ModelManagerUiState(
      tasks = getActiveCustomTasks().map { it.task }.toList(),
      tasksByCategory = mapOf(),
      modelDownloadStatus = modelDownloadStatus,
      modelInitializationStatus = modelInstances,
      textInputHistory = textInputHistory,
    )
  }

  private fun createModelFromImportedModelInfo(info: ImportedModel): Model {
    val accelerators: MutableList<Accelerator> =
      info.llmConfig.compatibleAcceleratorsList
        .mapNotNull { acceleratorLabel ->
          when (acceleratorLabel.trim()) {
            Accelerator.GPU.label -> Accelerator.GPU
            Accelerator.CPU.label -> Accelerator.CPU
            Accelerator.NPU.label -> Accelerator.NPU
            else -> null
          }
        }
        .toMutableList()
    val llmMaxToken = info.llmConfig.defaultMaxTokens
    val llmSupportThinking = info.llmConfig.supportThinking
    val configs: MutableList<Config> =
      createLlmChatConfigs(
          defaultMaxToken = llmMaxToken,
          defaultTopK = info.llmConfig.defaultTopk,
          defaultTopP = info.llmConfig.defaultTopp,
          defaultTemperature = info.llmConfig.defaultTemperature,
          accelerators = accelerators,
          supportThinking = llmSupportThinking,
        )
        .toMutableList()
    val model =
      Model(
        name = info.fileName,
        url = "",
        configs = configs,
        sizeInBytes = info.fileSize,
        downloadFileName = "$IMPORTS_DIR/${info.fileName}",
        showBenchmarkButton = false,
        showRunAgainButton = false,
        imported = true,
        capabilities =
          if (llmSupportThinking) listOf(ModelCapability.LLM_THINKING) else emptyList(),
        capabilityToTaskTypes =
          if (llmSupportThinking) {
            mapOf(
              ModelCapability.LLM_THINKING to listOf(BuiltInTaskId.LLM_CHAT)
            )
          } else {
            emptyMap()
          },
        llmMaxToken = llmMaxToken,
        accelerators = accelerators,
        isLlm = true,
        runtimeType = com.pockettalk.app.data.RuntimeType.LITERT_LM,
      )
    model.preProcess()

    return model
  }

  private fun groupTasksByCategory(): Map<String, List<Task>> {
    val tasks = getActiveCustomTasks().map { it.task }

    val groupedTasks = tasks.groupBy { it.category.id }
    val groupedSortedTasks: MutableMap<String, List<Task>> = mutableMapOf()
    for (categoryId in groupedTasks.keys) {
      val sortedTasks =
        groupedTasks[categoryId]!!.sortedWith { a, b -> a.label.compareTo(b.label) }
      for ((index, task) in sortedTasks.withIndex()) {
        task.index = index
      }
      groupedSortedTasks[categoryId] = sortedTasks
    }

    return groupedSortedTasks
  }

  private fun getModelDownloadStatus(model: Model): ModelDownloadStatus {
    Log.d(TAG, "Checking model ${model.name} download status...")

    if (model.localFileRelativeDirPathOverride.isNotEmpty()) {
      Log.d(TAG, "Model has localFileRelativeDirPathOverride set. Set status to SUCCEEDED")
      return ModelDownloadStatus(
        status = ModelDownloadStatusType.SUCCEEDED,
        receivedBytes = 0,
        totalBytes = 0,
      )
    }

    var status = ModelDownloadStatusType.NOT_DOWNLOADED
    var receivedBytes = 0L
    var totalBytes = 0L

    if (isModelPartiallyDownloaded(model = model)) {
      status = ModelDownloadStatusType.PARTIALLY_DOWNLOADED
      val tmpFilePath =
        model.getPath(context = context, fileName = "${model.downloadFileName}.$TMP_FILE_EXT")
      val tmpFile = File(tmpFilePath)
      receivedBytes = tmpFile.length()
      totalBytes = model.totalBytes
      Log.d(TAG, "${model.name} is partially downloaded. $receivedBytes/$totalBytes")
    } else if (isModelDownloaded(model = model)) {
      status = ModelDownloadStatusType.SUCCEEDED
      Log.d(TAG, "${model.name} has been downloaded.")
    } else {
      Log.d(TAG, "${model.name} has not been downloaded.")
    }

    return ModelDownloadStatus(
      status = status,
      receivedBytes = receivedBytes,
      totalBytes = totalBytes,
    )
  }

  private fun isFileInExternalFilesDir(fileName: String): Boolean {
    if (externalFilesDir != null) {
      val file = File(externalFilesDir, fileName)
      return file.exists()
    } else {
      return false
    }
  }

  private fun deleteFileFromExternalFilesDir(fileName: String) {
    if (isFileInExternalFilesDir(fileName)) {
      val file = File(externalFilesDir, fileName)
      file.delete()
    }
  }

  private fun deleteFilesFromImportDir(fileName: String) {
    val dir = context.getExternalFilesDir(null) ?: return

    val prefixAbsolutePath = "${context.getExternalFilesDir(null)}${File.separator}$fileName"
    val filesToDelete =
      File(dir, IMPORTS_DIR).listFiles { dirFile, name ->
        File(dirFile, name).absolutePath.startsWith(prefixAbsolutePath)
      } ?: arrayOf()
    for (file in filesToDelete) {
      Log.d(TAG, "Deleting file: ${file.name}")
      file.delete()
    }
  }

  private fun deleteDirFromExternalFilesDir(dir: String) {
    if (isFileInExternalFilesDir(dir)) {
      val file = File(externalFilesDir, dir)
      file.deleteRecursively()
    }
  }

  private fun updateModelInitializationStatus(
    model: Model,
    status: ModelInitializationStatusType,
    error: String = "",
  ) {
    val curModelInstance = uiState.value.modelInitializationStatus.toMutableMap()
    val initializedBackends = curModelInstance[model.name]?.initializedBackends ?: setOf()
    val backend =
      model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)
    val newInitializedBackends =
      if (status == ModelInitializationStatusType.INITIALIZED) {
        initializedBackends + backend
      } else {
        initializedBackends
      }
    curModelInstance[model.name] =
      ModelInitializationStatus(
        status = status,
        error = error,
        initializedBackends = newInitializedBackends,
      )
    val newUiState = uiState.value.copy(modelInitializationStatus = curModelInstance)
    _uiState.update { newUiState }
  }

  @androidx.annotation.VisibleForTesting
  fun isModelDownloaded(model: Model): Boolean {
    model.updatable = false
    if (checkIfModelDownloaded(model, model.version)) return true

    for (updatableFile in model.updatableModelFiles) {
      if (updatableFile.commitHash.isEmpty()) continue
      if (checkIfModelDownloaded(model, updatableFile.commitHash, updatableFile.fileName)) {
        model.version = updatableFile.commitHash
        model.downloadFileName = updatableFile.fileName
        model.updatable = true
        return true
      }
    }

    return false
  }

  private fun checkIfModelDownloaded(
    model: Model,
    version: String,
    fileName: String = model.downloadFileName,
  ): Boolean {
    val modelRelativePath =
      listOf(model.normalizedName, version, fileName).joinToString(File.separator)
    val downloadedFileExists =
      fileName.isNotEmpty() &&
        ((model.localModelFilePathOverride.isEmpty() &&
          isFileInExternalFilesDir(modelRelativePath)) ||
          (model.localModelFilePathOverride.isNotEmpty() &&
            File(model.localModelFilePathOverride).exists()))

    val unzippedDirectoryExists =
      model.isZip &&
        model.unzipDir.isNotEmpty() &&
        isFileInExternalFilesDir(
          listOf(model.normalizedName, version, model.unzipDir).joinToString(File.separator)
        )

    return downloadedFileExists || unzippedDirectoryExists
  }
}

private fun getAllowlistUrl(version: String): String {
  return "$ALLOWLIST_BASE_URL/${version}.json"
}
