package com.buzbuz.smartautoclicker.feature.script.config

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.buzbuz.smartautoclicker.core.script.packages.ScriptPackageRepository
import com.buzbuz.smartautoclicker.core.script.packages.StoredScriptPackage
import com.buzbuz.smartautoclicker.core.script.runtime.QuickJsScriptRuntime
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

data class ScriptLibraryState(
    val packages: List<StoredScriptPackage> = emptyList(),
    val selected: StoredScriptPackage? = null,
    val files: List<String> = emptyList(),
    val path: String? = null,
    val original: String = "",
    val draft: String = "",
    val imagePreview: ScriptImagePreview? = null,
    val busy: Boolean = false,
    val message: String? = null,
) {
    val dirty: Boolean get() = draft != original
    val editable: Boolean get() = path?.endsWith(".js") == true || path?.endsWith(".json") == true
}

data class ScriptImagePreview(val bitmap: Bitmap, val originalWidth: Int, val originalHeight: Int)

data class ScriptCalibrationRequest(val storageId: String, val name: String, val image: Boolean)

/** Retains an editor draft across rotation; every save publishes a separate immutable revision. */
class ScriptLibraryViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val repository by lazy { ScriptPackageRepository(File(application.filesDir, "scripts/packages")) }
    private val recentValues by lazy { application.getSharedPreferences("script-start-answers", Context.MODE_PRIVATE) }
    private val mutableState = MutableStateFlow(ScriptLibraryState())
    val state = mutableState.asStateFlow()

    init { refresh() }

    /** Refreshes the script library, e.g. after returning from an import. */
    fun refresh() = work {
        val packages = io { repository.listPackages() }
        mutableState.update { it.copy(packages = packages) }
        val selectedId = savedState.get<String>("selected") ?: return@work
        val selected = packages.find { it.storageId == selectedId } ?: return@work
        val restoredDraft = savedState.get<String>("draft")
        load(selected, savedState["path"])
        restoredDraft?.let { draft ->
            savedState["draft"] = draft
            mutableState.update { it.copy(draft = draft) }
        }
        savedState.remove<String>("scriptError")?.let { failure -> mutableState.update { it.copy(message = failure) } }
    }

    /** Opens a revision after the UI has resolved any unsaved edits. */
    fun select(selected: StoredScriptPackage) = work { load(selected) }

    /** Opens a named file after the UI has resolved any unsaved edits. */
    fun selectFile(path: String) = work { state.value.selected?.let { load(it, path) } }

    /** Updates the in-memory/saved draft; does not modify the imported package. */
    fun edit(text: String) {
        if (!state.value.editable || state.value.busy) return
        savedState["draft"] = text
        mutableState.update { it.copy(draft = text, message = null) }
    }

    /** Restores the saved file after the user confirms discarding its draft. */
    fun discardDraft() {
        savedState["draft"] = state.value.original
        mutableState.update { it.copy(draft = it.original, message = null) }
    }

    /** Persists the exact saved revision/answers while Android's projection consent screen is open. */
    fun prepareRun(answers: JsonObject) {
        val selected = checkNotNull(state.value.selected)
        check(!state.value.dirty) { "Salve o rascunho antes de iniciar" }
        val validated = selected.manifest.answers(answers).toString()
        savedState["runId"] = selected.storageId
        savedState["runAnswers"] = validated
        if (selected.manifest.parameters.isNotEmpty()) recentValues.edit().putString(selected.manifest.id, validated).apply()
    }

    /** Restores compatible recent answers; changed definitions fall back to the package defaults. */
    fun recentAnswers(): JsonObject {
        val manifest = state.value.selected?.manifest ?: return JsonObject(emptyMap())
        if (manifest.parameters.isEmpty()) return JsonObject(emptyMap())
        val previous = runCatching { Json.parseToJsonElement(recentValues.getString(manifest.id, "{}")!!).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }
        return JsonObject(previous.filter { (name, value) ->
            val parameter = manifest.parameters[name] ?: return@filter false
            runCatching { parameter.validate(name, value) }.isSuccess
        })
    }

    /** Reads the pending immutable launch request after an activity recreation. */
    fun pendingRun(): Pair<String, String>? {
        val id = savedState.get<String>("runId") ?: return null
        return id to (savedState.get<String>("runAnswers") ?: "{}")
    }

    /** Keeps calibration metadata across permission dialogs/rotation without changing package contents. */
    fun prepareCalibration(name: String, image: Boolean) {
        val current = state.value
        check(!current.dirty) { "Salve o rascunho antes de calibrar" }
        savedState["calibrationId"] = checkNotNull(current.selected).storageId
        savedState["calibrationName"] = name
        savedState["calibrationImage"] = image
    }

    /** Reads a reviewed pending calibration after activity recreation. */
    fun pendingCalibration(): ScriptCalibrationRequest? {
        val id = savedState.get<String>("calibrationId") ?: return null
        return ScriptCalibrationRequest(id, checkNotNull(savedState["calibrationName"]), savedState["calibrationImage"] ?: false)
    }

    /** Clears a consumed or cancelled Android consent request. */
    fun clearPendingRun() {
        savedState.remove<String>("runId")
        savedState.remove<String>("runAnswers")
        savedState.remove<String>("calibrationId")
        savedState.remove<String>("calibrationName")
        savedState.remove<Boolean>("calibrationImage")
    }

    /** Returns to the library, discarding the current draft only after UI confirmation. */
    fun closeEditor() {
        savedState.remove<String>("selected")
        savedState.remove<String>("path")
        savedState.remove<String>("draft")
        mutableState.update { it.copy(selected = null, files = emptyList(), path = null, draft = "", original = "", imagePreview = null, message = null) }
    }

    /** Creates an editable starter package, e.g. `model.create(getString(R.string.script_new))`. */
    fun create(name: String) = work {
        val manifest = buildJsonObject {
            put("formatVersion", 1); put("apiVersion", 1); put("id", "script-${UUID.randomUUID()}")
            put("name", name); put("scriptVersion", "1.0.0"); put("entry", "entry.js")
            put("parameters", JsonObject(emptyMap())); put("resources", JsonObject(emptyMap()))
        }
        val stored = io {
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { zip ->
                for ((path, source) in mapOf("manifest.json" to manifest.toString(),
                    "entry.js" to "import { observe } from 'klickr';\n\nconst screen = await observe();\n")) {
                    zip.putNextEntry(ZipEntry(path)); zip.write(source.toByteArray()); zip.closeEntry()
                }
            }
            repository.importPackage(ByteArrayInputStream(bytes.toByteArray()))
        }
        val packages = io { repository.listPackages() }
        mutableState.update { it.copy(packages = packages) }
        load(stored)
    }

    /** Imports a document URI without executing its source. */
    fun importPackage(uri: Uri) = work {
        val stored = io {
            repository.importPackage(checkNotNull(getApplication<Application>().contentResolver.openInputStream(uri)) { "Não foi possível abrir o pacote" })
        }
        val packages = io { repository.listPackages() }
        mutableState.update { it.copy(packages = packages) }
        load(stored)
    }

    /** Exports only package files to a document URI. */
    fun exportPackage(uri: Uri) = work {
        val selected = checkNotNull(state.value.selected)
        io { repository.exportPackage(selected.storageId,
            checkNotNull(getApplication<Application>().contentResolver.openOutputStream(uri)) { "Não foi possível abrir o destino" }) }
    }

    /** Validates JavaScript syntax without invoking Android operations. */
    fun validate() = work { validateDraft(); mutableState.update { it.copy(message = "Sintaxe válida") } }

    /** Validates and publishes the edited file, retaining the previous revision. */
    fun save() = work {
        validateDraft()
        val current = state.value
        val stored = io { repository.saveRevision(checkNotNull(current.selected).storageId,
            mapOf(checkNotNull(current.path) to current.draft.toByteArray())) }
        val packages = io { repository.listPackages() }
        mutableState.update { it.copy(packages = packages) }
        load(stored, current.path)
    }

    private suspend fun validateDraft() {
        val current = state.value
        val selected = checkNotNull(current.selected)
        if (current.path?.endsWith(".js") == true) {
            val program = io { repository.program(selected.storageId) }
            QuickJsScriptRuntime(Dispatchers.Default).validate(program.copy(sources = program.sources + (current.path to current.draft)))
        } else if (current.path?.endsWith(".json") == true) {
            Json.parseToJsonElement(current.draft)
        }
    }

    private suspend fun load(selected: StoredScriptPackage, requestedPath: String? = null) {
        val files = io { repository.files(selected.storageId) }
        val path = requestedPath?.takeIf { it in files } ?: selected.manifest.entry
        val text = if (path.endsWith(".js") || path.endsWith(".json")) io { repository.readFile(selected.storageId, path).toString(Charsets.UTF_8) } else ""
        val imagePreview = if (path.endsWith(".png")) io { loadImagePreview(selected.storageId, path) } else null
        savedState["selected"] = selected.storageId
        savedState["path"] = path
        savedState["draft"] = text
        mutableState.update { it.copy(selected = selected, files = files, path = path, draft = text, original = text, imagePreview = imagePreview, message = null) }
    }

    private fun loadImagePreview(storageId: String, path: String): ScriptImagePreview {
        val bytes = repository.readFile(storageId, path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Imagem $path inválida; esperado PNG decodificável" }
        // Preview resolution is independent of the original template used by the detector.
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) > 1024 * inSampleSize) inSampleSize *= 2
        }
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) {
            "Imagem $path inválida; esperado PNG decodificável"
        }
        return ScriptImagePreview(bitmap, bounds.outWidth, bounds.outHeight)
    }

    private fun work(operation: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try { operation() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.update { it.copy(message = failure.scriptFailureMessage()) } }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    private suspend fun <T> io(operation: () -> T): T = withContext(Dispatchers.IO) { operation() }
}
