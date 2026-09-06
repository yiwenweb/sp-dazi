package com.sunnypilot.toolbox.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * 超级视频：WebView 加载 C3 的 stream_server (MJPEG + 触摸转发) 页面。
 * stream_server 的 / 页面已内嵌触摸 JS（touch 事件 -> POST /input -> c3touchd -> uinput），
 * WebView 开启 JS 后即可直接观看并操作 C3 UI。
 */
class C3StreamActivity : ComponentActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val host = intent.getStringExtra("host") ?: "192.168.1.36"
        val url = "http://$host:8081/"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        // 顶部栏：标题 + 返回
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(24, 30, 40))
            setPadding(dp(12), 0, dp(12), 0)
        }
        val title = TextView(this).apply {
            text = "超级视频 · $host"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, dp(10), 0, dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val back = TextView(this).apply {
            text = "返回"
            setTextColor(Color.rgb(120, 200, 255))
            textSize = 15f
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnClickListener { finish() }
        }
        bar.addView(title)
        bar.addView(back)
        root.addView(
            bar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // 视频流 WebView
        val webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.loadUrl(url)
        root.addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
