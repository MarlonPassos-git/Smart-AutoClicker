package com.buzbuz.smartautoclicker.core.script.runtime

import com.buzbuz.smartautoclicker.core.script.runtime.api.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import com.buzbuz.smartautoclicker.core.script.runtime.session.AutomationSessionCoordinator
import com.buzbuz.smartautoclicker.core.script.runtime.session.ScriptSession
import com.buzbuz.smartautoclicker.core.script.runtime.session.ScriptSessionState
import org.junit.Assert.assertEquals
import org.junit.Test

class JavaScriptApiIntegrationTest {
    @Test(timeout = 15000)
    fun groupedReadsKeepOrderedStatusesOneFrameAndRejectStaleOrMalformedGroupsBeforeRecognition() = runBlocking {
        val backend = Backend(AtomicLong())
        QuickJsScriptRuntime(Dispatchers.Default).execute(program("""
            import { observe, readAll, tap } from 'klickr';
            await observe();
            const observation = await observe();
            const region = { left:0, top:0, right:100, bottom:100 };
            const number = { kind:'number', threshold:80, region };
            const image = { kind:'image', reference:'ready', threshold:12, region };
            const results = await readAll(observation, [number, image]);
            if (results.length !== 2 || results[0].status !== 'invalid_reading' || 'number' in results[0] || results[1].status !== 'found') throw new Error('Group lost ordered statuses');
            if (!Object.isFrozen(results) || results.some(result => !Object.isFrozen(result) || result.frame !== observation.frame)) throw new Error('Group lost immutable frame results');
            for (const invalid of [[], Array(65).fill(image), [image,null], [image,{...number,threshold:101}]]) {
                let rejected = false;
                try { await readAll(observation, invalid); } catch (error) { rejected = true; }
                if (!rejected) throw new Error('Malformed group was accepted');
            }
            await tap(results[1]);
            let stale = false;
            try { await readAll(observation, [image]); } catch (error) { stale = error.code === 'stale_observation'; }
            if (!stale) throw new Error('Stale group was accepted');
        """.trimIndent()), ScreenAutomationHost(backend, { true }))
        assertEquals(2, backend.captures)
        assertEquals(2, backend.reads)
        assertEquals(listOf(ScreenPoint(20, 30)), backend.taps)
    }

    @Test(timeout = 15000)
    fun aPauseRequestedInsideAReadGroupCannotAuthorizeAGestureFromThatOldFrame() = runBlocking {
        val backend = Backend(AtomicLong())
        val session = ScriptSession(QuickJsScriptRuntime(Dispatchers.Default), AutomationSessionCoordinator(Dispatchers.Default) {})
        backend.onRead = { if (backend.reads == 1) session.pause() }
        val execution = launch {
            session.run(program("""
                import { observe, readAll, resumeWhen, tap } from 'klickr';
                const query = {kind:'image',reference:'ready',threshold:12,region:{left:0,top:0,right:100,bottom:100}};
                await resumeWhen(query);
                await observe();
                let observation = await observe();
                let results = await readAll(observation, [query,query]);
                let stale = false;
                try { await tap(results[1]); } catch (error) { stale = error.code === 'stale_observation'; }
                if (!stale) throw new Error('Pre-pause group authorized a gesture');
                observation = await observe();
                results = await readAll(observation, [query,query]);
                await tap(results[1]);
            """.trimIndent()), ScreenAutomationHost(backend, { false }))
        }
        withTimeout(5000) { session.status.first { it.state == ScriptSessionState.PAUSED } }
        assertEquals(2, backend.reads)
        assertEquals(emptyList<ScreenPoint>(), backend.taps)
        session.resume()
        withTimeout(5000) { execution.join() }
        assertEquals(session.status.value.failure?.stackTraceToString(), ScriptSessionState.COMPLETED, session.status.value.state)
        assertEquals(4, backend.captures)
        assertEquals(5, backend.reads)
        assertEquals(listOf(ScreenPoint(20, 30)), backend.taps)
    }

    @Test(timeout = 15000)
    fun stoppingDuringAGroupedReadPreventsRemainingReadsAndTheFollowingGesture() = runBlocking {
        val backend = Backend(AtomicLong())
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        backend.onRead = { if (backend.reads == 1) { entered.complete(Unit); kotlinx.coroutines.awaitCancellation() } }
        val session = ScriptSession(QuickJsScriptRuntime(Dispatchers.Default), AutomationSessionCoordinator(Dispatchers.Default) {})
        val execution = launch {
            session.run(program("""
                import { observe, readAll, tap } from 'klickr';
                const observation = await observe();
                const query = {kind:'number',threshold:80,region:{left:0,top:0,right:100,bottom:100}};
                await readAll(observation, [query,query]);
                await tap({x:10,y:20});
            """.trimIndent()), ScreenAutomationHost(backend, { true }))
        }
        withTimeout(5000) { entered.await() }
        session.stop()
        withTimeout(5000) { execution.join() }
        assertEquals(ScriptSessionState.STOPPED, session.status.value.state)
        assertEquals(1, backend.reads)
        assertEquals(emptyList<ScreenPoint>(), backend.taps)
    }

    @Test(timeout = 15000)
    fun tapSequencesValidateTheWholeBlockBeforeDispatchAndInvalidateItsFrame() = runBlocking {
        val backend = Backend(AtomicLong())
        QuickJsScriptRuntime(Dispatchers.Default).execute(program("""
            import { observe, tapSequence } from 'klickr';
            let frame = await observe();
            await tapSequence([{ at: { x: 10, y: 20 }, pressMs: 20, afterMs: 20 },
                { at: { x: 30, y: 40 }, pressMs: 20, afterMs: 0 }], frame);
            let stale = false;
            try { await tapSequence([{ at: { x: 1, y: 1 }, pressMs: 20, afterMs: 0 }], frame); }
            catch (error) { stale = error.code === 'stale_observation'; }
            if (!stale) throw new Error('Sequence reused a stale frame');
            frame = await observe();
            for (const invalid of [
                [], [{ at: { x: 1, y: 1 }, pressMs: 700, afterMs: 301 }],
                [{ at: { x: 1, y: 1 }, pressMs: 20, afterMs: 0 },
                 { at: { x: 201, y: 20 }, pressMs: 20, afterMs: 0 }]
            ]) {
                let rejected = false;
                try { await tapSequence(invalid, frame); } catch (error) { rejected = true; }
                if (!rejected) throw new Error('Invalid sequence was dispatched');
            }
        """.trimIndent()), ScreenAutomationHost(backend, { true }))
        assertEquals(listOf(ScreenPoint(10, 20), ScreenPoint(30, 40)), backend.taps)
    }

    @Test(timeout = 15000)
    fun calibratedRegionsAndPositionsReachJavaScriptAtTheCurrentResolution(): Unit = runBlocking {
        val backend = Backend(AtomicLong())
        val profile = ScreenProfile.parse(Json.parseToJsonElement("""{"width":100,"height":200,
            "regions":{"ready":{"left":10,"top":20,"right":31,"bottom":41}},
            "positions":{"confirm":{"x":99,"y":199}}}"""))
        QuickJsScriptRuntime(Dispatchers.Default).execute(program("""
            import { observe, profile, read, tap } from 'klickr';
            const observation = await observe();
            const screen = await profile(observation);
            if (screen.width !== 200 || screen.regions.ready.right !== 62 || screen.regions.ready.top !== 40) throw new Error('Wrong region scaling');
            if (screen.positions.confirm.x !== 198 || screen.positions.confirm.y !== 398) throw new Error('Wrong position scaling');
            await read(observation, { kind: 'image', reference: 'ready', threshold: 80, region: screen.regions.ready });
            await tap(screen.positions.confirm);
            let rejected = false;
            try { await tap(screen.positions.confirm); } catch (error) { rejected = error.code === 'stale_observation'; }
            if (!rejected) throw new Error('Profile coordinates lost the frame guard');
        """.trimIndent()), ScreenAutomationHost(backend, { true }, referenceProfile = profile))
        assertEquals(listOf(ScreenPoint(198, 398)), backend.taps)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { profile.resolve(ScreenSize(400, 200)) }
    }

    @Test(timeout = 15000)
    fun waitingAfterPauseDiscardsTheOldPollAndExcludesPausedTimeFromItsDeadline() = runBlocking {
        val clock = AtomicLong()
        val backend = Backend(clock)
        val session = ScriptSession(QuickJsScriptRuntime(Dispatchers.Default), AutomationSessionCoordinator(Dispatchers.Default) {})
        backend.onCapture = { if (backend.captures == 1) session.pause() }
        val guard = buildJsonObject {
            put("kind", "image"); put("reference", "ready"); put("threshold", 80)
            put("region", buildJsonObject { put("left", 0); put("top", 0); put("right", 100); put("bottom", 100) })
        }
        val host = ScreenAutomationHost(backend, { false }, clock::get, initialResumeQuery = guard)
        val execution = launch {
            session.run(program("""
                import { waitFor, tap } from 'klickr';
                const result = await waitFor({ kind: 'image', reference: 'ready', threshold: 80,
                    region: { left: 0, top: 0, right: 100, bottom: 100 } }, { timeoutMs: 100 });
                if (result.status !== 'found' || result.frame !== 3) throw new Error('Wait did not restart on a fresh frame');
                await tap(result);
            """.trimIndent()), host)
        }
        withTimeout(5000) { session.status.first { it.state == ScriptSessionState.PAUSED } }
        clock.addAndGet(5_000_000_000)
        session.resume()
        withTimeout(5000) { execution.join() }
        assertEquals(ScriptSessionState.COMPLETED, session.status.value.state)
        assertEquals(3, backend.captures)
        assertEquals(2, backend.reads)
        assertEquals(listOf(ScreenPoint(20, 30)), backend.taps)
    }

    @Test(timeout = 15000)
    fun zoomAndMultitouchCrossThePublicApiAndMalformedCommandsDoNotReachTheBackend() = runBlocking {
        val backend = Backend(AtomicLong())
        QuickJsScriptRuntime(Dispatchers.Default).execute(program("""
            import { observe, zoom, gesture } from 'klickr';
            let frame = await observe();
            await zoom({ x: 100, y: 200, frame: frame.frame }, 'out', 50, 300);
            frame = await observe();
            await gesture([
                { start: { x: 20, y: 30 }, holdMs: 100, keepDown: true },
                { start: { x: 80, y: 30 }, steps: [{ to: { x: 90, y: 40 }, durationMs: 200 }] }
            ], frame);
            for (const invalid of [
                { start: { x: 1, y: 1 }, keepDown: 'yes' },
                { start: { x: 1, y: 1 }, holdMs: '100' },
                { start: { x: 1, y: 1 }, startDelayMs: 60000 }
            ]) {
                let rejected = false;
                try { await gesture([invalid]); } catch (error) { rejected = true; }
                if (!rejected) throw new Error('Malformed gesture was accepted');
            }
        """.trimIndent()), ScreenAutomationHost(backend, { true }))
        assertEquals(listOf(ScreenZoomDirection.OUT), backend.zooms)
        assertEquals(1, backend.gestures.size)
        assertEquals(2, backend.gestures.single().size)
        assertEquals(true, backend.gestures.single().first().keepDown)
        assertEquals(ScreenPoint(90, 40), backend.gestures.single()[1].steps.single().position)
    }

    @Test(timeout = 15000)
    fun declaredResumeConditionUsesANewFrameAndRejectsTheOldObservation() = runBlocking {
        val backend = Backend(AtomicLong())
        val session = ScriptSession(QuickJsScriptRuntime(Dispatchers.Default), AutomationSessionCoordinator(Dispatchers.Default) {})
        val host = ScreenAutomationHost(backend, { false }, onDiagnostic = { if (it.kind == "stage") session.pause() })
        val execution = launch {
            session.run(program("""
                import { resumeWhen, observe, read, stage } from 'klickr';
                const query = { kind: 'image', reference: 'ready', threshold: 80,
                    region: { left: 0, top: 0, right: 100, bottom: 100 } };
                await resumeWhen(query);
                const old = await observe();
                await stage('checking');
                let rejected = false;
                try { await read(old, query); } catch (error) { rejected = true; }
                if (!rejected) throw new Error('Old observation was accepted');
                const fresh = await observe();
                if (fresh.resumeGeneration !== old.resumeGeneration + 1) throw new Error('Pause generation was lost');
            """.trimIndent()), host)
        }
        withTimeout(5000) { session.status.first { it.state == ScriptSessionState.PAUSED } }
        session.resume()
        withTimeout(5000) { execution.join() }
        assertEquals(ScriptSessionState.COMPLETED, session.status.value.state)
        assertEquals(3, backend.captures)
        assertEquals(1, backend.reads)
        assertEquals(emptyList<ScreenPoint>(), backend.taps)
    }

    @Test(timeout = 15000)
    fun scriptStopPreservesItsReasonAndPreventsFollowingGestures() = runBlocking {
        val backend = Backend(AtomicLong())
        val session = ScriptSession(QuickJsScriptRuntime(Dispatchers.Default), AutomationSessionCoordinator(Dispatchers.Default) {})
        val execution = launch {
            session.run(program("""
                import { stop, tap } from 'klickr';
                await stop('Meta atingida');
                await tap({ x: 20, y: 30 });
            """.trimIndent()), ScreenAutomationHost(backend, { true }))
        }
        execution.join()
        assertEquals(ScriptSessionState.STOPPED, session.status.value.state)
        assertEquals("Meta atingida", session.status.value.reason)
        assertEquals(emptyList<ScreenPoint>(), backend.taps)
    }

    @Test(timeout = 15000)
    fun parametersReachJavaScriptAndDiagnosticsRetainOnlyTheConfiguredTail() = runBlocking {
        val diagnostics = ScriptDiagnostics(capacity = 2)
        val values = buildJsonObject { put("limit", 20); put("enabled", true); put("currency", "gold") }
        val host = ScreenAutomationHost(Backend(AtomicLong()), { true }, startParameters = values,
            onDiagnostic = diagnostics::record)
        QuickJsScriptRuntime(Dispatchers.Default).execute(program("""
            import { parameters, stage, log } from 'klickr';
            const options = await parameters();
            if (options.limit !== 20 || options.enabled !== true || options.currency !== 'gold') throw new Error('Invalid parameters');
            await stage('checking', 'Conferindo a tela');
            await log('Primeira leitura');
            await log('Segunda leitura');
        """.trimIndent()), host)
        assertEquals(listOf("Primeira leitura", "Segunda leitura"), diagnostics.snapshot().map { it.message })
        assertEquals("checking", diagnostics.latest.value?.stage)
        assertEquals(2, diagnostics.jsonLines().lineSequence().count())
        assertEquals(20, values["limit"]?.toString()?.toInt())
    }

    @Test(timeout = 15000)
    fun waitsForAnAlternativeThenClicksItsRecognizedPosition() = runBlocking {
        val clock = AtomicLong()
        val backend = Backend(clock)
        val host = ScreenAutomationHost(backend, { true }, clock::get)
        QuickJsScriptRuntime(Dispatchers.Default).execute(program("""
            import { waitAny, tap } from 'klickr';
            const query = reference => ({ kind: 'image', reference, threshold: 80,
                region: { left: 0, top: 0, right: 100, bottom: 100 } });
            const outcome = await waitAny([
                { name: 'ready', query: query('ready') },
                { name: 'recover', query: query('recover') }
            ], { timeoutMs: 100, intervalMs: 1 });
            if (outcome.name !== 'ready') throw new Error('Wrong transition');
            await tap(outcome.result);
        """.trimIndent()), host)
        assertEquals(2, backend.captures)
        assertEquals(3, backend.reads)
        assertEquals(listOf(ScreenPoint(20, 30)), backend.taps)
    }

    @Test(timeout = 15000)
    fun timeoutPreservesTheLastReadingAndDoesNotDispatchAGesture() = runBlocking {
        val clock = AtomicLong()
        val backend = Backend(clock)
        val host = ScreenAutomationHost(backend, { true }, clock::get)
        QuickJsScriptRuntime(Dispatchers.Default).execute(program("""
            import { waitFor } from 'klickr';
            const result = await waitFor({ kind: 'number', threshold: 80,
                region: { left: 0, top: 0, right: 100, bottom: 100 } },
                { timeoutMs: 2, intervalMs: 1 });
            if (result.status !== 'timeout') throw new Error('Expected timeout');
            if (result.last[0].result.status !== 'invalid_reading') throw new Error('Reading was lost');
        """.trimIndent()), host)
        assertEquals(emptyList<ScreenPoint>(), backend.taps)
    }

    @Test(timeout = 15000)
    fun retriesAreBoundedAndDoNotCatchUnexpectedFailures() = runBlocking {
        QuickJsScriptRuntime(Dispatchers.Default).execute(program("""
            import { retry } from 'klickr';
            let calls = 0;
            const result = await retry(async () => { calls++; return { status: 'not_found' }; },
                { attempts: 3, intervalMs: 1 });
            if (calls !== 3 || result.status !== 'not_found') throw new Error('Retry limit ignored');
            let failures = 0;
            try {
                await retry(async () => { failures++; throw new Error('unexpected'); }, { attempts: 3 });
            } catch (error) {
                if (error.message !== 'unexpected') throw error;
            }
            if (failures !== 1) throw new Error('Unexpected error was retried');
        """.trimIndent()), ScreenAutomationHost(Backend(AtomicLong()), { true }))
    }

    private fun program(source: String) = ScriptProgram("entry.js", mapOf("entry.js" to source))

    private class Backend(private val clock: AtomicLong) : ScreenAutomationBackend {
        var captures = 0
        var reads = 0
        var onCapture: () -> Unit = {}
        var onRead: suspend () -> Unit = {}
        val taps = mutableListOf<ScreenPoint>()
        val zooms = mutableListOf<ScreenZoomDirection>()
        val gestures = mutableListOf<List<ScreenGestureFinger>>()
        override suspend fun capture(): ScreenSize {
            captures++
            clock.addAndGet(1_000_000)
            onCapture()
            return ScreenSize(200, 400)
        }
        override suspend fun recognize(query: RecognitionQuery): ScreenRecognition {
            reads++
            onRead()
            return when {
                query.kind == RecognitionKind.NUMBER -> ScreenRecognition(RecognitionStatus.INVALID_READING, 0.0)
                captures >= 2 && query.reference == "ready" -> ScreenRecognition(RecognitionStatus.FOUND, 95.0, ScreenPoint(20, 30))
                else -> ScreenRecognition(RecognitionStatus.NOT_FOUND, 0.0)
            }
        }
        override suspend fun tap(position: ScreenPoint, durationMs: Long) { taps.add(position) }
        override suspend fun tapSequence(steps: List<ScreenTapStep>) { steps.forEach { tap(it.position, it.pressMs) } }
        override suspend fun swipe(from: ScreenPoint, to: ScreenPoint, durationMs: Long) {}
        override suspend fun zoom(center: ScreenPoint, intensityPx: Int, durationMs: Long, direction: ScreenZoomDirection) { zooms.add(direction) }
        override suspend fun gesture(fingers: List<ScreenGestureFinger>) { gestures.add(fingers) }
        override suspend fun releasePointers() {}
        override suspend fun invalidateObservation() {}
    }
}
