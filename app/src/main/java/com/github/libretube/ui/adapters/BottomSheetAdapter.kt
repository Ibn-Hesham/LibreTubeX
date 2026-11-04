package com.github.libretube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.databinding.BottomSheetItemBinding
import com.github.libretube.obj.bottomsheetitem.BottomSheetItem
import com.github.libretube.ui.adapters.callbacks.DiffUtilItemCallback
import com.github.libretube.ui.extensions.setDrawables
import com.github.libretube.ui.viewholders.BottomSheetViewHolder

class BottomSheetAdapter(
    // mutable so that BaseBottomSheet can replace the listener when setItems
    // is called again while the sheet is already visible
    internal var listener: (index: Int) -> Unit
) : ListAdapter<BottomSheetItem, BottomSheetViewHolder>(
    DiffUtilItemCallback()
) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BottomSheetViewHolder {
        val binding = BottomSheetItemBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return BottomSheetViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BottomSheetViewHolder, position: Int) {
        val item = getItem(position)!!
        holder.binding.root.apply {
            val current = item.getCurrent()?.getText(context)
            val title = item.title.getText(context)
            text = if (current != null) "$title ($current)" else title
            isSelected = item.isSelected
            setDrawables(start = item.drawable)

            setOnClickListener {
                item.onClick.invoke()
                // resolve the index at click time: the bind-time position goes
                // stale when a later submitList shifts this row, e.g. when
                // options get prepended while the sheet is already shown
                val index = holder.bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) listener.invoke(index)
            }
        }
    }
}
