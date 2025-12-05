package com.v2ray.ang.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.AngApplication.Companion.application
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ItemRecyclerFooterBinding
import com.v2ray.ang.databinding.ItemRecyclerMainBinding
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.V2RayServiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainRecyclerAdapter(val activity: MainActivity) : RecyclerView.Adapter<MainRecyclerAdapter.BaseViewHolder>() {
    companion object {
        private const val VIEW_TYPE_ITEM = 1
        private const val VIEW_TYPE_FOOTER = 2 // Footer kept minimal if needed, but logic focused on items
    }

    private var mActivity: MainActivity = activity
    var isRunning = false

    override fun getItemCount() = mActivity.mainViewModel.serversCache.size

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        if (holder is MainViewHolder) {
            val guid = mActivity.mainViewModel.serversCache[position].guid
            val profile = mActivity.mainViewModel.serversCache[position].profile
            
            // نام سرور
            holder.itemMainBinding.tvName.text = profile.remarks

            // وضعیت انتخاب شده
            if (guid == MmkvManager.getSelectServer()) {
                // دایره پررنگ تر یا رنگ متفاوت برای آیتم انتخاب شده
                holder.itemMainBinding.layoutIndicator.backgroundTintList = 
                    ColorStateList.valueOf(ContextCompat.getColor(mActivity, R.color.colorAccent))
                holder.itemMainBinding.tvName.setTextColor(ContextCompat.getColor(mActivity, R.color.colorAccent))
                holder.itemMainBinding.ivStatusIcon.setColorFilter(Color.WHITE)
            } else {
                // حالت عادی
                holder.itemMainBinding.layoutIndicator.backgroundTintList = 
                    ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))
                holder.itemMainBinding.tvName.setTextColor(Color.WHITE)
                holder.itemMainBinding.ivStatusIcon.setColorFilter(Color.WHITE)
            }

            // کلیک برای انتخاب دستی (که باعث اسکرول شدن به وسط در اکتیویتی می‌شود)
            holder.itemView.setOnClickListener {
                setSelectServer(guid)
                // اسکرول به پوزیشن این آیتم تا وسط بیاید از طریق متد عمومی اکتیویتی
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