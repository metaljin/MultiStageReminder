package com.reminder.multistage
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.reminder.multistage.databinding.ItemTemplateBinding

class TemplateAdapter(private val onStart: (Long)->Unit, private val onEdit: (Long)->Unit, private val onDelete: (TemplateWithStages)->Unit) : ListAdapter<TemplateWithStages, TemplateAdapter.VH>(Diff()) {
    inner class VH(val binding: ItemTemplateBinding) : RecyclerView.ViewHolder(binding.root)
    class Diff : DiffUtil.ItemCallback<TemplateWithStages>() {
        override fun areItemsTheSame(old: TemplateWithStages, new: TemplateWithStages) = old.template.id == new.template.id
        override fun areContentsTheSame(old: TemplateWithStages, new: TemplateWithStages) = old == new
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(ItemTemplateBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: VH, pos: Int) {
        val data = getItem(pos)
        holder.binding.tvName.text = data.template.name
        holder.binding.tvCycles.text = "循环：${data.template.totalCycles} 次"
        holder.binding.btnStart.setOnClickListener { onStart(data.template.id) }
        holder.binding.btnEdit.setOnClickListener { onEdit(data.template.id) }
        holder.binding.btnDelete.setOnClickListener { onDelete(data) }
    }
}
