package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.blacksquircle.ui.editorkit.widget.TextProcessor
import com.blacksquircle.ui.language.json.JsonLanguage
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.core.CoreController
import io.nekohasekai.sagernet.bg.core.CoreEngine
import io.nekohasekai.sagernet.bg.box.BoxCoreManager
import io.nekohasekai.sagernet.bg.meta.MetaCoreManager
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.utils.HuiVisuals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConfigCenterFragment : ToolbarFragment(R.layout.layout_config_center) {
    private lateinit var editor: TextProcessor
    private var loadedEngine: CoreEngine? = null
    private var dirty = false
    private var suppressDirty = false
    private var busy = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.title = "配置"
        editor = view.findViewById(R.id.config_editor)
        listOf(R.id.config_save, R.id.config_apply, R.id.config_update).forEach { id ->
            HuiVisuals.applyLiquidPress(view.findViewById(id))
        }
        view.findViewById<View>(R.id.config_save).setOnClickListener { saveConfig(false) }
        view.findViewById<View>(R.id.config_apply).setOnClickListener { saveConfig(true) }
        view.findViewById<View>(R.id.config_update).setOnClickListener { updateSource() }
        editor.addTextChangedListener {
            if (!suppressDirty && !dirty) {
                dirty = true
                updateDirtyBadge(view)
            }
        }
        loadConfig(view)
    }

    private fun loadConfig(view: View) {
        val engine = CoreController.selected
        loadedEngine = engine
        viewLifecycleOwner.lifecycleScope.launch {
            val payload = withContext(Dispatchers.IO) {
                when (engine) {
                    CoreEngine.META -> {
                        val remote = MetaCoreManager.sourceUrl(requireContext()).isNotBlank()
                        Triple(
                            MetaCoreManager.readConfigText(requireContext()),
                            if (remote) "Meta YAML · 已保留远程来源，可继续更新" else "Meta YAML · 本地配置",
                            remote,
                        )
                    }
                    CoreEngine.BOX -> {
                        val editable = BoxCoreManager.editableConfig()
                        Triple(editable.text, editable.description, editable.canUpdateSource)
                    }
                }
            }
            if (!isAdded) return@launch
            view.findViewById<TextView>(R.id.config_center_title).text = engine.displayName
            view.findViewById<TextView>(R.id.config_center_source).text = payload.second
            view.findViewById<View>(R.id.config_update).apply {
                isEnabled = payload.third
                alpha = if (payload.third) 1f else 0.45f
            }
            if (engine == CoreEngine.BOX) editor.language = JsonLanguage()
            suppressDirty = true
            editor.setTextContent(payload.first)
            suppressDirty = false
            dirty = false
            updateDirtyBadge(view)
        }
    }

    private fun notifyUser(message: CharSequence) {
        (activity as? MainActivity)?.snackbar(message)?.show()
    }

    private fun updateDirtyBadge(view: View) {
        val source = view.findViewById<TextView>(R.id.config_center_source)
        val base = source.text.toString().removeSuffix(" · 未保存")
        source.text = if (dirty) "$base · 未保存" else base
    }

    private fun setBusy(value: Boolean) {
        busy = value
        view?.let { root ->
            listOf(R.id.config_save, R.id.config_apply, R.id.config_update).forEach { id ->
                root.findViewById<View>(id)?.isEnabled = !value
            }
        }
    }

    private fun saveConfig(applyAfter: Boolean) {
        if (busy) return
        val engine = loadedEngine ?: CoreController.selected
        val raw = editor.text.toString()
        setBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (engine) {
                        CoreEngine.META -> MetaCoreManager.saveEditedConfig(requireContext(), raw)
                        CoreEngine.BOX -> BoxCoreManager.saveEditedConfig(raw)
                    }
                }
            }
            setBusy(false)
            result.onSuccess {
                dirty = false
                view?.let(::updateDirtyBadge)
                notifyUser(if (applyAfter) "配置已保存，正在应用" else "配置已保存")
                if (applyAfter) (activity as? MainActivity)?.reloadCurrentCoreFromUi()
            }.onFailure { notifyUser(it.readableMessage) }
        }
    }

    private fun updateSource() {
        if (busy) return
        val engine = loadedEngine ?: CoreController.selected
        setBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (engine) {
                        CoreEngine.META -> MetaCoreManager.refreshFromSource(requireContext())
                        CoreEngine.BOX -> {
                            val group = BoxCoreManager.sourceGroup()
                                ?: error("当前 Box 配置没有订阅来源")
                            GroupUpdater.startUpdate(group, true)
                        }
                    }
                }
            }
            setBusy(false)
            result.onSuccess {
                notifyUser(if (engine == CoreEngine.META) "远程配置已更新，本地旧版已备份" else "已开始更新订阅，本地覆写会保留")
                if (engine == CoreEngine.META && isAdded) loadConfig(requireView())
            }.onFailure { notifyUser(it.readableMessage) }
        }
    }
    override fun onResume() {
        super.onResume()
        if (::editor.isInitialized && loadedEngine != CoreController.selected) {
            view?.let(::loadConfig)
        }
    }

}
