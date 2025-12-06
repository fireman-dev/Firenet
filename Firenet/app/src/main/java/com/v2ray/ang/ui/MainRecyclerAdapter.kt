package com.v2ray.ang.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ItemRecyclerMainBinding
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.V2RayServiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainRecyclerAdapter(val activity: MainActivity) : RecyclerView.Adapter<MainRecyclerAdapter.BaseViewHolder>() {
    companion object {
        private const val VIEW_TYPE_ITEM = 1
    }

    private var mActivity: MainActivity = activity
    var isRunning = false

    override fun getItemCount() = mActivity.mainViewModel.serversCache.size

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        if (holder is MainViewHolder) {
            val guid = mActivity.mainViewModel.serversCache[position].guid
            val profile = mActivity.mainViewModel.serversCache[position].profile
            
            holder.itemMainBinding.tvName.text = profile.remarks

            val isSelected = (guid == MmkvManager.getSelectServer())

            if (isSelected) {
                // Active State
                holder.itemMainBinding.layoutIndicator.setBackgroundResource(R.drawable.bg_glass_input) // Assuming active style
                holder.itemMainBinding.layoutIndicator.backgroundTintList = 
                    ColorStateList.valueOf(ContextCompat.getColor(mActivity, R.color.colorAccent))
                
                holder.itemMainBinding.ivStatusIcon.setImageResource(R.drawable.ic_server_active)
                holder.itemMainBinding.ivStatusIcon.setColorFilter(Color.WHITE)

                holder.itemMainBinding.tvName.maxLines = 2
                holder.itemMainBinding.tvName.setTextColor(ContextCompat.getColor(mActivity, R.color.colorAccent))
                holder.itemMainBinding.tvName.ellipsize = null
            } else {
                // Idle State
                holder.itemMainBinding.layoutIndicator.setBackgroundResource(R.drawable.bg_glass_input)
                holder.itemMainBinding.layoutIndicator.backgroundTintList = 
                    ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))

                holder.itemMainBinding.ivStatusIcon.setImageResource(R.drawable.ic_server_idle)
                holder.itemMainBinding.ivStatusIcon.setColorFilter(Color.LTGRAY)

                holder.itemMainBinding.tvName.maxLines = 1
                holder.itemMainBinding.tvName.setTextColor(Color.WHITE)
                holder.itemMainBinding.tvName.ellipsize = TextUtils.TruncateAt.END
            }

            holder.itemView.setOnClickListener {
                setSelectServer(guid)
                mActivity.scrollToPositionCentered(position)
            }
        }
    }

    fun setSelectServer(guid: String) {
        val selected = MmkvManager.getSelectServer()
        if (guid != selected) {
            MmkvManager.setSelectServer(guid)
            if (!TextUtils.isEmpty(selected)) {
                notifyItemChanged(mActivity.mainViewModel.getPosition(selected.orEmpty()))
            }
            notifyItemChanged(mActivity.mainViewModel.getPosition(guid))
            if (isRunning) {
                V2RayServiceManager.stopVService(mActivity)
                mActivity.lifecycleScope.launch {
                    try {
                        delay(500)
                        V2RayServiceManager.startVService(mActivity)
                    } catch (e: Exception) {
                        Log.e(AppConfig.TAG, "Failed to restart V2Ray service", e)
                    }
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
         return MainViewHolder(ItemRecyclerMainBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun getItemViewType(position: Int): Int {
        return VIEW_TYPE_ITEM
    }

    open class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    class MainViewHolder(val itemMainBinding: ItemRecyclerMainBinding) :
        BaseViewHolder(itemMainBinding.root)
}