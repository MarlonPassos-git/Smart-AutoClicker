package com.buzbuz.smartautoclicker.feature.script.config

import android.os.Bundle
import android.os.Build
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.widget.Toast
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import com.buzbuz.smartautoclicker.core.script.packages.StoredScriptPackage
import com.buzbuz.smartautoclicker.feature.script.config.databinding.ActivityScriptLibraryBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.buzbuz.smartautoclicker.core.common.permissions.model.PermissionOverlay
import com.buzbuz.smartautoclicker.core.common.permissions.model.PermissionAccessibilityService
import com.buzbuz.smartautoclicker.core.common.permissions.model.PermissionPostNotification
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.buzbuz.smartautoclicker.core.base.data.AppComponentsProvider
import com.buzbuz.smartautoclicker.core.common.accessibility.domain.LocalAccessibilityServiceConnection
import com.buzbuz.smartautoclicker.core.common.permissions.PermissionsController

/** Script library and simple draft editor, using the application's existing Material theme. */
@AndroidEntryPoint
class ScriptLibraryActivity : AppCompatActivity() {
    @Inject lateinit var serviceConnection: LocalAccessibilityServiceConnection
    @Inject lateinit var appComponents: AppComponentsProvider
    @Inject lateinit var permissionController: PermissionsController
    private lateinit var binding: ActivityScriptLibraryBinding
    private lateinit var model: ScriptLibraryViewModel
    private val packageAdapter = PackageAdapter()
    private var fileNames = emptyList<String>()
    private var pendingEditorPosition: IntArray? = null
    private val projectionRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val newRound = model.pendingNewRound()
        val setup = model.pendingSetup()
        val pending = model.pendingRun()
        val calibration = model.pendingCalibration()
        model.clearPendingRun()
        if (result.resultCode != RESULT_OK || result.data == null || (pending == null && calibration == null)) {
            model.recordStartFailure(pending?.first ?: calibration?.storageId, getString(R.string.script_projection_denied))
            Toast.makeText(this, R.string.script_projection_denied, Toast.LENGTH_LONG).show()
        } else {
            lifecycleScope.launch {
                try {
                    val revision = (pending?.first ?: calibration?.storageId)?.let { model.executionPackage(it) }
                    lifecycle.withResumed {
                        val service = checkNotNull(serviceConnection.getLocalService()) { getString(R.string.script_start_failed) }
                        ScriptTargetAppLauncher(this@ScriptLibraryActivity).open(revision?.manifest?.targetPackage)
                        val accepted = if (calibration != null) {
                            service.startScriptCalibration(result.resultCode, result.data!!, calibration.storageId, calibration.name, calibration.image)
                        } else {
                            service.startScript(result.resultCode, result.data!!, pending!!.first, pending.second, newRound, setup)
                        }
                        if (accepted) finishAffinity()
                        else {
                            model.recordStartFailure(pending?.first ?: calibration?.storageId, getString(R.string.script_start_failed))
                            Toast.makeText(this@ScriptLibraryActivity, R.string.script_start_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    model.recordStartFailure(pending?.first ?: calibration?.storageId, failure.message ?: getString(R.string.script_start_failed), failure)
                    Toast.makeText(this@ScriptLibraryActivity, failure.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { model.importPackage(it) } }
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { model.exportPackage(it) } }
    private val exportHistory = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { model.exportHistory(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingEditorPosition = savedInstanceState?.getIntArray("scriptEditorPosition")
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityScriptLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        model = ViewModelProvider(this)[ScriptLibraryViewModel::class.java]
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.toolbar.menu.add(R.string.script_export_history).setOnMenuItemClickListener {
            if (!model.state.value.busy) exportHistory.launch("klickr-historico.zip")
            true
        }
        onBackPressedDispatcher.addCallback(this) {
            if (model.state.value.selected == null || model.state.value.busy) finish()
            else confirmDiscard { model.closeEditor() }
        }
        binding.packages.adapter = packageAdapter
        binding.packages.setOnItemClickListener { _, _, position, _ -> model.select(packageAdapter.items[position]) }
        binding.create.setOnClickListener { model.create(getString(R.string.script_new)) }
        binding.importPackage.setOnClickListener { importDocument.launch(arrayOf("application/zip", "application/octet-stream")) }
        binding.exportPackage.setOnClickListener {
            confirmDiscard { exportDocument.launch("${model.state.value.selected?.manifest?.id ?: "script"}.zip") }
        }
        binding.save.setOnClickListener { model.save() }
        binding.validate.setOnClickListener { model.validate() }
        supportFragmentManager.setFragmentResultListener(ScriptStartParametersDialog.RESULT, this) { _, result ->
            val revision = result.getString("revision") ?: return@setFragmentResultListener
            lifecycleScope.launch {
                val selected = model.state.first { !it.busy }.selected
                if (selected?.storageId != revision) return@launch
                val answers = kotlinx.serialization.json.Json.parseToJsonElement(result.getString("answers")!!)
                    as kotlinx.serialization.json.JsonObject
                model.clearPendingRun()
                model.prepareRun(answers, result.getBoolean("newRound"))
                requestExecutionPermissions()
            }
        }
        supportFragmentManager.setFragmentResultListener(ScriptCalibrationDialog.RESULT, this) { _, result ->
            lifecycleScope.launch {
                val selected = model.state.first { !it.busy }.selected
                if (selected?.storageId != result.getString("revision")) return@launch
                model.clearPendingRun()
                model.prepareCalibration(result.getString("name")!!, result.getBoolean("image"))
                requestExecutionPermissions()
            }
        }
        binding.calibrate.setOnClickListener {
            model.state.value.selected?.let { ScriptCalibrationDialog.show(this, it.storageId) }
        }
        binding.setup.setOnClickListener { offerSetup() }
        binding.start.setOnClickListener {
            offerRoundStart()
        }
        if (savedInstanceState == null && (intent.getBooleanExtra("startFromHome", false) || intent.getBooleanExtra("setupFromHome", false))) {
            lifecycleScope.launch {
                model.state.first { !it.busy && it.selected?.storageId == intent.getStringExtra("selected") }
                if (intent.getBooleanExtra("setupFromHome", false)) offerSetup() else offerRoundStart()
            }
        }
        binding.editor.setHorizontallyScrolling(true)
        binding.editor.doAfterTextChanged { text ->
            binding.lines.text = (1..((text?.count { it == '\n' } ?: 0) + 1)).joinToString("\n")
            if (text.toString() != model.state.value.draft) model.edit(text.toString())
        }
        binding.editor.setOnScrollChangeListener { _, _, y, _, _ -> binding.lines.scrollTo(0, y) }
        binding.files.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val path = fileNames.getOrNull(position) ?: return
                if (path == model.state.value.path) return
                confirmDiscard { model.selectFile(path) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect(::render) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (model.state.value.editable) {
            binding.editor.let { editor ->
                outState.putIntArray("scriptEditorPosition", intArrayOf(
                    editor.selectionStart, editor.selectionEnd, editor.scrollX, editor.scrollY))
            }
        }
        super.onSaveInstanceState(outState)
    }

    private fun offerSetup() {
        val selected = model.state.value.selected ?: return
        val setup = selected.manifest.setup ?: return
        MaterialAlertDialogBuilder(this).setTitle(setup.label)
            .setMessage(setup.description ?: getString(R.string.script_setup_description))
            .setNegativeButton(R.string.script_cancel, null)
            .setPositiveButton(R.string.script_continue) { _, _ ->
                model.clearPendingRun()
                model.prepareSetup()
                requestExecutionPermissions()
            }.show()
    }

    private fun showStartForm(selected: StoredScriptPackage, newRound: Boolean = false) {
        lifecycleScope.launch {
            try {
                val configuration = model.configuration()
                if (model.state.value.selected?.storageId != selected.storageId) return@launch
                if (selected.manifest.parameters.values.any { it.type == "selection" }) {
                    com.buzbuz.smartautoclicker.core.script.packages.configurationItems(configuration)
                }
                ScriptStartParametersDialog.show(this@ScriptLibraryActivity, selected.manifest, selected.storageId,
                    model.recentAnswers(), newRound, configuration)
            } catch (failure: Exception) {
                MaterialAlertDialogBuilder(this@ScriptLibraryActivity).setTitle(R.string.script_setup_required)
                    .setMessage(failure.message).setNegativeButton(R.string.script_cancel, null)
                    .apply { selected.manifest.setup?.let { setup -> setPositiveButton(setup.label) { _, _ -> offerSetup() } } }.show()
            }
        }
    }

    private fun offerRoundStart() {
        val selected = model.state.value.selected ?: return
        lifecycleScope.launch {
            val result = runCatching { model.unfinishedRound() }
            if (model.state.value.selected?.storageId != selected.storageId) return@launch
            val previous = result.getOrNull()
            if (previous == null && result.isSuccess) {
                showStartForm(selected)
                return@launch
            }
            val compatible = previous?.get("revision")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } == selected.storageId
            MaterialAlertDialogBuilder(this@ScriptLibraryActivity)
                .setTitle(R.string.script_saved_round)
                .setMessage(buildString {
                    append(if (result.isFailure) getString(R.string.script_invalid_round)
                        else if (!compatible) getString(R.string.script_incompatible_round) else getString(R.string.script_continue_round_description))
                    (previous?.get("interruptionReason") as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { append("\n\n").append(it.take(4096)) }
                    val state = previous?.get("state") as? kotlinx.serialization.json.JsonObject
                    (state?.get("details") as? kotlinx.serialization.json.JsonArray)?.take(64)?.forEach { value ->
                        (value as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { append("\n").append(it.take(512)) }
                    }
                })
                .setNegativeButton(R.string.script_cancel, null)
                .setNeutralButton(R.string.script_new_round) { _, _ ->
                    showStartForm(selected, newRound = true)
                }.apply {
                    if (compatible) setPositiveButton(R.string.script_continue_round) { _, _ ->
                        val answers = previous!!.getValue("answers") as kotlinx.serialization.json.JsonObject
                        continueSavedRound(previous, answers)
                    }
                }.show()
        }
    }

    private fun continueSavedRound(previous: kotlinx.serialization.json.JsonObject, answers: kotlinx.serialization.json.JsonObject) {
        val checkpoint = previous["state"] as? kotlinx.serialization.json.JsonObject
        val recovery = checkpoint?.get("recovery") as? kotlinx.serialization.json.JsonObject
        val choices = recovery?.get("choices") as? kotlinx.serialization.json.JsonArray
        if (recovery == null || choices.isNullOrEmpty()) {
            model.prepareRun(answers)
            requestExecutionPermissions()
            return
        }
        val definitions = choices.take(8).mapNotNull { it as? kotlinx.serialization.json.JsonObject }
        val labels = definitions.map { (it["label"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty().take(256) }.toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle(R.string.script_recovery_pending)
            .setMessage((recovery["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.take(4096))
            .setItems(labels) { _, index ->
                lifecycleScope.launch {
                    runCatching {
                        val choice = (definitions[index].getValue("id") as kotlinx.serialization.json.JsonPrimitive).content
                        model.resolveRecovery(previous, choice)
                        model.prepareRun(answers)
                        requestExecutionPermissions()
                    }.onFailure { Toast.makeText(this@ScriptLibraryActivity, it.message, Toast.LENGTH_LONG).show() }
                }
            }.setNegativeButton(R.string.script_cancel, null).show()
    }

    private fun requestExecutionPermissions() {
        permissionController.startPermissionsUiFlow(this, listOf(
            PermissionOverlay(), PermissionAccessibilityService(appComponents.klickrServiceComponentName,
                isServiceRunning = { serviceConnection.isServiceStarted() }), PermissionPostNotification(optional = true),
        ), onAllGranted = {
            val manager = checkNotNull(getSystemService(MediaProjectionManager::class.java)) { "Captura de tela indisponível" }
            val intent = if (Build.VERSION.SDK_INT >= 35) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                else manager.createScreenCaptureIntent()
            projectionRequest.launch(intent)
        }, onMandatoryDenied = {
            model.recordStartFailure(model.pendingRun()?.first ?: model.pendingCalibration()?.storageId, "Permissão obrigatória negada antes de iniciar")
            model.clearPendingRun()
        })
    }

    private fun render(state: ScriptLibraryState) = with(binding) {
        toolbar.title = state.selected?.manifest?.name ?: getString(R.string.script_library_title)
        library.isVisible = state.selected == null
        details.isVisible = state.selected != null
        progress.isVisible = state.busy
        message.isVisible = state.message != null
        message.text = state.message
        empty.isVisible = state.packages.isEmpty() && !state.busy
        packageAdapter.items = state.packages
        packageAdapter.notifyDataSetChanged()
        if (fileNames != state.files) {
            fileNames = state.files
            files.adapter = ArrayAdapter(this@ScriptLibraryActivity, android.R.layout.simple_spinner_dropdown_item, fileNames)
        }
        val selectedIndex = fileNames.indexOf(state.path)
        if (selectedIndex >= 0 && files.selectedItemPosition != selectedIndex) files.setSelection(selectedIndex)
        state.selected?.let { version.text = getString(R.string.script_version, it.manifest.scriptVersion, it.storageId.take(8)) }
        editorContainer.isVisible = state.editable
        resource.isVisible = !state.editable && state.selected != null
        resource.text = state.imagePreview?.let { image ->
            getString(R.string.script_image_preview, state.path, image.originalWidth, image.originalHeight)
        } ?: getString(R.string.script_resource, state.path ?: "")
        imagePreview.isVisible = state.imagePreview != null && !state.editable && !state.busy
        imagePreview.setImageBitmap(state.imagePreview?.bitmap)
        imagePreview.contentDescription = resource.text
        if (editor.text.toString() != state.draft) editor.setText(state.draft)
        if (!state.busy && state.editable && state.selected != null) {
            pendingEditorPosition?.takeIf { it.size == 4 }?.let { position ->
                pendingEditorPosition = null
                editor.setSelection(position[0].coerceIn(0, editor.length()), position[1].coerceIn(0, editor.length()))
                editor.doOnLayout { editor.scrollTo(position[2], position[3]) }
            }
        }
        create.isEnabled = !state.busy
        importPackage.isEnabled = !state.busy
        packages.isEnabled = !state.busy
        files.isEnabled = !state.busy
        editor.isEnabled = !state.busy
        save.isEnabled = state.editable && state.dirty && !state.busy
        validate.isEnabled = state.editable && !state.busy
        exportPackage.isEnabled = !state.busy
        start.isEnabled = state.selected != null && !state.dirty && !state.busy
        setup.isVisible = state.selected?.manifest?.setup != null
        setup.text = state.selected?.manifest?.setup?.label
        setup.isEnabled = state.selected != null && !state.dirty && !state.busy
        calibrate.isEnabled = state.selected != null && !state.dirty && !state.busy
    }

    private fun confirmDiscard(action: () -> Unit) {
        if (!model.state.value.dirty) { action(); return }
        MaterialAlertDialogBuilder(this).setTitle(R.string.script_discard_title).setMessage(R.string.script_discard_message)
            .setNegativeButton(R.string.script_cancel) { _, _ ->
                val index = fileNames.indexOf(model.state.value.path)
                if (index >= 0) binding.files.setSelection(index)
            }
            .setPositiveButton(R.string.script_discard) { _, _ -> model.discardDraft(); action() }.show()
    }

    private inner class PackageAdapter : BaseAdapter() {
        var items: List<StoredScriptPackage> = emptyList()
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): StoredScriptPackage = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            val item = items[position]
            row.findViewById<TextView>(android.R.id.text1).text = item.manifest.name
            row.findViewById<TextView>(android.R.id.text2).text = getString(R.string.script_version, item.manifest.scriptVersion, item.storageId.take(8))
            row.minimumHeight = (64 * resources.displayMetrics.density).toInt()
            return row
        }
    }
}
