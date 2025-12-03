package com.v2ray.ang.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayout
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.VPN
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MigrateManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.v2ray.ang.data.auth.AuthRepository
import com.v2ray.ang.data.auth.TokenStore
import com.v2ray.ang.net.StatusResponse
import com.v2ray.ang.ui.login.LoginActivity
import com.v2ray.ang.ui.main.StatusFormatter
import androidx.appcompat.app.AppCompatActivity

import android.widget.ImageButton
import android.widget.Toast

import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.Context

import com.v2ray.ang.net.ApiClient

import android.widget.TextView
import android.view.View

import android.graphics.Color
import androidx.core.view.WindowInsetsControllerCompat

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

    // Removed requestSubSettingActivity as SubSettingActivity is deleted
    
    private val tabGroupListener = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab?) {
            val selectId = tab?.tag.toString()
            if (selectId != mainViewModel.subscriptionId) {
                mainViewModel.subscriptionIdChanged(selectId)
            }
        }

        override fun onTabUnselected(tab: TabLayout.Tab?) {
        }

        override fun onTabReselected(tab: TabLayout.Tab?) {
        }
    }
    private var mItemTouchHelper: ItemTouchHelper? = null
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

    // Removed scanQRCodeForConfig as ScannerActivity is deleted

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

        val testState = findViewById<TextView>(R.id.tv_test_state)
        testState.bringToFront()
        testState.elevation = 100f
        testState.translationZ = 100f

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

        findViewById<View>(R.id.layout_test).apply {
            elevation = 100f
            translationZ = 100f
            bringToFront()
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
            if (mainViewModel.isRunning.value == true) {
                V2RayServiceManager.stopVService(this)
            } else if ((MmkvManager.decodeSettingsString(AppConfig.PREF_MODE) ?: VPN) == VPN) {
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

        binding.recyclerView.setHasFixedSize(true)
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)) {
            binding.recyclerView.layoutManager = GridLayoutManager(this, 2)
        } else {
            binding.recyclerView.layoutManager = GridLayoutManager(this, 1)
        }
        addCustomDividerToRecyclerView(binding.recyclerView, this, R.drawable.custom_divider)
        binding.recyclerView.adapter = adapter

        mItemTouchHelper = ItemTouchHelper(SimpleItemTouchHelperCallback(adapter))
        mItemTouchHelper?.attachToRecyclerView(binding.recyclerView)

        binding.recyclerView.clipToPadding = false

        binding.fab.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                binding.fab.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val lp = binding.fab.layoutParams as android.view.ViewGroup.MarginLayoutParams
                val bottomPad = binding.fab.height + lp.bottomMargin + (16 * resources.displayMetrics.density).toInt()
                binding.recyclerView.setPadding(
                    binding.recyclerView.paddingLeft,
                    binding.recyclerView.paddingTop,
                    binding.recyclerView.paddingRight,
                    bottomPad
                )
            }
        })

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

    override fun onDestroy() {
        if (isForceLogoutReceiverRegistered) {
            try { unregisterReceiver(forceLogoutReceiver) } catch (_: IllegalArgumentException) {}
            isForceLogoutReceiverRegistered = false
        }
        super.onDestroy()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun setupViewModel() {
        mainViewModel.updateListAction.observe(this) { index ->
            if (index >= 0) adapter.notifyItemChanged(index) else adapter.notifyDataSetChanged()
        }
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }

        mainViewModel.isRunning.observe(this) { isRunning ->
            adapter.isRunning = isRunning

            if (isRunning) {
                binding.fab.setImageResource(R.drawable.disconnect_button)
                binding.fab.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                setTestState(getString(R.string.connection_connected))
                binding.layoutTest.isFocusable = true
                binding.drawerLayout.setBackgroundResource(R.drawable.bg_main_active)
                binding.tvConnectionStatus.setText(R.string.connected)
            } else {
                binding.fab.setImageResource(R.drawable.connect_button)
                binding.fab.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                setTestState(getString(R.string.connection_not_connected))
                binding.layoutTest.isFocusable = false
                binding.drawerLayout.setBackgroundResource(R.drawable.bg_main)
                binding.tvConnectionStatus.setText(R.string.not_connected)
            }
        }

        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
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

        val logoutHost = menu.findItem(R.id.action_logout_host)
        val logoutBtn = logoutHost.actionView?.findViewById<ImageButton>(R.id.btn_logout)
        logoutBtn?.setOnClickListener {
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
            // Changed to simple startActivity as requestSubSettingActivity is removed
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
        val payload = links
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
        importBatchConfig(payload)
    }
    
    private fun importBatchConfig(server: String?) {
        binding.pbWaiting.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            toast(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                        }

                        countSub > 0 -> initGroupTab()
                        else -> toastError(R.string.toast_failure)
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

        repo.reportAppUpdateIfNeeded(token) { /* silent */ }

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
                    if (errMsg.contains("HTTP_401", true) || errMsg.contains("invalid or expired", true)) {
                        Toast.makeText(this, "نشست منقضی شده؛ دوباره وارد شوید", Toast.LENGTH_SHORT).show()
                        TokenStore.clear(this)
                        goLoginClearTask()
                    } else {
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

    private fun delAllConfig() {
        binding.pbWaiting.show()
        lifecycleScope.launch(Dispatchers.IO) {
            val ret = mainViewModel.removeAllServer()
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
        binding.tvDaysSummary.text = if (dout.remainDays == "نامحدود")
            "نامحدود"
        else
            "${dout.remainDays} روز باقی‌مانده"
    }

    private fun hasActiveProfile(): Boolean {
        return false
    }
    
    private fun goLoginClearTask() {
        val jwt = TokenStore.token(applicationContext)

        if (!jwt.isNullOrBlank()) {
            Log.d("AUTH", "Logout → POST /api/logout")
            ApiClient.postLogout(jwt) { res ->
                if (res.isSuccess) Log.d("AUTH", "Logout ← 200 OK")
                else Log.w("AUTH", "Logout ← FAILED: ${res.exceptionOrNull()?.message}")
            }
        } else {
            Log.w("AUTH", "Logout skipped: token is null/blank")
        }

        TokenStore.clear(applicationContext)

        val i = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        delAllConfig()
        startActivity(i)
    }

    private fun maybeShowUpdateDialog(token: String, s: StatusResponse) {
        val need = s.need_to_update == true
        val ignorable = s.is_ignoreable == true
        if (!need) return

        repo.updatePromptSeen(token) { /* silent */ }

        if (ignorable) {
            showOptionalUpdateDialog()
        } else {
            showForcedUpdateDialog()
        }
    }

    private fun showOptionalUpdateDialog() {
        val dlg = AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_title))
            .setMessage(getString(R.string.update_message))
            .setPositiveButton(getString(R.string.update_now)) { d, _ ->
                openUpdateLink()
                d.dismiss()
            }
            .setNegativeButton(getString(R.string.update_later)) { d, _ ->
                d.dismiss()
            }
            .create()
        dlg.setCanceledOnTouchOutside(true)
        dlg.show()
    }

    private fun showForcedUpdateDialog() {
        val dlg = AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_title))
            .setMessage(getString(R.string.update_message))
            .setPositiveButton(getString(R.string.update_now)) { _, _ ->
                openUpdateLink()
            }
            .setCancelable(false)
            .create()
        dlg.setCanceledOnTouchOutside(false)
        dlg.show()
    }

    private fun openUpdateLink() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://dl.soft99.sbs"))
        startActivity(intent)
    }
}