package com.v2ray.ang.ui

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewAnimationUtils
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayout
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.VPN
import com.v2ray.ang.R
import com.v2ray.ang.data.auth.AuthRepository
import com.v2ray.ang.data.auth.TokenStore
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MigrateManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.net.ApiClient
import com.v2ray.ang.net.StatusResponse
import com.v2ray.ang.ui.login.LoginActivity
import com.v2ray.ang.ui.main.StatusFormatter
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot

class MainActivity : BaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    private val repo by lazy { AuthRepository(this) }
    private var currentLinks: List<String> = emptyList()

    private val adapter by lazy { MainRecyclerAdapter(this) }
    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }

    // Animators
    private var ring1Animator: ObjectAnimator? = null
    private var ring2Animator: ObjectAnimator? = null
    private var ring3Animator: ObjectAnimator? = null
    private var isConnectingAnimationRunning = false
    
    // وضعیت آپدیت اجباری
    private var isForcedUpdateRequired = false

    private val tabGroupListener = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab?) {
            val selectId = tab?.tag.toString()
            if (selectId != mainViewModel.subscriptionId) {
                mainViewModel.subscriptionIdChanged(selectId)
            }
        }

        override fun onTabUnselected(tab: TabLayout.Tab?) {}
        override fun onTabReselected(tab: TabLayout.Tab?) {}
    }

    val mainViewModel: MainViewModel by viewModels()

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                when (pendingAction) {
                    Action.POST_NOTIFICATIONS -> {}
                    else -> {}
                }
            } else {
                toast(R.string.toast_permission_denied)
            }
            pendingAction = Action.NONE
        }

    private var pendingAction: Action = Action.NONE

    enum class Action {
        NONE,
        POST_NOTIFICATIONS
    }

    private val forceLogoutReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AppConfig.ACTION_FORCE_LOGOUT) {
                Toast.makeText(this@MainActivity, "نشست منقضی شد", Toast.LENGTH_SHORT).show()
                goLoginClearTask()
            }
        }
    }

    private var isForceLogoutReceiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        title = getString(R.string.title_server)

        binding.btnMenu.setOnClickListener {
            if (!binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                binding.drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        binding.btnLogout.setOnClickListener {
            val token = TokenStore.token(this) ?: return@setOnClickListener
            repo.logout(token) { r ->
                runOnUiThread {
                    r.onSuccess {
                        V2RayServiceManager.stopVService(this)
                        TokenStore.clear(this)
                        Toast.makeText(this, "خروج انجام شد", Toast.LENGTH_SHORT).show()
                        goLoginClearTask()
                    }
                }
            }
        }

        val filter = IntentFilter(AppConfig.ACTION_FORCE_LOGOUT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                forceLogoutReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(forceLogoutReceiver, filter)
        }
        isForceLogoutReceiverRegistered = true

        binding.fab.setOnClickListener {
            // لاجیک آپدیت اجباری: اگر آپدیت لازم باشد، دکمه کار نمی‌کند و دیالوگ باز می‌شود
            if (isForcedUpdateRequired) {
                showForcedUpdateDialog()
                return@setOnClickListener
            }

            if (mainViewModel.isRunning.value == true) {
                // Disconnect
                V2RayServiceManager.stopVService(this)
            } else if ((MmkvManager.decodeSettingsString(AppConfig.PREF_MODE) ?: VPN) == VPN) {
                // Connect
                val intent = VpnService.prepare(this)
                if (intent == null) {
                    startV2Ray()
                } else {
                    requestVpnPermission.launch(intent)
                }
            } else {
                startV2Ray()
            }
        }
        binding.layoutTest.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                setTestState(getString(R.string.connection_test_testing))
                mainViewModel.testCurrentServerRealPing()
            }
        }

        setupHorizontalRecyclerView()

        binding.navView.setNavigationItemSelectedListener(this)
        TokenStore.token(this)?.let { loadStatus(it) }
        val token = TokenStore.token(this)
        if (token.isNullOrEmpty()) { goLoginClearTask(); return }

        initGroupTab()
        setupViewModel()
        migrateLegacy()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingAction = Action.POST_NOTIFICATIONS
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    // --- Animation Logic ---

    private fun startConnectingAnimation() {
        if (isConnectingAnimationRunning) return
        isConnectingAnimationRunning = true

        binding.ring1.visibility = View.VISIBLE
        binding.ring2.visibility = View.VISIBLE
        binding.ring3.visibility = View.VISIBLE
        
        binding.ring1.scaleX = 1f; binding.ring1.scaleY = 1f; binding.ring1.alpha = 1f
        binding.ring2.scaleX = 1f; binding.ring2.scaleY = 1f; binding.ring2.alpha = 1f
        binding.ring3.scaleX = 1f; binding.ring3.scaleY = 1f; binding.ring3.alpha = 1f

        ring1Animator = ObjectAnimator.ofFloat(binding.ring1, "rotation", 0f, 360f).apply {
            duration = 2000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        ring2Animator = ObjectAnimator.ofFloat(binding.ring2, "rotation", 0f, -360f).apply {
            duration = 1500
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        ring3Animator = ObjectAnimator.ofFloat(binding.ring3, "rotation", 0f, 360f).apply {
            duration = 1000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun startConnectedAnimation() {
        isConnectingAnimationRunning = false
        ring1Animator?.cancel()
        ring2Animator?.cancel()
        ring3Animator?.cancel()

        // Shrink rings
        listOf(binding.ring1, binding.ring2, binding.ring3).forEach {
            it.animate()
                .scaleX(0f).scaleY(0f).alpha(0f)
                .setDuration(500)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }

        // Enlarge FAB
        binding.fab.animate()
            .scaleX(1.1f).scaleY(1.1f)
            .setDuration(300)
            .setInterpolator(OvershootInterpolator())
            .start()

        // Circular Reveal
        binding.bgActive.post {
            if (!binding.bgActive.isAttachedToWindow) return@post
            val cx = (binding.fab.left + binding.fab.right) / 2
            val cy = (binding.fab.top + binding.fab.bottom) / 2
            val finalRadius = hypot(binding.root.width.toDouble(), binding.root.height.toDouble()).toFloat()

            val anim = ViewAnimationUtils.createCircularReveal(binding.bgActive, cx, cy, 0f, finalRadius)
            binding.bgActive.visibility = View.VISIBLE
            anim.duration = 800
            anim.interpolator = AccelerateDecelerateInterpolator()
            anim.start()
        }
    }

    private fun startDisconnectAnimation() {
        // Reset FAB
        binding.fab.animate().scaleX(1f).scaleY(1f).setDuration(300).start()

        // Reverse Circular Reveal
        val cx = (binding.fab.left + binding.fab.right) / 2
        val cy = (binding.fab.top + binding.fab.bottom) / 2
        val initialRadius = hypot(binding.root.width.toDouble(), binding.root.height.toDouble()).toFloat()

        if (binding.bgActive.isVisible) {
            val anim = ViewAnimationUtils.createCircularReveal(binding.bgActive, cx, cy, initialRadius, 0f)
            anim.duration = 600
            anim.interpolator = AccelerateDecelerateInterpolator()
            anim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.bgActive.visibility = View.INVISIBLE
                }
            })
            anim.start()
        }
    }

    // --- Recycler View Logic ---

    private fun setupHorizontalRecyclerView() {
        binding.recyclerView.setHasFixedSize(true)
        val layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = adapter

        val snapHelper = LinearSnapHelper()
        snapHelper.attachToRecyclerView(binding.recyclerView)

        // Padding to center items
        binding.recyclerView.post {
            val width = binding.recyclerView.width
            // Approximate item width (64dp indicator + padding)
            val padding = (width / 2) - (80 * resources.displayMetrics.density).toInt()
            binding.recyclerView.setPadding(padding, 0, padding, 0)
        }

        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                scaleItems(recyclerView)
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val centerView = snapHelper.findSnapView(layoutManager)
                    if (centerView != null) {
                        val pos = layoutManager.getPosition(centerView)
                        if (pos != RecyclerView.NO_POSITION && pos < mainViewModel.serversCache.size) {
                            val guid = mainViewModel.serversCache[pos].guid
                            if (guid != MmkvManager.getSelectServer()) {
                                adapter.setSelectServer(guid)
                            }
                        }
                    }
                }
            }
        })
    }

    private fun scaleItems(recyclerView: RecyclerView) {
        val centerX = recyclerView.width / 2
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            val childCenterX = (child.left + child.right) / 2
            val dist = abs(centerX - childCenterX)
            val scale = 1f - (dist.toFloat() / recyclerView.width)
            val finalScale = Math.max(0.7f, scale)
            child.scaleX = finalScale
            child.scaleY = finalScale
            child.alpha = Math.max(0.5f, scale)
        }
    }

    fun scrollToPositionCentered(position: Int) {
        val layoutManager = binding.recyclerView.layoutManager as? LinearLayoutManager ?: return
        val smoothScroller = object : LinearSmoothScroller(this) {
            override fun getHorizontalSnapPreference(): Int = SNAP_TO_ANY
            override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int): Int {
                return (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)
            }
        }
        smoothScroller.targetPosition = position
        layoutManager.startSmoothScroll(smoothScroller)
    }

    override fun onDestroy() {
        if (isForceLogoutReceiverRegistered) {
            try { unregisterReceiver(forceLogoutReceiver) } catch (_: IllegalArgumentException) {}
            isForceLogoutReceiverRegistered = false
        }
        ring1Animator?.cancel()
        ring2Animator?.cancel()
        ring3Animator?.cancel()
        super.onDestroy()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun setupViewModel() {
        mainViewModel.updateListAction.observe(this) { index ->
            if (index >= 0) adapter.notifyItemChanged(index) else adapter.notifyDataSetChanged()
            scrollToSelected()
        }
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }

        mainViewModel.isRunning.observe(this) { isRunning ->
            adapter.isRunning = isRunning
            if (isRunning) {
                startConnectedAnimation()
                binding.fab.setImageResource(R.drawable.disconnect_button)
                setTestState(getString(R.string.connection_connected))
                binding.tvConnectionStatus.setText(R.string.connected)
            } else {
                startDisconnectAnimation()
                isConnectingAnimationRunning = false
                binding.fab.setImageResource(R.drawable.connect_button)
                setTestState(getString(R.string.connection_not_connected))
                binding.tvConnectionStatus.setText(R.string.not_connected)
            }
        }

        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun scrollToSelected() {
        val selected = MmkvManager.getSelectServer()
        if (!selected.isNullOrEmpty()) {
            val pos = mainViewModel.getPosition(selected)
            if (pos >= 0) {
                // Post delay to ensure layout is ready
                binding.recyclerView.postDelayed({
                    scrollToPositionCentered(pos)
                }, 100)
            }
        }
    }

    private fun migrateLegacy() {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = MigrateManager.migrateServerConfig2Profile()
            launch(Dispatchers.Main) {
                if (result) {
                    mainViewModel.reloadServerList()
                }
            }
        }
    }

    private fun initGroupTab() {
        binding.tabGroup.removeOnTabSelectedListener(tabGroupListener)
        binding.tabGroup.removeAllTabs()
        binding.tabGroup.isVisible = false

        val (listId, listRemarks) = mainViewModel.getSubscriptions(this)
        if (listId == null || listRemarks == null) {
            return
        }

        for (it in listRemarks.indices) {
            val tab = binding.tabGroup.newTab()
            tab.text = listRemarks[it]
            tab.tag = listId[it]
            binding.tabGroup.addTab(tab)
        }
        val selectIndex =
            listId.indexOf(mainViewModel.subscriptionId).takeIf { it >= 0 } ?: (listId.count() - 1)
        binding.tabGroup.selectTab(binding.tabGroup.getTabAt(selectIndex))
        binding.tabGroup.addOnTabSelectedListener(tabGroupListener)
        binding.tabGroup.isVisible = true
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        startConnectingAnimation()
        V2RayServiceManager.startVService(this)
    }

    private fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    public override fun onResume() {
        super.onResume()
        mainViewModel.reloadServerList()
    }

    public override fun onPause() {
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.per_app_proxy_settings -> startActivity(Intent(this, PerAppProxyActivity::class.java))
            R.id.routing_setting -> startActivity(Intent(this, RoutingSettingActivity::class.java))
            R.id.user_asset_setting -> startActivity(Intent(this, UserAssetActivity::class.java))
            R.id.settings -> startActivity(
                Intent(this, SettingsActivity::class.java)
                    .putExtra("isRunning", mainViewModel.isRunning.value == true)
            )
            R.id.logcat -> startActivity(Intent(this, LogcatActivity::class.java))
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun importFromApiLinks(links: List<String>) {
        if (links.isNullOrEmpty()) return
        val payload = links.filter { it.isNotBlank() }.distinct().joinToString("\n")
        importBatchConfig(payload)
    }
    
    private fun importBatchConfig(server: String?) {
        binding.pbWaiting.show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    if (count > 0) {
                        toast(getString(R.string.title_import_config_count, count))
                        mainViewModel.reloadServerList()
                    } else if (countSub > 0) {
                        initGroupTab()
                    } else {
                        toastError(R.string.toast_failure)
                    }
                    binding.pbWaiting.hide()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                    binding.pbWaiting.hide()
                }
                Log.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }

    private fun loadStatus(token: String) {
        repo.reportAppUpdateIfNeeded(token) { }
        repo.status(token) { r ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (r.isSuccess) {
                    val s: StatusResponse = r.getOrNull()!!
                    fillUiWithStatus(s)
                    currentLinks = s.links ?: emptyList()
                    delAllConfig()
                    importFromApiLinks(currentLinks)
                    maybeShowUpdateDialog(token, s)
                } else {
                    val errMsg = r.exceptionOrNull()?.message ?: ""
                    
                    if (errMsg.contains("HTTP_403", true) || errMsg.contains("suspended", true)) {
                        handleSuspendedUser()
                    } 
                    else if (errMsg.contains("HTTP_401", true) || errMsg.contains("invalid or expired", true)) {
                        Toast.makeText(this, "نشست منقضی شده؛ دوباره وارد شوید", Toast.LENGTH_SHORT).show()
                        TokenStore.clear(this)
                        goLoginClearTask()
                    } 
                    else {
                        val cached = MmkvManager.loadLastStatus()
                        if (cached != null) {
                            Toast.makeText(this, "خطا در اتصال به سرور. استفاده از آخرین بروزرسانی اطلاعات", Toast.LENGTH_LONG).show()
                            fillUiWithStatus(cached)
                            currentLinks = cached.links ?: emptyList()
                            delAllConfig()
                            importFromApiLinks(currentLinks)
                            maybeShowUpdateDialog(token, cached)
                        } else {
                            Toast.makeText(this, "خطا در اتصال به سرور و عدم وجود داده کش‌شده", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun handleSuspendedUser() {
        delAllConfig()
        // اصلاح شده: استفاده از تابع جدید برای حذف کش
        MmkvManager.removeLastStatus()
        
        AlertDialog.Builder(this)
            .setTitle("حساب مسدود شد")
            .setMessage("از اتصال جلوگیری شد. وضعیت حساب شما غیرفعال است. لطفا با ادمین تماس بگیرید.")
            .setCancelable(false)
            .setPositiveButton("تلاش مجدد") { _, _ ->
                val token = TokenStore.token(this)
                if (token != null) {
                    binding.pbWaiting.show()
                    loadStatus(token)
                } else {
                    goLoginClearTask()
                }
            }
            .setNegativeButton("خروج از حساب") { _, _ ->
                goLoginClearTask()
            }
            .show()
    }

    private fun delAllConfig() {
        binding.pbWaiting.show()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.removeAllServer()
            launch(Dispatchers.Main) {
                mainViewModel.reloadServerList()
                binding.pbWaiting.hide()
            }
        }
    }

    private fun fillUiWithStatus(s: StatusResponse) {
        val username = s.username ?: "-"
        val status = s.status ?: "-"
        binding.tvUserStatus.text = "$username : $status"
        val used = s.used_traffic ?: 0L
        val total = s.data_limit
        val tout = StatusFormatter.traffic(total, used)
        binding.tvTrafficSummary.text = "${tout.total} / ${tout.remain}"
        val dout = StatusFormatter.days(this, s.expire)
        binding.tvDaysSummary.text = if (dout.remainDays == "نامحدود") "نامحدود" else "${dout.remainDays} روز باقی‌مانده"
    }

    private fun goLoginClearTask() {
        val jwt = TokenStore.token(applicationContext)
        if (!jwt.isNullOrBlank()) {
            ApiClient.postLogout(jwt) { }
        }
        TokenStore.clear(applicationContext)
        val i = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        delAllConfig()
        startActivity(i)
    }

    private fun maybeShowUpdateDialog(token: String, s: StatusResponse) {
        if (s.need_to_update == true) {
            repo.updatePromptSeen(token) { }
            
            // اگر آپدیت اجباری است (is_ignoreable == false)
            if (s.is_ignoreable == true) {
                showOptionalUpdateDialog()
                isForcedUpdateRequired = false
            } else {
                showForcedUpdateDialog()
                isForcedUpdateRequired = true // بلاک کردن دکمه اتصال
            }
        } else {
            isForcedUpdateRequired = false
        }
    }

    private fun showOptionalUpdateDialog() {
        val dlg = AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_title))
            .setMessage(getString(R.string.update_message))
            .setPositiveButton(getString(R.string.update_now)) { d, _ -> openUpdateLink(); d.dismiss() }
            .setNegativeButton(getString(R.string.update_later)) { d, _ -> d.dismiss() }
            .create()
        dlg.setCanceledOnTouchOutside(true)
        dlg.show()
    }

    private fun showForcedUpdateDialog() {
        val dlg = AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_title))
            .setMessage(getString(R.string.update_message))
            .setPositiveButton(getString(R.string.update_now)) { _, _ -> openUpdateLink() }
            .setCancelable(false) // غیر قابل بستن
            .create()
        dlg.setCanceledOnTouchOutside(false)
        dlg.show()
    }

    private fun openUpdateLink() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://dl.soft99.sbs"))
        startActivity(intent)
    }
}