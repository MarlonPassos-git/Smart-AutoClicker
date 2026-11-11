/*
 * Copyright (C) 2026 Kevin Buzeau
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
import android.os.Build

import com.buzbuz.smartautoclicker.core.common.navigation.RevenueNavigator
import com.buzbuz.smartautoclicker.core.common.navigation.getRevenueNavigator
import com.buzbuz.smartautoclicker.core.common.revenue.domain.IRevenueRepository
import com.buzbuz.smartautoclicker.core.common.revenue.domain.model.UserBillingState
import com.buzbuz.smartautoclicker.core.common.tutorial.domain.TutorialRepository
import com.buzbuz.smartautoclicker.core.processing.domain.SmartProcessingRepository
import com.buzbuz.smartautoclicker.core.processing.domain.model.DetectionState
import com.buzbuz.smartautoclicker.core.smart.debugging.domain.DebuggingRepository
import com.buzbuz.smartautoclicker.feature.smart.config.domain.EditionRepository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class MainMenuModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val userBillingState = MutableStateFlow(UserBillingState.AD_REQUESTED)
    private val paywallFinished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val mockContext: Context = mockk()
    private val mockRevenueNavigator: RevenueNavigator = mockk(relaxed = true)
    private val mockRevenueRepository: IRevenueRepository = mockk(relaxed = true) {
        every { userBillingState } returns this@MainMenuModelTest.userBillingState
        every { paywallFinished } returns this@MainMenuModelTest.paywallFinished
    }
    private val mockSmartProcessingRepository: SmartProcessingRepository = mockk(relaxed = true) {
        every { scenarioId } returns MutableStateFlow(null)
        every { detectionState } returns flowOf(DetectionState.RECORDING)
        every { canStartDetection } returns flowOf(true)
    }
    private val mockEditionRepository: EditionRepository = mockk(relaxed = true) {
        every { isEditionSynchronized } returns flowOf(true)
    }
    private val mockTutorialRepository: TutorialRepository = mockk(relaxed = true) {
        every { shouldShowTip(any()) } returns flowOf(false)
        every { isTutorialStarted() } returns false
    }
    private val mockDebuggingRepository: DebuggingRepository = mockk(relaxed = true) {
        every { isLiveDebugging } returns flowOf(false)
    }

    private lateinit var viewModel: MainMenuModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(REVENUE_NAVIGATOR_FILE)
        every { any<Context>().getRevenueNavigator() } returns mockRevenueNavigator

        viewModel = MainMenuModel(
            mockSmartProcessingRepository,
            mockEditionRepository,
            mockTutorialRepository,
            NoTrialRevenueRepository(mockRevenueRepository),
            mockk(relaxed = true),
            mockDebuggingRepository,
            mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        unmockkStatic(REVENUE_NAVIGATOR_FILE)
        Dispatchers.resetMain()
    }

    @Test
    fun toggleDetection_showsPaywallWithoutWaitingForGooglePlay_whenAdRequested() {
        viewModel.toggleDetection(mockContext)

        coVerify(exactly = 0) { mockRevenueRepository.refreshUnsettledPurchases() }
        verify(exactly = 1) { mockRevenueNavigator.startPaywallActivity(mockContext) }
        coVerify(exactly = 0) { mockSmartProcessingRepository.startDetection(mockContext, false, false, null) }
    }

    @Test
    fun toggleDetection_startsDetection_whenPaywallFinishesPurchased() {
        viewModel.toggleDetection(mockContext)
        userBillingState.value = UserBillingState.PURCHASED
        paywallFinished.tryEmit(Unit)

        coVerify(exactly = 1) { mockSmartProcessingRepository.startDetection(mockContext, false, false, null) }
    }

    @Test
    fun toggleDetection_doesNotStartDetection_whenPaywallFinishesStillAdRequested() {
        viewModel.toggleDetection(mockContext)
        paywallFinished.tryEmit(Unit)

        coVerify(exactly = 0) { mockSmartProcessingRepository.startDetection(mockContext, false, false, null) }
    }

    @Test
    fun toggleDetection_startsDetectionWithoutPaywall_whenAlreadyPurchased() {
        userBillingState.value = UserBillingState.PURCHASED

        viewModel.toggleDetection(mockContext)

        verify(exactly = 0) { mockRevenueNavigator.startPaywallActivity(any()) }
        coVerify(exactly = 1) { mockSmartProcessingRepository.startDetection(mockContext, false, false, null) }
    }

    @Test
    fun questions_cancelDoesNotStartOrOverwriteConfirmedAnswers() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val counter = prepareQuestions(context)
        RecentStartAnswers(context).write(7, listOf(counter), mapOf("target" to 75.0))

        viewModel.toggleDetection(context)
        org.junit.Assert.assertEquals(75.0, viewModel.startQuestions.value!!.initialAnswers.getValue("target"), 0.0)
        viewModel.toggleDetection(context)
        coVerify(exactly = 1) { mockSmartProcessingRepository.getStartCounters() }
        viewModel.cancelStartQuestions()
        coVerify(exactly = 0) { mockSmartProcessingRepository.startDetection(any(), any(), any(), any(), any()) }
        org.junit.Assert.assertEquals(75.0, RecentStartAnswers(context).read(7, listOf(counter)).getValue("target"), 0.0)
    }

    @Test
    fun questions_confirmStartsOnceAndPreservesDefaults() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val counter = prepareQuestions(context)
        viewModel.toggleDetection(context)
        viewModel.confirmStartQuestions(context, mapOf("target" to 90.0))
        viewModel.confirmStartQuestions(context, mapOf("target" to 80.0))

        coVerify(exactly = 1) { mockSmartProcessingRepository.startDetection(context, false, false, null, mapOf("target" to 90.0)) }
        org.junit.Assert.assertEquals(50.0, counter.defaultValue, 0.0)
        org.junit.Assert.assertEquals(90.0, RecentStartAnswers(context).read(7, listOf(counter)).getValue("target"), 0.0)
    }

    @Test
    fun questions_invalidAnswerCannotStartOrPersistAndScenarioChangeCancels() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val counter = prepareQuestions(context)
        viewModel.toggleDetection(context)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            viewModel.confirmStartQuestions(context, mapOf("target" to 101.0))
        }
        org.junit.Assert.assertNotNull(viewModel.startQuestions.value)
        org.junit.Assert.assertEquals(50.0, RecentStartAnswers(context).read(7, listOf(counter)).getValue("target"), 0.0)
        every { mockSmartProcessingRepository.scenarioId } returns MutableStateFlow(com.buzbuz.smartautoclicker.core.base.identifier.Identifier(databaseId = 8L))
        viewModel.confirmStartQuestions(context, mapOf("target" to 90.0))
        org.junit.Assert.assertNull(viewModel.startQuestions.value)
        coVerify(exactly = 0) { mockSmartProcessingRepository.startDetection(any(), any(), any(), any(), any()) }
    }

    @Test
    fun questions_scenarioChangeDuringCounterLoadingDoesNotOpenFormOrStart() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        for (hasQuestions in listOf(true, false)) {
            val counter = prepareQuestions(context)
            val scenario = MutableStateFlow<com.buzbuz.smartautoclicker.core.base.identifier.Identifier?>(counter.scenarioId)
            every { mockSmartProcessingRepository.scenarioId } returns scenario
            coEvery { mockSmartProcessingRepository.getStartCounters() } coAnswers {
                scenario.value = com.buzbuz.smartautoclicker.core.base.identifier.Identifier(databaseId = 8L)
                if (hasQuestions) listOf(counter) else emptyList()
            }

            viewModel.toggleDetection(context)

            org.junit.Assert.assertNull(viewModel.startQuestions.value)
            coVerify(exactly = 0) { mockSmartProcessingRepository.startDetection(any(), any(), any(), any(), any()) }
        }
    }

    private fun prepareQuestions(context: Context): com.buzbuz.smartautoclicker.core.domain.model.counter.Counter {
        context.getSharedPreferences("scenario_start_answers", Context.MODE_PRIVATE).edit().clear().commit()
        userBillingState.value = UserBillingState.PURCHASED
        val counter = com.buzbuz.smartautoclicker.core.domain.model.counter.Counter(
            "target", 50.0, com.buzbuz.smartautoclicker.core.base.identifier.Identifier(databaseId = 7L),
            com.buzbuz.smartautoclicker.core.domain.model.counter.CounterStartQuestion("Meta", minimum = 1.0, maximum = 100.0),
        )
        every { mockSmartProcessingRepository.scenarioId } returns MutableStateFlow(counter.scenarioId)
        coEvery { mockSmartProcessingRepository.getStartCounters() } returns listOf(counter)
        return counter
    }

    /** MockK can't stub [IRevenueRepository.consumeTrial] because it returns a value class. */
    private class NoTrialRevenueRepository(revenueRepository: IRevenueRepository) : IRevenueRepository by revenueRepository {
        override fun consumeTrial(): Duration? = null
    }
}

private const val REVENUE_NAVIGATOR_FILE = "com.buzbuz.smartautoclicker.core.common.navigation.RevenueNavigatorKt"
