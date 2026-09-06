package com.timmonet.core

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 判断 TIM 当前是否处于夜间模式。
 *
 * 优先询问 TIM 自己的 QQTheme.isNowThemeIsNight()（跟随 TIM 的主题开关），
 * 失败时退回系统深色模式。
 */
object ThemeState {

    private const val QQ_THEME_CLASS = "com.tencent.mobileqq.utils.QQTheme"

    private val classCache = ConcurrentHashMap<ClassLoader, Class<*>>()
    private val methodCache = ConcurrentHashMap<Class<*>, Method?>()

    @Volatile
    private var cachedNight: Boolean? = null

    fun isNight(context: Context?, hintLoader: ClassLoader? = null): Boolean {
        cachedNight?.let { return it }
        cachedNight = computeNight(context, hintLoader)
        return cachedNight ?: false
    }

    private fun computeNight(context: Context?, hintLoader: ClassLoader?): Boolean {
        val cl = context?.classLoader ?: hintLoader ?: Thread.currentThread().contextClassLoader
        if (cl == null) return systemNight(context)

        // 直接调用 TIM 的主题判断
        val cls = try {
            classCache[cl] ?: Class.forName(QQ_THEME_CLASS, false, cl).also { classCache[cl] = it }
        } catch (t: Throwable) {
            null
        }
        if (cls != null) {
            val method = methodCache.getOrPut(cls) {
                try {
                    cls.getMethod("isNowThemeIsNight")
                } catch (t: Throwable) {
                    null
                }
            }
            if (method != null) {
                try {
                    return method.invoke(null) as? Boolean ?: systemNight(context)
                } catch (t: Throwable) {
                    // fall through
                }
            }
        }
        return systemNight(context)
    }

    private fun systemNight(context: Context?): Boolean {
        if (context == null) return false
        return try {
            val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            (ui?.nightMode ?: Configuration.UI_MODE_NIGHT_UNDEFINED) == Configuration.UI_MODE_NIGHT_YES
        } catch (t: Throwable) {
            false
        }
    }
}
