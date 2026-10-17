package com.vnlingo.app.ui.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 轻量时间格式化助手。 */
object TimeFormats {
    /** 完整日期时间：yyyy-MM-dd HH:mm。ts<=0 返回空串。 */
    fun formatDateTime(timestamp: Long): String {
        if (timestamp <= 0) return ""
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
        }.getOrDefault("")
    }

    /** 日期：yyyy-MM-dd。ts<=0 返回空串。 */
    fun formatDate(timestamp: Long): String {
        if (timestamp <= 0) return ""
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
        }.getOrDefault("")
    }
}