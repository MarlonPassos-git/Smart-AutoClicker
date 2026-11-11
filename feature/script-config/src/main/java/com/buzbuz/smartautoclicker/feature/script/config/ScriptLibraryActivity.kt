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
        val pending = model.pendingRun()
        val calibration = model.pendingCalibration()
        model.clearPendingRun()
        if (result.resultCode != RESULT_OK || result.data == null || (pending == null && calibration == null)) {
            Toast.makeText(this, R.string.script_projection_denied, Toast.LENGTH_LONG).show()
        } else {
            try {
                val service = serviceConnection.getLocalService()
                val accepted = if (calibration != null) service?.startScriptCalibration(result.resultCode, result.data!!,
                    calibration.storageId, calibration.name, calibration.image) == true
                else service?.startScript(result.resultCode, result.data!!, pending!!.first, pending.second) == true
                if (accepted) finishAffinity()
                else Toast.makeText(this, R.string.script_start_failed, Toast.LENGTH_LONG).show()
            } catch (failure: Exception) { Toast.makeText(this, failure.message, Toast.LENGTH_LONG).show() }
        }
    }
    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { model.importPackage(it) } }
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { model.exportPackage(it) } }

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
                model.prepareRun(answers)
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
        binding.start.setOnClickListener {
            val selected = model.state.value.selected ?: return@setOnClickListener
            ScriptStartParametersDialog.show(this, selected.manifest, selected.storageId, model.recentAnswers())
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

    private fun requestExecutionPermissions() {
        permissionController.startPermissionsUiFlow(this, listOf(
            PermissionOverlay(), PermissionAccessibilityService(appComponents.klickrServiceComponentName,
                isServiceRunning = { serviceConnection.isServiceStarted() }), PermissionPostNotification(optional = true),
        ), onAllGranted = {
            val manager = checkNotNull(getSystemService(MediaProjectionManager::class.java)) { "Captura de tela indisponível" }
            val intent = if (Build.VERSION.SDK_INT >= 35) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                else manager.createScreenCaptureIntent()
            projectionRequest.launch(intent)
        }, onMandatoryDenied = { model.clearPendingRun() })
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
