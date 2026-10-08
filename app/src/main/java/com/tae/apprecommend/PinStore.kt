package com.tae.apprecommend

import android.content.Context

/** 사용자가 고정한 앱. 고정한 순서대로 위젯 앞칸에 점수와 상관없이 나온다. */
object PinStore {
    private const val PREFS = "pins"
    private const val KEY = "packages"

    fun get(context: Context): List<String> =
        prefs(context).getString(KEY, "").orEmpty().split(',').filter { it.isNotEmpty() }

    fun isPinned(context: Context, pkg: String) = pkg in get(context)

    /** 고정 상태를 뒤집고, 바뀐 뒤 고정 여부를 돌려준다. */
    fun toggle(context: Context, pkg: String): Boolean {
        val pins = get(context).toMutableList()
        val pinned = if (pkg in pins) {
            pins.remove(pkg)
            false
        } else {
            pins.add(pkg)
            true
        }
        prefs(context).edit().putString(KEY, pins.joinToString(",")).apply()
        return pinned
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
