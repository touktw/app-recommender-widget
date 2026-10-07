package com.tae.apprecommend

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import java.util.Calendar
import kotlin.math.pow

data class Recommendation(
    val packageName: String,
    val label: String,
    val score: Double,
    /** 그 시간대 사용 횟수 (최근성·요일 가중치 반영) */
    val uses: Double,
    /** 전체 사용 중 그 시간대 비율 (0~1) */
    val share: Double,
)

/**
 * 시스템이 기록해 둔 앱 사용 기록(UsageStats)으로 "지금 이 시간대에 쓸 앱"을 고른다.
 *
 * 1) 앱마다 0~23시, 1시간 단위 사용 횟수 히스토그램을 만든다.
 *    (최근 기록일수록, 오늘과 평일/주말이 같을수록 1회를 더 크게 센다)
 * 2) 점수 = 지금 시간대 사용 횟수 × 시간대 집중도
 *    집중도 = 이 앱 사용량 중 지금 시간대 비율 × 24
 *    - 유튜브·브라우저처럼 하루 종일 고르게 쓰면 ≈ 1 → 가중치 낮음
 *    - 식권 앱처럼 점심에만 쓰면 ≈ 24 → 가중치 높음
 *    비율에는 라플라스 스무딩(+1 / +24)을 넣어 한두 번 쓴 앱이 튀지 않게 한다.
 */
object Recommender {
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val HOURS = 24
    private const val LOOKBACK_DAYS = 28
    /** 기록 가중치가 절반이 되는 기간 (일) */
    private const val HALF_LIFE_DAYS = 7.0
    /** 오늘과 같은 요일 유형(평일/주말)의 기록에 주는 배수 */
    private const val SAME_DAY_TYPE_BONUS = 1.5
    /** 같은 앱 안에서 화면만 바뀐 경우를 한 번으로 치기 위한 간격 */
    private const val DEDUPE_MS = 60_000L

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun recommend(
        context: Context,
        limit: Int = 5,
        now: Long = System.currentTimeMillis(),
    ): List<Recommendation> {
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        return rank(context, loadHistograms(context, now), hour, limit)
    }

    /** 앱별 0~23시 사용 히스토그램. 런처에서 열 수 있는 앱만 담는다. */
    fun loadHistograms(
        context: Context,
        now: Long = System.currentTimeMillis(),
    ): Map<String, DoubleArray> {
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val pm = context.packageManager
        val excluded = excludedPackages(context)
        val nowWeekend = isWeekend(Calendar.getInstance().apply { timeInMillis = now })

        val histograms = HashMap<String, DoubleArray>()
        val cal = Calendar.getInstance()
        val events = usm.queryEvents(now - LOOKBACK_DAYS * DAY_MS, now)
        val event = UsageEvents.Event()
        var lastPkg: String? = null
        var lastTime = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION")
            if (event.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND) continue
            val pkg = event.packageName
            if (pkg in excluded) continue
            if (pkg == lastPkg && event.timeStamp - lastTime < DEDUPE_MS) {
                lastTime = event.timeStamp
                continue
            }
            lastPkg = pkg
            lastTime = event.timeStamp

            cal.timeInMillis = event.timeStamp
            val ageDays = (now - event.timeStamp).toDouble() / DAY_MS
            val recency = 0.5.pow(ageDays / HALF_LIFE_DAYS)
            val dayType = if (isWeekend(cal) == nowWeekend) SAME_DAY_TYPE_BONUS else 1.0
            histograms.getOrPut(pkg) { DoubleArray(HOURS) }[cal.get(Calendar.HOUR_OF_DAY)] +=
                recency * dayType
        }
        return histograms.filterKeys { pm.getLaunchIntentForPackage(it) != null }
    }

    /** 히스토그램으로 특정 시간대(0~23시)의 순위를 매긴다. */
    fun rank(
        context: Context,
        histograms: Map<String, DoubleArray>,
        hour: Int,
        limit: Int = Int.MAX_VALUE,
    ): List<Recommendation> = histograms.entries
        .map { (pkg, hist) -> pkg to hist }
        .filter { (_, hist) -> hist[hour] > 0.0 }
        .sortedByDescending { (_, hist) -> score(hist, hour) }
        .take(limit)
        .map { (pkg, hist) ->
            Recommendation(
                packageName = pkg,
                label = labelOf(context, pkg),
                score = score(hist, hour),
                uses = hist[hour],
                share = hist[hour] / hist.sum(),
            )
        }

    /** 지금 시간대 사용 횟수 × 시간대 집중도 */
    internal fun score(hist: DoubleArray, hour: Int): Double {
        val freq = hist[hour]
        if (freq <= 0.0) return 0.0
        val share = (freq + 1) / (hist.sum() + HOURS)
        val concentration = share * HOURS
        return freq * concentration
    }

    private fun excludedPackages(context: Context): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launchers = context.packageManager.queryIntentActivities(home, 0)
            .map { it.activityInfo.packageName }
        return buildSet {
            add(context.packageName)
            add("com.android.systemui")
            addAll(launchers)
        }
    }

    private fun labelOf(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun isWeekend(cal: Calendar): Boolean {
        val day = cal.get(Calendar.DAY_OF_WEEK)
        return day == Calendar.SATURDAY || day == Calendar.SUNDAY
    }
}
