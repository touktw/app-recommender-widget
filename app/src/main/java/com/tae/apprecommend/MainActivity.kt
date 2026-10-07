package com.tae.apprecommend

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import com.tae.apprecommend.widget.RecommendWidget
import com.tae.apprecommend.widget.RefreshWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 위젯의 더보기 화면: 지금 시간대 추천 전체 순위. 권한이 없으면 권한 안내. */
class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var permissionButton: Button
    private lateinit var list: LinearLayout
    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * dp).toInt()
        status = TextView(this).apply { textSize = 18f }
        permissionButton = Button(this).apply {
            text = "사용 기록 접근 설정 열기"
            setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(
            ScrollView(this).apply {
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(pad, pad * 2, pad, pad)
                        addView(status)
                        addView(permissionButton)
                        addView(list)
                    },
                )
            },
        )
    }

    override fun onResume() {
        super.onResume()
        list.removeAllViews()
        val hasAccess = Recommender.hasUsageAccess(this)
        permissionButton.visibility = if (hasAccess) Button.GONE else Button.VISIBLE
        if (!hasAccess) {
            status.text = "추천하려면 사용 기록 접근을 허용해야 해요. 아래 버튼에서 이 앱을 켜 주세요."
            return
        }
        status.text = "지금 시간대 추천"
        RefreshWorker.schedule(this)
        lifecycleScope.launch {
            val recs = withContext(Dispatchers.IO) { Recommender.recommend(this@MainActivity, limit = 20) }
            if (recs.isEmpty()) status.text = "이 시간대 기록이 아직 부족해요."
            recs.forEachIndexed { i, rec -> list.addView(row(i + 1, rec)) }
            RecommendWidget().updateAll(this@MainActivity)
        }
    }

    private fun row(rank: Int, rec: Recommendation): LinearLayout {
        val size = (40 * dp).toInt()
        val gap = (12 * dp).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, gap, 0, 0)
            addView(TextView(context).apply { text = "$rank"; width = (28 * dp).toInt() })
            addView(
                ImageView(context).apply {
                    setImageDrawable(packageManager.getApplicationIcon(rec.packageName))
                },
                LinearLayout.LayoutParams(size, size).apply { marginEnd = gap },
            )
            addView(TextView(context).apply { text = rec.label; textSize = 16f })
            setOnClickListener {
                packageManager.getLaunchIntentForPackage(rec.packageName)?.let(::startActivity)
            }
        }
    }
}
