package io.nekohasekai.sagernet.ui

import android.Manifest.permission.POST_NOTIFICATIONS
import android.app.Activity
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.IdRes
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceDataStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.core.CoreController
import io.nekohasekai.sagernet.bg.core.CoreEngine
import io.nekohasekai.sagernet.bg.core.CoreStatus
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutMainBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.PluginEntry
import io.nekohasekai.sagernet.group.GroupInterfaceAdapter
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.isPreview
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.parseProxies
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.HuiVisuals
import moe.matsuri.nb4a.utils.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ThemedActivity(),
    OnPreferenceDataStoreChangeListener,
    NavigationView.OnNavigationItemSelectedListener {

    lateinit var binding: LayoutMainBinding
    lateinit var navigation: NavigationView
    @Volatile private var currentStatusSnapshot = CoreStatus()

    fun coreStatusSnapshot(): CoreStatus = currentStatusSnapshot

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutMainBinding.inflate(layoutInflater)
        if (!DataStore.configurationStore.getBoolean("huiAutoCoreMigrated", false)) {
            val previous = CoreEngine.fromId(DataStore.huiCoreEngine)
            DataStore.huiAutoEngine = previous.id
            DataStore.huiCoreEngine = "auto"
            DataStore.configurationStore.putBoolean("huiAutoCoreMigrated", true)
        }
        if (!DataStore.configurationStore.getBoolean("huiRc3LayoutMigrated", false)) {
            if (DataStore.huiNodeLayout == "standard") DataStore.huiNodeLayout = "grid"
            DataStore.configurationStore.putBoolean("huiRc3LayoutMigrated", true)
        }
        binding.fab.initProgress(binding.fabProgress)
        if (themeResId !in intArrayOf(
                R.style.Theme_SagerNet_Black
            )
        ) {
            navigation = binding.navView
            binding.drawerLayout.removeView(binding.navViewBlack)
        } else {
            navigation = binding.navViewBlack
            binding.drawerLayout.removeView(binding.navView)
        }
        navigation.setNavigationItemSelectedListener(this)
        setContentView(HuiVisuals.wrap(this, binding.root))
        setupDockPager()
        setupDock()

        if (savedInstanceState == null) {
            binding.dockPager.setCurrentItem(0, false)
            currentDockId = R.id.nav_home
            lastDockId = R.id.nav_home
            updateDockSelection(currentDockId)
        } else {
            val restored = dockOrder.getOrElse(binding.dockPager.currentItem) { R.id.nav_home }
            currentDockId = restored
            lastDockId = restored
            updateDockSelection(currentDockId)
        }
        onBackPressedDispatcher.addCallback {
            when {
                currentDockId == -1 -> {
                    val overlay = supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
                    if (overlay?.onBackPressed() != true) closeSecondary()
                }
                currentDockId != R.id.nav_home -> showDockFragment(R.id.nav_home, true)
                else -> moveTaskToBack(true)
            }
        }

        CoreController.normalizeSelection()
        binding.fab.setOnClickListener { toggleServiceFromUi() }
        binding.stats.findViewById<android.view.View>(R.id.connect_action).setOnClickListener { toggleServiceFromUi() }
        binding.stats.setOnClickListener { if (CoreController.status(this).state == CoreStatus.State.RUNNING) binding.stats.testConnection() }
        binding.stats.findViewById<android.view.View>(R.id.latency_action).setOnClickListener {
            if (CoreController.status(this).state == CoreStatus.State.RUNNING) binding.stats.testConnection()
        }
        HuiVisuals.applyLiquidPress(binding.stats.findViewById(R.id.latency_action))
        HuiVisuals.applyLiquidPress(binding.stats.findViewById(R.id.connect_action))

        changeState(BaseService.State.Idle)
        DataStore.configurationStore.registerChangeListener(this)
        GroupManager.userInterface = GroupInterfaceAdapter(this)

        if (intent?.action == Intent.ACTION_VIEW) {
            onNewIntent(intent)
        }

        refreshNavMenu(DataStore.enableClashAPI)

        // sdk 33 notification
        if (Build.VERSION.SDK_INT >= 33) {
            val checkPermission =
                ContextCompat.checkSelfPermission(this@MainActivity, POST_NOTIFICATIONS)
            if (checkPermission != PackageManager.PERMISSION_GRANTED) {
                //动态申请
                ActivityCompat.requestPermissions(
                    this@MainActivity, arrayOf(POST_NOTIFICATIONS), 0
                )
            }
        }

        if (isPreview) {
            MaterialAlertDialogBuilder(this)
                .setTitle(BuildConfig.PRE_VERSION_NAME)
                .setMessage(R.string.preview_version_hint)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    fun refreshNavMenu(clashApi: Boolean) {
        if (::navigation.isInitialized) {
            navigation.menu.findItem(R.id.nav_traffic)?.isVisible = clashApi
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val uri = intent.data ?: return

        runOnDefaultDispatcher {
            if (uri.scheme == "sn" && uri.host == "subscription" || uri.scheme == "clash") {
                importSubscription(uri)
            } else {
                importProfile(uri)
            }
        }
    }

    fun urlTest(): Int {
        require(CoreController.status(this).state == CoreStatus.State.RUNNING) { "not started" }
        val url = URL(DataStore.connectionTestURL)
        require(url.protocol == "http" || url.protocol == "https") { "invalid test URL" }
        val started = android.os.SystemClock.elapsedRealtime()
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", "Hui/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = connection.responseCode
            require(code in 200..399) { "HTTP $code" }
            runCatching { connection.inputStream?.close() }
        } finally {
            connection.disconnect()
        }
        return (android.os.SystemClock.elapsedRealtime() - started).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    suspend fun importSubscription(uri: Uri) {
        val group: ProxyGroup

        val url = uri.getQueryParameter("url")
        if (!url.isNullOrBlank()) {
            group = ProxyGroup(type = GroupType.SUBSCRIPTION)
            val subscription = SubscriptionBean()
            group.subscription = subscription

            // cleartext format
            subscription.link = url
            group.name = uri.getQueryParameter("name")
        } else {
            val data = uri.encodedQuery.takeIf { !it.isNullOrBlank() } ?: return
            try {
                group = KryoConverters.deserialize(
                    ProxyGroup().apply { export = true }, Util.zlibDecompress(Util.b64Decode(data))
                ).apply {
                    export = false
                }
            } catch (e: Exception) {
                onMainDispatcher {
                    alert(e.readableMessage).show()
                }
                return
            }
        }

        val name = group.name.takeIf { !it.isNullOrBlank() } ?: group.subscription?.link
        ?: group.subscription?.token
        if (name.isNullOrBlank()) return

        group.name = group.name.takeIf { !it.isNullOrBlank() }
            ?: ("Subscription #" + System.currentTimeMillis())

        onMainDispatcher {

            displayFragmentWithId(R.id.nav_group)

            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.subscription_import)
                .setMessage(getString(R.string.subscription_import_message, name))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportSubscription(group)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

        }

    }

    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        GroupManager.createGroup(subscription)
        GroupUpdater.startUpdate(subscription, true)
    }

    suspend fun importProfile(uri: Uri) {
        val profile = try {
            parseProxies(uri.toString()).getOrNull(0) ?: error(getString(R.string.no_proxies_found))
        } catch (e: Exception) {
            onMainDispatcher {
                alert(e.readableMessage).show()
            }
            return
        }

        onMainDispatcher {
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.profile_import)
                .setMessage(getString(R.string.profile_import_message, profile.displayName()))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportProfile(profile)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

    }

    private suspend fun finishImportProfile(profile: AbstractBean) {
        val targetId = DataStore.selectedGroupForImport()

        ProfileManager.createProfile(targetId, profile)

        onMainDispatcher {
            displayFragmentWithId(R.id.nav_configuration)

            snackbar(resources.getQuantityString(R.plurals.added, 1, 1)).show()
        }
    }

    fun missingPlugin(profileName: String, pluginName: String) {
        val pluginEntity = PluginEntry.find(pluginName)

        // unknown exe or neko plugin
        if (pluginEntity == null) {
            snackbar(getString(R.string.plugin_unknown, pluginName)).show()
            return
        }

        // official exe

        MaterialAlertDialogBuilder(this).setTitle(R.string.missing_plugin)
            .setMessage(
                getString(
                    R.string.profile_requiring_plugin, profileName, pluginEntity.displayName
                )
            )
            .setPositiveButton(R.string.action_download) { _, _ ->
                showDownloadDialog(pluginEntity)
            }
            .setNeutralButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.action_learn_more) { _, _ ->
                launchCustomTab("https://matsuridayo.github.io/nb4a-plugin/")
            }
            .show()
    }

    private fun showDownloadDialog(pluginEntry: PluginEntry) {
        var index = 0
        var playIndex = -1
        var fdroidIndex = -1

        val items = mutableListOf<String>()
        if (pluginEntry.downloadSource.playStore) {
            items.add(getString(R.string.install_from_play_store))
            playIndex = index++
        }
        if (pluginEntry.downloadSource.fdroid) {
            items.add(getString(R.string.install_from_fdroid))
            fdroidIndex = index++
        }

        items.add(getString(R.string.download))
        val downloadIndex = index

        MaterialAlertDialogBuilder(this).setTitle(pluginEntry.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    playIndex -> launchCustomTab("https://play.google.com/store/apps/details?id=${pluginEntry.packageName}")
                    fdroidIndex -> launchCustomTab("https://f-droid.org/packages/${pluginEntry.packageName}/")
                    downloadIndex -> launchCustomTab(pluginEntry.downloadSource.downloadLink)
                }
            }
            .show()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        if (item.isChecked) binding.drawerLayout.closeDrawers() else {
            return displayFragmentWithId(item.itemId)
        }
        return true
    }


    private val dockOrder = listOf(
        R.id.nav_home, R.id.nav_configuration, R.id.nav_group, R.id.nav_settings
    )
    private val dockIds = dockOrder.toSet()
    private var currentDockId: Int = R.id.nav_home
    private var lastDockId: Int = R.id.nav_home

    private inner class DockPagerAdapter : FragmentStateAdapter(this) {
        override fun getItemCount(): Int = dockOrder.size
        override fun createFragment(position: Int): Fragment = dockFragment(dockOrder[position])
    }

    private fun setupDockPager() {
        binding.dockPager.adapter = DockPagerAdapter()
        binding.dockPager.offscreenPageLimit = 2
        binding.dockPager.isUserInputEnabled = true
        (binding.dockPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)?.apply {
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            setItemViewCacheSize(3)
        }
        // Keep ViewPager2's native translation untouched. Scaling/alpha transforms exposed
        // the background between pages and made the page junction feel disconnected.
        binding.dockPager.setPageTransformer(null)
        binding.dockPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val id = dockOrder.getOrNull(position) ?: return
                currentDockId = id
                lastDockId = id
                updateDockSelection(id)
                if (::navigation.isInitialized) navigation.menu.findItem(id)?.isChecked = true
                binding.root.post { HuiVisuals.suppressFocusHighlights(binding.root) }
            }
        })
    }

    private fun setupDock() {
        val items = listOf(
            binding.dockHome to R.id.nav_home,
            binding.dockNodes to R.id.nav_configuration,
            binding.dockConfig to R.id.nav_group,
            binding.dockSettings to R.id.nav_settings,
        )
        items.forEach { (view, id) ->
            HuiVisuals.applyLiquidPress(view)
            view.setOnClickListener {
                if (currentDockId == id && binding.fragmentHolder.visibility != View.VISIBLE) return@setOnClickListener
                showDockFragment(id, true)
            }
        }
        updateDockSelection(currentDockId)
    }

    private fun updateDockSelection(id: Int) {
        val items = listOf(
            binding.dockHome to R.id.nav_home,
            binding.dockNodes to R.id.nav_configuration,
            binding.dockConfig to R.id.nav_group,
            binding.dockSettings to R.id.nav_settings,
        )
        items.forEach { (item, itemId) ->
            val selected = itemId == id
            item.isSelected = selected
            item.animate().cancel()
            item.animate()
                .scaleX(if (selected) 1f else 0.985f)
                .scaleY(if (selected) 1f else 0.985f)
                .translationY(if (selected) -2f * resources.displayMetrics.density else 0f)
                .alpha(if (selected) 1f else 0.78f)
                .setDuration(180L)
                .start()
        }
    }

    private fun dockFragment(id: Int): ToolbarFragment = when (id) {
        R.id.nav_home -> DashboardFragment()
        R.id.nav_configuration -> ConfigurationFragment()
        R.id.nav_group -> GroupFragment()
        R.id.nav_settings -> SettingsFragment()
        else -> error("Not a dock destination: $id")
    }

    private fun setSecondaryMode(active: Boolean) {
        binding.dockPager.isUserInputEnabled = !active
        binding.dockPager.visibility = if (active) View.GONE else View.VISIBLE
        binding.huiBottomDock.visibility = if (active) View.GONE else View.VISIBLE
        (binding.fragmentHolder.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            lp.bottomMargin = if (active) 0 else (92 * resources.displayMetrics.density).toInt()
            binding.fragmentHolder.layoutParams = lp
        }
        binding.fragmentHolder.isClickable = active
        binding.fragmentHolder.isFocusable = active
    }

    fun closeSecondary() {
        showDockFragment(lastDockId, false)
    }

    private fun showDockFragment(@IdRes id: Int, smooth: Boolean = true) {
        val targetIndex = dockOrder.indexOf(id)
        if (targetIndex < 0) return
        val fm = supportFragmentManager
        val overlayFragments = fm.fragments.filter { it.id == R.id.fragment_holder && it.isAdded }
        if (overlayFragments.isNotEmpty()) {
            fm.beginTransaction()
                .setReorderingAllowed(true)
                .setCustomAnimations(0, R.anim.hui_page_exit_right)
                .apply { overlayFragments.forEach { remove(it) } }
                .commitAllowingStateLoss()
            binding.fragmentHolder.visibility = View.GONE
        }
        setSecondaryMode(false)
        val distance = kotlin.math.abs(binding.dockPager.currentItem - targetIndex)
        binding.dockPager.setCurrentItem(targetIndex, smooth && distance == 1)
        currentDockId = id
        lastDockId = id
        updateDockSelection(id)
        binding.drawerLayout.closeDrawers()
    }

    @SuppressLint("CommitTransaction")
    fun displayFragment(fragment: ToolbarFragment) {
        currentDockId = -1
        updateDockSelection(-1)
        setSecondaryMode(true)
        binding.fragmentHolder.visibility = View.VISIBLE
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
            .setReorderingAllowed(true)
            .setCustomAnimations(R.anim.hui_page_enter_right, R.anim.hui_page_exit_left)
        fm.fragments.filter { it.id == R.id.fragment_holder && it.isAdded }.forEach { current ->
            if (current.tag?.startsWith("hui-dock-") == true) {
                tx.hide(current)
                tx.setMaxLifecycle(current, Lifecycle.State.STARTED)
            } else {
                tx.remove(current)
            }
        }
        tx.add(R.id.fragment_holder, fragment, "hui-secondary-${fragment::class.java.simpleName}")
            .setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
            .commitAllowingStateLoss()
        binding.drawerLayout.closeDrawers()
    }

    fun displayFragmentWithId(@IdRes id: Int): Boolean {
        if (id in dockIds) {
            showDockFragment(id, true)
        } else {
            when (id) {
            R.id.nav_config_center -> displayFragment(ConfigCenterFragment())
            R.id.nav_route -> displayFragment(RouteFragment())
            R.id.nav_traffic -> displayFragment(WebviewFragment())
            R.id.nav_tools -> displayFragment(ToolsFragment())
            R.id.nav_logcat -> displayFragment(LogcatFragment())
            R.id.nav_faq -> {
                launchCustomTab("https://github.com/rongyang226-cpu/yingbao")
                return false
            }
            R.id.nav_about -> displayFragment(AboutFragment())
            else -> return false
            }
        }
        navigation.menu.findItem(id)?.isChecked = true
        return true
    }

    private var lastServiceToggleAt = 0L

    fun toggleServiceFromUi() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastServiceToggleAt < 600L) return
        lastServiceToggleAt = now
        val status = currentStatusSnapshot
        if (status.active) {
            changeState(BaseService.State.Stopping, animate = true)
            CoreController.stopAll(this)
        } else {
            requestCoreStart()
        }
    }

    fun reloadCurrentCoreFromUi() {
        if (currentStatusSnapshot.active) requestCoreStart()
    }

    private fun changeState(
        state: BaseService.State,
        msg: String? = null,
        animate: Boolean = false,
    ) {
        val previousState = DataStore.serviceState
        DataStore.serviceState = state

        binding.fab.changeState(state, previousState, animate)
        binding.stats.changeState(state)
        if (msg != null) snackbar(getString(R.string.vpn_error, msg)).show()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar =
        Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG).apply {
            anchorView = binding.huiBottomDock
        }

    private var pendingCoreStart: CoreEngine? = null
    private val corePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val requested = pendingCoreStart
        pendingCoreStart = null
        if (result.resultCode == Activity.RESULT_OK && requested == CoreController.selected) {
            startSelectedCore()
        } else if (result.resultCode != Activity.RESULT_OK) {
            changeState(BaseService.State.Idle)
            snackbar(R.string.vpn_permission_denied).show()
        }
    }

    private fun requestCoreStart() {
        val originallySelected = CoreController.selected
        lifecycleScope.launch {
            val requested = withContext(Dispatchers.IO) {
                CoreController.ensureUsableSelection(this@MainActivity)
            }
            if (requested != originallySelected) {
                snackbar("${originallySelected.displayName} 没有可用配置，已自动切换到 ${requested.displayName}").show()
            }
            changeState(BaseService.State.Connecting, animate = true)
            CoreController.stopAll(this@MainActivity)
            delay(250L)
            val prepared = runCatching {
                withContext(Dispatchers.IO) { CoreController.prepareSelected(this@MainActivity) }
            }
            prepared.onFailure {
                changeState(BaseService.State.Idle)
                snackbar(it.message ?: "${requested.displayName} 配置准备失败").show()
                return@launch
            }
            if (requested != CoreController.selected) {
                changeState(BaseService.State.Idle)
                return@launch
            }
            pendingCoreStart = requested
            val permission = android.net.VpnService.prepare(this@MainActivity)
            if (permission != null) corePermission.launch(permission) else {
                pendingCoreStart = null
                startSelectedCore()
            }
        }
    }

    private fun startSelectedCore() {
        val engine = CoreController.selected
        runCatching { CoreController.startPrepared(this) }.onFailure {
            changeState(BaseService.State.Idle)
            snackbar("${engine.displayName}：${it.message ?: "启动失败"}").show()
        }
    }

    private var lastCoreState: CoreStatus.State? = null
    private var lastCoreMessage = ""
    private var lastCoreEngine: CoreEngine? = null
    private var corePollJob: Job? = null

    private fun applyCoreSnapshot(engine: CoreEngine, status: CoreStatus) {
        currentStatusSnapshot = status
        if (engine != lastCoreEngine) {
            lastCoreEngine = engine
            lastCoreState = null
            lastCoreMessage = ""
        }
        val mapped = when (status.state) {
            CoreStatus.State.STARTING -> BaseService.State.Connecting
            CoreStatus.State.RUNNING -> BaseService.State.Connected
            CoreStatus.State.STOPPING -> BaseService.State.Stopping
            else -> BaseService.State.Idle
        }
        if (status.state != lastCoreState) {
            lastCoreState = status.state
            changeState(mapped, animate = true)
        }
        if (status.state == CoreStatus.State.ERROR && status.message.isNotBlank() && status.message != lastCoreMessage) {
            lastCoreMessage = status.message
            snackbar("${engine.displayName}：${status.message}").show()
        }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        when (key) {
            "huiCoreEngine" -> {
                CoreController.stopAll(this)
                lastCoreEngine = null
                lastCoreState = null
                currentStatusSnapshot = CoreStatus()
                changeState(BaseService.State.Idle)
            }
            Key.SERVICE_MODE -> Unit
            Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL -> {
                if (currentStatusSnapshot.active) {
                    snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
                        requestCoreStart()
                    }.show()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        corePollJob?.cancel()
        corePollJob = lifecycleScope.launch {
            while (isActive) {
                val engine = CoreController.selected
                val status = withContext(Dispatchers.IO) {
                    CoreController.status(applicationContext)
                }
                applyCoreSnapshot(engine, status)
                delay(1000L)
            }
        }
    }

    override fun onStop() {
        corePollJob?.cancel()
        corePollJob = null
        super.onStop()
    }

    override fun onDestroy() {
        corePollJob?.cancel()
        corePollJob = null
        super.onDestroy()
        GroupManager.userInterface = null
        DataStore.configurationStore.unregisterChangeListener(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (super.onKeyDown(keyCode, event)) return true
                binding.drawerLayout.open()
                navigation.requestFocus()
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (binding.drawerLayout.isOpen) {
                    binding.drawerLayout.close()
                    return true
                }
            }
        }

        if (super.onKeyDown(keyCode, event)) return true
        if (binding.drawerLayout.isOpen) return false

        val fragment = if (currentDockId == -1) {
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        } else {
            supportFragmentManager.findFragmentByTag("f${binding.dockPager.currentItem}") as? ToolbarFragment
        }
        return fragment != null && fragment.onKeyDown(keyCode, event)
    }

}
