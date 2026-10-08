package com.tae.apprecommend

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
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
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * 위젯의 더보기 화면: 시간대별(0~23시) 추천 순위.
 * 위쪽 시간 칩으로 시간대를 고르면 그 시간의 순위를 보여준다. 처음엔 지금 시간대.
 * 각 앱의 핀 버튼으로 고정하면 위젯 앞칸에 점수와 상관없이 항상 나온다.
 */
class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var permissionButton: Button
    private lateinit var hourScroll: HorizontalScrollView
    private lateinit var hourStrip: LinearLayout
    private lateinit var title: TextView
    private lateinit var pinSection: LinearLayout
    private lateinit var list: LinearLayout
    private val dp get() = resources.displayMetrics.density

    private var histograms: Map<String, DoubleArray> = emptyMap()
    private var selectedHour = currentHour()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * dp).toInt()
        status = TextView(this).apply { textSize = 15f }
        permissionButton = Button(this).apply {
            text = "사용 기록 접근 설정 열기"
            setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }
        hourStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hourScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
            addView(hourStrip)
        }
        for (h in 0 until 24) hourStrip.addView(hourChip(h))
        title = TextView(this).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (12 * dp).toInt(), 0, 0)
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pinSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        setContentView(
            ScrollView(this).apply {
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(pad, pad * 2, pad, pad)
                        addView(status)
                        addView(permissionButton)
                        addView(pinSection)
                        addView(hourScroll)
                        addView(title)
                        addView(list)
                    },
                )
            },
        )
    }

    override fun onResume() {
        super.onResume()
        val hasAccess = Recommender.hasUsageAccess(this)
        permissionButton.visibility = if (hasAccess) View.GONE else View.VISIBLE
        hourScroll.visibility = if (hasAccess) View.VISIBLE else View.GONE
        title.visibility = hourScroll.visibility
        if (!hasAccess) {
            status.text = "추천하려면 사용 기록 접근을 허용해야 해요. 아래 버튼에서 이 앱을 켜 주세요."
            list.removeAllViews()
            pinSection.removeAllViews()
            return
        }
        showPins()
        status.text = "최근 4주 사용 기록 기준, 시간대별 순위예요."
        RefreshWorker.schedule(this)
        selectedHour = currentHour()
        lifecycleScope.launch {
            histograms = withContext(Dispatchers.IO) { Recommender.loadHistograms(this@MainActivity) }
            showHour(selectedHour)
            RecommendWidget().updateAll(this@MainActivity)
        }
    }

    private fun showHour(hour: Int) {
        selectedHour = hour
        for (h in 0 until 24) styleChip(hourStrip.getChildAt(h) as TextView, h == hour)
        hourStrip.getChildAt(hour).let { chip ->
            hourScroll.post { hourScroll.smoothScrollTo(chip.left - hourScroll.width / 2 + chip.width / 2, 0) }
        }
        val nowMark = if (hour == currentHour()) " (지금)" else ""
        title.text = "${hour}시~${(hour + 1) % 24}시 순위$nowMark"

        list.removeAllViews()
        lifecycleScope.launch {
            val recs = withContext(Dispatchers.IO) {
                Recommender.rank(this@MainActivity, histograms, hour, limit = 20)
            }
            if (hour != selectedHour) return@launch
            if (recs.isEmpty()) {
                list.addView(TextView(this@MainActivity).apply {
                    text = "이 시간대에는 아직 사용 기록이 없어요."
                    setPadding(0, (16 * dp).toInt(), 0, 0)
                })
            }
            recs.forEachIndexed { i, rec ->
                val sharePct = (rec.share * 100).roundToInt()
                list.addView(
                    row("${i + 1}", rec.packageName, rec.label, "이 시간대 비중 $sharePct% · 점수 ${rec.score.roundToInt()}"),
                )
            }
        }
    }

    private fun hourChip(hour: Int) = TextView(this).apply {
        text = "${hour}시"
        textSize = 14f
        gravity = Gravity.CENTER
        minWidth = (52 * dp).toInt()
        setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = (6 * dp).toInt() }
        setOnClickListener { showHour(hour) }
    }

    private fun styleChip(chip: TextView, selected: Boolean) {
        chip.background = GradientDrawable().apply {
            cornerRadius = 18 * dp
            setColor(if (selected) ACCENT else Color.parseColor("#FFEEF0FA"))
        }
        chip.setTextColor(if (selected) Color.WHITE else Color.parseColor("#FF3C4043"))
    }

    /** 고정한 앱 목록. 고정한 앱이 없으면 안내 한 줄만 보여준다. */
    private fun showPins() {
        pinSection.removeAllViews()
        pinSection.addView(TextView(this).apply {
            text = "고정한 앱"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (16 * dp).toInt(), 0, 0)
        })
        val pins = PinStore.get(this).filter { packageManager.getLaunchIntentForPackage(it) != null }
        if (pins.isEmpty()) {
            pinSection.addView(TextView(this).apply {
                text = "순위에서 핀 버튼을 누르면 위젯에 항상 나오게 고정할 수 있어요."
                textSize = 13f
                setTextColor(Color.GRAY)
                setPadding(0, (4 * dp).toInt(), 0, 0)
            })
            return
        }
        pins.forEachIndexed { i, pkg ->
            val label = packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0))
            pinSection.addView(row("${i + 1}", pkg, label.toString(), "위젯 ${i + 1}번째 칸"))
        }
    }

    private fun togglePin(pkg: String) {
        PinStore.toggle(this, pkg)
        showPins()
        showHour(selectedHour)
        lifecycleScope.launch { RecommendWidget().updateAll(this@MainActivity) }
    }

    private fun row(rank: String, pkg: String, label: String, detail: String): LinearLayout {
        val size = (40 * dp).toInt()
        val gap = (12 * dp).toInt()
        val pinned = PinStore.isPinned(this, pkg)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, gap, 0, 0)
            addView(TextView(context).apply { text = rank; width = (28 * dp).toInt() })
            addView(
                ImageView(context).apply { setImageDrawable(packageManager.getApplicationIcon(pkg)) },
                LinearLayout.LayoutParams(size, size).apply { marginEnd = gap },
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply { text = label; textSize = 16f })
                    addView(TextView(context).apply {
                        text = detail
                        textSize = 12f
                        setTextColor(Color.GRAY)
                    })
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(TextView(context).apply {
                text = if (pinned) "고정됨" else "고정"
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(gap, (6 * dp).toInt(), gap, (6 * dp).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 16 * dp
                    if (pinned) setColor(ACCENT) else setStroke((1 * dp).toInt(), ACCENT)
                }
                setTextColor(if (pinned) Color.WHITE else ACCENT)
                contentDescription = if (pinned) "$label 고정 해제" else "$label 고정"
                setOnClickListener { togglePin(pkg) }
            })
            setOnClickListener {
                packageManager.getLaunchIntentForPackage(pkg)?.let(::startActivity)
            }
        }
    }

    private companion object {
        val ACCENT = Color.parseColor("#FF3D5AFE")
        fun currentHour() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    }
}
