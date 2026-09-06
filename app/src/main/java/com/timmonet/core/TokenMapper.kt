package com.timmonet.core

import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import java.util.concurrent.ConcurrentHashMap

/**
 * 把 TIM 的语义色 token 映射到莫奈调色板。
 *
 * 输入名称既支持完整的资源名（qui_common_brand_standard），
 * 也支持 QUIUtil 里去掉前缀后的键（brand_standard）。
 */
object TokenMapper {

    private val memo = ConcurrentHashMap<Long, Int>()
    private val inlineMemo = ConcurrentHashMap<Long, Int>()
    private val tintMemoLight = ConcurrentHashMap<String, Int>()
    private val tintMemoDark = ConcurrentHashMap<String, Int>()
    private val roleMemo = ConcurrentHashMap<String, Role>()

    @Volatile
    private var memoGeneration = -1L

    private const val NO_MAPPING = Int.MIN_VALUE

    private enum class Role {
        KEEP,
        PRIMARY,
        PRIMARY_PRESSED,
        PRIMARY_LINK_TINT, // 蓝色气泡上的浅色链接文字
        PRIMARY_SOFT,      // 主色调 tone 90（浅色气泡底）
        PRIMARY_CONTAINER,
        GUEST_BUBBLE,      // 对方气泡（深色模式下要比背景浅一档）
        ON_PRIMARY,
        ON_PRIMARY_CONTAINER,
        ON_SURFACE,
        ON_SURFACE_VARIANT,
        SURFACE,
        BG_NAV_TINT, // 导航栏/品牌浅色底
        BG_PAGE,     // 页面纯白底
        BG_CARD,     // 卡片/白色填充
        BG_LIST,     // 列表灰底
        BG_AIO,      // 聊天页底
        INPUT_BG,    // 输入框/搜索框（比页面浅一档）
        SURFACE_CONTAINER_LOW,
        SURFACE_CONTAINER,
        SURFACE_CONTAINER_HIGH,
        SURFACE_CONTAINER_HIGHEST,
        SURFACE_VARIANT,
        OUTLINE,
        OUTLINE_VARIANT,
        UNKNOWN
    }

    fun mapColor(name: String?, color: Int, dark: Boolean): Int {
        refreshMemoGeneration()
        val key = (
            ((name?.hashCode() ?: 0).toLong() shl 33) xor
                ((color.toLong() and 0xFFFFFFFFL) shl 1) xor
                (if (dark) 0x1L else 0x0L)
            )
        memo[key]?.let { return it }

        val scheme = MonetPalette.palette(dark)
        val mapped = if (name == null) {
            inferByColor(color, scheme)
        } else {
            val role = roleOf(name)
            if (role != null) resolve(role, color, scheme) else inferByColor(color, scheme)
        }

        if (memo.size > 65536) memo.clear()
        memo[key] = mapped
        return mapped
    }

    /** 给 drawable 求目标染色；无法识别的名字返回 null（保持原样）。 */
    fun tintColorFor(name: String, dark: Boolean): Int? {
        val role = roleOf(name) ?: return null
        if (role == Role.KEEP) return null
        refreshMemoGeneration()
        val cache = if (dark) tintMemoDark else tintMemoLight
        cache[name]?.let { return if (it == NO_MAPPING) null else it }
        val color = resolve(role, 0xFF000000.toInt(), MonetPalette.palette(dark))
        cache[name] = color
        if (cache.size > 8192) cache.clear()
        return color
    }

    /**
     * 内联颜色背景（android:background="#F5F5F5" 之类）没有资源名，
     * 按明度映射：白→卡片色、浅灰→列表色、蓝→主色，其余保持不动。
     */
    fun inlineBgColor(color: Int, dark: Boolean): Int? {
        refreshMemoGeneration()
        val key = ((color.toLong() and 0xFFFFFFFFL) shl 1) or (if (dark) 0x1L else 0x0L)
        inlineMemo[key]?.let { return if (it == NO_MAPPING) null else it }
        val result = computeInlineBgColor(color, dark)
        inlineMemo[key] = result ?: NO_MAPPING
        if (inlineMemo.size > 16384) inlineMemo.clear()
        return result
    }

    private fun computeInlineBgColor(color: Int, dark: Boolean): Int? {
        val alpha = color ushr 24
        if (alpha == 0) return null
        val opaque = (color and 0x00FFFFFF) or 0xFF000000.toInt()
        val scheme = MonetPalette.palette(dark)
        return try {
            val hct = Hct.fromInt(opaque)
            val role = when {
                hct.chroma < 8.0 -> when {
                    hct.tone < 60.0 -> return null
                    // 中浅灰/近白（60~100）都是卡片/占位底，统一染成比页面
                    // 浅一档的 surfaceBright；页面级底色由 bg_page 等
                    // 名字规则显式控制，不依赖颜色猜测。
                    hct.tone < 100.0 -> Role.BG_CARD
                    else -> Role.BG_CARD
                }
                hct.hue in 190.0..265.0 && hct.chroma >= 24.0 -> Role.PRIMARY
                else -> return null
            }
            val mapped = resolve(role, opaque, scheme)
            (mapped and 0x00FFFFFF) or (alpha shl 24)
        } catch (t: Throwable) {
            null
        }
    }

    /** 聊天列表条目背景的两种角色色。 */
    fun bgList(dark: Boolean): Int =
        resolve(Role.BG_LIST, 0xFF000000.toInt(), MonetPalette.palette(dark))

    fun bgCard(dark: Boolean): Int =
        resolve(Role.BG_CARD, 0xFF000000.toInt(), MonetPalette.palette(dark))

    /** 对方消息气泡的背景色（免打扰灰泡沿用）。 */
    fun guestBubble(dark: Boolean): Int =
        resolve(Role.GUEST_BUBBLE, 0xFF000000.toInt(), MonetPalette.palette(dark))

    private fun inferByColor(color: Int, scheme: DynamicScheme): Int {
        val alpha = color ushr 24
        if (alpha == 0) return color
        val opaque = (color and 0x00FFFFFF) or 0xFF000000.toInt()
        return try {
            val hct = Hct.fromInt(opaque)
            val mapped = when {
                hct.chroma < 8.0 -> {
                    val tone = hct.tone
                    // 纯黑纯白一般是图片上的蒙层文字，保持不动
                    if (tone < 4.0 || tone > 96.0) return color
                    grayToRole(tone, scheme)
                }
                hct.hue in 190.0..265.0 && hct.chroma >= 24.0 ->
                    if (hct.tone < 18.0) scheme.onSurface else scheme.primary
                else -> return color
            }
            (mapped and 0x00FFFFFF) or (alpha shl 24)
        } catch (t: Throwable) {
            color
        }
    }

    private fun grayToRole(tone: Double, scheme: DynamicScheme): Int = when {
        tone <= 12.0 -> scheme.onSurface
        tone <= 32.0 -> scheme.onSurfaceVariant
        tone <= 55.0 -> scheme.outline
        tone <= 76.0 -> scheme.outlineVariant
        // 76 以上灰按“面”处理（卡片/底），AMOLED 黑时压成纯黑
        tone <= 87.0 -> MonetPalette.amoledBlack(scheme.surfaceContainerHigh)
        else -> MonetPalette.amoledBlack(scheme.surfaceContainer)
    }

    /** AMOLED 黑模式下要整体压成纯黑的“表面/背景”角色（文字/图标角色不在内）。 */
    private val AMOLED_SURFACES = setOf(
        Role.SURFACE,
        Role.BG_NAV_TINT,
        Role.BG_PAGE,
        Role.BG_LIST,
        Role.BG_AIO,
        Role.BG_CARD,
        Role.INPUT_BG,
        Role.GUEST_BUBBLE,
        Role.SURFACE_CONTAINER_LOW,
        Role.SURFACE_CONTAINER,
        Role.SURFACE_CONTAINER_HIGH,
        Role.SURFACE_CONTAINER_HIGHEST,
        Role.SURFACE_VARIANT
    )

    private fun resolve(role: Role, original: Int, scheme: DynamicScheme): Int {
        if (role == Role.KEEP || role == Role.UNKNOWN) return original
        val alpha = original ushr 24
        // AMOLED 纯黑：所有表面槽位置黑
        if (MonetPalette.isAmoled() && role in AMOLED_SURFACES) {
            return 0xFF000000.toInt() or (alpha shl 24)
        }
        val c = when (role) {
            Role.KEEP -> return original
            Role.UNKNOWN -> return original
            Role.PRIMARY -> scheme.primary
            Role.PRIMARY_PRESSED -> scheme.primaryPalette.tone(if (scheme.isDark) 70 else 30)
            // 自己气泡里的链接：直接用对方气泡的背景色（深浅各自取 GUEST_BUBBLE 同款）
            Role.PRIMARY_LINK_TINT ->
                if (MonetPalette.isAmoled()) scheme.primary
                else if (scheme.isDark) scheme.surfaceBright else scheme.surface
            Role.PRIMARY_SOFT -> scheme.primaryPalette.tone(if (scheme.isDark) 30 else 90)
            Role.PRIMARY_CONTAINER -> scheme.primaryContainer
            Role.GUEST_BUBBLE ->
                if (scheme.isDark) scheme.surfaceBright else scheme.surface
            Role.ON_PRIMARY -> scheme.onPrimary
            Role.ON_PRIMARY_CONTAINER -> scheme.onPrimaryContainer
            Role.ON_SURFACE -> scheme.onSurface
            Role.ON_SURFACE_VARIANT -> scheme.onSurfaceVariant
            Role.SURFACE -> scheme.surface
            Role.BG_NAV_TINT -> scheme.surfaceContainer
            Role.BG_PAGE -> scheme.surfaceContainer
            Role.BG_CARD -> scheme.surfaceBright
            Role.BG_LIST -> scheme.surfaceContainer
            Role.BG_AIO -> scheme.surfaceContainer
            // 输入栏/搜索框：亮色下用 surfaceBright（比页面浅一档），
            // 深色下用 surfaceContainerHigh，避免在深色页面里显得过亮
            Role.INPUT_BG ->
                if (scheme.isDark) scheme.surfaceContainerHigh else scheme.surfaceBright
            Role.SURFACE_CONTAINER_LOW -> scheme.surfaceContainerLow
            Role.SURFACE_CONTAINER -> scheme.surfaceContainer
            Role.SURFACE_CONTAINER_HIGH -> scheme.surfaceContainerHigh
            Role.SURFACE_CONTAINER_HIGHEST -> scheme.surfaceContainerHighest
            Role.SURFACE_VARIANT -> scheme.surfaceVariant
            Role.OUTLINE -> scheme.outline
            Role.OUTLINE_VARIANT -> scheme.outlineVariant
        }
        return (c and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun roleOf(name: String): Role? {
        val n = name.lowercase()
        val cached = roleMemo[n]
        if (cached != null) return if (cached == Role.UNKNOWN) null else cached
        val computed = computeRole(n)
        roleMemo[n] = computed ?: Role.UNKNOWN
        if (roleMemo.size > 4096) roleMemo.clear()
        return computed
    }

    private fun computeRole(n: String): Role? {
        // 不参与莫奈取色的固定语义色
        if (n.contains("mask") ||
            n.contains("feedback_error") ||
            n.contains("feedback_warning") ||
            n.contains("feedback_success") ||
            n.contains("allwhite") ||
            n.contains("allblack")
        ) return Role.KEEP

        // 红点素材（"…"按钮右上角闪烁红点 RedDotImageView 等复用 skin_tips_dot）
        if (n.contains("tips_dot")) return Role.PRIMARY

        // 群公告气泡（troop aiosm）：标题黑字 → onSurface，正文灰字 → onSurfaceVariant
        if (n.contains("troop_aiosm_title")) return Role.ON_SURFACE
        if (n.contains("troop_aiosm_content")) return Role.ON_SURFACE_VARIANT
        // AIO 右上角“新文件/有人@我/N条消息”角标的 “^” 形图标：
        // 6u=浅色蓝版、6v=白版；角标整体统一成 primary 底 + onPrimary 前景。
        if (n == "6u" || n == "6v") return Role.ON_PRIMARY
        // 个人资料卡（更多资料）卡片底：与设置页选项列表一致（surfaceBright）
        if (n == "aea" || n == "aeb") return Role.BG_CARD
        if (n == "aeq" || n == "a9w") return Role.BG_CARD
        if (n.contains("qq_profilecard_info_bg") ||
            n.contains("qq_profilecard_foot_bg")
        ) return Role.BG_CARD
        // 未读气泡背景：免打扰灰泡用对方气泡色，红泡/顶栏泡用 primary
        if (n.contains("gray_unread")) return Role.GUEST_BUBBLE
        if (n.contains("unread_bg")) return Role.PRIMARY

        // 品牌 / 强调色
        if (n.contains("feedback_normal")) return Role.PRIMARY
        if (n.contains("brand_standard")) return Role.PRIMARY
        if (n.contains("brand_light")) return Role.PRIMARY
        if (n.contains("on_brand_primary")) return Role.ON_PRIMARY
        if (n.contains("text_link")) return Role.PRIMARY

        // AMOLED 黑（仅此模式）：自己气泡整体纯黑、与对方一致，
        // 内部文字统一亮色（onSurface）、链接用亮主色，保证黑底对比度
        if (MonetPalette.isAmoled()) {
            if (n.contains("bubble_host_text_link")) return Role.PRIMARY_LINK_TINT
            if (n.contains("bubble_host_text")) return Role.ON_SURFACE
            if (n.contains("bubble_host") || n.contains("user_bubble")) {
                return Role.GUEST_BUBBLE
            }
        }
        // 聊天气泡：自己发的蓝色气泡
        if (n.contains("bubble_host_text_primary")) return Role.ON_PRIMARY
        if (n.contains("bubble_host_text_secondary")) return Role.ON_PRIMARY
        if (n.contains("bubble_host_text_link")) return Role.PRIMARY_LINK_TINT
        if (n.contains("bubble_host_text")) return Role.ON_PRIMARY
        if (n.contains("bubble_host_top") || n.contains("bubble_host_bottom")) return Role.PRIMARY
        if (n.contains("bubble_host")) return Role.PRIMARY
        if (n.contains("bubble_guest_text_primary")) return Role.ON_SURFACE
        if (n.contains("bubble_guest_text_secondary")) return Role.ON_SURFACE_VARIANT
        if (n.contains("bubble_guest_text")) return Role.ON_SURFACE
        if (n.contains("bubble_guest")) return Role.GUEST_BUBBLE
        // 经典皮肤的气泡九宫格（friend=对方白底，user=自己蓝底）
        if (n.contains("friend_bubble")) return Role.GUEST_BUBBLE
        if (n.contains("user_bubble")) return Role.PRIMARY

        // 按钮
        if (n.contains("button_bg_primary_pressed")) return Role.PRIMARY_PRESSED
        if (n.contains("button_bg_primary")) return Role.PRIMARY
        if (n.contains("button_border_primary_outline")) return Role.PRIMARY
        if (n.contains("button_text_primary_outline")) return Role.PRIMARY
        if (n.contains("button_text_primary")) return Role.ON_PRIMARY
        if (n.contains("button_text_secondary")) return Role.ON_SURFACE
        if (n.contains("button_bg_secondary") ||
            n.contains("button_bg_ghost") ||
            n.contains("button_text_ghost")
        ) return Role.KEEP

        // 文字
        if (n.contains("text_primary")) return Role.ON_SURFACE
        if (n.contains("text_secondary")) return Role.ON_SURFACE_VARIANT
        if (n.contains("text_tertiary")) return Role.OUTLINE
        if (n.contains("text_nav_primary") ||
            n.contains("text_tabbar_primary") ||
            n.contains("text_nav_secondary")
        ) return Role.ON_SURFACE

        // 经典皮肤文字色（搜索条目等布局直接引用 @color/skin_black_theme_version2，
        // 深色下仍返回 #03081A 近黑）：黑字→onSurface，灰字→onSurfaceVariant
        if (n.startsWith("skin_black")) return Role.ON_SURFACE
        if (n.startsWith("skin_gray")) return Role.ON_SURFACE_VARIANT
        // 输入栏“按住 说话”文字（TimAIOInputSimpleUIVBDelegate 使用
        // skin_input_theme_version2）：与“说点什么...”提示（text_secondary）同色。
        if (n.contains("skin_input_theme")) return Role.ON_SURFACE_VARIANT
        // 输入 @ 弹出的成员列表面板底色（gg5/4q 两个 shape 都引用
        // skin_aio_at_white_for_theme=#FFFFFF）：按卡片色 surfaceBright 处理。
        if (n.contains("aio_at_white_for_theme")) return Role.BG_CARD

        // 图标：选中/激活走主色，其余走中性色
        if (n.contains("icon_aio_toolbar_active") ||
            n.contains("icon_aio_nav_active") ||
            n.contains("icon_tabbar_active") ||
            n.contains("icon_active")
        ) return Role.PRIMARY
        if (n.contains("icon_press")) return Role.ON_SURFACE_VARIANT
        if (n.contains("icon_primary")) return Role.ON_SURFACE
        if (n.contains("icon_secondary")) return Role.ON_SURFACE_VARIANT
        if (n.contains("icon_tertiary")) return Role.OUTLINE
        if (n.contains("icon_white")) return Role.KEEP
        if (n.contains("icon_nav_primary") ||
            n.contains("icon_nav_secondary") ||
            n.contains("icon_aio_toolbar_normal") ||
            n.contains("icon_tabbar_primary")
        ) return Role.ON_SURFACE
        if (n.contains("icon_")) return Role.ON_SURFACE

        // 背景
        if (n.contains("bg_nav_primary") || n.contains("bg_bottom_brand")) return Role.BG_NAV_TINT
        if (n.contains("bg_top_dark") || n.contains("bg_bottom_dark")) return Role.KEEP
        if (n.contains("bg_page_secondary")) return Role.BG_LIST
        if (n.contains("bg_page_teriary")) return Role.BG_AIO
        if (n.contains("bg_page")) return Role.BG_PAGE
        if (n.contains("bg_setting") || n.contains("setting_me")) return Role.BG_PAGE
        if (n.contains("bg_primary")) return Role.BG_PAGE
        if (n.contains("bottom_bar_background_gradient")) return Role.BG_PAGE
        if (n.contains("bg_top_light_pressed")) return Role.BG_LIST
        // QUI 的 bg_top/bottom/middle_light 是页面上的浅色卡片层，
        // 应跟随卡片色 surfaceBright，而不是页面底色。
        if (n.contains("bg_bottom_light") ||
            n.contains("bg_top_light") ||
            n.contains("bg_middle_light")
        ) return Role.BG_CARD
        if (n.contains("bg_middle_standard")) return Role.BG_LIST
        if (n.contains("bg_aio")) return Role.BG_AIO
        if (n.contains("bg_bottom_standard")) return Role.BG_LIST
        if (n.contains("bg_nav_bottom_aio")) return Role.INPUT_BG
        if (n.contains("bg_nav_bottom") ||
            n.contains("bg_nav_aio") ||
            n.contains("bg_nav_secondary")
        ) return Role.BG_LIST

        // 填充
        if (n.contains("fill_light_primary_stick")) return Role.BG_NAV_TINT
        if (n.contains("fill_standard_brand")) return Role.PRIMARY
        if (n.contains("fill_light_primary")) return Role.BG_CARD
        // 设置页 QUIListItem 等“二级填充”行背景：TIM 亮色下和
        // fill_light_primary 同为白色卡片，深色莫奈也应同为卡片色。
        if (n.contains("fill_light_secondary")) return Role.BG_CARD
        if (n.contains("fill_light_tertiary")) return Role.BG_LIST
        if (n.contains("fill_standard")) return Role.SURFACE_VARIANT

        // 白色/卡片底（Ark 聊天记录卡等直接用 white/bg_card 语义 token）
        if (n.contains("bg_white") ||
            n.contains("bg_card") ||
            n.contains("card_bg") ||
            n.contains("white_bg")
        ) return Role.BG_CARD

        // 边框
        if (n.contains("border_superlight")) return Role.SURFACE_CONTAINER_HIGHEST
        if (n.contains("border_light")) return Role.OUTLINE_VARIANT
        if (n.contains("border_standard")) return Role.OUTLINE_VARIANT

        // 覆盖层 / 蒙层 / 固定白
        if (n.contains("overlay_") || n.contains("text_white")) return Role.KEEP

        // 进度条
        if (n.contains("progressbar_played") || n.contains("progressbar_rate")) return Role.PRIMARY
        if (n.contains("progressbar_dot")) return Role.ON_SURFACE
        if (n.contains("progressbar_bg")) return Role.SURFACE_VARIANT

        // 卡片
        if (n.contains("cardlist_card_bg")) return Role.BG_CARD
        if (n.contains("text_disable")) return Role.ON_SURFACE_VARIANT

        // 底栏图标（qui_tab_*，选中态是烘焙的蓝色 PNG/矢量，需要染色）
        if (n.startsWith("qui_tab_")) {
            return if (n.contains("pressed")) Role.PRIMARY else Role.ON_SURFACE
        }

        // AIO 输入栏背景 drawable
        if (n.contains("aio_input") || n.contains("input_full_screen") || n.contains("searchbar")) {
            return Role.INPUT_BG
        }

        // 经典皮肤的列表项背景
        // 群卡片（新朋友/群通知）与置顶项是白卡，普通聊天条目是浅灰
        if (n.contains("group_list_item_normal") || n.contains("item_sticky_normal")) {
            return Role.BG_CARD
        }
        if (n.contains("list_item_normal")) return Role.BG_LIST
        if (n.contains("group_list_item_pressed") ||
            n.contains("item_sticky_pressed") ||
            n.contains("list_item_pressed")
        ) return Role.BG_LIST

        // 未知 token 按颜色本身推断
        return null
    }

    /** 调色板重建后统一失效所有结果缓存。 */
    private fun refreshMemoGeneration() {
        val generation = MonetPalette.generation()
        if (memoGeneration == generation) return
        synchronized(this) {
            if (memoGeneration == generation) return
            memo.clear()
            inlineMemo.clear()
            tintMemoLight.clear()
            tintMemoDark.clear()
            memoGeneration = generation
        }
    }
}
