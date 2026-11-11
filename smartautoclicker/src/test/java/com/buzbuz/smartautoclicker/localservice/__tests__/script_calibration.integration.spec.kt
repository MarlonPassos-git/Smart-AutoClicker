package com.buzbuz.smartautoclicker.localservice

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfig
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfigManager
import com.buzbuz.smartautoclicker.core.display.recorder.DisplayRecorder
import com.buzbuz.smartautoclicker.core.script.packages.ScriptPackageRepository
import com.buzbuz.smartautoclicker.core.script.runtime.api.ScreenRegion
import com.buzbuz.smartautoclicker.core.script.runtime.api.ScreenSize
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScriptCalibrationIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test(timeout = 15000)
    fun calibrationPublishesPortableImageAndRegionPreservingOriginalAndBorrowedFrame() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns temporary.root
        val repository = ScriptPackageRepository(File(temporary.root, "scripts/packages"))
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { output ->
            mapOf("manifest.json" to """{"formatVersion":1,"apiVersion":1,"id":"example","name":"Exemplo","scriptVersion":"1","entry":"entry.js"}""",
                "entry.js" to "throw new Error('Calibration must never run this');").forEach { (path, source) ->
                output.putNextEntry(ZipEntry(path)); output.write(source.toByteArray()); output.closeEntry()
            }
        }
        val original = repository.importPackage(ByteArrayInputStream(zip.toByteArray()))
        val recorder = mockk<DisplayRecorder>(relaxed = true)
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.GREEN)
        coEvery { recorder.acquireLatestBitmap() } returns bitmap
        val display = mockk<DisplayConfigManager>(relaxed = true)
        every { display.displayConfig } returns DisplayConfig(Point(100, 200), 1, 0, emptyMap())
        val orientation = slot<(Context) -> Unit>()
        every { display.addOrientationListener(capture(orientation)) } returns Unit
        val driver = ScriptCalibrationDriver(context, recorder, display)
        try {
            driver.start(this, -1, Intent()) {}
            val savedId = driver.save(original.storageId, "confirm", true, ScreenSize(100, 200), ScreenRegion(10, 20, 30, 50))
            val saved = repository.listPackages().single { it.storageId == savedId }
            assertEquals("100", saved.manifest.document.getValue("screen").jsonObject.getValue("width").toString())
            assertEquals(10, saved.manifest.document.getValue("screen").jsonObject.getValue("regions").jsonObject
                .getValue("confirm").jsonObject.getValue("left").jsonPrimitive.int)
            val png = repository.readFile(savedId, "images/confirm.png")
            assertEquals(20, saved.manifest.resources.getValue("confirm").jsonObject.getValue("width").jsonPrimitive.int)
            assertEquals(30, saved.manifest.resources.getValue("confirm").jsonObject.getValue("height").jsonPrimitive.int)
            val template = BitmapFactory.decodeByteArray(png, 0, png.size)
            assertEquals(20, template.width); assertEquals(30, template.height)
            template.recycle()
            assertFalse(bitmap.isRecycled)
            assertTrue(repository.listPackages().single { it.storageId == original.storageId }.manifest.resources.isEmpty())
            val exported = ByteArrayOutputStream()
            repository.exportPackage(savedId, exported)
            val copied = repository.importPackage(ByteArrayInputStream(exported.toByteArray()))
            assertEquals(saved.manifest.document, copied.manifest.document)
            val regionId = driver.save(savedId, "balance", false, ScreenSize(100, 200), ScreenRegion(0, 0, 10, 10))
            assertTrue("images/balance.png" !in repository.files(regionId))
            var rejected = false
            try { driver.save(regionId, "other", false, ScreenSize(200, 100), ScreenRegion(0, 0, 10, 10)) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            every { display.displayConfig } returns DisplayConfig(Point(200, 100), 2, 0, emptyMap())
            orientation.captured(context)
            yield()
            coVerify(exactly = 1) { recorder.resizeDisplay(Point(200, 100)) }
            driver.close()
            verify(exactly = 1) { display.removeOrientationListener(orientation.captured) }
            orientation.captured(context)
            yield()
            coVerify(exactly = 1) { recorder.resizeDisplay(any()) }
            rejected = false
            try { driver.save(regionId, "late", false, ScreenSize(100, 200), ScreenRegion(0, 0, 10, 10)) }
            catch (_: IllegalStateException) { rejected = true }
            assertTrue(rejected)
            coVerify(exactly = 1) { recorder.stopProjection() }
            coVerify(exactly = 1) { recorder.startProjection(any(), any(), any()) }
        } finally { bitmap.recycle() }
    }
}
