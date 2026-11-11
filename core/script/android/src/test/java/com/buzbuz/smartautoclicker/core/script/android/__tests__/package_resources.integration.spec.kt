package com.buzbuz.smartautoclicker.core.script.android

import android.graphics.Bitmap
import com.buzbuz.smartautoclicker.core.script.packages.ScriptPackageRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PackageResourcesIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun namedTemplatesAreCachedAndReleasedWithoutChangingThePackage() {
        val image = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        image.recycle()
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { output ->
            for ((name, bytes) in mapOf(
                "manifest.json" to """{"formatVersion":1,"apiVersion":1,"id":"example","name":"Exemplo","scriptVersion":"1","entry":"entry.js",
                    "resources":{"ready":{"type":"image","path":"ready.png","width":2,"height":3},
                    "other":{"type":"image","path":"ready.png","width":2,"height":3}}}""".toByteArray(),
                "entry.js" to "const ready = true;".toByteArray(), "ready.png" to png,
            )) { output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry() }
        }
        val repository = ScriptPackageRepository(temporary.newFolder("packages"))
        val stored = repository.importPackage(ByteArrayInputStream(zip.toByteArray()))
        val resources = PackageScreenResources(repository, stored)
        val template = resources.resolve("ready") as AndroidScriptResource.Image
        assertEquals(2, template.bitmap.width)
        assertEquals(3, template.bitmap.height)
        assertSame(template, resources.resolve("ready"))
        val bounded = PackageScreenResources(repository, stored, maxTemplateBytes = 24)
        val first = bounded.resolve("ready") as AndroidScriptResource.Image
        val failure = assertThrows(IllegalArgumentException::class.java) { bounded.resolve("other") }
        assertTrue(failure.message!!.contains("resource=other"))
        assertTrue(failure.message!!.contains("within 24 bytes"))
        assertSame(first, bounded.resolve("ready"))
        assertTrue(!first.bitmap.isRecycled)
        bounded.close()
        assertTrue(first.bitmap.isRecycled)
        resources.close()
        resources.close()
        assertTrue(template.bitmap.isRecycled)
        assertTrue(repository.readFile(stored.storageId, "ready.png").contentEquals(png))
        var rejected = false
        try { resources.resolve("ready") } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
    }
}
