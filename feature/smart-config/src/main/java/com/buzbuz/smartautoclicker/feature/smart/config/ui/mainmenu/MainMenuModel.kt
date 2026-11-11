/*
 * Copyright (C) 2025 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.mainmenu

import android.content.Context
import android.util.Log
import android.view.View

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buzbuz.smartautoclicker.core.common.navigation.getRevenueNavigator
import com.buzbuz.smartautoclicker.core.common.revenue.domain.IRevenueRepository
import com.buzbuz.smartautoclicker.core.common.revenue.domain.model.UserBillingState

import com.buzbuz.smartautoclicker.core.common.tutorial.domain.TutorialRepository
import com.buzbuz.smartautoclicker.core.common.tutorial.domain.model.Tip
import com.buzbuz.smartautoclicker.core.domain.model.counter.resolveCounterInitialValues
import com.buzbuz.smartautoclicker.core.processing.domain.SmartProcessingRepository
import com.buzbuz.smartautoclicker.core.processing.domain.model.DetectionState
import com.buzbuz.smartautoclicker.core.smart.debugging.domain.DebuggingRepository
import com.buzbuz.smartautoclicker.core.common.tutorial.domain.MonitoredViewsManager
import com.buzbuz.smartautoclicker.core.common.tutorial.impl.monitoring.ViewPositioningType
import com.buzbuz.smartautoclicker.core.common.tutorial.domain.model.monitoring.MonitoredViewType
import com.buzbuz.smartautoclicker.feature.smart.config.domain.EditionRepository
import com.buzbuz.smartautoclicker.feature.smart.config.domain.usecase.alphabet.AreRequiredAlphabetModelsInstalledUseCase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import javax.inject.Inject

/** View model for the [MainMenu]. */
class MainMenuModel @Inject constructor(
    private val smartProcessingRepository: SmartProcessingRepository,
    private val editionRepository: EditionRepository,
    private val tutorialRepository: TutorialRepository,
    private val revenueRepository: IRevenueRepository,
    private val monitoredViewsManager: MonitoredViewsManager,
    private val debuggingRepository: DebuggingRepository,
    areRequiredAlphabetModelsInstalledUseCase: AreRequiredAlphabetModelsInstalledUseCase,
) : ViewModel() {

    private val scenarioDbId: StateFlow<Long?> = smartProcessingRepository.scenarioId
        .map { it?.databaseId }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    private var paywallResultJob: Job? = null
    private var startJob: Job? = null
    private val _startQuestions = MutableStateFlow<StartQuestionsRequest?>(null)
    val startQuestions: StateFlow<StartQuestionsRequest?> = _startQuestions


    /** Tells if the paywall is currently displayed. */
    private val _paywallIsVisible: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val paywallIsVisible: StateFlow<Boolean> = _paywallIsVisible

    val shouldShowStopWithVolumeDownTip: StateFlow<Boolean> = tutorialRepository
        .shouldShowTip(Tip.STOP_WITH_VOLUME_DOWN)
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Tells if the detection is currently running. */
    private val isDetecting: StateFlow<Boolean> = smartProcessingRepository.detectionState
        .map { it == DetectionState.DETECTING }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val isMediaProjectionStarted: StateFlow<Boolean> = smartProcessingRepository.detectionState
        .map { it == DetectionState.RECORDING || it == DetectionState.DETECTING }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** The condition being configured by the user. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val allModelsInstalled: StateFlow<Boolean> = scenarioDbId
        .flatMapLatest { identifier ->
            identifier?.let { dbId -> areRequiredAlphabetModelsInstalledUseCase(dbId) } ?: flowOf(true)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Tells if the scenario can be started. Edited scenario must be synchronized and engine should allow it. */
    private val isStartButtonEnabled: Flow<Boolean> = combine(
        smartProcessingRepository.canStartDetection,
        editionRepository.isEditionSynchronized,
        isMediaProjectionStarted
    ) { canStartDetection, isSynchronized, isProjectionStarted ->
        (canStartDetection || !isProjectionStarted) && isSynchronized
    }

    /** The state of the menu. Null until all values are known. */
    val uiState: StateFlow<MainMenuUiState?> = combine(
        isDetecting,
        isStartButtonEnabled,
        isMediaProjectionStarted,
        debuggingRepository.isLiveDebugging,
    ) { isDetecting, isStartEnabled, isProjectionStarted, isLiveDebugging ->
        MainMenuUiState(
            isDetecting = isDetecting,
            isPlayButtonEnabled = isStartEnabled,
            isProjectionErrorVisible = !isProjectionStarted,
            isDebugPanelVisible = isLiveDebugging,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Tells if the detector can't work due to a native library load error. */
    val nativeLibError: Flow<Boolean> = smartProcessingRepository.detectionState
        .map { it == DetectionState.ERROR_NO_NATIVE_LIB }
        .distinctUntilChanged()

    /** Tells if the device's GPU driver can't expose screen capture buffers for CPU access. */
    val screenCaptureError: Flow<Boolean> = smartProcessingRepository.detectionState
        .map { it == DetectionState.ERROR_SCREEN_IMAGE_CAPTURE_FAILED }
        .distinctUntilChanged()

    /** Load an advertisement, if needed. Should be called before showing the paywall to reduce user waiting time. */
    fun loadAdIfNeeded(context: Context) {
        revenueRepository.loadAdIfNeeded(context)
    }

    /** Start/Stop the detection. */
    fun toggleDetection(context: Context) {
        if (startJob?.isActive == true || _startQuestions.value != null || _paywallIsVisible.value) return
        when {
            isDetecting.value -> stopDetection()
            shouldStartPaywall() -> startPaywall(context)
            else -> startDetection(context)
        }
    }

    /** Stop the detection. Returns true if it was started, false if not. */
    fun stopDetection(): Boolean {
        if (!isDetecting.value) return false

        smartProcessingRepository.stopDetection()
        return true
    }

    private fun shouldStartPaywall(): Boolean =
        revenueRepository.userBillingState.value.isAdRequested() &&
                !tutorialRepository.isTutorialStarted()

    private fun startPaywall(context: Context) {
        context.getRevenueNavigator().startPaywallActivity(context)
        _paywallIsVisible.value = true

        paywallResultJob = revenueRepository.paywallFinished
            .take(1)
            .onEach {
                Log.d(TAG, "onPaywall finished")
                _paywallIsVisible.value = false
                if (!revenueRepository.userBillingState.value.isAdRequested()) startDetection(context)
                paywallResultJob = null
            }
            .launchIn(viewModelScope)
    }

    private fun startDetection(context: Context) {
        startJob = viewModelScope.launch {
            val id = smartProcessingRepository.scenarioId.value
            val counters = smartProcessingRepository.getStartCounters()
            if (id != smartProcessingRepository.scenarioId.value) return@launch
            if (counters.any { it.startQuestion != null }) {
                if (id == null) return@launch
                val store = RecentStartAnswers(context)
                _startQuestions.value = StartQuestionsRequest(id.databaseId, counters, store.read(id.databaseId, counters))
            } else {
                launchDetection(context, emptyMap())
            }
        }
    }

    /** Cancel discards only this draft. Example: call from the form's Cancel button. */
    fun cancelStartQuestions() {
        _startQuestions.value = null
    }

    /** Confirms one whole answer set; e.g. `confirmStartQuestions(context, answers)` from Iniciar. */
    fun confirmStartQuestions(context: Context, answers: Map<String, Double>) {
        val request = _startQuestions.value ?: return
        if (request.scenarioId != smartProcessingRepository.scenarioId.value?.databaseId) {
            _startQuestions.value = null
            return
        }
        require(answers.keys == request.counters.filter { it.startQuestion != null }.map { it.counterName }.toSet()) {
            "answers: expected one value for every declared question"
        }
        resolveCounterInitialValues(request.counters, answers)
        _startQuestions.value = null
        RecentStartAnswers(context).write(request.scenarioId, request.counters, answers)
        startJob = viewModelScope.launch { launchDetection(context, answers) }
    }

    private suspend fun launchDetection(context: Context, answers: Map<String, Double>) {
        smartProcessingRepository.startDetection(
            context = context,
            autoStopDuration = revenueRepository.consumeTrial(),
            liveDebugging = debuggingRepository.isDebugViewEnabled(),
            generateReport = debuggingRepository.isDebugReportEnabled(),
            initialAnswers = answers,
        )
    }

    fun startScenarioEdition(onEditionStarted: () -> Unit) {
        scenarioDbId.value?.let { scenarioDatabaseId ->
            viewModelScope.launch(Dispatchers.IO) {
                if (editionRepository.startEdition(scenarioDatabaseId)) {
                    withContext(Dispatchers.Main) { onEditionStarted() }
                }
            }
        }
    }

    /** Save the configured scenario in the database. */
    fun saveScenarioChanges(onCompleted: (success: Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = editionRepository.saveEditions()

            withContext(Dispatchers.Main) {
                onCompleted(result)
            }
        }
    }

    /** Cancel all changes made by the user. */
    fun cancelScenarioChanges() {
        viewModelScope.launch(Dispatchers.IO) {
            editionRepository.stopEdition()
        }
    }

    fun monitorViews(playMenuButton: View, configMenuButton: View) {
        monitoredViewsManager.apply {
            attach(MonitoredViewType.MAIN_MENU_BUTTON_PLAY, playMenuButton, ViewPositioningType.SCREEN)
            attach(MonitoredViewType.MAIN_MENU_BUTTON_CONFIG, configMenuButton, ViewPositioningType.SCREEN)
        }
    }

    fun stopViewMonitoring() {
        monitoredViewsManager.apply {
            detach(MonitoredViewType.MAIN_MENU_BUTTON_PLAY)
            detach(MonitoredViewType.MAIN_MENU_BUTTON_CONFIG)
        }
    }

    fun shouldDownloadModels(): Boolean =
        !allModelsInstalled.value

    fun shouldRestartMediaProjection(): Boolean =
        !isMediaProjectionStarted.value

    fun shouldShowStopVolumeDownTutorialDialog(): Boolean =
        !isDetecting.value && shouldShowStopWithVolumeDownTip.value

    private fun UserBillingState.isAdRequested(): Boolean =
        this == UserBillingState.AD_REQUESTED
}

private const val TAG = "MainMenuViewModel"
