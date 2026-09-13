package com.timmonet.core

import android.content.Context

/**
 * 深浅色判定（**已废弃的兼容壳**）。
 *
 * ⚠️ 历史问题：这里原本反射调用 TIM 的 QQTheme.isNowThemeIsNight() 判断夜间，
 * 但本模块的 hookForceLight 为了让 TIM 走浅色资源，恰好把那个方法 hook 成了
 * 永远返回 false —— 于是 isNight() 恒为 false，还被 cachedNight 永久缓存，
 * 导致所有 `if (!isNight) return` 的分支变成死代码。
 *
 * 现在统一委托给 [MonetPalette.isDarkNow]（只看模块设置与系统）。新代码请直接
 * 调用 MonetPalette.isDarkNow()，不要再用这个壳。
 */
object ThemeState {

    @Deprecated("Use MonetPalette.isDarkNow() instead")
    fun isNight(context: Context?, hintLoader: ClassLoader? = null): Boolean =
        MonetPalette.isDarkNow()
}
