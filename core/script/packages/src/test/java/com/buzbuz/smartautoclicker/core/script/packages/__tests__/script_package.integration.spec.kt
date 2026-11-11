package com.buzbuz.smartautoclicker.core.script.packages

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScriptPackageIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun savingEditsCreatesANewRevisionAndPreservesTheRunningSnapshot() {
        val repository = ScriptPackageRepository(temporary.newFolder("repository"))
        val original = repository.importPackage(archive())
        val running = repository.program(original.storageId)
        val edited = repository.saveRevision(original.storageId, mapOf("entry.js" to "const edited = true;".toByteArray()))
        assertEquals(running, repository.program(original.storageId))
        assertEquals("const edited = true;", repository.program(edited.storageId).sources["entry.js"])
        assertEquals(listOf("entry.js", "manifest.json"), repository.files(edited.storageId))
        assertEquals("const edited = true;", repository.readFile(edited.storageId, "entry.js").toString(Charsets.UTF_8))
        assertEquals(2, repository.listPackages().size)
    }

    @Test
    fun portableExportReimportsTheSameSourcesAndParametersWithoutExecutingCode() {
        val first = ScriptPackageRepository(temporary.newFolder("first"))
        val stored = first.importPackage(archive())
        val output = ByteArrayOutputStream()
        first.exportPackage(stored.storageId, output)
        val second = ScriptPackageRepository(temporary.newFolder("second"))
        val copied = second.importPackage(ByteArrayInputStream(output.toByteArray()))
        assertEquals(first.program(stored.storageId), second.program(copied.storageId))
        assertEquals(stored.manifest.document, copied.manifest.document)
        val entries = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) { entries.add((zip.nextEntry ?: break).name) }
        }
        assertEquals(listOf("entry.js", "manifest.json"), entries)
        assertEquals("throw new Error('must not execute during import');", first.program(stored.storageId).sources["entry.js"])
    }

    @Test
    fun rejectedImportsLeaveExistingRevisionsIntactAndRemoveStaging() {
        val root = temporary.newFolder("repository")
        val repository = ScriptPackageRepository(root)
        val existing = repository.importPackage(archive())
        val invalid = listOf(
            archive(extra = mapOf("../outside.js" to byteArrayOf(1))),
            archive(extra = mapOf("Entry.js" to byteArrayOf(1))),
            archive(manifest = manifest().replace("\"apiVersion\":1", "\"apiVersion\":99")),
            archive(manifest = JsonObject(Json.parseToJsonElement(manifest()).jsonObject +
                ("screen" to Json.parseToJsonElement("""{"width":100,"height":200,"regions":{"outside":{"left":0,"top":0,"right":101,"bottom":10}}}"""))).toString()),
            archive(manifest = manifest("""{"missing":{"type":"image","path":"missing.png","width":10,"height":10}}""")),
            archive(source = byteArrayOf(0xC3.toByte())),
        )
        for (input in invalid) {
            var rejected = false
            try { repository.importPackage(input) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue("Invalid import must fail", rejected)
            assertEquals(listOf(existing.storageId), repository.listPackages().map { it.storageId })
            assertTrue(root.listFiles()!!.none { it.name.startsWith(".pending-") })
        }
        assertTrue(!temporary.root.resolve("outside.js").exists())
    }

    @Test
    fun expansionLimitsUseExtractedBytesEvenForHighlyCompressedEntries() {
        val root = temporary.newFolder("repository")
        val repository = ScriptPackageRepository(root)
        var rejected = false
        try {
            repository.importPackage(archive(extra = mapOf("oversized.bin" to ByteArray(2 * 1024 * 1024 + 1))))
        } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertEquals(emptyList<StoredScriptPackage>(), repository.listPackages())
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test
    fun startAnswersAreValidatedWithoutChangingDefaults() {
        val parsed = ScriptManifest.parse(manifest())
        val defaults = parsed.answers(JsonObject(emptyMap()))
        val supplied = buildJsonObject { put("limit", 20); put("enabled", false); put("currency", "elixir") }
        assertEquals(supplied, parsed.answers(supplied))
        assertEquals(defaults, parsed.answers(JsonObject(emptyMap())))
        for (invalid in listOf(
            buildJsonObject { put("limit", -1) },
            buildJsonObject { put("limit", "20") },
            buildJsonObject { put("enabled", "false") },
            buildJsonObject { put("currency", "unknown") },
        )) {
            var rejected = false
            try { parsed.answers(invalid) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue("Invalid answers must fail: $invalid", rejected)
        }
    }

    private fun manifest(resources: String = "{}") = """
        {"formatVersion":1,"apiVersion":1,"id":"example","name":"Exemplo","scriptVersion":"1.0.0","entry":"entry.js",
         "resources":$resources,"parameters":{
           "limit":{"type":"number","label":"Limite","default":10,"min":0,"max":100},
           "enabled":{"type":"boolean","label":"Ativar","default":true},
           "currency":{"type":"choice","label":"Moeda","default":"gold","choices":["gold","elixir"]}
         }}
    """.trimIndent()

    private fun archive(
        manifest: String = manifest(),
        source: ByteArray = "throw new Error('must not execute during import');".toByteArray(),
        extra: Map<String, ByteArray> = emptyMap(),
    ): ByteArrayInputStream {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in mapOf("manifest.json" to manifest.toByteArray(), "entry.js" to source) + extra) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(output.toByteArray())
    }
}
