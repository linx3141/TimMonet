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

        val scheme = MonetPalette.palette()
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
        val color = resolve(role, 0xFF000000.toInt(), MonetPalette.palette())
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

    /**
     * TIM 品牌蓝（精确常量；取自 TIM 资源与 token dump 的 brand / 选中态）。
     *
     * 自己发送的气泡就是 primary，因此这些品牌底在**两种主题下**都必须映射成
     * primary：相册选中图片的圆形序号底、预览页"原图"勾选底等。
     * 注意这是"精确常量匹配"，不是按色相/明度猜主色。
     */
    val BRAND_BLUES = setOf(
        0xFF0099FF.toInt(), // button_bg_primary_default / bubble_host_bottom / brand_standard
        0xFF1E6FFE.toInt(),
        0xFF2D77E5.toInt(),
        0xFF4D94FF.toInt(),
        0xFF12B7F5.toInt()
    )

    /** 品牌蓝（含半透明变体）-> primary，保留原 alpha；非品牌蓝返回 null。 */
    private fun brandBlueRole(color: Int, scheme: DynamicScheme): Int? {
        val opaque = ColorMath.opaque(color)
        if (opaque !in BRAND_BLUES) return null
        return ColorMath.keepAlpha(scheme.primary, color)
    }

    private fun computeInlineBgColor(color: Int, dark: Boolean): Int? {
        // 全透明：不参与任何映射（约定见 AGENTS.md），调用方按"无映射"处理
        if (ColorMath.isTransparent(color)) return null
        val alpha = ColorMath.alpha(color)
        val opaque = ColorMath.opaque(color)
        val scheme = MonetPalette.palette()
        return try {
            // 位图/代码创建的品牌底没有资源名可依据(相册选中序号是烤进位图的
            // #0099FF，QUICheckBox 的圆底是代码设的 drawable)，只能按精确常量
            // 识别成 primary；否则它们会一直是 TIM 蓝，与自己气泡不同色
            brandBlueRole(color, scheme)?.let { return it }
            val hct = Hct.fromInt(opaque)
            val role = when {
                // 只做"白/浅灰 -> 卡片面"这一档明确映射(内联背景色没有
                // 资源名可依据);不再按色相猜测主色,避免把彩色元素当品牌色染
                hct.chroma < BgResolver.HCT_CHROMA_MAX -> when {
                    hct.tone < BgResolver.TONE_TEXT_MAX -> return null
                    else -> Role.BG_CARD
                }
                else -> return null
            }
            val mapped = resolve(role, opaque, scheme)
            ColorMath.keepAlpha(mapped, color)
        } catch (t: Throwable) {
            null
        }
    }

    /** drawable **背景**按值映射（背景语义，比 inlineBgColor 宽）。
     *
     *  与 inlineBgColor 的区别：内联颜色无从判断语义，只能保守地"白/浅灰才动"；
     *  这里是 drawable 背景（ColorDrawable / GradientDrawable / 位图底），语义明确，
     *  所以**无彩色一律映射到面色** —— TIM 深色主题里的 #36393E 这类"深灰面"
     *  (钱包页下半屏就是它) 按老规则 tone<60 被判成文字色、直接放过，
     *  结果一个页面被切成四段颜色。彩色一律不碰。
     */
    fun bgColorForDrawable(color: Int, dark: Boolean): Int? {
        // 全透明：不参与任何映射（约定见 AGENTS.md）
        if (ColorMath.isTransparent(color)) return null
        val alpha = ColorMath.alpha(color)
        // 品牌蓝**底**映射到"对方气泡背景"色，而不是 primary：
        // 钱包页顶栏(?attr/a_5 → @color/2p #0099FF)整条都是这个底，
        // 用 primary 会亮得刺眼；换成 guest bubble 后和聊天里收到的那侧气泡同色，
        // 白色数字/文字压在上面也刚好可读(内联颜色路径仍映射 primary，
        // 相册选中序号那种"品牌底+白字"的用法不受影响)。
        // ⚠️ 判据与取色必须**同源**：深浅一律取 scheme.isDark（= 模块设置的
        // effectiveDark），不再相信调用方传进来的 dark —— 历史上这里用入参判断
        // "亮底压暗"、颜色却来自 effectiveDark，两者可以不一致，导致同一个颜色
        // 有时被压暗有时不被压暗（"时好时坏"型 bug 的典型）。
        val scheme = MonetPalette.palette()
        brandBlueRole(color, scheme)?.let {
            return ColorMath.keepAlpha(guestBubble(scheme.isDark), color)
        }
        val opaque = ColorMath.opaque(color)
        val hct = try {
            Hct.fromInt(opaque)
        } catch (t: Throwable) {
            return null
        }
        // 深色主题下"很亮的背景"：TIM 亮色主题遗留的浅色底，一律压成页面底色，
        // 即使它带彩色 —— 文件页"本机"tab 顶部的微云入口条就是个浅蓝
        // ColorDrawable(#CDEEFE)，以前因为 chroma>=8 被整条放过，于是黑页面上
        // 留了一根刺眼的亮条（亮底 + 亮字，几乎看不清）。
        // 注意：这条**故意不排除彩色**（浅蓝的微云入口条就是靠它压暗的），
        // 所以用 BgResolver.luma 而不是 isLightLeftover（后者含彩色排除）。
        if (scheme.isDark && BgResolver.luma(opaque) > BgResolver.BRIGHT) {
            return bgPage(true)
        }
        if (hct.chroma >= BgResolver.HCT_CHROMA_MAX) return null
        val mapped = if (hct.tone < BgResolver.TONE_CONTAINER_SPLIT) {
            MonetPalette.amoledBlack(scheme.surfaceContainerHigh)
        } else {
            MonetPalette.amoledBlack(scheme.surfaceContainer)
        }
        return ColorMath.keepAlpha(mapped, color)
    }

    /** 聊天列表条目背景的两种角色色。 */
    fun bgList(dark: Boolean): Int =
        resolve(Role.BG_LIST, 0xFF000000.toInt(), MonetPalette.palette())

    /** 页面底色（BG_PAGE）：给"未选中底"这类需要跟页面融为一体的地方用。 */
    fun bgPage(dark: Boolean): Int =
        resolve(Role.BG_PAGE, 0xFF000000.toInt(), MonetPalette.palette())

    /** 深色下可见的"线"色（分隔线/边框）：给分隔线类资源用。 */
    fun outlineVariant(dark: Boolean): Int =
        resolve(Role.OUTLINE_VARIANT, 0xFF000000.toInt(), MonetPalette.palette())

    fun bgCard(dark: Boolean): Int =
        resolve(Role.BG_CARD, 0xFF000000.toInt(), MonetPalette.palette())

    /** 对方消息气泡的背景色（免打扰灰泡沿用）。 */
    fun guestBubble(dark: Boolean): Int =
        resolve(Role.GUEST_BUBBLE, 0xFF000000.toInt(), MonetPalette.palette())

    private fun inferByColor(color: Int, scheme: DynamicScheme): Int {
        // 全透明：原样返回（约定见 AGENTS.md）
        if (ColorMath.isTransparent(color)) return color
        val alpha = ColorMath.alpha(color)
        val opaque = ColorMath.opaque(color)
        return try {
            // 精确品牌蓝 -> primary(与内联背景色、文字色路径保持同一规则)
            brandBlueRole(color, scheme)?.let { return it }
            val hct = Hct.fromInt(opaque)
            val mapped = when {
                hct.chroma < BgResolver.HCT_CHROMA_MAX -> {
                    val tone = hct.tone
                    // 纯黑纯白一般是图片上的蒙层文字，保持不动
                    if (tone < GRAY_LADDER_MIN || tone > GRAY_LADDER_MAX) return color
                    grayToRole(tone, scheme)
                }
                // 不再按色相把彩色猜成主色(会误染彩色元素)
                else -> return color
            }
            ColorMath.keepAlpha(mapped, color)
        } catch (t: Throwable) {
            color
        }
    }

    // 无彩色的 HCT tone 阶梯：**本项目唯一的"灰阶 -> 角色"映射**。
    // 越过 96 之后的灰按"面"处理（卡片/底），AMOLED 黑时压成纯黑。
    // 上下界之外的纯黑/纯白一般是图片上的蒙层文字，调用方保持原色。
    private const val GRAY_LADDER_MIN = 4.0
    private const val GRAY_LADDER_MAX = 96.0
    private const val GRAY_ON_SURFACE_MAX = 12.0
    private const val GRAY_ON_SURFACE_VARIANT_MAX = 32.0
    private const val GRAY_OUTLINE_MAX = 55.0
    private const val GRAY_OUTLINE_VARIANT_MAX = 76.0
    private const val GRAY_CONTAINER_HIGH_MAX = 87.0

    private fun grayToRole(tone: Double, scheme: DynamicScheme): Int = when {
        tone <= GRAY_ON_SURFACE_MAX -> scheme.onSurface
        tone <= GRAY_ON_SURFACE_VARIANT_MAX -> scheme.onSurfaceVariant
        tone <= GRAY_OUTLINE_MAX -> scheme.outline
        tone <= GRAY_OUTLINE_VARIANT_MAX -> scheme.outlineVariant
        tone <= GRAY_CONTAINER_HIGH_MAX ->
            MonetPalette.amoledBlack(scheme.surfaceContainerHigh)
        else -> MonetPalette.amoledBlack(scheme.surfaceContainer)
    }

    /** 文字/前景类角色:统一不透明(消除 TIM 次要文字自带的 #8C 等 alpha)。 */
    private val TEXT_ROLES = setOf(
        Role.ON_SURFACE,
        Role.ON_SURFACE_VARIANT,
        Role.ON_PRIMARY,
        Role.ON_PRIMARY_CONTAINER
    )

    /**
     * "表面/背景"类角色。
     *
     * 两处用它，语义各自不同但**清单必须一致**，所以只定义一次：
     * 1. AMOLED 黑模式下这些角色整体压成纯黑（文字/图标角色不在内）；
     * 2. 判断"这个颜色是不是我们已经铺过的面"（见 [isSurfaceColorOfScheme]）。
     *
     * ⚠️ 以前这两处各抄了一份一模一样的 13 项清单，加角色时只改一处就会让
     * "AMOLED 压黑"和"判已染"对同一个角色给出不同答案。
     */
    private val SURFACE_ROLES = listOf(
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
        val alpha = ColorMath.alpha(original)
        // ⚠️ **半透明纯黑 = 遮罩/蒙层，原样保留**（与"全透明不参与映射"同一类约定）。
        //
        // 为什么必须在这里短路：下面的 `TEXT_ROLES` 分支会**强制 alpha = 0xFF**
        // （文字色不该半透明），可"纯黑 + 半透明"在 TIM 里是**压暗遮罩**的惯用写法
        // （首页右上角菜单、各种弹层）。一旦按名字落进文字角色，遮罩就被刷成
        // 不透明的 `onSurface` —— 下方内容全被盖死（用户实测：首页菜单遮罩
        // 变成实心 `#FFE6E4F0`）。
        // 注意只认"RGB 全黑"的：半透明白/灰是另一类叠加层（如 30% 白），
        // 它们必须继续参与映射，否则深色主题下会留下一块白。
        if (alpha != 0xFF && (original and 0x00FFFFFF) == 0) return original
        // AMOLED 纯黑：所有表面槽位置黑
        if (MonetPalette.isAmoled() && role in SURFACE_ROLES) {
            // ⚠️ 全透明的槽位按约定**原样返回**。以前这里写 `return 0`，虽然
            // "透明色"的 RGB 本来就是 0、结果恰好一样，但读起来像另一个规则，
            // 而且和 MonetPalette.amoledBlack() 的 `return color` 注释上自称
            // "同一语义"、代码却不一样。现在两边都走同一条约定。
            if (ColorMath.isTransparent(original)) return original
            // 只保留纯黑：不能写 `0xFF000000 or (alpha shl 24)` —— 0xFF000000 的
            // alpha 位已全是 1，或运算结果恒为 0xFF000000，半透明表面
            // （蒙层/遮罩/#80FFFFFF）会变成**不透明纯黑**把内容盖死（C1 那次事故）。
            return ColorMath.withAlpha(0xFF000000.toInt(), alpha)
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
        // 文字/前景类角色强制不透明:TIM 的次要文字色自带 alpha(实测
        // "我的"页说明文字映射后为 #8C87B0CC,alpha=140),保留下来在深色
        // 主题里显得暗淡。背景类仍保留原 alpha(半透明蒙层是设计需要)。
        val outAlpha = if (role in TEXT_ROLES) 0xFF else alpha
        return ColorMath.withAlpha(c, outAlpha)
    }

    private fun roleOf(name: String): Role? {
        val n = name.lowercase()
        val cached = roleMemo[n]
        if (cached != null) return if (cached == Role.UNKNOWN) null else cached
        var computed = computeRole(n)
        // 兜底：名字是"背景/条/横幅/填充"语义的资源，不该落到**前景色**角色。
        // computeRole 的顺序是 文字 -> 图标 -> 背景，名字里同时含 bg/banner 和
        // text_/icon_ 的资源会先被前者截胡成 onSurface，渲染出来就是一条亮底
        // 配亮字（文件页的微云入口条 #CDEEFE 就是这样，几乎看不清）。这类名字
        // 一律按背景处理。
        if ((computed == Role.ON_SURFACE || computed == Role.ON_SURFACE_VARIANT) &&
            isBackgroundLike(n)
        ) {
            if (bgRoleFixLog++ < 25) {
                android.util.Log.i(
                    "TimMonet",
                    "bg-like role fixed: $n $computed -> BG_LIST"
                )
            }
            computed = Role.BG_LIST
        }
        roleMemo[n] = computed ?: Role.UNKNOWN
        if (roleMemo.size > 4096) roleMemo.clear()
        return computed
    }

    /** 名字是"背景/条/横幅/填充"语义（排除纯文字/字体资源）。 */
    private fun isBackgroundLike(n: String): Boolean {
        if (n.contains("text") || n.contains("font")) return false
        return n.contains("_bg") || n.contains("bg_") || n.contains("background") ||
            n.contains("banner") || n.contains("_bar") || n.contains("fill") ||
            n.contains("panel") || n.contains("_strip")
    }

    private var bgRoleFixLog = 0

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

        // 沉浸式顶栏底（ImmersiveTitleBar -> R.color.skin_color_title_immersive_bar）：
        // 这是**背景**色，跟随页面底。以前它不在名字白名单里，深色皮肤给的深色
        // 被"纯黑 -> onSurface"兜底成亮色 (#CCE9FF)，顶栏/状态栏与下方内容割裂
        // （AMOLED 下最明显）。这一条覆盖所有用 ImmersiveTitleBar 的页面。
        if (n.contains("title_immersive_bar")) return Role.BG_NAV_TINT

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
        // 顶栏底（QUI 的 bg_top_light，TIM 原版是纯白顶栏 #FFFFFF，只比页面底
        // #F5F6FA 亮一档）：跟随**页面底色**。以前跟卡片色 surfaceBright
        // (#003045)，比内容 surfaceContainer(#001C2A) 亮两档 —— 深色主题下
        // 一大堆页面的顶栏/状态栏跟下方割成两截。
        if (n.contains("bg_top_light")) return Role.BG_NAV_TINT
        // 其余 bottom/middle_light 是页面上的浅色卡片层，仍跟随卡片色。
        if (n.contains("bg_bottom_light") ||
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
            // ⚠️ roleMemo 也必须一起失效：computeRole 依赖 MonetPalette.isAmoled()
            // （AMOLED 开时 bubble_host → GUEST_BUBBLE 等），而 isAmoled 会随设置
            // 变化。以前它只靠 "size > 4096" 兜底，于是切换 AMOLED 开关后
            // 气泡/气泡文字沿用旧角色，只有重启进程才对 —— 典型的"改了没效果"。
            roleMemo.clear()
            memoGeneration = generation
        }
    }

    // ------------------------------------------------------------------
    // 当前配色方案里"我们已经输出过的颜色"
    //
    // `isSchemeColor` / `isSurfaceColor` 曾经把同一份角色清单各抄一遍（而且越加越长，
    // 加角色时经常只改一处 —— 于是"已经染过的颜色"在一条路径上被认出、在另一条
    // 路径上被再染一次）。现在共用下面的 SURFACE_ROLES / PRIMARY_ROLES /
    // FOREGROUND_ROLES，且判定必须留在 TokenMapper 内部：`Role` 是它的 private
    // 嵌套枚举，同文件的其它 object 也访问不到。
    //
    // 比较基准统一用 `resolve(role, ROLE_PROBE, scheme)`：故意传一个**不透明、
    // 且没有任何角色会把它映成别的颜色**的输入色，拿到的就是该角色的标准取值
    // （含 TEXT_ROLES 的"强制不透明"处理），与调用点 `ColorMath.opaque(x)` 的
    // 比较语义一致。
    // ------------------------------------------------------------------

    /** 基准输入色：不透明黑。 */
    private const val ROLE_PROBE: Int = 0xFF000000.toInt()

    private val PRIMARY_ROLES = listOf(
        Role.PRIMARY,
        Role.ON_PRIMARY,
        Role.PRIMARY_CONTAINER,
        Role.ON_PRIMARY_CONTAINER
    )

    private val FOREGROUND_ROLES = listOf(
        Role.ON_SURFACE,
        Role.ON_SURFACE_VARIANT,
        Role.OUTLINE,
        Role.OUTLINE_VARIANT
    )

    /**
     * [color]（忽略 alpha）是否等于 [roles] 里任一角色在当前方案中的取值，
     * 或等于 [extra] 里显式列出的方案色。
     *
     * [extra] 的存在是因为有两个方案槽位**没有对应的 Role**（`surfaceDim`、
     * `surfaceContainerLowest`），历史上 `isSchemeColor` 直接比了它们；
     * 去掉会让"判已染"的覆盖面变窄，等于让这些颜色被重复染色一次。
     */
    private fun matchesAnyRole(
        color: Int,
        roles: List<Role>,
        scheme: DynamicScheme,
        extra: List<Int> = emptyList()
    ): Boolean {
        val opaque = ColorMath.opaque(color)
        if (MonetPalette.isAmoled() && opaque == ROLE_PROBE) return true
        if (roles.any { resolve(it, ROLE_PROBE, scheme) == opaque }) return true
        return extra.any { (it and 0x00FFFFFF) == (opaque and 0x00FFFFFF) }
    }

    /**
     * 是否"当前配色方案里我们已经输出过的颜色"（表面 + 主色 + 前景 + 描边）。
     *
     * 取色一律来自 `palette()`，**不接受调用方的深浅参数** —— 历史上那个
     * `dark` 形参从来没参与过判据，留着只会让人以为它能改变结果。
     *
     * 这是实现层；对外的统一入口是 [BgResolver.isSchemeColor]。
     */
    internal fun isSchemeColorOfScheme(color: Int): Boolean {
        val scheme = MonetPalette.palette()
        return matchesAnyRole(
            color,
            SURFACE_ROLES + PRIMARY_ROLES + FOREGROUND_ROLES,
            scheme,
            extra = listOf(scheme.surfaceDim, scheme.surfaceContainerLowest)
        )
    }

    /** 只判"表面/容器类"角色（[isSchemeColorOfScheme] 的子集）。 */
    internal fun isSurfaceColorOfScheme(color: Int): Boolean {
        val scheme = MonetPalette.palette()
        return matchesAnyRole(
            color,
            SURFACE_ROLES,
            scheme,
            extra = listOf(scheme.surfaceDim, scheme.surfaceContainerLowest)
        )
    }
}

/**
 * 背景色判定的统一入口（P0-2 第二步）。
 *
 * "颜色 -> 面色"的判定历史上散在 10 处（inlineBgColor / bgColorForDrawable /
 * inferByColor / mappedBitmapBgColor / fixTinySolidBg / loginPageMonetizePass /
 * recolorContainer / hookMineGrid / dialogMonetizePass / hookStatusBar），
 * 混用两种颜色度量（HCT tone 与 BT.601 luma）和 7 个不同的"亮"阈值 ——
 * 同一个颜色经不同入口会落到不同结果。
 *
 * 这里把**判据**统一出来（度量、彩色排除、亮度档位），各调用点只需说明
 * "我要哪一档面色"，不再各写一套阈值。迁移是渐进的：新代码一律走这里。
 */
object BgResolver {

    /**
     * 彩色底（品牌色块、按钮、图片主色）不参与面色映射。
     *
     * ⚠️ 这里的"彩色"用 **RGB 跨度**（max-min），TokenMapper 的另几处历史代码用
     * **HCT chroma**。两者尺度不可互换 —— 迁移中的代码请一律用本对象的方法，
     * 不要再新写 `Hct.fromInt(...).chroma >= 8` 这类判据。
     */
    const val CHROMA_SPAN_MAX = 40

    // ---- 亮度档位（BT.601 luma）----
    // 与原先散在各处的 LUMA_* 常量一一对应，取值一个没改。
    const val NEAR_WHITE = 235   // 近乎纯白（卡片底、白色填充）
    const val VERY_LIGHT = 220   // 很亮（浅色主题遗留的底）
    const val LIGHT = 200        // 亮
    const val BRIGHT = 170       // 亮背景：深色下应当压暗
    const val MID = 160          // 中间偏亮：决定用哪一档前景色
    const val MID_LOW = 150      // 中间
    const val DARK_TEXT = 140    // 亮字 / 深字的分界
    const val DARK = 120         // 偏暗

    // ---- 其余仍在用的判据线 ----
    // 这几条不属于上面"亮度档位"的量表（有的是灰度跨度、有的是像素取样门槛），
    // 但同样被散着写成裸数字。收在这里只为**可读与可调**，取值一个没改。

    /** 近似灰度：RGB 跨度 ≤ 此值就当作"灰"（不是彩色）。 */
    const val GRAY_SPAN = 24

    /** 暗灰前景上限：luma 低于此值的灰字在深色底上读不出来，需要提亮。 */
    const val DIM_TEXT = 110

    /** 提亮灰字的档位：低于 [DIM_TEXT_STRONG] 给 onSurface，否则给 onSurfaceVariant。 */
    const val DIM_TEXT_STRONG = 70

    /**
     * 位图采样时"这个像素算数"的 **alpha** 下限（低于此值视为透明/抗锯齿边缘，跳过）。
     *
     * ⚠️ 它是 alpha 门槛，不是亮度门槛 —— 历史命名 `PIXEL_DARK` 与"暗/亮分界"
     * 的注释都是错的，容易让人误当成 luma 阈值去用。
     */
    const val ALPHA_MIN = 110

    /** 判定"主色系"（与角色色同色相）时允许的分量偏移。 */
    const val ROLE_TOLERANCE = 45

    /** HCT tone 低于此值视为"文字色"而非"面色"（内联背景色的保守边界）。 */
    const val TONE_TEXT_MAX = 60.0

    /** HCT tone 低于此值给 containerHigh、否则给 container（无彩 drawable 背景）。 */
    const val TONE_CONTAINER_SPLIT = 50.0

    /** HCT chroma 低于此值视为无彩。**仅用于尚未迁移的历史分支**。 */
    const val HCT_CHROMA_MAX = 8.0

    /** BT.601 亮度。 */
    fun luma(color: Int): Int = luma((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)

    fun luma(r: Int, g: Int, b: Int): Int = (r * 299 + g * 587 + b * 114) / 1000

    /** 彩度跨度（max-min），用来判断"是不是彩色"。 */
    fun chromaSpan(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return maxOf(r, g, b) - minOf(r, g, b)
    }

    /** 是否近似灰度（弱彩色）。 */
    fun isGray(color: Int, maxSpan: Int = GRAY_SPAN): Boolean = chromaSpan(color) <= maxSpan

    /**
     * 是否"浅色主题遗留的亮底"：深色主题下应当压暗的、**无彩色/弱彩色**的亮色。
     * 彩色一律不动（按钮、品牌色块、图片主色都靠这条排除）。
     */
    fun isLightLeftover(color: Int, dark: Boolean): Boolean {
        if (!dark) return false
        if (chromaSpan(color) > CHROMA_SPAN_MAX) return false
        return luma(color) > BRIGHT
    }

    /**
     * 是否"近乎纯白"——**逐通道**判定（R/G/B 三者都 ≥ [NEAR_WHITE]）。
     *
     * ⚠️ 这是本项目里唯一的 isNearWhite 语义。历史上同一个文件里还并存过一个
     * luma 版本（`luma >= 235`，hooks 里另有一份逐通道副本），两者对偏色白给出
     * **不同答案**（例：`#FFEEEE` 逐通道 false、luma 244 true），调用点却按同一个
     * 名字理解 —— 典型的"同一判据两个结果"。现已全部收敛到本函数。
     *
     * 若某处确实需要 luma 尺度（"整体够亮"而非"每个通道都够亮"），请显式写
     * `luma(c) >= NEAR_WHITE`，不要另起一个叫 isNearWhite 的函数。
     */
    fun isNearWhite(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return r >= NEAR_WHITE && g >= NEAR_WHITE && b >= NEAR_WHITE
    }

    /**
     * 深色底上给灰字挑前景档位（唯一入口）。
     *
     * [brightenBelow] 是"低于多少亮度才值得提亮"：调用点的语境不同 ——
     * 纯净的 `setTextColor(Int)` 入口要覆盖到中灰（TIM 的 #333/#1a1a1a 系），
     * 而"前景已经被别处处理过"的入口只处理明显偏暗的。默认取 [BRIGHT]。
     */
    fun foregroundForDimText(
        color: Int,
        scheme: DynamicScheme,
        brightenBelow: Int = BRIGHT
    ): Int? {
        if (!scheme.isDark) return null
        if (!isGray(color)) return null
        if (luma(color) >= brightenBelow) return null
        return if (luma(color) < DIM_TEXT_STRONG) scheme.onSurface else scheme.onSurfaceVariant
    }

    /**
     * 是否"当前配色方案里我们已经输出过的颜色"（表面 + 主色 + 前景 + 描边）。
     *
     * 用途：判断一个颜色**要不要再染**。这是唯一入口 —— hooks 侧的同名函数已改成
     * 转发到这里；角色清单在 [TokenMapper] 内部只维护一份。
     */
    fun isSchemeColor(color: Int): Boolean = TokenMapper.isSchemeColorOfScheme(color)

    /** 只判"表面/容器类"角色（[isSchemeColor] 的子集）：这块颜色是不是一个"面"。 */
    fun isSurfaceColorOf(color: Int): Boolean = TokenMapper.isSurfaceColorOfScheme(color)
}
