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
            
            // نام سرور
            holder.itemMainBinding.tvName.text = profile.remarks

            // بررسی وضعیت انتخاب
            val isSelected = (guid == MmkvManager.getSelectServer())

            if (isSelected) {
                // --- حالت فعال (Active) ---
                
                // 1. تغییر دایره دور (مثلا رنگی شود)
                holder.itemMainBinding.layoutIndicator.backgroundTintList = 
                    ColorStateList.valueOf(ContextCompat.getColor(mActivity, R.color.colorAccent))
                
                // 2. تنظیم عکس سرور روشن (لطفا عکس مربوطه را در drawable قرار دهید)
                // اگر عکس ندارید فعلا از یک آیکون موجود استفاده میکند
                // holder.itemMainBinding.ivStatusIcon.setImageResource(R.drawable.ic_server_active) 
                holder.itemMainBinding.ivStatusIcon.setImageResource(R.drawable.ic_routing_24dp) // موقت
                holder.itemMainBinding.ivStatusIcon.setColorFilter(Color.WHITE)

                // 3. نمایش کامل متن (بدون محدودیت خط)
                holder.itemMainBinding.tvName.maxLines = 10 
                holder.itemMainBinding.tvName.ellipsize = null
                holder.itemMainBinding.tvName.setTextColor(ContextCompat.getColor(mActivity, R.color.colorAccent))

            } else {
                // --- حالت عادی (Idle/Hadi) ---

                // 1. دایره کمرنگ
                holder.itemMainBinding.layoutIndicator.backgroundTintList = 
                    ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))

                // 2. تنظیم عکس سرور عادی
                // holder.itemMainBinding.ivStatusIcon.setImageResource(R.drawable.ic_server_idle)
                holder.itemMainBinding.ivStatusIcon.setImageResource(R.drawable.ic_routing_24dp) // موقت
                holder.itemMainBinding.ivStatusIcon.setColorFilter(Color.LTGRAY)

                // 3. نمایش متن تا 2 خط (کاملتر از قبل اما نه خیلی زیاد)
                holder.itemMainBinding.tvName.maxLines = 2
                holder.itemMainBinding.tvName.ellipsize = TextUtils.TruncateAt.END
                holder.itemMainBinding.tvName.setTextColor(Color.WHITE)
            }

            // کلیک روی آیتم
            holder.itemView.setOnClickListener {
                setSelectServer(guid)
                // اسکرول خودکار به وسط صفحه
                mActivity.scrollToPositionCentered(position)
            }
        }
    }

    fun setSelectServer(guid: String) {
        val selected = MmkvManager.getSelectServer()
        if (guid != selected) {
            MmkvManager.setSelectServer(guid)
            
            // رفرش کردن آیتم قبلی (برای خاموش شدن)
            if (!TextUtils.isEmpty(selected)) {
                notifyItemChanged(mActivity.mainViewModel.getPosition(selected.orEmpty()))
            }
            // رفرش کردن آیتم جدید (برای روشن شدن)
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