package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.utils.HuiVisuals

open class ToolbarFragment : Fragment {

    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    lateinit var toolbar: Toolbar

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        HuiVisuals.suppressFocusHighlights(view)
        toolbar = view.findViewById(R.id.toolbar)
        val appBar = view.findViewById<View>(R.id.appbar)
        if (appBar != null) {
            val baseLeft = appBar.paddingLeft
            val baseTop = appBar.paddingTop
            val baseRight = appBar.paddingRight
            val baseBottom = appBar.paddingBottom
            ViewCompat.setOnApplyWindowInsetsListener(appBar) { v, insets ->
                val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                v.setPadding(baseLeft, baseTop + topInset, baseRight, baseBottom)
                insets
            }
            ViewCompat.requestApplyInsets(appBar)
        }
        val host = activity as? MainActivity
        if (id == R.id.fragment_holder) {
            toolbar.setNavigationIcon(R.drawable.ic_navigation_close)
            toolbar.setNavigationContentDescription("关闭")
            toolbar.setNavigationOnClickListener { host?.onBackPressedDispatcher?.onBackPressed() }
        } else {
            toolbar.setNavigationIcon(R.drawable.ic_navigation_menu)
            toolbar.setNavigationOnClickListener {
                host?.binding?.drawerLayout?.openDrawer(GravityCompat.START)
            }
        }
    }

    open fun onKeyDown(ketCode: Int, event: KeyEvent) = false
    open fun onBackPressed(): Boolean = false
}
