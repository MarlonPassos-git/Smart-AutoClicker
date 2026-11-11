package com.buzbuz.smartautoclicker.core.script.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buzbuz.smartautoclicker.core.common.actions.AndroidActionExecutor
import com.buzbuz.smartautoclicker.core.detection.NativeDetector
import com.buzbuz.smartautoclicker.core.script.packages.ScriptPackageRepository
import com.buzbuz.smartautoclicker.core.script.runtime.session.ScriptSessionState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeScriptResourcesIntegrationTest {
    @Test(timeout = 60000)
    fun templateDimensionsDescribeTheCropAndScaleWithTheReferenceScreen() = runBlocking {
        val frame = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        frame.eraseColor(Color.BLACK)
        val canvas = Canvas(frame)
        val paint = Paint()
        for (x in 20 until 40) for (y in 40 until 70) {
            paint.color = Color.rgb(30 + (x - 20) * 8, 30 + (y - 40) * 6, 210 - (x - 20) * 5)
            canvas.drawPoint(x.toFloat(), y.toFloat(), paint)
        }
        val template = Bitmap.createBitmap(frame, 20, 40, 20, 30)
        val png = ByteArrayOutputStream().also { template.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        template.recycle()
        try {
            for (scale in listOf(1, 2)) {
                val captured = Bitmap.createScaledBitmap(frame, 100 * scale, 200 * scale, true)
                try {
                    verify(captured, mapOf(
                        "manifest.json" to """{"formatVersion":1,"apiVersion":1,"id":"template-test","name":"Template","scriptVersion":"1","entry":"entry.js",
                            "resources":{"ready":{"type":"image","path":"ready.png","width":20,"height":30}},
                            "screen":{"width":100,"height":200,"regions":{"target":{"left":0,"top":0,"right":100,"bottom":200}}}}""".toByteArray(),
                        "ready.png" to png,
                        "entry.js" to """
                            import { observe, profile, read } from 'klickr';
                            const observation = await observe();
                            const screen = await profile(observation);
                            const result = await read(observation, { kind:'image', reference:'ready', threshold:12, region:screen.regions.target });
                            if (result.status !== 'found') throw new Error('Native template not recognized at scale $scale');
                            if (Math.abs(result.x - ${30 * scale}) > 2 || Math.abs(result.y - ${55 * scale}) > 2) throw new Error('Wrong native template position');
                            if (result.width !== ${20 * scale} || result.height !== ${30 * scale}) throw new Error('Native template dimensions were lost');
                        """.trimIndent().toByteArray(),
                    ))
                } finally { if (captured !== frame) captured.recycle() }
            }
        } finally { frame.recycle() }
    }

    @Test(timeout = 120000)
    fun farmBatchPricesControlsAndAvailabilityMatchTheExistingNativeFrames() = runBlocking {
        for (count in 1..7) withFrame("batch_selection_$count", 700, 690) { frame ->
            verifyFarm(frame, """
                import { snapshot, all, number } from './screen.js';
                const current = await snapshot(['batchRemove','batchAdd','selected18','goldBatchCost','elixirBatchCost',
                    'goldBatchAvailable1','goldBatchAvailable2','goldBatchInsufficient1','goldBatchInsufficient2',
                    'elixirBatchAvailable1','elixirBatchAvailable2','elixirBatchInsufficient1','elixirBatchInsufficient2']);
                if (!all(current,['batchRemove','batchAdd','selected18'])) throw new Error('Missing batch controls at $count');
                for (const resource of ['gold','elixir']) {
                    if (number(current.results[resource+'BatchCost']) !== ${count * 4_200_000}) throw new Error('Wrong batch price in '+resource);
                    const available = resource === 'elixir' && $count <= 2;
                    if (all(current,[resource+'BatchAvailable1',resource+'BatchAvailable2']) !== available) throw new Error('Wrong available price in '+resource);
                    if (all(current,[resource+'BatchInsufficient1',resource+'BatchInsufficient2']) !== !available) throw new Error('Wrong insufficient price in '+resource);
                }
            """.trimIndent())
        }
    }

    @Test(timeout = 60000)
    fun farmConfirmationRequiresTheCorrectCurrencyAndTheRealTotal() = runBlocking {
        for (currency in listOf("gold", "elixir")) withFrame("batch_confirmation_$currency", 690, 240) { frame ->
            verifyFarm(frame, """
                import { snapshot, all, number, found } from './screen.js';
                const current = await snapshot(['batchTitle','batchOk','batchConfirmCost','goldLabel','elixirLabel']);
                if (!all(current,['batchTitle','batchOk']) || number(current.results.batchConfirmCost) !== 8400000) throw new Error('Native confirmation mismatch');
                if (found(current.results.goldLabel) !== ${currency == "gold"} || found(current.results.elixirLabel) !== ${currency == "elixir"}) throw new Error('Native currency mismatch');
            """.trimIndent())
        }
    }

    @Test(timeout = 60000)
    fun farmSingleWallConfirmationAndCollapsedUpgradeMatchTheExistingNativeFrames() = runBlocking {
        withFrame("confirmation_current", 770, 42) { frame ->
            verifyFarm(frame, """
                import { snapshot, all, number, found } from './screen.js';
                const current = await snapshot(['singleTitle','goldConfirm','elixirConfirm','singleConfirmCost','confirmGreen1','confirmGreen2']);
                if (!all(current,['singleTitle','goldConfirm','confirmGreen1','confirmGreen2']) || number(current.results.singleConfirmCost) !== 4200000) throw new Error('Single-wall confirmation mismatch');
                if (found(current.results.elixirConfirm)) throw new Error('Single-wall currency confused');
            """.trimIndent())
        }
        withFrame("batch_upgraded", 920, 690) { frame ->
            verifyFarm(frame, """
                import { snapshot, all, found } from './screen.js';
                const current = await snapshot(['wallTitle','selected19','selected18']);
                if (!all(current,['wallTitle','selected19']) || found(current.results.selected18)) throw new Error('Upgraded level not confirmed');
            """.trimIndent())
        }
    }

    @Test(timeout = 60000)
    fun farmQuantityAndHeroBoundaryUseTheNativeArmyFrames() = runBlocking {
        for ((asset, minimum, maximum) in listOf(Triple("army_start", 480, 780), Triple("mixed_army", 1330, 1630))) {
            withFrame(asset, 80, 885, folder = "quantity_calibration") { frame ->
                verifyFarm(frame, """
                    import { snapshot, found, number } from './screen.js';
                    import { heroReferences } from './profile.js';
                    const current = await snapshot(['troops',...heroReferences]);
                    if (number(current.results.troops) !== 25 || current.results.troops.x >= 330) throw new Error('Native quantity or position mismatch');
                    const lefts = heroReferences.map(name => current.results[name]).filter(found).map(result => result.x-result.width/2);
                    const first = Math.min(...lefts);
                    if (first < $minimum || first > $maximum || !Number.isFinite(first)) throw new Error('Native hero boundary mismatch: '+first);
                """.trimIndent())
            }
        }
    }

    @Test(timeout = 60000)
    fun farmRecognizesOneFreeBuilderOutOfSevenOnTheSamsungFrame() = runBlocking {
        withFrame("builders_one_of_seven", 2010, 770) { frame ->
            verifyFarm(frame, """
                import { snapshot, found } from './screen.js';
                const current = await snapshot(['buildersFree']);
                if (!found(current.results.buildersFree)) throw new Error('One of seven builders was not recognized');
            """.trimIndent())
        }
    }

    @Test(timeout = 60000)
    fun theFreeBuilderProfileRejectsARecognizableZeroOfSevenCounter() = runBlocking {
        val frame = Bitmap.createBitmap(2340, 1080, Bitmap.Config.ARGB_8888)
        frame.eraseColor(Color.BLACK)
        Canvas(frame).drawText("0/7", 2052f, 844f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 40f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        val free = Json.parseToJsonElement(resource("manifest.json").toString(Charsets.UTF_8))
            .jsonObject.getValue("resources").jsonObject.getValue("buildersFree")
        try {
            verify(frame, mapOf(
                "manifest.json" to """{"formatVersion":1,"apiVersion":1,"id":"zero-builders","name":"Construtores","scriptVersion":"1","entry":"entry.js",
                    "resources":{"free":$free,"zero":{"type":"text","model":"LATIN","alternatives":["0/7","077","0"]}}}""".toByteArray(),
                "entry.js" to """
                    import { observe, read } from 'klickr';
                    const observation = await observe();
                    const region = {left:2045,top:795,right:2140,bottom:860};
                    const zero = await read(observation,{kind:'text',reference:'zero',threshold:80,region});
                    if (zero.status !== 'found') throw new Error('The negative counter must first be readable');
                    const free = await read(observation,{kind:'text',reference:'free',threshold:80,region});
                    if (free.status === 'found') throw new Error('Zero builders must not authorize wall upgrades');
                """.trimIndent().toByteArray(),
            ))
        } finally { frame.recycle() }
    }

    @Test(timeout = 60000)
    fun levelSixteenProfileRecognizesTheSelectedWallAndItsActualPrice() = runBlocking {
        withFrame("wall_16_selection", 850, 680) { frame ->
            verifyFarm(frame, """
                import { snapshot, all, number, found } from './screen.js';
                const available = ['goldAvailable1','goldAvailable2','elixirAvailable1','elixirAvailable2'];
                const insufficient = ['goldInsufficient1','goldInsufficient2','elixirInsufficient1','elixirInsufficient2'];
                const current = await snapshot(['wallTitle','selected16','selected17','goldCost','elixirCost',...available,...insufficient]);
                if (!all(current,['wallTitle','selected16']) || found(current.results.selected17)) throw new Error('Wrong wall level');
                if (number(current.results.goldCost) !== 2000000 || number(current.results.elixirCost) !== 2000000) throw new Error('Wrong level-16 price');
                if (!all(current,available) || insufficient.some(name => found(current.results[name]))) throw new Error('Wrong level-16 availability indicators');
            """.trimIndent(), "farm-walls-level16-sm-s916b")
        }
    }

    @Test(timeout = 60000)
    fun levelSixteenGoldConfirmationRequiresTheActualCurrencyAndDiscountedCost() = runBlocking {
        withFrame("wall_16_gold_confirmation", 385, 25) { frame ->
            verifyFarm(frame, """
                import { snapshot, all, number, found } from './screen.js';
                const current = await snapshot(['singleTitle','goldConfirm','elixirConfirm','singleConfirmCost','confirmGreen1','confirmGreen2']);
                if (!all(current,['singleTitle','goldConfirm','confirmGreen1','confirmGreen2'])) throw new Error('Missing level-16 gold confirmation: '+JSON.stringify(current.results));
                if (found(current.results.elixirConfirm)) throw new Error('Gold was confused with elixir');
                if (number(current.results.singleConfirmCost) !== 2000000) throw new Error('Wrong discounted level-16 cost: '+JSON.stringify(current.results));
            """.trimIndent(), "farm-walls-level16-sm-s916b")
        }
    }

    @Test(timeout = 60000)
    fun levelSixteenElixirConfirmationRequiresTheActualCurrencyAndDiscountedCost() = runBlocking {
        withFrame("wall_16_elixir_confirmation", 385, 25) { frame ->
            verifyFarm(frame, """
                import { snapshot, all, number, found } from './screen.js';
                const current = await snapshot(['singleTitle','goldConfirm','elixirConfirm','singleConfirmCost','confirmGreen1','confirmGreen2']);
                if (!all(current,['singleTitle','elixirConfirm','confirmGreen1','confirmGreen2'])) throw new Error('Missing level-16 elixir confirmation: '+JSON.stringify(current.results));
                if (found(current.results.goldConfirm)) throw new Error('Elixir was confused with gold');
                if (number(current.results.singleConfirmCost) !== 2000000) throw new Error('Wrong discounted level-16 cost: '+JSON.stringify(current.results));
            """.trimIndent(), "farm-walls-level16-sm-s916b")
        }
    }

    @Test(timeout = 60000)
    fun levelSixteenBatchSelectionReadsBothTotalsAndEnabledControls() = runBlocking {
        for ((fixture, total) in listOf("wall_16_batch_one" to 2000000, "wall_16_batch_two" to 4000000)) {
            withFrame(fixture, 500, 680) { frame ->
                verifyFarm(frame, """
                    import { snapshot, all, number, found } from './screen.js';
                    const available = ['goldBatchAvailable1','goldBatchAvailable2','elixirBatchAvailable1','elixirBatchAvailable2'];
                    const insufficient = ['goldBatchInsufficient1','goldBatchInsufficient2','elixirBatchInsufficient1','elixirBatchInsufficient2'];
                    const current = await snapshot(['batchRemove','batchAdd','selected16','goldBatchCost','elixirBatchCost',...available,...insufficient]);
                    if (!all(current,['batchRemove','batchAdd','selected16',...available]) || insufficient.some(name => found(current.results[name]))) throw new Error('Wrong batch controls: '+JSON.stringify(current.results));
                    if (number(current.results.goldBatchCost) !== $total || number(current.results.elixirBatchCost) !== $total) throw new Error('Wrong batch total: '+JSON.stringify(current.results));
                """.trimIndent(), "farm-walls-level16-sm-s916b")
            }
        }
    }

    @Test(timeout = 60000)
    fun levelSixteenBatchConfirmationRequiresTheExactTotalAndGoldCurrency() = runBlocking {
        withFrame("wall_16_batch_gold_confirmation", 690, 240) { frame ->
            verifyFarm(frame, """
                import { snapshot, all, number, found } from './screen.js';
                const current = await snapshot(['batchTitle','batchOk','batchConfirmCost','goldLabel','elixirLabel']);
                if (!all(current,['batchTitle','batchOk','goldLabel']) || found(current.results.elixirLabel)) throw new Error('Wrong batch confirmation: '+JSON.stringify(current.results));
                if (number(current.results.batchConfirmCost) !== 4000000) throw new Error('Wrong level-16 batch cost: '+JSON.stringify(current.results));
            """.trimIndent(), "farm-walls-level16-sm-s916b")
        }
    }

    @Test(timeout = 60000)
    fun levelSixteenProfileConfirmsTheActualUpgradeWithoutRequiringAnOcrParenthesis() = runBlocking {
        withFrame("wall_17_upgraded", 850, 680) { frame ->
            verifyFarm(frame, """
                import { snapshot, all, found } from './screen.js';
                const current = await snapshot(['wallTitle','selected16','selected17']);
                if (!all(current,['wallTitle','selected17']) || found(current.results.selected16)) throw new Error('Actual level-17 upgrade was not confirmed');
            """.trimIndent(), "farm-walls-level16-sm-s916b")
        }
    }

    @Test(timeout = 60000)
    fun levelSixteenBatchConfirmationRequiresTheExactTotalAndElixirCurrency() = runBlocking {
        withFrame("wall_16_batch_elixir_confirmation", 690, 240) { frame ->
            verifyFarm(frame, """
                import { snapshot, all, number, found } from './screen.js';
                const current = await snapshot(['batchTitle','batchOk','batchConfirmCost','goldLabel','elixirLabel']);
                if (!all(current,['batchTitle','batchOk','elixirLabel']) || found(current.results.goldLabel) || number(current.results.batchConfirmCost) !== 4000000) throw new Error('Wrong elixir batch confirmation: '+JSON.stringify(current.results));
            """.trimIndent(), "farm-walls-level16-sm-s916b")
        }
    }

    @Test(timeout = 60000)
    fun levelSixteenProfileConfirmsAnUpgradedBatchOfTwoWalls() = runBlocking {
        withFrame("wall_17_batch_upgraded", 850, 680) { frame ->
            verifyFarm(frame, """
                import { snapshot, all, found } from './screen.js';
                const current = await snapshot(['wallTitle','selected16','selected17']);
                if (!all(current,['wallTitle','selected17']) || found(current.results.selected16)) throw new Error('Batch upgrade was not confirmed: '+JSON.stringify(current.results));
            """.trimIndent(), "farm-walls-level16-sm-s916b")
        }
    }

    @Test(timeout = 60000)
    fun theCurrentBattleMenuCanBeRecognizedBeforeMatchmaking() = runBlocking {
        withFrame("battle_menu_current", 0, 0) { frame ->
            verifyFarm(frame, """
                import { snapshot, found } from './screen.js';
                const current = await snapshot(['search','searchText']);
                if (!found(current.results.search) && !found(current.results.searchText)) throw new Error('Current battle menu was not recognized');
            """.trimIndent(), "farm-walls-level16-sm-s916b")
        }
    }

    @Test(timeout = 60000)
    fun theCurrentRetreatConfirmationIsRecognizableWhenNotCoveredByControls() = runBlocking {
        withFrame("retreat_confirmation_current", 1200, 600) { frame ->
            verifyFarm(frame, """
                import { snapshot, found } from './screen.js';
                const current = await snapshot(['exitConfirm']);
                if (!found(current.results.exitConfirm)) throw new Error('Retreat confirmation was not recognized');
            """.trimIndent(), "farm-walls-level16-sm-s916b")
        }
    }

    private suspend fun verifyFarm(frame: Bitmap, source: String, packageName: String = "farm-walls-sm-s916b") {
        val manifestBytes = resource("manifest.json", packageName)
        val manifest = Json.parseToJsonElement(manifestBytes.toString(Charsets.UTF_8)).jsonObject
        val files = mutableMapOf("manifest.json" to manifestBytes, "entry.js" to source.toByteArray())
        for (file in listOf("screen.js", "profile.js")) files[file] = resource(file, packageName)
        manifest.getValue("resources").jsonObject.values.forEach { value ->
            val definition = value.jsonObject
            if (definition.getValue("type").jsonPrimitive.content == "image") {
                val path = definition.getValue("path").jsonPrimitive.content
                files[path] = resource(path, packageName)
            }
        }
        verify(frame, files)
    }

    private suspend fun verify(frame: Bitmap, files: Map<String, ByteArray>) {
        val context = InstrumentationRegistry.getInstrumentation().context
        val root = File(context.cacheDir, "native-script-${UUID.randomUUID()}")
        val repository = ScriptPackageRepository(root)
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { output ->
            files.forEach { (path, bytes) -> output.putNextEntry(ZipEntry(path)); output.write(bytes); output.closeEntry() }
        }
        val stored = repository.importPackage(ByteArrayInputStream(zip.toByteArray()))
        val actions = Proxy.newProxyInstance(AndroidActionExecutor::class.java.classLoader, arrayOf(AndroidActionExecutor::class.java)) { _, method, _ ->
            if (method.name == "releaseHeldPointer") Unit else error("Recognition must not dispatch Android action ${method.name}")
        } as AndroidActionExecutor
        try {
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                val execution = PackageScriptExecution(repository, dispatcher, actions,
                    { checkNotNull(NativeDetector.newInstance()) }, { frame }, { detector, names ->
                        val modelRoot = File(context.cacheDir, "script-test-ocr")
                        for (path in listOf("detect", "recognize/latin")) {
                            val destination = File(modelRoot, path).apply { mkdirs() }
                            context.assets.list("models/text/$path")!!.forEach { name ->
                                val target = File(destination, name)
                                if (!target.exists()) context.assets.open("models/text/$path/$name").use { input -> target.outputStream().use(input::copyTo) }
                            }
                        }
                        check(detector.loadTextDetectionModels(File(modelRoot,"detect").absolutePath,
                            names.associateWith { File(modelRoot,"recognize/latin").absolutePath }))
                    }, "com.buzbuz.smartautoclicker.core.script.android.test")
                execution.run(stored.storageId, JsonObject(emptyMap()))
                val status = execution.status.value
                assertEquals(status.failure?.stackTraceToString(), ScriptSessionState.COMPLETED, status.state)
                assertFalse("The recorder frame is borrowed", frame.isRecycled)
            }
        } finally { root.deleteRecursively() }
    }

    private suspend fun withFrame(asset: String, x: Int, y: Int, folder: String = "wall_calibration", block: suspend (Bitmap) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().context
        val crop = context.assets.open("$folder/$asset.png").use(BitmapFactory::decodeStream)
        val frame = Bitmap.createBitmap(2340, 1080, Bitmap.Config.ARGB_8888)
        Canvas(frame).drawBitmap(crop, x.toFloat(), y.toFloat(), null)
        crop.recycle()
        try { block(frame) } finally { frame.recycle() }
    }

    private fun resource(path: String, packageName: String = "farm-walls-sm-s916b"): ByteArray = checkNotNull(javaClass.classLoader!!.getResourceAsStream("$packageName/$path"))
        .use { it.readBytes() }
}
