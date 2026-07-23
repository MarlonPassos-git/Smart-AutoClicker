/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.image

import android.view.LayoutInflater
import android.view.ViewGroup

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

import com.buzbuz.smartautoclicker.core.domain.model.condition.ImageReference
import com.buzbuz.smartautoclicker.feature.smart.config.R
import com.buzbuz.smartautoclicker.feature.smart.config.databinding.ItemImageReferenceBinding

internal class ImageReferenceAdapter(
    private val onReplace: (Int) -> Unit,
    private val onRemove: (Int) -> Unit,
    private val onOrderChanged: (List<ImageReference>) -> Unit,
    private val onDragRequested: (RecyclerView.ViewHolder) -> Unit,
) : RecyclerView.Adapter<ImageReferenceViewHolder>() {

    private val referenceItems = mutableListOf<ImageReferenceItem>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageReferenceViewHolder =
        ImageReferenceViewHolder(
            ItemImageReferenceBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        )

    override fun getItemCount(): Int = referenceItems.size

    override fun onBindViewHolder(holder: ImageReferenceViewHolder, position: Int) {
        holder.bind(
            item = referenceItems[position],
            referenceNumber = position + 1,
            canRemove = itemCount > 1,
            onReplace = { onReplace(holder.requirePosition()) },
            onRemove = { onRemove(holder.requirePosition()) },
            onDragRequested = { onDragRequested(holder) },
        )
    }

    fun submitItems(items: List<ImageReferenceItem>) {
        referenceItems.clear()
        referenceItems.addAll(items)
        notifyDataSetChanged()
    }

    fun moveReference(from: Int, to: Int): Boolean {
        if (from !in referenceItems.indices || to !in referenceItems.indices) return false

        referenceItems.add(to, referenceItems.removeAt(from))
        notifyItemMoved(from, to)
        notifyItemRangeChanged(minOf(from, to), kotlin.math.abs(from - to) + 1)
        return true
    }

    fun notifyMoveFinished() {
        onOrderChanged(referenceItems.map(ImageReferenceItem::reference))
    }
}

internal class ImageReferenceViewHolder(
    private val viewBinding: ItemImageReferenceBinding,
) : RecyclerView.ViewHolder(viewBinding.root) {

    fun bind(
        item: ImageReferenceItem,
        referenceNumber: Int,
        canRemove: Boolean,
        onReplace: () -> Unit,
        onRemove: () -> Unit,
        onDragRequested: () -> Unit,
    ) {
        viewBinding.textReferenceNumber.text = itemView.context.getString(
            R.string.image_reference_number,
            referenceNumber,
        )
        item.bitmap?.let(viewBinding.imageReferenceThumbnail::setImageBitmap)
            ?: viewBinding.imageReferenceThumbnail.setImageResource(R.drawable.ic_cancel)
        viewBinding.buttonReplaceReference.setOnClickListener { onReplace() }
        viewBinding.buttonRemoveReference.isEnabled = canRemove
        viewBinding.buttonRemoveReference.setOnClickListener { onRemove() }
        viewBinding.buttonMoveReference.setOnLongClickListener {
            onDragRequested()
            true
        }
    }

    fun requirePosition(): Int = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
        ?: error("Invalid image reference adapter position: $bindingAdapterPosition; expected a bound position")
}

internal class ImageReferenceReorderTouchHelper : ItemTouchHelper.SimpleCallback(
    ItemTouchHelper.UP or ItemTouchHelper.DOWN,
    0,
) {
    private var referenceMoved = false

    override fun isLongPressDragEnabled(): Boolean = false

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean {
        val adapter = recyclerView.adapter as? ImageReferenceAdapter ?: return false
        referenceMoved = adapter.moveReference(
            viewHolder.bindingAdapterPosition,
            target.bindingAdapterPosition,
        )
        return referenceMoved
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        if (!referenceMoved) return

        (recyclerView.adapter as? ImageReferenceAdapter)?.notifyMoveFinished()
        referenceMoved = false
    }
}
