package com.timmonet.core

import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable

/**
 * 颜色位运算、"就地改色"与**实例级缓存**的统一入口。
 *
 * 这些式子在本仓库里手写过 30+ 遍（`(c and 0x00FFFFFF) or (alpha shl 24)`、
 * `c ushr 24`、成对的 `setColorFilter` + `setTint`）。手写的问题不是啰嗦，而是
 * **容易漏**：漏掉 alpha 位就是"半透明蒙层变不透明"（C1 那次事故），
 * 漏掉 `setColorFilter` 就是"皮肤引擎的 drawable 不生效"。收敛到这里之后，
 * 讨论"要不要保 alpha""要不要双写"只需要在一个地方进行。
 */
object ColorMath {

    /** 全透明：任何映射都必须原样放过（约定见 AGENTS.md）。 */
    fun isTransparent(color: Int): Boolean = (color ushr 24) == 0

    /** alpha 通道（0..255）。 */
    fun alpha(color: Int): Int = color ushr 24

    /** 丢掉 alpha，其余位保留（用于亮度/彩度判断，避免 alpha 影响比较）。 */
    fun opaque(color: Int): Int = (color and 0x00FFFFFF) or 0xFF000000.toInt()

    /**
     * 取 [mapped] 的 RGB、取 [source] 的 alpha —— 把角色色套回原色的透明度。
     *
     * ⚠️ 必须用 `and 0x00FFFFFF` 清掉 [mapped] 自带的 alpha 位再 `or`。
     * 写成 `0xFF000000 or (alpha shl 24)` 会因为 `0xFF000000` 的 alpha 位已全 1
     * 而**永远得到不透明黑**（TokenMapper 的 AMOLED 分支踩过这个坑）。
     */
    fun keepAlpha(mapped: Int, source: Int): Int =
        (mapped and 0x00FFFFFF) or (alpha(source) shl 24)

    /** 同上，但直接给 alpha（0..255）。 */
    fun withAlpha(mapped: Int, alpha: Int): Int =
        (mapped and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    /**
     * **就地**把 drawable 改成纯色，保留它原本的形状（圆角 / 九宫格留白 / 描边 /
     * 多状态）。
     *
     * ⚠️ 为什么必须双写 [Drawable.setColorFilter] 与 [Drawable.setTint]：
     * TIM 的皮肤引擎 drawable（`SkinnableBitmapDrawable` / `SkinnableNinePatchDrawable`）
     * **直接继承 `Drawable` 而不是 `BitmapDrawable`**，它们只重写了
     * `setColorFilter`（转发给自己的 `Paint`，见 TIM 反编译 `SkinnableBitmapDrawable.java:583`），
     * 而 `setTint` 是基类实现、对它们**完全不生效**；反过来，普通
     * `BitmapDrawable` / `NinePatchDrawable` 走的是 tint 管线。少写任何一个，
     * 都会有一半 drawable 静默不变色。
     *
     * 调用方通常还应在改色前自行判断"这个颜色是否需要改"，本函数不做判据。
     *
     * @return 是否至少成功设置了一种着色方式。
     */
    fun recolorInPlace(drawable: Drawable, color: Int): Boolean {
        var ok = false
        runCatching { drawable.mutate() }
        runCatching {
            drawable.setColorFilter(PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN))
        }.onSuccess { ok = true }
        runCatching { drawable.setTint(color) }
            .onSuccess { ok = true }
        return ok
    }

    /**
     * **按对象实例**记忆化的缓存，用于"同一个 drawable / View 反复经过热路径"的场景。
     *
     * 一次解决三个历史上各错各的问题：
     *
     * 1. **键用对象本身，不用 `System.identityHashCode()`。**
     *    用 Int 做键时对象被 GC 后哈希值会被新对象复用 —— 新 drawable 会被当成
     *    "已经染过/已经取样过"而跳过，表现为"偶尔有个图标不变色"，且随 GC 时机
     *    变化、无法稳定复现。`WeakHashMap` 用对象身份比较，条目随键一起消失。
     * 2. **线程安全。** 这些表由 `setBackground` / `setImageDrawable` 这类 hook
     *    写入，而 hook 可能跑在图片加载线程上；裸 `HashMap` 并发写会丢更新。
     * 3. **不需要手写容量上限。** 键被回收时条目自动消失；此前
     *    `if (size > N) clear()` 的写法会连刚写入的自身一起清掉，等于整表失效。
     *
     * ⚠️ 键必须是**身份语义**的对象（Drawable / View）。`String` 这类重写了
     * `equals` 的键会按值相等合并，不是本函数想要的语义。
     */
    fun <V> instanceCache(): MutableMap<Any, V> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, V>())

    /**
     * **按对象实例**记住"这个对象"的集合（弱引用，线程安全）。
     *
     * 用于"这些 View / Drawable 已经处理过、要特殊对待"的场景。相比
     * `newKeySet<Int>()` 存 `identityHashCode` 的写法：
     * - 对象被回收后条目自动消失，不会把哈希复用给新对象（否则新对象会被误判成
     *   "已处理"）；
     * - 不再需要 `if (size > N) clear()` —— 那种写法会把**存活的**条目一起清掉，
     *   实测表现为"超过一定数量后，原本受保护的控件突然失去保护"。
     */
    fun <T : Any> weakIdentitySet(): MutableSet<T> =
        java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(java.util.WeakHashMap<T, Boolean>())
        )

}
