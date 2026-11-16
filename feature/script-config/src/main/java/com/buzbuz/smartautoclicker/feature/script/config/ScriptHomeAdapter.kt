package com.buzbuz.smartautoclicker.feature.script.config

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.buzbuz.smartautoclicker.feature.script.config.R
import com.buzbuz.smartautoclicker.core.script.packages.StoredScriptPackage
import com.buzbuz.smartautoclicker.feature.script.config.databinding.ItemScriptHomeBinding

/** Imported scripts on the home screen; e.g. `ScriptHomeAdapter(onStart = ::openStartForm, onSetup = ::openSetup)`. */
class ScriptHomeAdapter(
    private val onStart: (StoredScriptPackage) -> Unit,
    private val onSetup: (StoredScriptPackage) -> Unit = {},
) :
    ListAdapter<StoredScriptPackage, ScriptHomeAdapter.Holder>(object : DiffUtil.ItemCallback<StoredScriptPackage>() {
        override fun areItemsTheSame(old: StoredScriptPackage, new: StoredScriptPackage) = old.manifest.id == new.manifest.id
        override fun areContentsTheSame(old: StoredScriptPackage, new: StoredScriptPackage) = old.storageId == new.storageId
    }) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemScriptHomeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    override fun onViewAttachedToWindow(holder: Holder) {
        super.onViewAttachedToWindow(holder)
        (holder.itemView.layoutParams as? StaggeredGridLayoutManager.LayoutParams)?.isFullSpan = true
    }

    inner class Holder(private val binding: ItemScriptHomeBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(script: StoredScriptPackage) = with(binding) {
            scriptName.text = script.manifest.name
            scriptDescription.text = root.context.getString(R.string.home_script_description, script.manifest.scriptVersion)
            scriptStart.contentDescription = root.context.getString(R.string.home_script_start, script.manifest.name)
            scriptStart.setOnClickListener { onStart(script) }
            root.setOnClickListener { onStart(script) }
            scriptSetup.visibility = if (script.manifest.setup == null) android.view.View.GONE else android.view.View.VISIBLE
            scriptSetup.text = script.manifest.setup?.label
            scriptSetup.setOnClickListener { onSetup(script) }
        }
    }
}
