package com.remote.dipgonc

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Dialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.ViewTreeObserver
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val KEY_WEBVIEW_STATE = "webview_state"
        private const val KEY_CURRENT_URL = "webViewUrl"
    }

    private lateinit var statusHeader: LinearLayout
    private lateinit var statusSummary: LinearLayout
    private lateinit var ivCollapseIndicator: ImageView
    private lateinit var ivExpandIndicator: ImageView
    private lateinit var tvStatusSummary: TextView
    private lateinit var tvP2PStatus: TextView
    private lateinit var webView: WebView
    private lateinit var btnSettings: Button
    private lateinit var btnRefresh: Button
    private var flag: Boolean = true;

    private var isStatusExpanded = true

    // 状态栏高度（用于动画）
    private var statusHeaderHeight = 0

    // 要加载的网页URL（可配置）
    private val webUrl = "http://127.0.0.1-8988.gonc.cc:" + P2PManager.PORT


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 恢复保存的状态
        if (savedInstanceState != null) {
            isStatusExpanded = savedInstanceState.getBoolean("isStatusExpanded")
            flag = savedInstanceState.getBoolean("flag")
        }

        initViews()
        setupClickListeners()
        measureStatusHeaderHeight()
        setupWebView()
        P2PManager.init(this, object : P2PManager.CallBack() {
            override fun onStatusChange(status: P2PManager.P2PStatus, msg: String) {
                updateP2PStatusDisplay(status, msg)
            }
        })
    }

    private fun initViews() {
        webView = findViewById(R.id.webView)
        tvP2PStatus = findViewById(R.id.tvP2PStatus)
        btnSettings = findViewById(R.id.btnSettings)
        btnRefresh = findViewById(R.id.btnRefresh)


        statusHeader = findViewById(R.id.statusHeader)
        statusSummary = findViewById(R.id.statusSummary)
        ivCollapseIndicator = findViewById(R.id.ivCollapseIndicator)
        ivExpandIndicator = findViewById(R.id.ivExpandIndicator)
        tvStatusSummary = findViewById(R.id.tvStatusSummary)

        btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
        btnRefresh.setOnClickListener {
            webView.url?.takeIf { it.isNotEmpty() }?.run { webView.reload() }
        }
    }

    private fun setupClickListeners() {
        statusHeader.setOnClickListener {
            if (isStatusExpanded) {
                collapseStatusBar()
            }
        }

        statusSummary.setOnClickListener {
            if (!isStatusExpanded) {
                expandStatusBar()
            }
        }
    }

    private fun measureStatusHeaderHeight() {
        statusHeader.viewTreeObserver.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                statusHeader.viewTreeObserver.removeOnGlobalLayoutListener(this)
                statusHeaderHeight = statusHeader.height
            }
        })
    }

    private fun collapseStatusBar() {
        if (!isStatusExpanded) return

        isStatusExpanded = false

        // 使用属性动画实现折叠
        statusHeader.animate()
            .translationY(-statusHeaderHeight.toFloat())
            .alpha(0f)
            .setDuration(300)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    statusHeader.visibility = View.GONE
                    statusSummary.visibility = View.VISIBLE
                    statusSummary.alpha = 0f
                    statusSummary.animate().alpha(1f).setDuration(150).start()

                    // 重置状态
                    statusHeader.translationY = 0f
                    statusHeader.alpha = 1f
                }
            })
            .start()

        // 箭头动画
        ivCollapseIndicator.animate().rotation(180f).setDuration(200).start()
    }

    private fun expandStatusBar() {
        if (isStatusExpanded) return

        isStatusExpanded = true

        // 先显示状态栏（透明），隐藏摘要栏
        statusHeader.visibility = View.VISIBLE
        statusHeader.alpha = 0f
        statusHeader.translationY = -statusHeaderHeight.toFloat()
        statusSummary.visibility = View.GONE

        // 展开动画
        statusHeader.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(300)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // 动画完成后确保状态正确
                    statusHeader.translationY = 0f
                    statusHeader.alpha = 1f
                }
            })
            .start()

        // 箭头动画
        ivExpandIndicator.animate().rotation(0f).setDuration(200).start()
    }

    private fun setupWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.setSupportMultipleWindows(true)
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        webView.settings.databaseEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowFileAccessFromFileURLs = true
        webView.settings.useWideViewPort = true // 使 WebView 支持 <meta name="viewport" ...>
        webView.settings.loadWithOverviewMode = true // 缩放页面使其适合 WebView 的宽度
        // 启用缓存
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        // 设置 WebChromeClient 处理新窗口
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                // 创建新窗口（新标签页）
                createNewWebViewTab(resultMsg)
                return true
            }

//            override fun onCloseWindow(window: WebView?) {
//                // 关闭窗口
//                closeWebViewTab(window)
//            }
        }
    }


    private fun updateP2PStatusDisplay(status: P2PManager.P2PStatus, msg: String = "") {
        // 检查 Activity 是否已被销毁或正在销毁
        if (isFinishing || isDestroyed) {
            return
        }

        val statusText = when (status) {
            P2PManager.P2PStatus.CONNECTED -> "已连接 ✓"
            P2PManager.P2PStatus.CONNECTING -> "连接中..."
            P2PManager.P2PStatus.DISCONNECTED -> "未连接 ✗"
            P2PManager.P2PStatus.ERROR -> "错误 ⚠ " + msg
        }

        val statusColor = when (status) {
            P2PManager.P2PStatus.CONNECTED -> "#00ff00"
            P2PManager.P2PStatus.CONNECTING -> "#0000ff"
            P2PManager.P2PStatus.DISCONNECTED -> "#ff0000"
            P2PManager.P2PStatus.ERROR -> "#ff0000"
        }
        // 确保在主线程执行
        runOnUiThread {
            // 再次检查 Activity 状态，因为可能在 runOnUiThread 排队期间 Activity 被销毁
            if (isFinishing || isDestroyed) {
                return@runOnUiThread
            }
            if (msg.isNotEmpty()) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
            if (flag && status == P2PManager.P2PStatus.CONNECTED) {
                Toast.makeText(this, "连接成功，开始加载首页", Toast.LENGTH_SHORT).show()
                webView.loadUrl(webUrl + "?auth=" + P2PManager.getAuth())
                flag = false
            }
            tvP2PStatus.text = statusText
            tvP2PStatus.setTextColor(Color.parseColor(statusColor))
            tvStatusSummary.text = "连接状态: $statusText"
            tvStatusSummary.setTextColor(Color.parseColor(statusColor))
        }
    }

    override fun onResume() {
        super.onResume()
        updateP2PStatusDisplay(P2PManager.getP2PStatus())
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("isStatusExpanded", isStatusExpanded)
        outState.putBoolean("flag", flag)
    }

    private fun createNewWebViewTab(resultMsg: Message?) {
        // 创建对话框显示新页面
        val dialog = Dialog(this, android.R.style.Theme_NoTitleBar_Fullscreen)
        val newWebView = WebView(this)

        // 配置新WebView（同上）
        newWebView.settings.javaScriptEnabled = true
        newWebView.settings.domStorageEnabled = true
        newWebView.settings.setSupportMultipleWindows(true)
        newWebView.settings.javaScriptCanOpenWindowsAutomatically = true

        newWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                dialog.setTitle(view?.title ?: "新窗口")
            }
        }

        newWebView.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView?) {
                dialog.dismiss()
            }
        }

        dialog.setContentView(newWebView)
        dialog.show()

        // 告诉WebView新窗口已创建
        val transport = resultMsg?.obj as? WebView.WebViewTransport
        transport?.webView = newWebView
        resultMsg?.sendToTarget()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 屏幕方向改变时重新测量高度
        measureStatusHeaderHeight()
    }
}