package com.buzbuz.smartautoclicker.core.script.packages

import com.buzbuz.smartautoclicker.core.script.runtime.ScriptProgram
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.*

data class StoredScriptPackage(val storageId: String, val manifest: ScriptManifest)

/** Private immutable revisions. Blocking file operations must run on an I/O dispatcher.
 * The repository never executes imported source or changes the smart scenario database.
 */
class ScriptPackageRepository(private val root: File) {
    init {
        check(root.isDirectory || root.mkdirs()) { "Cannot create script package directory" }
    }

    /** Consumes/closes a ZIP and publishes only a fully validated revision.
     * Example: `repository.importPackage(contentResolver.openInputStream(uri)!!)`. Existing revisions stay intact.
     */
    @Synchronized
    fun importPackage(input: InputStream): StoredScriptPackage = publishRevision { staging -> extract(input, staging) }

    /** Saves edits as a new revision, preserving the version used by a running session.
     * Example: `repository.saveRevision(id, mapOf("entry.js" to editedText.toByteArray()))`.
     */
    @Synchronized
    fun saveRevision(storageId: String, changes: Map<String, ByteArray>): StoredScriptPackage {
        val source = directory(storageId)
        validateContents(source)
        return publishRevision { staging ->
            check(source.copyRecursively(staging, overwrite = true)) { "Cannot copy the original script revision" }
            changes.forEach { (path, bytes) ->
                validatePackagePath(path)
                require(bytes.size <= MAX_FILE_BYTES) { "file=$path bytes=${bytes.size}; expected at most $MAX_FILE_BYTES" }
                val file = File(staging, path)
                check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "Cannot create parent for $path" }
                file.writeBytes(bytes)
            }
        }
    }

    /** Lists editable files, e.g. `repository.files(id)` for the package details screen. */
    @Synchronized
    fun files(storageId: String): List<String> {
        val source = directory(storageId)
        validateContents(source)
        return source.walkTopDown().filter { it.isFile }.map { it.relativeTo(source).invariantSeparatorsPath }.sorted().toList()
    }

    /** Reads one package resource, e.g. `repository.readFile(id, "images/ready.png")`. */
    @Synchronized
    fun readFile(storageId: String, path: String): ByteArray {
        validatePackagePath(path)
        val source = directory(storageId)
        val file = File(source, path)
        require(file.isFile && file.canonicalPath.startsWith(source.canonicalPath + File.separator) && file.length() <= MAX_FILE_BYTES) {
            "path=$path; expected an existing package file of at most $MAX_FILE_BYTES bytes"
        }
        return file.readBytes()
    }

    private fun publishRevision(prepare: (File) -> Unit): StoredScriptPackage {
        val id = UUID.randomUUID().toString()
        val staging = File(root, ".pending-$id")
        check(staging.mkdir()) { "Cannot create package staging directory" }
        try {
            prepare(staging)
            val manifest = validateContents(staging)
            check(staging.renameTo(File(root, id))) { "Cannot atomically publish script package" }
            return StoredScriptPackage(id, manifest)
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    /** Lists completed revisions only, e.g. `repository.listPackages()` for the library screen. */
    @Synchronized
    fun listPackages(): List<StoredScriptPackage> = checkNotNull(root.listFiles()) { "Cannot list script packages" }
        .filter { it.isDirectory && it.name.matches(storageIdPattern) }
        .sortedBy { it.name }
        .map { StoredScriptPackage(it.name, validateContents(it)) }

    /** Loads a fixed code snapshot. Example: `session.run(repository.program(id), host)`. */
    @Synchronized
    fun program(storageId: String): ScriptProgram {
        val directory = directory(storageId)
        val manifest = validateContents(directory)
        val sources = directory.walkTopDown().filter { it.isFile && it.extension == "js" }.associate {
            val path = it.relativeTo(directory).invariantSeparatorsPath
            validatePackagePath(path)
            require(it.length() <= MAX_SOURCE_BYTES) { "source=$path; expected at most $MAX_SOURCE_BYTES bytes" }
            path to decodeUtf8(it.readBytes(), path)
        }
        return ScriptProgram(manifest.entry, sources)
    }

    /** Writes/closes a portable ZIP containing package files only, e.g. `repository.exportPackage(id, output)`. */
    @Synchronized
    fun exportPackage(storageId: String, output: OutputStream) {
        val directory = directory(storageId)
        validateContents(directory)
        ZipOutputStream(output).use { zip ->
            directory.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(directory).invariantSeparatorsPath }.forEach { file ->
                val path = file.relativeTo(directory).invariantSeparatorsPath
                validatePackagePath(path)
                check(file.canonicalPath.startsWith(directory.canonicalPath + File.separator)) { "Package file escapes its revision" }
                zip.putNextEntry(ZipEntry(path).apply { time = 0 })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun directory(id: String): File {
        require(id.matches(storageIdPattern)) { "storageId=$id; expected a repository revision UUID" }
        return File(root, id).also { require(it.isDirectory) { "storageId=$id; expected an existing revision" } }
    }

    private fun extract(input: InputStream, staging: File) {
        var total = 0L
        val paths = mutableSetOf<String>()
        val buffer = ByteArray(8192)
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = entry.name.removeSuffix("/")
                validatePackagePath(path)
                require(paths.add(path.lowercase())) { "path=$path; expected a unique portable file path" }
                require(paths.size <= MAX_ENTRIES) { "entries=${paths.size}; expected at most $MAX_ENTRIES" }
                val file = File(staging, path)
                check(file.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "Package path escapes staging" }
                if (entry.isDirectory) {
                    check(file.isDirectory || file.mkdirs()) { "Cannot create package directory $path" }
                } else {
                    require(entry.size <= MAX_FILE_BYTES) { "file=$path size=${entry.size}; expected at most $MAX_FILE_BYTES bytes" }
                    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "Cannot create package parent for $path" }
                    var fileSize = 0L
                    file.outputStream().use { output ->
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            fileSize += count
                            total += count
                            require(fileSize <= MAX_FILE_BYTES && total <= MAX_PACKAGE_BYTES) {
                                "file=$path bytes=$fileSize, packageBytes=$total; expected file <= $MAX_FILE_BYTES and package <= $MAX_PACKAGE_BYTES"
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun validateContents(directory: File): ScriptManifest {
        val files = directory.walkTopDown().filter { it.isFile }.toList()
        require(files.size <= MAX_ENTRIES && files.sumOf { it.length() } <= MAX_PACKAGE_BYTES) {
            "package: expected at most $MAX_ENTRIES files and $MAX_PACKAGE_BYTES bytes"
        }
        val paths = mutableSetOf<String>()
        files.forEach { file ->
            val path = file.relativeTo(directory).invariantSeparatorsPath
            validatePackagePath(path)
            require(paths.add(path.lowercase()) && file.length() <= MAX_FILE_BYTES &&
                file.canonicalPath.startsWith(directory.canonicalPath + File.separator)) {
                "path=$path; expected a unique portable package file of at most $MAX_FILE_BYTES bytes"
            }
        }
        val manifestFile = File(directory, "manifest.json")
        require(manifestFile.isFile && manifestFile.length() <= MAX_SOURCE_BYTES) { "manifest.json: expected a file of at most $MAX_SOURCE_BYTES bytes" }
        val manifest = ScriptManifest.parse(decodeUtf8(manifestFile.readBytes(), "manifest.json"))
        require(File(directory, manifest.entry).isFile) { "entry=${manifest.entry}; expected an existing JavaScript source" }
        directory.walkTopDown().filter { it.isFile && it.extension == "js" }.forEach {
            require(it.length() <= MAX_SOURCE_BYTES) { "source=${it.name}; expected at most $MAX_SOURCE_BYTES bytes" }
            decodeUtf8(it.readBytes(), it.name)
        }
        manifest.resources.forEach { (name, element) ->
            require(name.matches(Regex("[a-zA-Z][a-zA-Z0-9_-]{0,63}"))) { "resource=$name; expected a named identifier" }
            val resource = element as? JsonObject ?: throw IllegalArgumentException("resources.$name: expected an object")
            when (val type = resource.text("type")) {
                "image" -> {
                    val path = resource.text("path")
                    validatePackagePath(path)
                    val file = File(directory, path)
                    require(file.isFile) { "resources.$name.path=$path; expected an existing PNG image" }
                    val header = file.inputStream().use { stream -> ByteArray(24).also { bytes ->
                        require(stream.read(bytes) == bytes.size) { "resources.$name.path=$path; expected a PNG header" }
                    } }
                    require(header.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte)) {
                        "resources.$name.path=$path; expected PNG bytes"
                    }
                    val width = ByteBuffer.wrap(header, 16, 4).int
                    val height = ByteBuffer.wrap(header, 20, 4).int
                    require(width > 0 && height > 0 && width.toLong() * height <= 16_000_000) {
                        "resources.$name dimensions=${width}x$height; expected positive dimensions up to 16 million pixels"
                    }
                    require(resource.integer("width") in 1..10000 && resource.integer("height") in 1..10000) {
                        "resources.$name: expected template width and height in 1..10000 reference pixels"
                    }
                }
                "text" -> {
                    resource.text("model")
                    val alternatives = resource["alternatives"] as? JsonArray
                    require(alternatives != null && alternatives.isNotEmpty() && alternatives.size <= 64 && alternatives.all {
                        it is JsonPrimitive && it.isString && it.content.isNotBlank() && it.content.length <= 256
                    }) { "resources.$name.alternatives: expected 1..64 nonempty strings up to 256 characters" }
                }
                "color" -> resource.integer("argb")
                "number" -> require(resource.text("format") in setOf("AUTO", "DOT_DECIMAL", "COMMA_DECIMAL", "POSITIVE_QUANTITY")) {
                    "resources.$name.format=${resource["format"]}; expected a supported number format"
                }
                else -> throw IllegalArgumentException("resources.$name.type=$type; expected image, text, color or number")
            }
        }
        return manifest
    }

    private fun decodeUtf8(bytes: ByteArray, path: String): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (failure: java.nio.charset.CharacterCodingException) {
        throw IllegalArgumentException("source=$path; expected valid UTF-8", failure)
    }

    private companion object {
        const val MAX_ENTRIES = 128
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
        const val MAX_SOURCE_BYTES = 256L * 1024
        const val MAX_PACKAGE_BYTES = 16L * 1024 * 1024
        val storageIdPattern = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    }
}
