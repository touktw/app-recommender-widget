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
    /** 그 요일·시간대 사용 횟수 (최근성 가중치, 요일 섞기 반영) */
    val uses: Double,
    /** 그 요일 사용 중 그 시간대 비율 (0~1) */
    val share: Double,
    /** 그 요일·시간에 사용한 주의 수 (최근 4주 중) */
    val repeatWeeks: Int,
)

/** 앱 하나의 요일(0=일 ~ 6=토) × 시간(0~23) 사용 횟수 */
class UsageGrid {
    val counts = Array(DAYS) { DoubleArray(HOURS) }
    /** 그 요일·시간에 쓴 주(최근 0주차~3주차)를 비트로 표시 */
    val weeks = Array(DAYS) { IntArray(HOURS) }

    /** 같은 요일·시간에 사용한 서로 다른 주의 수 (0~4) */
    fun repeatWeeks(day: Int, hour: Int) = Integer.bitCount(weeks[day][hour])

    /**
     * 그 요일·시간의 사용 횟수. 4주 기록이면 요일별 표본이 적으니
     * 같은 요일 기록과 모든 요일 평균을 섞어서 쓴다.
     */
    fun at(day: Int, hour: Int): Double {
        val sameDay = counts[day][hour]
        val allDaysAvg = (0 until DAYS).sumOf { counts[it][hour] } / DAYS
        return SAME_DAY_WEIGHT * sameDay + (1 - SAME_DAY_WEIGHT) * allDaysAvg
    }

    fun dayTotal(day: Int) = (0 until HOURS).sumOf { at(day, it) }

    companion object {
        const val DAYS = 7
        const val HOURS = 24
        /** 같은 요일 기록의 비중. 나머지는 모든 요일 평균 */
        const val SAME_DAY_WEIGHT = 0.6
    }
}

/**
 * 시스템이 기록해 둔 앱 사용 기록(UsageStats)으로 "지금 이 요일·시간대에 쓸 앱"을 고른다.
 *
 * 1) 앱마다 요일 × 1시간 단위 사용 횟수를 센다. (최근 기록일수록 1회를 더 크게 센다)
 * 2) 점수 = 그 요일·시간대 사용 횟수 × 시간대 집중도
 *    집중도 = 그 요일 사용량 중 그 시간대 비율 × 24
 *    - 유튜브·브라우저처럼 하루 종일 고르게 쓰면 ≈ 1 → 가중치 낮음
 *    - 식권 앱처럼 평일 점심에만 쓰면 ≈ 24 → 가중치 높음
 *    비율에는 라플라스 스무딩(+1 / +24)을 넣어 한두 번 쓴 앱이 튀지 않게 한다.
 * 3) 반복 보너스: 같은 요일·시간에 여러 주에 걸쳐 쓴 앱은 주 수만큼 점수를 더 준다.
 *    (매주 월요일 9시에 여는 앱 → 한 주에 몰아서 많이 연 앱보다 우선)
 */
object Recommender {
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val HOURS = UsageGrid.HOURS
    private const val LOOKBACK_DAYS = 28
    /** 기록 가중치가 절반이 되는 기간 (일) */
    private const val HALF_LIFE_DAYS = 7.0
    /** 반복한 주 하나당 더하는 배수. 4주 연속이면 1 + 0.5 × 3 = 2.5배 */
    private const val REPEAT_BONUS_PER_WEEK = 0.5
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
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        return rank(context, loadGrids(context, now), dayOf(cal), cal.get(Calendar.HOUR_OF_DAY), limit)
    }

    /** 앱별 요일 × 시간 사용 기록. 런처에서 열 수 있는 앱만 담는다. */
    fun loadGrids(
        context: Context,
        now: Long = System.currentTimeMillis(),
    ): Map<String, UsageGrid> {
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val pm = context.packageManager
        val excluded = excludedPackages(context)

        val grids = HashMap<String, UsageGrid>()
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
            val day = dayOf(cal)
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val week = (ageDays / 7).toInt().coerceIn(0, 31)
            grids.getOrPut(pkg) { UsageGrid() }.apply {
                counts[day][hour] += recency
                weeks[day][hour] = weeks[day][hour] or (1 shl week)
            }
        }
        return grids.filterKeys { pm.getLaunchIntentForPackage(it) != null }
    }

    /** 특정 요일(0=일 ~ 6=토)·시간(0~23)의 순위를 매긴다. */
    fun rank(
        context: Context,
        grids: Map<String, UsageGrid>,
        day: Int,
        hour: Int,
        limit: Int = Int.MAX_VALUE,
    ): List<Recommendation> = grids.entries
        .filter { (_, grid) -> grid.at(day, hour) > 0.0 }
        .sortedByDescending { (_, grid) -> score(grid, day, hour) }
        .take(limit)
        .map { (pkg, grid) ->
            Recommendation(
                packageName = pkg,
                label = labelOf(context, pkg),
                score = score(grid, day, hour),
                uses = grid.at(day, hour),
                share = grid.at(day, hour) / grid.dayTotal(day),
                repeatWeeks = grid.repeatWeeks(day, hour),
            )
        }

    /** 그 요일·시간대 사용 횟수 × 시간대 집중도 × 반복 보너스 */
    internal fun score(grid: UsageGrid, day: Int, hour: Int): Double {
        val freq = grid.at(day, hour)
        if (freq <= 0.0) return 0.0
        val share = (freq + 1) / (grid.dayTotal(day) + HOURS)
        val concentration = share * HOURS
        val repeats = grid.repeatWeeks(day, hour)
        val repeatBonus = 1 + REPEAT_BONUS_PER_WEEK * (repeats - 1).coerceAtLeast(0)
        return freq * concentration * repeatBonus
    }

    /** Calendar 기준 요일을 0=일 ~ 6=토로 */
    fun dayOf(cal: Calendar) = cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY

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
}
