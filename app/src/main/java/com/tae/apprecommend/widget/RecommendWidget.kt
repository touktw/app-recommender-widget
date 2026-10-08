package com.tae.apprecommend.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.tae.apprecommend.MainActivity
import com.tae.apprecommend.PinStore
import com.tae.apprecommend.R
import com.tae.apprecommend.Recommender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class WidgetItem(
    val label: String,
    val icon: Bitmap,
    val launchIntent: Intent,
)

/** 한 줄에 4칸, 마지막 칸은 항상 더보기 */
private const val COLUMNS = 4

class RecommendWidget : GlanceAppWidget() {

    companion object {
        private val SMALL = DpSize(110.dp, 40.dp)   // 2x1: 앱 3개 + 더보기
        private val LARGE = DpSize(110.dp, 110.dp)  // 2x2: 앱 7개 + 더보기
        const val MAX_APPS = COLUMNS * 2 - 1
    }

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val hasAccess = Recommender.hasUsageAccess(context)
        val items = if (hasAccess) withContext(Dispatchers.IO) { loadItems(context) } else emptyList()
        provideContent {
            GlanceTheme {
                Content(hasAccess, items)
            }
        }
    }

    /** 고정한 앱을 먼저, 남은 칸은 시간대 추천으로 채운다. */
    private fun loadItems(context: Context): List<WidgetItem> {
        val pm = context.packageManager
        val pinned = PinStore.get(context)
        val recommended = Recommender.recommend(context, limit = MAX_APPS + pinned.size)
            .map { it.packageName }
            .filterNot { it in pinned }
        return (pinned + recommended).asSequence().mapNotNull { pkg ->
            val intent = pm.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
            val label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            val icon = pm.getApplicationIcon(pkg).toBitmap(96, 96)
            WidgetItem(label, icon, intent)
        }.take(MAX_APPS).toList()
    }

    @Composable
    private fun Content(hasAccess: Boolean, items: List<WidgetItem>) {
        val rows = if (LocalSize.current.height >= LARGE.height) 2 else 1
        val apps = items.take(rows * COLUMNS - 1)
        val openMore = actionStartActivity<MainActivity>()

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!hasAccess) {
                Text(
                    text = "사용 기록 접근을 허용해 주세요 (탭)",
                    style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
                    modifier = GlanceModifier.padding(4.dp).clickable(
                        actionStartActivity(
                            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        ),
                    ),
                )
            } else {
                Grid(rows, apps, openMore)
            }
        }
    }

    @Composable
    private fun Grid(rows: Int, apps: List<WidgetItem>, openMore: Action) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            // 칸 채우기: 추천 앱 → (부족하면 빈칸) → 마지막은 더보기
            val cells = rows * COLUMNS
            for (r in 0 until rows) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (c in 0 until COLUMNS) {
                        val index = r * COLUMNS + c
                        val cellModifier = GlanceModifier.defaultWeight()
                        when {
                            index == cells - 1 ->
                                Cell(ImageProvider(R.drawable.ic_more), "더보기", openMore, cellModifier)
                            index < apps.size -> apps[index].let {
                                Cell(ImageProvider(it.icon), it.label, actionStartActivity(it.launchIntent), cellModifier)
                            }
                            else -> Box(cellModifier) {}
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Cell(icon: ImageProvider, label: String, onClick: Action, modifier: GlanceModifier) {
        Box(
            modifier = modifier.clickable(onClick),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = icon,
                contentDescription = label,
                modifier = GlanceModifier.size(32.dp),
            )
        }
    }
}
