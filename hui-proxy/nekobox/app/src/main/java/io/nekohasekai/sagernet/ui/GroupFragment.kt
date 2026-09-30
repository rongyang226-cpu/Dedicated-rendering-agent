package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.FrameLayout
import android.text.InputType
import java.net.URI
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.view.*
import androidx.core.net.toUri
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.bg.core.CoreController
import io.nekohasekai.sagernet.bg.core.CoreEngine
import io.nekohasekai.sagernet.bg.meta.MetaCoreManager
import io.nekohasekai.sagernet.databinding.LayoutGroupItemBinding
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import io.nekohasekai.sagernet.ui.profile.*
import moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity
import moe.matsuri.nb4a.proxy.config.ConfigSettingActivity
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSSettingsActivity
import okhttp3.internal.closeQuietly
import kotlinx.coroutines.delay
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.toBytesString
import java.lang.NumberFormatException
import java.util.*
import java.util.zip.ZipInputStream

class GroupFragment : ToolbarFragment(R.layout.layout_group),
    Toolbar.OnMenuItemClickListener {

    lateinit var activity: MainActivity
    lateinit var groupListView: RecyclerView
    lateinit var layoutManager: LinearLayoutManager
    lateinit var groupAdapter: GroupAdapter
    lateinit var undoManager: UndoSnackbarManager<ProxyGroup>

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity = requireActivity() as MainActivity

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle("配置")
        toolbar.inflateMenu(R.menu.add_group_menu)
        toolbar.setOnMenuItemClickListener(this)

        groupListView = view.findViewById(R.id.group_list)
        layoutManager = FixedLinearLayoutManager(groupListView)
        groupListView.layoutManager = layoutManager
        groupAdapter = GroupAdapter()
        GroupManager.addListener(groupAdapter)
        groupListView.adapter = groupAdapter

        undoManager = UndoSnackbarManager(activity, groupAdapter)

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
        ) {
            override fun getSwipeDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val proxyGroup = (viewHolder as GroupHolder).proxyGroup
                if (proxyGroup.ungrouped || proxyGroup.id in GroupUpdater.updating) {
                    return 0
                }
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun getDragDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val proxyGroup = (viewHolder as GroupHolder).proxyGroup
                if (proxyGroup.ungrouped || proxyGroup.id in GroupUpdater.updating) {
                    return 0
                }
                return super.getDragDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                groupAdapter.remove(index)
                undoManager.remove(index to (viewHolder as GroupHolder).proxyGroup)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
            ): Boolean {
                groupAdapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                groupAdapter.commitMove()
            }
        }).attachToRecyclerView(groupListView)

    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_edit_raw_config -> activity.displayFragmentWithId(R.id.nav_config_center)

            R.id.action_add_subscription -> showQuickSubscription()

            R.id.action_scan_qr_code -> startActivity(Intent(context, ScannerActivity::class.java))

            R.id.action_import_clipboard -> importClipboard()
            R.id.action_import_url -> showManualUrlImport()
            R.id.action_import_file -> startFilesForResult(importFile, "*/*")
            R.id.action_import_meta_file -> startFilesForResult(importMetaFile, "*/*")
            R.id.action_import_meta_url -> showMetaUrlImport()

            R.id.action_new_socks -> startActivity(Intent(requireActivity(), SocksSettingsActivity::class.java))
            R.id.action_new_http -> startActivity(Intent(requireActivity(), HttpSettingsActivity::class.java))
            R.id.action_new_ss -> startActivity(Intent(requireActivity(), ShadowsocksSettingsActivity::class.java))
            R.id.action_new_vmess -> startActivity(Intent(requireActivity(), VMessSettingsActivity::class.java))
            R.id.action_new_vless -> startActivity(Intent(requireActivity(), VMessSettingsActivity::class.java).apply { putExtra("vless", true) })
            R.id.action_new_trojan -> startActivity(Intent(requireActivity(), TrojanSettingsActivity::class.java))
            R.id.action_new_trojan_go -> startActivity(Intent(requireActivity(), TrojanGoSettingsActivity::class.java))
            R.id.action_new_mieru -> startActivity(Intent(requireActivity(), MieruSettingsActivity::class.java))
            R.id.action_new_naive -> startActivity(Intent(requireActivity(), NaiveSettingsActivity::class.java))
            R.id.action_new_hysteria -> startActivity(Intent(requireActivity(), HysteriaSettingsActivity::class.java))
            R.id.action_new_tuic -> startActivity(Intent(requireActivity(), TuicSettingsActivity::class.java))
            R.id.action_new_shadowtls -> startActivity(Intent(requireActivity(), ShadowTLSSettingsActivity::class.java))
            R.id.action_new_anytls -> startActivity(Intent(requireActivity(), AnyTLSSettingsActivity::class.java))
            R.id.action_new_ssh -> startActivity(Intent(requireActivity(), SSHSettingsActivity::class.java))
            R.id.action_new_wg -> startActivity(Intent(requireActivity(), WireGuardSettingsActivity::class.java))
            R.id.action_new_config -> startActivity(Intent(requireActivity(), ConfigSettingActivity::class.java))
            R.id.action_new_chain -> startActivity(Intent(requireActivity(), ChainSettingsActivity::class.java))

            R.id.action_new_group -> {
                startActivity(Intent(context, GroupSettingsActivity::class.java))
            }

            R.id.action_update_all -> {
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                    .setMessage(R.string.update_all_subscription)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        SagerDatabase.groupDao.allGroups()
                            .filter { it.type == GroupType.SUBSCRIPTION }
                            .forEach {
                                GroupUpdater.startUpdate(it, true)
                            }
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
            }
        }
        return true
    }

    private val importMetaFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val appContext = context?.applicationContext ?: return@registerForActivityResult
            if (uri != null) runOnDefaultDispatcher {
                runCatching { MetaCoreManager.importUri(appContext, uri) }
                    .onSuccess {
                        CoreController.selectEngine(CoreEngine.META)
                        onMainDispatcher { snackbar("Meta YAML 已导入并设为当前内核").show() }
                    }
                    .onFailure { e ->
                        Logs.w(e)
                        onMainDispatcher { snackbar(e.readableMessage).show() }
                    }
            }
        }

    private val importFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
            if (file != null) runOnDefaultDispatcher {
                try {
                    val fileName = requireContext().contentResolver.query(file, null, null, null, null)
                        ?.use { cursor ->
                            cursor.moveToFirst()
                            cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME).let(cursor::getString)
                        }
                    val proxies = mutableListOf<AbstractBean>()
                    if (fileName?.endsWith(".zip", true) == true) {
                        val zip = ZipInputStream(requireContext().contentResolver.openInputStream(file)!!)
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (entry.isDirectory) continue
                            val fileText = zip.bufferedReader().readText()
                            RawUpdater.parseRaw(fileText, entry.name)?.let(proxies::addAll)
                            zip.closeEntry()
                        }
                        zip.closeQuietly()
                    } else {
                        val fileText = requireContext().contentResolver.openInputStream(file)!!.use {
                            it.bufferedReader().readText()
                        }
                        RawUpdater.parseRaw(fileText, fileName ?: "")?.let(proxies::addAll)
                    }
                    if (proxies.isEmpty()) onMainDispatcher {
                        snackbar(getString(R.string.no_proxies_found_in_file)).show()
                    } else importProfiles(proxies)
                } catch (e: SubscriptionFoundException) {
                    activity.importSubscription(e.link.toUri())
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher { snackbar(e.readableMessage).show() }
                }
            }
        }

    private suspend fun importProfiles(proxies: List<AbstractBean>) {
        val targetId = DataStore.selectedGroupForImport()
        proxies.forEach { ProfileManager.createProfile(targetId, it) }
        onMainDispatcher {
            DataStore.editingGroup = targetId
            snackbar(resources.getQuantityString(R.plurals.added, proxies.size, proxies.size)).show()
        }
    }

    private fun importClipboard() {
        val text = SagerNet.getClipboardText()
        if (text.isBlank()) {
            snackbar(getString(R.string.clipboard_empty)).show()
            return
        }
        runOnDefaultDispatcher {
            try {
                val proxies = RawUpdater.parseRaw(text)
                if (proxies.isNullOrEmpty()) onMainDispatcher {
                    snackbar(getString(R.string.no_proxies_found_in_clipboard)).show()
                } else importProfiles(proxies)
            } catch (e: SubscriptionFoundException) {
                activity.importSubscription(e.link.toUri())
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher { snackbar(e.readableMessage).show() }
            }
        }
    }

    private fun showManualUrlImport() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.hui_import_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = false
            maxLines = 4
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.hui_import_url_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.hui_import_url) { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isBlank()) return@setPositiveButton
                runOnDefaultDispatcher {
                    try {
                        if (text.startsWith("http://", true) || text.startsWith("https://", true)) {
                            activity.importSubscription("sn://subscription?url=${android.net.Uri.encode(text)}".toUri())
                        } else {
                            val proxies = RawUpdater.parseRaw(text)
                            if (proxies.isNullOrEmpty()) onMainDispatcher {
                                snackbar(R.string.hui_import_url_invalid).show()
                            } else importProfiles(proxies)
                        }
                    } catch (e: SubscriptionFoundException) {
                        activity.importSubscription(e.link.toUri())
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher { snackbar(e.readableMessage).show() }
                    }
                }
            }.show()
    }

    private fun showMetaUrlImport() {
        val input = EditText(requireContext()).apply {
            hint = "https://example.com/config.yaml"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = false
            maxLines = 4
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("导入 Meta URL")
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("导入") { _, _ ->
                val raw = input.text?.toString()?.trim().orEmpty()
                if (raw.isBlank()) return@setPositiveButton
                val appContext = requireContext().applicationContext
                runOnDefaultDispatcher {
                    runCatching { MetaCoreManager.importUrl(appContext, raw) }
                        .onSuccess {
                            CoreController.selectEngine(CoreEngine.META)
                            onMainDispatcher { snackbar("Meta URL 已导入并设为当前内核").show() }
                        }
                        .onFailure { e ->
                            Logs.w(e)
                            onMainDispatcher { snackbar(e.readableMessage).show() }
                        }
                }
            }.show()
    }

    private fun showQuickSubscription() {
        val field = EditText(requireContext()).apply {
            hint = getString(R.string.hui_subscription_url)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val holder = FrameLayout(requireContext()).apply {
            setPadding(padding, 0, padding, 0)
            addView(field)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.hui_add_subscription)
            .setView(holder)
            .setNegativeButton(R.string.no, null)
            .setPositiveButton(R.string.hui_import_subscription) { _, _ ->
                val url = field.text.toString().trim()
                val parsed = runCatching { URI(url) }.getOrNull()
                if (parsed == null || parsed.host.isNullOrBlank() ||
                    parsed.scheme?.lowercase(Locale.ROOT) !in listOf("https", "http")) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setMessage("请输入完整的 HTTP 或 HTTPS 订阅地址")
                        .setPositiveButton(R.string.yes, null).show()
                } else {
                    runOnDefaultDispatcher {
                        val subscription = SubscriptionBean().apply {
                            link = url
                            applyDefaultValues()
                        }
                        val group = GroupManager.createGroup(ProxyGroup(
                            name = parsed.host, type = GroupType.SUBSCRIPTION,
                            subscription = subscription
                        ))
                        GroupUpdater.startUpdate(group, true)
                    }
                }
            }.show()
    }

    private lateinit var selectedGroup: ProxyGroup

    private val exportProfiles =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
            if (data != null) {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(selectedGroup.id)
                    val links = profiles.joinToString("\n") { it.toStdLink(compact = true) }
                    try {
                        (requireActivity() as MainActivity).contentResolver.openOutputStream(
                            data
                        )!!.bufferedWriter().use {
                            it.write(links)
                        }
                        onMainDispatcher {
                            snackbar(getString(R.string.action_export_msg)).show()
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }

                }
            }
        }

    inner class GroupAdapter : RecyclerView.Adapter<GroupHolder>(),
        GroupManager.Listener,
        UndoSnackbarManager.Interface<ProxyGroup> {

        val groupList = ArrayList<ProxyGroup>()

        suspend fun reload() {
            val groups = SagerDatabase.groupDao.allGroups().toMutableList()
            if (groups.size > 1 && SagerDatabase.proxyDao.countByGroup(groups.find { it.ungrouped }!!.id) == 0L) groups.removeAll { it.ungrouped }
            groupList.clear()
            groupList.addAll(groups)
            groupListView.post {
                notifyDataSetChanged()
            }
        }

        init {
            setHasStableIds(true)

            runOnDefaultDispatcher {
                reload()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupHolder {
            return GroupHolder(LayoutGroupItemBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: GroupHolder, position: Int) {
            holder.bind(groupList[position])
        }

        override fun getItemCount(): Int {
            return groupList.size
        }

        override fun getItemId(position: Int): Long {
            return groupList[position].id
        }

        private val updated = HashSet<ProxyGroup>()

        fun move(from: Int, to: Int) {
            val first = groupList[from]
            var previousOrder = first.userOrder
            val (step, range) = if (from < to) Pair(1, from until to) else Pair(
                -1, to + 1 downTo from
            )
            for (i in range) {
                val next = groupList[i + step]
                val order = next.userOrder
                next.userOrder = previousOrder
                previousOrder = order
                groupList[i] = next
                updated.add(next)
            }
            first.userOrder = previousOrder
            groupList[to] = first
            updated.add(first)
            notifyItemMoved(from, to)
        }

        fun commitMove() = runOnDefaultDispatcher {
            updated.forEach { SagerDatabase.groupDao.updateGroup(it) }
            updated.clear()
        }

        fun remove(index: Int) {
            groupList.removeAt(index)
            notifyItemRemoved(index)
        }

        override fun undo(actions: List<Pair<Int, ProxyGroup>>) {
            for ((index, item) in actions) {
                groupList.add(index, item)
                notifyItemInserted(index)
            }
        }

        override fun commit(actions: List<Pair<Int, ProxyGroup>>) {
            val groups = actions.map { it.second }
            runOnDefaultDispatcher {
                GroupManager.deleteGroup(groups)
                reload()
            }
        }

        override suspend fun groupAdd(group: ProxyGroup) {
            groupList.add(group)
            delay(300L)

            onMainDispatcher {
                undoManager.flush()
                notifyItemInserted(groupList.size - 1)

                if (group.type == GroupType.SUBSCRIPTION) {
                    GroupUpdater.startUpdate(group, true)
                }
            }
        }

        override suspend fun groupRemoved(groupId: Long) {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) return
            onMainDispatcher {
                undoManager.flush()
                if (SagerDatabase.groupDao.allGroups().size <= 2) {
                    runOnDefaultDispatcher {
                        reload()
                    }
                } else {
                    groupList.removeAt(index)
                    notifyItemRemoved(index)
                }
            }
        }

        override suspend fun groupUpdated(group: ProxyGroup) {
            val index = groupList.indexOfFirst { it.id == group.id }
            if (index == -1) {
                reload()
                return
            }
            groupList[index] = group
            onMainDispatcher {
                undoManager.flush()

                notifyItemChanged(index)
            }
        }

        override suspend fun groupUpdated(groupId: Long) {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) {
                reload()
                return
            }
            onMainDispatcher {
                notifyItemChanged(index)
            }
        }

    }

    override fun onDestroy() {
        if (::groupAdapter.isInitialized) {
            GroupManager.removeListener(groupAdapter)
        }

        super.onDestroy()

        if (!::undoManager.isInitialized) return
        undoManager.flush()
    }

    inner class GroupHolder(binding: LayoutGroupItemBinding) :
        RecyclerView.ViewHolder(binding.root),
        PopupMenu.OnMenuItemClickListener {

        lateinit var proxyGroup: ProxyGroup
        val groupName = binding.groupName
        val groupStatus = binding.groupStatus
        val groupTraffic = binding.groupTraffic
        val groupUser = binding.groupUser
        val editButton = binding.edit
        val optionsButton = binding.options
        val updateButton = binding.groupUpdate
        val subscriptionUpdateProgress = binding.subscriptionUpdateProgress

        override fun onMenuItemClick(item: MenuItem): Boolean {

            fun export(link: String) {
                val success = SagerNet.trySetPrimaryClip(link)
                activity.snackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
                    .show()
            }

            when (item.itemId) {
                R.id.action_universal_qr -> {
                    QRCodeDialog(
                        proxyGroup.toUniversalLink(), proxyGroup.displayName()
                    ).showAllowingStateLoss(parentFragmentManager)
                }

                R.id.action_universal_clipboard -> {
                    export(proxyGroup.toUniversalLink())
                }

                R.id.action_export_clipboard -> {
                    runOnDefaultDispatcher {
                        val profiles = SagerDatabase.proxyDao.getByGroup(selectedGroup.id)
                        val links = profiles.joinToString("\n") { it.toStdLink(compact = true) }
                        onMainDispatcher {
                            SagerNet.trySetPrimaryClip(links)
                            snackbar(getString(R.string.copy_toast_msg)).show()
                        }
                    }
                }

                R.id.action_export_file -> {
                    startFilesForResult(exportProfiles, "profiles_${proxyGroup.displayName()}.txt")
                }

                R.id.action_clear -> {
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                        .setMessage(R.string.clear_profiles_message)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            runOnDefaultDispatcher {
                                GroupManager.clearGroup(proxyGroup.id)
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }

            return true
        }


        fun bind(group: ProxyGroup) {
            proxyGroup = group

            itemView.setOnClickListener { }

            editButton.isGone = proxyGroup.ungrouped
            updateButton.isInvisible = proxyGroup.type != GroupType.SUBSCRIPTION
            groupName.text = proxyGroup.displayName()

            editButton.setOnClickListener {
                startActivity(Intent(it.context, GroupSettingsActivity::class.java).apply {
                    putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
                })
            }

            updateButton.setOnClickListener {
                GroupUpdater.startUpdate(proxyGroup, true)
            }

            optionsButton.setOnClickListener {
                selectedGroup = proxyGroup

                val popup = PopupMenu(requireContext(), it)
                popup.menuInflater.inflate(R.menu.group_action_menu, popup.menu)

                if (proxyGroup.type != GroupType.SUBSCRIPTION) {
                    popup.menu.removeItem(R.id.action_share_subscription)
                }
                popup.setOnMenuItemClickListener(this)
                popup.show()
            }

            if (proxyGroup.id in GroupUpdater.updating) {
                (groupName.parent as LinearLayout).apply {
                    setPadding(paddingLeft, dp2px(11), paddingRight, paddingBottom)
                }

                subscriptionUpdateProgress.isVisible = true

                if (!GroupUpdater.progress.containsKey(proxyGroup.id)) {
                    subscriptionUpdateProgress.isIndeterminate = true
                } else {
                    subscriptionUpdateProgress.isIndeterminate = false
                    GroupUpdater.progress[proxyGroup.id]?.let {
                        subscriptionUpdateProgress.max = it.max
                        subscriptionUpdateProgress.progress = it.progress
                    }
                }

                updateButton.isInvisible = true
                editButton.isGone = true
            } else {
                (groupName.parent as LinearLayout).apply {
                    setPadding(paddingLeft, dp2px(15), paddingRight, paddingBottom)
                }

                subscriptionUpdateProgress.isVisible = false
                updateButton.isInvisible = proxyGroup.type != GroupType.SUBSCRIPTION
                editButton.isGone = proxyGroup.ungrouped
            }

            val subscription = proxyGroup.subscription
            if (subscription != null && subscription.bytesUsed > 0L) { // SIP008 & Open Online Config
                groupTraffic.isVisible = true
                groupTraffic.text = if (subscription.bytesRemaining > 0L) {
                    app.getString(
                        R.string.subscription_traffic, Formatter.formatFileSize(
                            app, subscription.bytesUsed
                        ), Formatter.formatFileSize(
                            app, subscription.bytesRemaining
                        )
                    )
                } else {
                    app.getString(
                        R.string.subscription_used, Formatter.formatFileSize(
                            app, subscription.bytesUsed
                        )
                    )
                }
                groupStatus.setPadding(0)
            } else if (subscription != null && !subscription.subscriptionUserinfo.isNullOrBlank()) { // Raw
                var text = ""

                fun get(regex: String): String? {
                    return regex.toRegex().findAll(subscription.subscriptionUserinfo).mapNotNull {
                        if (it.groupValues.size > 1) it.groupValues[1] else null
                    }.firstOrNull()
                }

                try {
                    var used: Long = 0
                    get("upload=([0-9]+)")?.apply {
                        used += toLong()
                    }
                    get("download=([0-9]+)")?.apply {
                        used += toLong()
                    }
                    val total = get("total=([0-9]+)")?.toLong() ?: 0
                    val remain = total - used
                    if (used > 0 || total > 0) {
                        text += if (remain > 0) {
                            getString(
                                R.string.subscription_traffic,
                                used.toBytesString(),
                                remain.toBytesString()
                            )
                        } else {
                            getString(R.string.subscription_used, used.toBytesString())
                        }
                    }
                    get("expire=([0-9]+)")?.apply {
                        text += "\n"
                        text += getString(
                            R.string.subscription_expire,
                            Util.timeStamp2Text(this.toLong() * 1000)
                        )
                    }
                } catch (_: NumberFormatException) {
                    // ignore
                }

                if (text.isNotEmpty()) {
                    groupTraffic.isVisible = true
                    groupTraffic.text = text
                    groupStatus.setPadding(0)
                }
            } else {
                groupTraffic.isVisible = false
                groupStatus.setPadding(0, 0, 0, dp2px(4))
            }

            groupUser.text = subscription?.username ?: ""

            runOnDefaultDispatcher {
                val size = SagerDatabase.proxyDao.countByGroup(group.id)
                onMainDispatcher {
                    @Suppress("DEPRECATION") when (group.type) {
                        GroupType.BASIC -> {
                            if (size == 0L) {
                                groupStatus.setText(R.string.group_status_empty)
                            } else {
                                groupStatus.text = getString(R.string.group_status_proxies, size)
                            }
                        }

                        GroupType.SUBSCRIPTION -> {
                            groupStatus.text = if (size == 0L) {
                                getString(R.string.group_status_empty_subscription)
                            } else {
                                val date = Date(group.subscription!!.lastUpdated * 1000L)
                                getString(
                                    R.string.group_status_proxies_subscription,
                                    size,
                                    "${date.month + 1} - ${date.date}"
                                )
                            }

                        }
                    }
                }

            }

        }
    }

}