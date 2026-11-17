package com.buzbuz.smartautoclicker.core.script.runtime

import com.buzbuz.smartautoclicker.core.script.runtime.api.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BalanceReadingIntegrationTest {
    @Test(timeout = 15000)
    fun aSingleReadableCropAcrossThreeFreshFramesConfirmsTheBalanceWithoutRepeatingTheFullScan() = runBlocking {
        val backend = BalanceBackend(listOf(7756688.0))
        execute(backend, """
            const history = new Map();
            for (let index=0;index<3;index++) {
                const current=await snapshot(['goldBalance']);
                const value=await readResourceBalance(current,'gold',history);
                if (value !== (index===2 ? 7756688 : undefined)) throw new Error('Wrong confirmation at '+index+': '+value);
            }
        """)
        assertTrue("Recovery should reuse the readable crop", backend.readsPerFrame[1] < backend.readsPerFrame[0] / 2)
    }

    @Test(timeout = 15000)
    fun changingBalancesRequireThreeNewMatchingFrames() = runBlocking {
        execute(BalanceBackend(listOf(7756688.0, 7756688.0, 7756690.0, 7756690.0, 7756690.0)), """
            const history=new Map();
            for (let index=0;index<5;index++) {
                const value=await readResourceBalance(await snapshot(['goldBalance']),'gold',history);
                if (value !== (index===4 ? 7756690 : undefined)) throw new Error('Old balance survived a change: '+value);
            }
        """)
    }

    @Test(timeout = 15000)
    fun contradictoryAndMissingReadingsResetTheSequence() = runBlocking {
        for (backend in listOf(
            BalanceBackend(listOf(7756688.0)).apply { conflictingFrames=setOf(2) },
            BalanceBackend(listOf(7756688.0, null, 7756688.0)),
        )) execute(backend, """
            const history=new Map();
            for (let index=0;index<5;index++) {
                const value=await readResourceBalance(await snapshot(['goldBalance']),'gold',history);
                if (value !== (index===4 ? 7756688 : undefined)) throw new Error('Uncertain reading retained evidence: '+value);
            }
        """)
    }

    @Test(timeout = 15000)
    fun rereadingTheSameFrameCannotConfirmTheBalance() = runBlocking {
        execute(BalanceBackend(listOf(7756688.0)), """
            const history=new Map();
            const current=await snapshot(['goldBalance']);
            for (let index=0;index<4;index++) {
                if (await readResourceBalance(current,'gold',history)!==undefined) throw new Error('Repeated frame counted twice');
            }
            if (await readResourceBalance(await snapshot(['goldBalance']),'gold',history)!==undefined) throw new Error('Only two frames');
            if (await readResourceBalance(await snapshot(['goldBalance']),'gold',history)!==7756688) throw new Error('Third frame ignored');
        """)
    }

    @Test(timeout = 15000)
    fun resumedObservationsCannotReuseEarlierBalanceEvidence() = runBlocking {
        execute(BalanceBackend(listOf(7756688.0)), """
            const history=new Map();
            for (let index=0;index<5;index++) {
                const current=await snapshot(['goldBalance']);
                current.frame={...current.frame,resumeGeneration:index<2 ? 0 : 1};
                const value=await readResourceBalance(current,'gold',history);
                if (value !== (index===4 ? 7756688 : undefined)) throw new Error('Resume retained earlier evidence: '+value);
            }
        """)
    }

    @Test(timeout = 15000)
    fun anUnreadableBalanceNeverBecomesZeroAndAStatelessCallStillRequiresCropAgreement() = runBlocking {
        execute(BalanceBackend(listOf(null)), """
            const history=new Map();
            for (let index=0;index<4;index++) {
                if (await readResourceBalance(await snapshot(['goldBalance']),'gold',history)!==undefined) throw new Error('Unreadable became zero');
            }
        """)
        execute(BalanceBackend(listOf(7756688.0)), """
            for (let index=0;index<3;index++) {
                if (await readResourceBalance(await snapshot(['goldBalance']),'gold')!==undefined) throw new Error('Stateless call accepted an isolated crop');
            }
        """)
    }

    @Test(timeout = 15000)
    fun theVillageLoopUsesTheStableBalanceBeforeMakingAResourceDecision() = runBlocking {
        execute(BalanceBackend(listOf(7756688.0)), """
            const reason=await runVillage({improveWalls:false,stopFarm:'nunca',goldMinimum:8000000,elixirMinimum:0,
                goldReserve:0,elixirReserve:0,maxBattles:1,maxWallBatch:50,finishMode:'destruição',targetDestruction:50});
            if (!reason.includes('Saldo mínimo de gold')) throw new Error('Stable balance did not reach the resource rule: '+reason);
        """)
    }

    private suspend fun execute(backend: BalanceBackend, source: String) {
        val modules=listOf("farm.js","battle.js","walls.js","spells.js","rewards.js","screen.js","profile.js","policy.js","villages.js")
        val program=ScriptProgram("entry.js",modules.associateWith(::resource)+("entry.js" to """
            import {snapshot} from './screen.js';
            import {readResourceBalance,runVillage} from './farm.js';
            $source
        """.trimIndent()))
        val manifest=Json.parseToJsonElement(resource("manifest.json")).jsonObject
        val host=ScreenAutomationHost(backend,{false},backend.clock::get,referenceProfile=ScreenProfile.parse(manifest.getValue("screen")))
        QuickJsScriptRuntime(Dispatchers.Default).execute(program,host)
    }

    private fun resource(path:String)=checkNotNull(javaClass.classLoader!!.getResourceAsStream("farm-walls-sm-s916b/$path"))
        .bufferedReader().use { it.readText() }

    private class BalanceBackend(private val values:List<Double?>):ScreenAutomationBackend {
        val clock=AtomicLong()
        val readsPerFrame=mutableListOf<Int>()
        var conflictingFrames=emptySet<Int>()
        private var frame=0
        override suspend fun capture():ScreenSize {
            frame++;readsPerFrame.add(0);clock.addAndGet(200_000_000)
            return ScreenSize(2340,1080)
        }
        override suspend fun recognize(query:RecognitionQuery):ScreenRecognition {
            if (query.reference=="village") return ScreenRecognition(RecognitionStatus.FOUND,99.0,ScreenPoint(100,100))
            readsPerFrame[frame-1]++
            val value=values[minOf(frame-1,values.lastIndex)]
            val region=query.region
            val balance=when {
                query.reference!="decimal" -> null
                region==ScreenRegion(1930,25,2180,90) -> value
                frame in conflictingFrames && region==ScreenRegion(1930,23,2180,92) -> value?.plus(1)
                else -> null
            }
            return if (balance==null) ScreenRecognition(RecognitionStatus.INVALID_READING,0.0)
                else ScreenRecognition(RecognitionStatus.FOUND,99.0,ScreenPoint(2100,55),balance)
        }
        override suspend fun tap(position:ScreenPoint,durationMs:Long):Unit=error("Unconfirmed balance must not dispatch gestures")
        override suspend fun readText(region:ScreenRegion,model:String,threshold:Int,maxResults:Int)=emptyList<ScreenText>()
        override suspend fun tapSequence(steps:List<ScreenTapStep>):Unit=error("Unexpected gesture")
        override suspend fun swipe(from:ScreenPoint,to:ScreenPoint,durationMs:Long):Unit=error("Unexpected gesture")
        override suspend fun zoom(center:ScreenPoint,intensityPx:Int,durationMs:Long,direction:ScreenZoomDirection):Unit=error("Unexpected gesture")
        override suspend fun gesture(fingers:List<ScreenGestureFinger>):Unit=error("Unexpected gesture")
        override suspend fun releasePointers() {}
        override suspend fun invalidateObservation() {}
    }
}
