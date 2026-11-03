package com.bilitv.app.util

import android.content.Context
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat

/** Localized resources for formatters, network errors and non-composable callbacks. */
object Strings {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun get(@StringRes id: Int, vararg args: Any?): String {
        val context = ContextCompat.getContextForLanguage(appContext)
        return if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
    }
}
