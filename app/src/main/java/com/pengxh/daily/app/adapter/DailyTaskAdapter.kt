package com.pengxh.daily.app.adapter

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.pengxh.daily.app.R
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.kt.lite.adapter.NormalRecyclerAdapter.ItemComparator
import com.pengxh.kt.lite.adapter.ViewHolder
import com.pengxh.kt.lite.extensions.convertColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DailyTaskAdapter(
    private val context: Context, private val dataBeans: MutableList<DailyTaskBean>
) : RecyclerView.Adapter<ViewHolder>() {

    private var layoutInflater = LayoutInflater.from(context)
    private var mPosition = -1
    private var actualTime = "--:--:--"
    private var onItemClickListener: OnItemClickListener? = null

    fun updateCurrentTaskState(position: Int) {
        this.mPosition = position
        notifyItemRangeChanged(0, dataBeans.size)
    }

    fun updateCurrentTaskState(position: Int, actualTime: String) {
        this.mPosition = position
        this.actualTime = actualTime
        if (position < 0 || position >= dataBeans.size) {
            return
        }
        notifyItemRangeChanged(0, mPosition + 1)
    }

    override fun getItemCount(): Int = dataBeans.size

    override fun getItemId(position: Int): Long = position.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            layoutInflater.inflate(R.layout.item_daily_task_rv_l, parent, false)
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val taskBean = dataBeans[position]
        holder.setText(R.id.taskTimeView, taskBean.time)
        val arrowView = holder.getView<AppCompatImageView>(R.id.arrowView)
        if (position == mPosition) {
            holder.itemView.isSelected = true
            holder.setVisibility(R.id.actualTimeCardView, View.VISIBLE)
                .setText(R.id.actualTimeView, actualTime)
                .setTextColor(R.id.actualTimeView, R.color.theme_color.convertColor(context))
                .setTextColor(R.id.taskTimeView, R.color.text_hint_color.convertColor(context))
            arrowView.animate().rotation(90f).setDuration(500).start()
        } else {
            holder.itemView.isSelected = false
            holder.setVisibility(R.id.actualTimeCardView, View.GONE)
                .setText(R.id.actualTimeView, "--:--:--")
                .setTextColor(R.id.taskTimeView, Color.WHITE)
            arrowView.animate().rotation(0f).setDuration(500).start()
        }

        holder.itemView.setOnClickListener {
            onItemClickListener?.onItemClick(position)
        }

        holder.itemView.setOnLongClickListener {
            onItemClickListener?.onItemLongClick(position)
            return@setOnLongClickListener true
        }
    }

    fun refresh(
        newRows: MutableList<DailyTaskBean>, itemComparator: ItemComparator<DailyTaskBean>? = null
    ) {
        // 防御性拷贝：调用方传入的列表可能就是 dataBeans 本身（如删除条目后直接回传），
        // 若直接 clear() 会把数据源一起清空，导致数据丢失及 RecyclerView 状态不一致崩溃
        val snapshot = ArrayList(newRows)

        if (itemComparator != null) {
            val oldDataSnapshot = ArrayList(dataBeans) // 旧数据副本

            val diffCallback = object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldDataSnapshot.size
                override fun getNewListSize(): Int = snapshot.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return itemComparator.areItemsTheSame(
                        oldDataSnapshot[oldItemPosition], snapshot[newItemPosition]
                    )
                }

                override fun areContentsTheSame(
                    oldItemPosition: Int, newItemPosition: Int
                ): Boolean {
                    return itemComparator.areContentsTheSame(
                        oldDataSnapshot[oldItemPosition], snapshot[newItemPosition]
                    )
                }
            }

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val result = DiffUtil.calculateDiff(diffCallback)
                    withContext(Dispatchers.Main) {
                        dataBeans.clear()
                        dataBeans.addAll(snapshot)
                        result.dispatchUpdatesTo(this@DailyTaskAdapter)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } else {
            // 无差异对比时整体替换并全量刷新。任务列表数据量很小（个位数），
            // notifyDataSetChanged 开销可忽略，且能保证增/删/清空任何场景下状态一致
            // （旧实现里 notifyItemRangeRemoved + notifyItemRangeChanged 在
            //   传入列表与 dataBeans 同引用时会计算出错误的通知范围，导致越界崩溃）
            dataBeans.clear()
            dataBeans.addAll(snapshot)
            notifyDataSetChanged()
        }
    }

    interface OnItemClickListener {
        fun onItemClick(position: Int)

        fun onItemLongClick(position: Int)
    }

    fun setOnItemClickListener(listener: OnItemClickListener) {
        this.onItemClickListener = listener
    }
}