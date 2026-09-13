package com.timmonet.hooks

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.TypedArray
import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableContainer
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.util.Log
import android.util.TypedValue
import android.os.Bundle
import android.os.SystemClock
import android.text.Spannable
import android.text.Spanned
import android.text.style.DynamicDrawableSpan
import android.text.style.ForegroundColorSpan
import android.view.ViewTreeObserver
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.timmonet.MainModule
import com.timmonet.core.ArkPackagePatcher
import com.timmonet.core.MonetPalette
import android.content.res.Configuration
import com.timmonet.core.SettingsBridge
import com.timmonet.core.ThemeState
import com.timmonet.ui.theme.ColorMode
import com.timmonet.core.TokenMapper
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Arrays
import java.util.HashMap
import java.util.HashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale
import org.json.JSONObject

/**
 * TIM 4.1.0（NT 架构）的颜色解析链路：
 *
 * 1. QUI token 组件 → com.tencent.biz.qui.quitoken.b.a.d()/e()
 *    （QUITokenThemeManager 的 getQuiColor/getQuiColorStateList）
 * 2. Hippy / 网页页面 → com.tencent.mobileqq.vas.theme.api.QUIUtil.getCurrentTokenMap()
 * 3. 经典 QQ 皮肤 → com.tencent.theme.SkinEngine.getColor()/loadColorStateList()
 *
 * 全部做 after-hook：每次调用都基于原始值重算，天然幂等，不会污染 TIM 的缓存。
 */
object TimMonetHooks {

    private const val TAG = MainModule.TAG
    private const val QUI_TOKEN_MANAGER = "com.tencent.biz.qui.quitoken.b.a"
    private const val SKIN_ENGINE = "com.tencent.theme.SkinEngine"
    private const val QUI_UTIL = "com.tencent.mobileqq.vas.theme.api.QUIUtil"
    private const val SIMPLE_TINT = "com.tencent.mobileqq.vas.theme.SimpleTintManager"
    private const val BUSINESS_TINT = "com.tencent.mobileqq.vas.theme.BusinessTintManager"
    private const val QUI_TINT = "com.tencent.mobileqq.vas.theme.QUITintManager"
    private const val SKINNABLE_CSL = "com.tencent.theme.SkinnableColorStateList"
    private const val AIO_UTILS = "com.tencent.mobileqq.aio.utils.ai"
    private const val RESCONFIG_A = "com.tencent.mobileqq.resconfig.a"
    private const val CHATS_UTILS = "com.tencent.qqnt.chats.utils.a"
    private const val QUI_BADGE = "com.tencent.mobileqq.quibadge.c"
    private const val AIO_REPLY = "com.tencent.mobileqq.aio.msglist.holder.component.reply.AIOReplyComponent"
    private const val DARK_MODE_MANAGER = "com.tencent.mobileqq.theme.DarkModeManager"
    private const val QQ_THEME = "com.tencent.mobileqq.utils.QQTheme"

    private const val TOKEN_NIGHT = 1002

    private val INT_TYPE: Class<*> = Int::class.javaPrimitiveType!!

    @Volatile
    private var timClassLoader: ClassLoader? = null

    @Volatile
    private var skinCslConstructor: Constructor<*>? = null

    private class CachedCsl(val generation: Long, val csl: ColorStateList)

    private val cslCache = ConcurrentHashMap<Long, CachedCsl>()

    private val loggedOnce = ConcurrentHashMap.newKeySet<String>()

    private val colorDrawableColorField: Field? = runCatching {
        ColorDrawable::class.java.getDeclaredField("mColor").apply { isAccessible = true }
    }.getOrNull()

    /** ColorDrawable 的当前颜色。
     *
     *  必须优先走 public API(API 29+ 的 getColor())：在 Android 17 上反射
     *  ColorDrawable.mColor 拿不到值(设备实测读出来恒为 0)，于是所有**纯色背景**
     *  ——钱包页根布局那片纯白、HomeToolbar 的品牌蓝顶栏——都读不到颜色，
     *  tintAnyDrawable 的 ColorDrawable 分支直接 return false，从来没被染过；
     *  只有 GradientDrawable 那条路是好的，所以页面上出现"一块染了一块没染"。 */
    private fun colorOfColorDrawable(d: ColorDrawable): Int =
        runCatching { d.color }.getOrElse {
            colorDrawableColorField?.get(d) as? Int ?: 0
        }

    private val fieldCache = ConcurrentHashMap<String, Field?>()
    private val typeFieldCache = ConcurrentHashMap<String, Field?>()
    private val methodCache = ConcurrentHashMap<String, Method?>()
    private val entryNameCache = ConcurrentHashMap<Long, String?>()

    @Volatile
    private var forwardDumpDone = false

    private var whiteBgLogCount = 0

    private var replyLogCount = 0

    private var profileSubtreeLogCount = 0

    private var profileTreeDumpCount = 0

    private var navIconTintLogCount = 0

    private var profileBtnLogCount = 0

    private var switchColorLogCount = 0

    private var unreadBubbleLogCount = 0


    /** 转发页行绑定耗时统计（定位分享页卡顿）。 */



    private var summaryHighlightLogCount = 0

    private var todoRedLogCount = 0

    private var todoSeenLogCount = 0

    @Volatile
    private var arkTokenCacheGen = -1L

    @Volatile
    private var arkTokenCache: JSONObject? = null

    @Volatile
    private var cachedSelfUin: String? = null

    @Volatile
    private var selfUinResolved = false

    @Volatile
    private var quiTokenCache: Map<String, String>? = null

    @Volatile
    private var quiTokenCacheGen = -1L

    private val tintMapCache =
        ConcurrentHashMap<String, Pair<Long, Map<String, String>>>()

    private var badgeRowCache = ConcurrentHashMap<Int, Int>()

    private var redSolidBgLogCount = 0

    private var redDotImgLogCount = 0

    private var colorSwatchLogCount = 0

    private var redDotNameLogCount = 0

    private var redDotTreeLogCount = 0

    private var highlightSpanLogCount = 0

    private var tintMapSampleLogCount = 0

    private var quiTokenSampleLogCount = 0

    /** 是否“错误红/通知红”色系（前缀高亮用的红，如群待办、有人@我）。 */
    private fun isErrorRed(opaque: Int): Boolean {
        val r = (opaque shr 16) and 0xFF
        val g = (opaque shr 8) and 0xFF
        val b = opaque and 0xFF
        return r >= 170 && g <= 140 && b <= 140 &&
            r - g >= 55 && Math.abs(g - b) <= 30
    }

    /** chats.utils.a 行背景/卡片底色接口：i()=行底、l()=卡片底。 */
    private fun hookChatsUtils(module: XposedModule, cl: ClassLoader) {
        try {
            val cls = Class.forName(CHATS_UTILS, false, cl)
            findMethod(cls, setOf("i"))
                ?.let { method ->
                    logOnce("hook installed: $CHATS_UTILS.i")
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        if (result is Int) {
                            TokenMapper.bgList(ThemeState.isNight(null, cl))
                        } else {
                            result
                        }
                    }
                }
            findMethod(cls, setOf("l"))
                ?.let { method ->
                    logOnce("hook installed: $CHATS_UTILS.l")
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        if (result is Int) {
                            TokenMapper.bgCard(ThemeState.isNight(null, cl))
                        } else {
                            result
                        }
                    }
                }
        } catch (t: Throwable) {
            Log.w(TAG, "chats.utils.a not found", t)
        }
    }

    /** 全部功能钩子的统一入口（MainModule 调用）。 */
    fun install(module: XposedModule, classLoader: ClassLoader) {
        timClassLoader = classLoader
        SettingsBridge.attach(module)
        hookQuiTokenManager(module, classLoader)
        hookSkinEngine(module, classLoader)
        hookQuiUtil(module, classLoader)
        hookTintManagers(module, classLoader)
        hookResources(module)
        hookDrawables(module)
        hookViewBackground(module)
        hookTextContrast(module)
        hookStatusBar(module)
        hookAioEditText(module, classLoader)
        hookAioBubbleText(module, classLoader)
        hookAioBubbleBg(module, classLoader)
        hookResconfig(module, classLoader)
        hookChatsUtils(module, classLoader)
        hookMineGrid(module, classLoader)
        hookArkDialogBg(module, classLoader)
        hookForwardArkConfirm(module, classLoader)
        hookEmoticonToggleBtn(module, classLoader)
        hookQuiBadge(module, classLoader)
        hookQuiBadgeResource(module, classLoader)
        hookQuiBadgeView(module, classLoader)
        hookSummaryBadge(module)
        hookChatsSummaryHighlight(module, classLoader)
        hookProfileContentCard(module, classLoader)
        hookProfileAddFriendButton(module, classLoader)
        hookSwitchColors(module, classLoader)
        hookUnreadBubble(module, classLoader)
        hookImageViewRedDot(module)
        hookBadgeRenderers(module, classLoader)
        hookSingleLineBadge(module, classLoader)
        hookAioNavBadge(module, classLoader)
        hookArkToken(module, classLoader)
        hookArkPackagePatch(module, classLoader)
        hookFileDownloadIcons(module)
        hookPlusPanelIcons(module, classLoader)
        hookSingleLineDrawables(module, classLoader)
        hookPlusPanelPlate(module)
        hookTitleBarLeftButton(module)
        hookHeaderTabText(module)
        hookLoginDeviceBanner(module, classLoader)
        hookLongNumberText(module)
        hookProfileHeaderText(module, classLoader)
        hookTroopMemberLevel(module, classLoader)
        hookPanelDispatch(module)
        hookMannounceWeb(module)
        hookHighlightSpans(module)
        hookDarkTextColors(module)
        hookSearchItemText(module, classLoader)
        hookAioReply(module, classLoader)
        hookForwardRecentTheme(module, classLoader)
        hookSplashBackground(module, classLoader)
        hookForwardDialog(module, classLoader)
        hookQuickMenuTheme(module, classLoader)
        hookAlbumTimelineText(module, classLoader)
        hookForceLight(module, classLoader)
        hookResumeRefresh(module, classLoader)
        registerSystemNightCallback()
        hookSettingEntry(module, classLoader)
    }

    // ------------------------------------------------------------------
    // 设置页入口(仿 QAuxiliary)
    //
    // TIM 设置主页(MainSettingFragment)的列表来自 MainSettingConfigProvider.d(Context):
    // 返回的 List 每一项(processor.c)就是一张卡片,所以"追加一张只含我们一个条目的
    // 卡片"天然就是单独一块、和别的卡片不相连。位置照 QAuxiliary 放在 index 1
    // (第一张卡之后)。item/Group 的类型不写死混淆名,而是从返回值里取实际类型。
    // ------------------------------------------------------------------

    private const val MODULE_PACKAGE = "io.github.linx3141.timmonet"
    private const val MODULE_SETTINGS_CLASS = "com.timmonet.SettingsActivity"

    /** 入口条目的 id(processor.e())。TIM 里没有 setting2Activity_settingEntryItem,
     *  取一个远离现有 id(1/2/3/7/15/17…)的值,避免被 SettingConfigProvider.b(int) 误命中。 */
    private const val SETTING_ENTRY_ID = 0x7F0F0101

    private fun hookSettingEntry(module: XposedModule, cl: ClassLoader) {
        val providerCls = runCatching {
            Class.forName("com.tencent.mobileqq.setting.main.MainSettingConfigProvider", false, cl)
        }.getOrNull() ?: return
        val method = runCatching {
            providerCls.getDeclaredMethod("d", Context::class.java)
        }.getOrNull() ?: return
        logOnce("hook installed: MainSettingConfigProvider.d (module setting entry)")
        runCatching { module.deoptimize(method) }
        module.hook(method).intercept { chain ->
            val result = chain.proceed()
            val list = result as? MutableList<*>
            val ctx = chain.getArg(0) as? Context
            if (list != null && ctx != null) {
                try {
                    injectSettingEntry(list, ctx, cl)
                } catch (t: Throwable) {
                    logOnce("setting entry inject failed: ${t.javaClass.simpleName} ${t.message}")
                }
            }
            result
        }
    }

    private fun injectSettingEntry(list: MutableList<*>, ctx: Context, cl: ClassLoader) {
        @Suppress("UNCHECKED_CAST")
        val groups = list as MutableList<Any?>
        val firstGroup = groups.firstOrNull() ?: return
        val groupCls = firstGroup.javaClass
        // processor.c.b() = 这一组的 processor 列表,拿它取到条目类型(抗混淆)
        val processors = runCatching {
            groupCls.getMethod("b").invoke(firstGroup) as? List<*>
        }.getOrNull() ?: return
        val itemCls = processors.firstOrNull()?.javaClass ?: return
        val itemCtor = itemCls.getConstructor(
            Context::class.java,
            Int::class.javaPrimitiveType,
            CharSequence::class.java,
            Int::class.javaPrimitiveType
        )
        // 点击回调:processor.g.d(Context, int, CharSequence, int) 内部是 Function0
        val setClick = itemCls.methods.firstOrNull {
            it.returnType == Void.TYPE && it.parameterTypes.size == 1 &&
                it.parameterTypes[0].name == "kotlin.jvm.functions.Function0"
        } ?: return
        val res = ctx.resources
        val pkg = ctx.packageName
        // 图标用宿主自己的资源(模块资源 id 在宿主 Resources 里解析不了):
        // qui_fill_color = 颜料滴,和"莫奈取色"语义最贴
        val iconRes = res.getIdentifier("qui_fill_color", "drawable", pkg)
        val entry = itemCtor.newInstance(ctx, SETTING_ENTRY_ID, "TimMonet", iconRes)
        settingEntryClickHandler(cl, ctx)?.let { runCatching { setClick.invoke(entry, it) } }
        val group = runCatching {
            groupCls.getConstructor(
                List::class.java, CharSequence::class.java, CharSequence::class.java
            ).newInstance(listOf(entry), "", "")
        }.getOrElse {
            // 新版是合成构造器 (List, CharSequence, CharSequence, int, DefaultConstructorMarker)
            val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker", false, cl)
            groupCls.getConstructor(
                List::class.java, CharSequence::class.java, CharSequence::class.java,
                Int::class.javaPrimitiveType, marker
            ).newInstance(listOf(entry), "", "", 6, null)
        }
        val index = minOf(1, groups.size)
        groups.add(index, group)
        logOnce("setting entry injected at index $index, groups=${groups.size}")
        // 诊断:逐组打印条目数与 processor id(我们的入口 id 是 SETTING_ENTRY_ID),
        // 便于核对入口在设置页里的实际位置
        runCatching {
            val summary = groups.mapIndexed { i, g ->
                val items = g?.javaClass?.getMethod("b")?.invoke(g) as? List<*>
                val ids = items?.map { p ->
                    runCatching { p?.javaClass?.getMethod("e")?.invoke(p) as? Int }.getOrNull()
                }
                "$i:n=${items?.size ?: -1}$ids"
            }
            logOnce("setting groups = $summary")
        }
    }

    /** 造一个宿主进程里的 kotlin.jvm.functions.Function0,invoke() 时打开模块设置页。 */
    private fun settingEntryClickHandler(hostCl: ClassLoader, ctx: Context): Any? {
        val function0 = runCatching {
            hostCl.loadClass("kotlin.jvm.functions.Function0")
        }.getOrNull() ?: return null
        val unit = runCatching {
            hostCl.loadClass("kotlin.Unit").getField("INSTANCE").get(null)
        }.getOrNull()
        return try {
            java.lang.reflect.Proxy.newProxyInstance(hostCl, arrayOf(function0)) { proxy, method, args ->
                when (method.name) {
                    "invoke" -> {
                        openModuleSettings(ctx)
                        unit
                    }
                    "toString" -> "TimMonetSettingEntry"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** 打开模块设置页。
     *
     *  Android 11+ 的包可见性把宿主对我们 app 的跨包访问全拦了：实测显式
     *  startActivity 报 START_CLASS_NOT_FOUND、我们自己的 provider 报
     *  Unknown authority（同一组件用 adb shell 起是正常的）。所以主路径改成
     *  "在宿主进程里直接显示设置页"（QAuxiliary/TAssistant 也是把界面跑在宿主
     *  进程里），跨包启动只作为兜底。
     */
    private fun openModuleSettings(ctx: Context) {
        if (com.timmonet.ui.SettingsDialogHost.show(ctx)) {
            logOnce("module settings shown in host process")
            return
        }
        try {
            val intent = Intent().apply {
                setClassName(MODULE_PACKAGE, MODULE_SETTINGS_CLASS)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            logOnce("module settings opened via startActivity fallback")
        } catch (t: Throwable) {
            logOnce("open module settings failed: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    private var sltvIconLogCount = 0

    /** 摘要行左复合图标（草稿铅笔等暖橙单色小图形）的染色计数：
     *  见 hookSingleLineDrawables —— 命中后整体染成摘要文字的 onSurface。 */
    /** “我的”页功能宫格（mine/b.java ViewHolder.h() 绑定点保底）：
     *  宫格图标若在 Resources 层没被 tintDrawable 拦到（皮肤包装等路径），
     *  在这里对 itemView 树里所有 ImageView 直接整体染 primary；
     *  同时打印树内各 ImageView 的 drawable 形态（诊断红点载体）。 */
    /** 品牌图 V2：按运行时真实两色（深蓝 #2170FF 系 → primary、纯黑 → onSurface）软混合重染。 */
    /** 品牌图重染结果缓存：源图最大 1000x1000（4MB IntArray 逐像素重染），
     *  同一张图重复进来代价很高；配色变了（代次变化）要整体失效。 */
    private val brandLogoMemo =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<Drawable, Drawable>())

    private var brandLogoMemoGen = -1L

    private fun tintBrandLogoV2(drawable: Drawable): Drawable? {
        val gen = MonetPalette.generation()
        if (gen != brandLogoMemoGen) {
            brandLogoMemo.clear()
            brandLogoMemoGen = gen
        }
        brandLogoMemo[drawable]?.let { return it }
        val out = computeBrandLogoV2(drawable)
        if (out != null) {
            if (brandLogoMemo.size > 64) brandLogoMemo.clear()
            brandLogoMemo[drawable] = out
        }
        return out
    }

    private fun computeBrandLogoV2(drawable: Drawable): Drawable? {
        return try {
            var iw = drawable.intrinsicWidth
            var ih = drawable.intrinsicHeight
            if (iw <= 0 || ih <= 0 || iw > 1000 || ih > 1000) return null
            val bmp = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val saved = Rect(drawable.bounds)
            drawable.setBounds(0, 0, iw, ih)
            drawable.draw(canvas)
            drawable.bounds = saved
            val scheme = MonetPalette.palette(ThemeState.isNight(null, timClassLoader))
            val primary = scheme.primary
            val light = scheme.onSurface
            val pr = (primary shr 16) and 0xFF
            val pg = (primary shr 8) and 0xFF
            val pb = primary and 0xFF
            val lr = (light shr 16) and 0xFF
            val lg = (light shr 8) and 0xFF
            val lb = light and 0xFF
            val px = IntArray(iw * ih)
            bmp.getPixels(px, 0, iw, 0, 0, iw, ih)
            for (i in px.indices) {
                val c = px[i]
                val a = (c ushr 24) and 0xFF
                if (a < 12) {
                    px[i] = 0
                    continue
                }
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                val mx = maxOf(r, g, b)
                val mn = minOf(r, g, b)
                // 防误染兜底: 饱和且非蓝相的像素(黄/红/绿/肤色等)保持原样,
                // 只重染蓝相/黑/白/灰(品牌横幅仅含这些色族; 判定虽已滤彩噪,
                // 残余彩色像素也不再被插值改色)
                if (mx - mn >= 40 && !(b >= r && b >= g)) continue
                // 原型：QQ 蓝 0x2170FF、纯黑字、纯白字（仅夜间版白字用）
                if (r > 200 && g > 200 && b > 190) {
                    // 纯白系 → onSurfaceVariant
                    px[i] = (a shl 24) or
                        ((light shr 16) and 0xFF shl 16) or
                        (light and 0xFF00) or (light and 0xFF)
                    continue
                }
                val db = ((r - 0x21) * (r - 0x21) + (g - 0x70) * (g - 0x70) +
                    (b - 0xFF) * (b - 0xFF)).toDouble()
                val dk = (r * r + g * g + b * b).toDouble()
                // 0=蓝→primary, 1=黑→onSurface
                val t = db / (db + dk)
                val nr = (pr + ((lr - pr) * t)).toInt()
                val ng = (pg + ((lg - pg) * t)).toInt()
                val nb = (pb + ((lb - pb) * t)).toInt()
                px[i] = (a shl 24) or (nr.coerceIn(0, 255) shl 16) or
                    (ng.coerceIn(0, 255) shl 8) or nb.coerceIn(0, 255)
            }
            bmp.setPixels(px, 0, iw, 0, 0, iw, ih)
            if (aboutBrandLogCount < 6) {
                aboutBrandLogCount++
                Log.i(
                    TAG,
                    "about brand recolored ${iw}x${ih} blue->#" +
                        Integer.toHexString(primary) + " black->#" +
                        Integer.toHexString(light)
                )
            }
            BitmapDrawable(Resources.getSystem(), bmp)
        } catch (t: Throwable) {
            if (brandLogoLogCount++ < 3) {
                Log.w(TAG, "brand v2 recolor failed", t)
            }
            null
        }
    }


    /** 品牌横幅判定(蓝族+黑/白族、近乎零彩噪的纯矢量图)：
     *  用于 Resources 层对"短名+长宽≥1.3 大图"的通用识别(资源短名随构建漂移)。 */
    /** 品牌图判定结果缓存：同一张图在列表复用 / 重复 loadDrawable 时会反复进来，
     *  每次都要渲染 + 逐像素统计。判定只看图像内容，与配色无关，不需要按代次失效。 */
    private val brandLikeMemo =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<Drawable, Boolean>())

    private val brandLikeStateMemo = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    private fun isBrandLikeImage(drawable: Drawable): Boolean {
        brandLikeMemo[drawable]?.let { return it }
        val stateKey = System.identityHashCode(drawable.constantState)
        brandLikeStateMemo[stateKey]?.let {
            brandLikeMemo[drawable] = it
            return it
        }
        val result = computeBrandLikeImage(drawable)
        brandLikeMemo[drawable] = result
        if (brandLikeStateMemo.size > 256) brandLikeStateMemo.clear()
        brandLikeStateMemo[stateKey] = result
        return result
    }

    private fun computeBrandLikeImage(drawable: Drawable): Boolean {
        return try {
            var iw = drawable.intrinsicWidth
            var ih = drawable.intrinsicHeight
            if (iw <= 0 || ih <= 0 || iw > 700 || ih > 700) return false
            // 性能：品牌判定只需色簇比例，缩放到长边<=160 渲染即可
            // （nearest 缩放保持原色值，分类结果一致，像素量降约 20~50x）
            val longSide = maxOf(iw, ih)
            if (longSide > 160) {
                val scale = 160f / longSide
                iw = maxOf(1, (iw * scale).toInt())
                ih = maxOf(1, (ih * scale).toInt())
            }
            // 早退：不透明像素数 tot 不可能超过缩放后的面积，够不到 3000 就别渲染了
            if (iw * ih < 3000) return false
            val bmp = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val saved = Rect(drawable.bounds)
            drawable.setBounds(0, 0, iw, ih)
            drawable.draw(canvas)
            drawable.bounds = saved
            val px = IntArray(iw * ih)
            bmp.getPixels(px, 0, iw, 0, 0, iw, ih)
            var tot = 0
            var blue = 0
            var mono = 0
            var colorNoise = 0
            for (c in px) {
                val a = (c ushr 24) and 0xFF
                if (a < 12) continue
                tot++
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                val mx = maxOf(r, g, b)
                val mn = minOf(r, g, b)
                if (b > 120 && b > r + 40 && b > g + 10) {
                    blue++
                } else if (mx - mn < 50) {
                    // 近灰/黑/白
                    mono++
                } else {
                    colorNoise++
                }
            }
            if (tot < 3000) return false
            // 实测品牌横幅(jdl/jdm: 蓝85%/黑白14%/彩噪0; kzy: 蓝61%/白族38%/彩噪0)。
            // 收紧: 蓝≥30%、黑白≥10%、彩噪≤4% —— 只剩纯矢量风横幅能通过,
            // 彩色贴纸/插画/照片(彩噪通常≥8%)全部排除。
            blue * 10 >= tot * 3 && mono * 10 >= tot * 1 &&
                colorNoise * 10 <= tot * 4
        } catch (t: Throwable) {
            false
        }
    }

    private fun hookMineGrid(module: XposedModule, cl: ClassLoader) {
        val cls = try {
            Class.forName("com.tencent.mobileqq.activity.tim.mine.b", false, cl)
        } catch (t: Throwable) {
            return
        }
        val itemType = try {
            Class.forName("com.tencent.tim.function.FunctionItemInfo", false, cl)
        } catch (t: Throwable) {
            return
        }
        findMethod(cls, setOf("h"), itemType)
            ?.let { method ->
                logOnce("hook installed: mine grid bind")
                runCatching { module.deoptimize(method) }
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val holder = chain.thisObject ?: return@intercept result
                        // 沿继承链找 itemView（ViewHolder 公开字段），再退回 View 类型字段
                        var root: View? = if (holder is View) holder else null
                        if (root == null) {
                            var c: Class<*>? = holder.javaClass
                            while (c != null && root == null) {
                                try {
                                    for (f in c.declaredFields) {
                                        if (f.name == "itemView" &&
                                            View::class.java.isAssignableFrom(f.type)
                                        ) {
                                            f.isAccessible = true
                                            root = f.get(holder) as? View
                                            break
                                        }
                                    }
                                } catch (t: Throwable) {
                                    // ignore
                                }
                                c = c.superclass
                            }
                        }
                        if (root == null) {
                            var c: Class<*>? = holder.javaClass
                            while (c != null && root == null) {
                                try {
                                    for (f in c.declaredFields) {
                                        if (View::class.java.isAssignableFrom(f.type)) {
                                            f.isAccessible = true
                                            root = f.get(holder) as? View
                                            if (root != null) break
                                        }
                                    }
                                } catch (t: Throwable) {
                                    // ignore
                                }
                                c = c.superclass
                            }
                        }
                        if (root == null) return@intercept result
                        val scheme = MonetPalette.palette(ThemeState.isNight(null, cl))
                        // 浅色模式下卡片底常是“10% 白高光层”(如 #1AF7FAFC 叠页面底)，
                        // 无独立卡底 → 与页面几乎同色。把卡容器背景不透明化为
                        // surfaceBright(保留圆角形状,不透明)。深色模式卡底另有路径。
                        if (!scheme.isDark) {
                            runCatching {
                                val rb = root.background
                                var rCol = 0
                                if (rb is android.graphics.drawable.ColorDrawable) {
                                    rCol = rb.color
                                } else if (rb is android.graphics.drawable.GradientDrawable) {
                                    rCol = runCatching {
                                        rb.color?.defaultColor
                                    }.getOrNull() ?: 0
                                }
                                val alpha = (rCol ushr 24) and 0xFF
                                val opaqueCol = opaqueColor(rCol)
                                if (rCol != 0 && alpha in 1..127 &&
                                    colorLuma(opaqueCol) >= 235
                                ) {
                                    val cardCol = scheme.surfaceBright
                                    if (rb is android.graphics.drawable.ColorDrawable) {
                                        rb.mutate()
                                        rb.color = cardCol
                                    } else if (rb is android.graphics.drawable.GradientDrawable) {
                                        rb.mutate()
                                        rb.setColor(cardCol)
                                        rb.alpha = 255
                                    }
                                    if (mineCardLogCount < 6) {
                                        mineCardLogCount++
                                        Log.i(
                                            TAG,
                                            "mine card bg opaque: #" +
                                                Integer.toHexString(rCol) + " -> #" +
                                                Integer.toHexString(cardCol)
                                        )
                                    }
                                }
                            }
                        }
                        fun walk(v: View) {
                            if (v is ImageView) {
                                val d = v.drawable
                                if (d != null) {
                                    // 图标/红点统一取气泡底色（保留 alpha 剪影）。
                                    // 红点可能自带皮肤红色 filter，必须先清掉再 SRC_IN。
                                    runCatching {
                                        d.mutate()
                                        d.colorFilter = null
                                        d.setColorFilter(
                                            scheme.primary, PorterDuff.Mode.SRC_IN
                                        )
                                        d.setTint(scheme.primary)
                                    }
                                }
                            }
                            if (v is ViewGroup) {
                                for (i in 0 until v.childCount) {
                                    walk(v.getChildAt(i))
                                }
                            }
                        }
                        walk(root)
                    } catch (t: Throwable) {
                        Log.w(TAG, "mine grid tint failed", t)
                    }
                    result
                }
            }
    }

    private var aboutBrandLogCount = 0

    private var confirmBodyLogCount = 0

    /** Ark 转发确认弹窗（QQCustomArkDialog*）整体莫奈化入口。
     *  取色对齐：转发普通文字的确认界面（ForwardRecent 页底 / ForwardDialog
     *  根卡）落在 surfaceContainer（#001C2A 档）；Ark 浮卡原被资源层 tint 成
     *  surfaceBright（#003045，浅一档）。这里把 Ark 弹窗窗底 + 内容根卡背景
     *  都按到 surfaceContainer 同档。 */
    private fun hookArkDialogBg(module: XposedModule, cl: ClassLoader) {
        for (clsName in listOf(
            "com.tencent.mobileqq.utils.QQCustomArkDialog",
            "com.tencent.mobileqq.utils.QQCustomArkDialogForAio"
        )) {
            val cls = try {
                Class.forName(clsName, false, cl)
            } catch (t: Throwable) {
                continue
            }
            findMethod(cls, setOf("show"))
                ?.let { method ->
                    logOnce("hook installed: $clsName.show (ark dialog bg)")
                    runCatching { module.deoptimize(method) }
                    module.hook(method).intercept { chain ->
                        try {
                            forwardConfirmDialogMonetize(chain.thisObject as? Dialog, cl)
                        } catch (t: Throwable) {
                            Log.w(TAG, "ark show monetize failed", t)
                        }
                        chain.proceed()
                    }
                }
        }
    }

    /** 转发确认弹窗（Ark/普通带表情输入两系）统一处理：窗底与根卡平染成
     *  surfaceBright（比页面深底浅一档的卡片面），内容子树莫奈化 + hint/
     *  小图标补色；多轮补跑兜底异步加载后再覆盖一次。 */
    /** 转发确认弹窗统一莫奈化（QQCustomArkDialog / QQCustomDialogWtihEmoticonInput /
     *  ForwardDialog 全系共用，不再分两套）：
     *  窗底与根卡平染 surfaceBright → 子树莫奈化 → 表情/键盘键染 onSurface →
     *  hint/非键小图标补色。补跑 = 300ms 防抖布局监听 + 120/400/1200ms 延时，
     *  覆盖内容异步加载与键盘/表情面板切换产生的任何新视图。 */
    private fun forwardConfirmDialogMonetize(dlg: Dialog?, cl: ClassLoader) {
        if (dlg == null) return
        Log.i(TAG, "confirm dialog monetize enter: ${dlg.javaClass.name}")
        try {
            // 注意：不要用 ThemeState.isNight 判定 —— QQTheme 被模块 pin 成 light 后
            // 该值缓存为 false；模块输出深浅由 MonetPalette.colorMode(effectiveDark)
            // 决定，palette() 返回的 scheme.isDark 才是真实档位。
            val scheme = MonetPalette.palette(false)
            val dark = scheme.isDark
            val color = when {
                MonetPalette.isAmoled() && dark -> 0xFF000000.toInt()
                dark -> scheme.surfaceBright
                else -> scheme.surface
            }
            val win = dlg.window
            win?.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(color)
            )
            val decor = win?.decorView ?: return
            // 事件触发即整窗染色一次(延时/轮询补跑经实验证明非必需)
            runCatching {
                dialogMonetizePass(decor, cl, dark)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "confirm dialog monetize failed", t)
        }
    }

    /** 单次染色 pass（两系确认弹窗共用）：
     *  ① 整棵子树莫奈化（文字/背景）
     *  ② 根卡(colorFilter)平染 surfaceBright，与窗底同档
     *  ③ 表情/键盘切换键 → onSurface（覆盖旧 filter，与全局 icon_ 规则一致）
     *  ④ hint 过暗 → onSurfaceVariant；非键暗小图标 → onSurfaceVariant */
    private fun dialogMonetizePass(decor: View, cl: ClassLoader, dark: Boolean) {
        val scheme = MonetPalette.palette(false)
        runCatching {
            forceMonetSubtree(decor, cl)
        }
        if (dark) {
            runCatching {
                val content = decor.findViewById(android.R.id.content) as? ViewGroup
                val root = if (content != null && content.childCount > 0) {
                    content.getChildAt(0)
                } else {
                    content
                }
                val bg = root?.background
                if (bg != null) {
                    bg.mutate()
                    bg.setColorFilter(scheme.surfaceBright, PorterDuff.Mode.SRC_IN)
                    bg.setTint(scheme.surfaceBright)
                }
            }
        }
        runCatching {
            tintEmoButtonIn(decor, cl)
        }
        // 转发内容正文预览文字 → onSurfaceVariant：
        // 被转发消息的文字在“预览卡”内：卡结构为 头像(ImageView) + 正文文本 同
        // 组；但转发目标行(发送给：群名)同样是 头像+名字 结构，需先按
        // “发送给：/发送给…”标记跳过目标名，再染正文。亮主色文本、不可点击、
        // 向上 3 层内有 ImageView 兄弟 → onSurfaceVariant；卡片内容(Ark/图片)
        // 不在原生 TextView 上，天然不受影响。
        runCatching {
            val previewTarget = scheme.onSurfaceVariant
            val onSurfaceCol = scheme.onSurface
            var sendToPending = false
            walkViewTree(decor, 400) { v3 ->
                if (v3 !is TextView || v3 is android.widget.EditText) {
                    return@walkViewTree
                }
                val txt = runCatching { v3.text?.toString()?.trim() }.getOrNull()
                if (txt.isNullOrEmpty()) return@walkViewTree
                // “发送给：”标签 → 期待下一个带头像文本(目标名)
                if (txt == "发送给：") {
                    sendToPending = true
                    return@walkViewTree
                }
                // “发送给+名字”整行(单控件)：目标行，跳过
                if (txt.startsWith("发送给")) {
                    sendToPending = false
                    return@walkViewTree
                }
                // 亮主色才可能染
                val cur = opaqueColor(v3.currentTextColor)
                if (cur != onSurfaceCol && cur != 0xFFFFFFFF.toInt()) {
                    return@walkViewTree
                }
                if (v3.isClickable || v3.isFocusable || v3.isLongClickable) {
                    return@walkViewTree
                }
                var hasImgSibling = false
                var p1 = v3.parent
                var depth = 0
                while (p1 is ViewGroup && depth < 3) {
                    for (i in 0 until p1.childCount) {
                        if (p1.getChildAt(i) is ImageView) {
                            hasImgSibling = true
                            break
                        }
                    }
                    if (hasImgSibling) break
                    p1 = p1.parent
                    depth++
                }
                if (!hasImgSibling) return@walkViewTree
                // 紧跟“发送给：”标签后的带头像文本 = 目标聊天名，跳过
                if (sendToPending) {
                    sendToPending = false
                    return@walkViewTree
                }
                runCatching {
                    v3.setTextColor(previewTarget)
                    v3.invalidate()
                }
                if (confirmBodyLogCount < 5) {
                    confirmBodyLogCount++
                    Log.i(
                        TAG,
                        "confirm body preview tinted: '" + txt.take(24) + "'"
                    )
                }
            }
        }
        // 补色：
        // ① 输入提示(hint)过暗 → 次级文字色
        // ② 其它 ≤96px 深色小图标（键已被 tintEmoButtonIn 染上 onSurface filter，
        //    这里经 filter!=null 检查自动跳过）→ 次级文字色
        runCatching {
            val target = scheme.onSurfaceVariant
            walkViewTree(decor, 400) { v2 ->
                if (v2 is TextView) {
                    try {
                        val hint = v2.currentHintTextColor
                        val alpha = hint ushr 24
                        if (alpha != 0) {
                            val opaque = opaqueColor(hint)
                            if (colorLuma(opaque) < 160) {
                                v2.setHintTextColor(target)
                            }
                        }
                    } catch (t: Throwable) {
                        // ignore
                    }
                }
                if (v2 is ImageView) {
                    val d2 = v2.drawable ?: return@walkViewTree
                    if (d2.colorFilter != null) return@walkViewTree
                    val iw2 = d2.intrinsicWidth
                    val ih2 = d2.intrinsicHeight
                    if (iw2 in 1..96 && ih2 in 1..96) {
                        val dom = runCatching {
                            sampleBitmapColorOfDrawable(d2)
                        }.getOrNull() ?: return@walkViewTree
                        val op2 = dom or 0xFF000000.toInt()
                        if (colorLuma(op2) < 160) {
                            runCatching {
                                d2.mutate()
                                d2.setColorFilter(target, PorterDuff.Mode.SRC_IN)
                                d2.setTint(target)
                            }
                        }
                    }
                }
            }
        }
    }

    /** 转发确认弹窗构建入口（ForwardBaseOption.buildConfirmDialog，final 方法，
     *  所有 Forward*Option 子类共用）：确认弹窗对象在此时已创建并 setContentView，
     *  直接取 mConfirmDialog 处理，绕开 show() 的异步时序。 */
    private fun hookForwardArkConfirm(module: XposedModule, cl: ClassLoader) {
        // 反编译确认：QQCustomArkDialog.w0(Context,Bundle) 是内容初始化(setContentView jw)
        // 的必经入口，ba.b 构造后立即调用；比 show() 更早且无异步分叉。
        val w0Cls = try {
            Class.forName("com.tencent.mobileqq.utils.QQCustomArkDialog", false, cl)
        } catch (t: Throwable) {
            null
        }
        if (w0Cls != null) {
            listOf("w0").forEach { mName ->
                findMethod(w0Cls, setOf(mName), Context::class.java, Bundle::class.java)
                    ?.let { method ->
                        logOnce("hook installed: QQCustomArkDialog.w0 (ark confirm)")
                        runCatching { module.deoptimize(method) }
                        module.hook(method).intercept { chain ->
                            val result = chain.proceed()
                            try {
                                forwardConfirmDialogMonetize(chain.thisObject as? Dialog, cl)
                            } catch (t: Throwable) {
                                Log.w(TAG, "ark w0 monetize failed", t)
                            }
                            result
                        }
                    }
            }
        }
        val cls = try {
            Class.forName("com.tencent.mobileqq.forward.ForwardBaseOption", false, cl)
        } catch (t: Throwable) {
            Log.w(TAG, "ForwardBaseOption not found", t)
            return
        }
        val field = runCatching {
            cls.getDeclaredField("mConfirmDialog").apply { isAccessible = true }
        }.getOrNull()
        if (field == null) {
            Log.w(TAG, "ForwardBaseOption.mConfirmDialog field not found")
            return
        }
        listOf("buildConfirmDialog").forEach { mName ->
            findMethod(cls, setOf(mName))
                ?.let { method ->
                    logOnce("hook installed: ForwardBaseOption.$mName (ark confirm)")
                    runCatching { module.deoptimize(method) }
                    module.hook(method).intercept { chain ->
                        Log.i(TAG, "fwd confirm hook hit: ${chain.thisObject?.javaClass?.name}")
                        val result = chain.proceed()
                        try {
                            val owner = chain.thisObject ?: return@intercept result
                            val dlg = field.get(owner) as? Dialog
                            Log.i(
                                TAG,
                                "fwd confirm after: owner=${owner.javaClass.name} " +
                                    "dlg=${dlg?.javaClass?.name}"
                            )
                            if (dlg != null) {
                                // 转发确认弹窗都是 cu(QQCustomDialog) 家族：
                                // QQCustomArkDialog(带 Ark 预览) /
                                // QQCustomDialogWtihEmoticonInput(带表情输入) /
                                // QQCustomDialog(普通)；统一染 surfaceBright。
                                if (dlg.javaClass.name.contains("QQCustomDialog")) {
                                    forwardConfirmDialogMonetize(dlg, cl)
                                }
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "forward ark confirm monetize failed", t)
                        }
                        result
                    }
                }
        }
    }

    private var emoticonToggleLogCount = 0

    /** 在 decor 树里找表情/键盘切换键（emo_btn 特征：内容描述为“打开键盘/
     *  打开表情面板”），把 ≤96px 无既有 filter 的图标剪影染成次级文字色。
     *  两套转发确认弹窗共用：QQCustomDialogWtihEmoticonInput.a0(换 PNG 图标)
     *  与 ForwardDialog/ForwardPreViewForShareDialog.setEmoButtonImageResourceATag
     *  (qui_emoticon / qui_keyboard_circle 换图，desc 文案相同)。 */
    private fun tintEmoButtonIn(decor: View, cl: ClassLoader): Boolean {
        var tinted = false
        runCatching {
            val scheme = MonetPalette.palette(ThemeState.isNight(null, cl))
            // 与普通确认弹窗的 icon_ 规则同色：onSurface(#CCE9FF)
            val target = scheme.onSurface
            walkViewTree(decor, 400) { v ->
                if (v !is ImageView) return@walkViewTree
                val desc = runCatching { v.contentDescription?.toString() }.getOrNull()
                val isToggle = desc == "打开键盘" || desc == "打开表情面板"
                // ForwardDialog/ForwardPreViewForShareDialog 初始态(l())
                // 只 setImageResource(qui_emoticon)，desc/tag 均为空；切键后
                // setEmoButtonImageResourceATag 才设 desc。两者都要染。
                val tag = runCatching { v.tag }.getOrNull()
                val isInitEmo = !isToggle && desc == null && tag == null
                if (!isToggle && !isInitEmo) return@walkViewTree
                val d = v.drawable ?: return@walkViewTree
                if (!isToggle && d.colorFilter != null) return@walkViewTree
                val iw = d.intrinsicWidth
                val ih = d.intrinsicHeight
                if (iw in 1..96 && ih in 1..96) {
                    // 初始态兜底只染暗色剪影，避免误伤彩色/图片小图标
                    if (isInitEmo) {
                        val dom = runCatching {
                            sampleBitmapColorOfDrawable(d)
                        }.getOrNull() ?: return@walkViewTree
                        val op = dom or 0xFF000000.toInt()
                        if (colorLuma(op) >= 160) return@walkViewTree
                    }
                    runCatching {
                        d.mutate()
                        // 已确认是切换键：即使已有其它色 filter（如 Ark 弹窗
                        // 被 polish 染过 onSurfaceVariant）也强制覆盖为同色
                        d.colorFilter = null
                        d.setColorFilter(target, PorterDuff.Mode.SRC_IN)
                        d.setTint(target)
                    }
                    tinted = true
                    if (emoticonToggleLogCount++ < 5) {
                        Log.i(
                            TAG,
                            "emo toggle btn tinted ($desc) -> #" +
                                Integer.toHexString(target)
                        )
                    }
                }
            }
        }
        return tinted
    }

    /** 带表情输入的确认弹窗（含 Ark 转发确认弹窗的父类 QQCustomDialogWtihEmoticonInput）
     *  的表情/键盘切换键：TIM 在 a0(boolean) 里 setImageResource 换成另一张 PNG
     *  （forward_dialog_new_edit_emoji/keyboard，深灰剪影 56×56），换图时机在弹窗
     *  首轮染色之后，不做处理就会以原始深灰色显示（深色面板上像一团黑）。
     *  精准方案：a0 返回后把该按钮图标染成次级文字色（图标本体，不碰其它染色逻辑）。 */
    private fun hookEmoticonToggleBtn(module: XposedModule, cl: ClassLoader) {
        val cls = try {
            Class.forName(
                "com.tencent.mobileqq.utils.QQCustomDialogWtihEmoticonInput",
                false, cl
            )
        } catch (t: Throwable) {
            Log.w(TAG, "QQCustomDialogWtihEmoticonInput not found", t)
            return
        }
        findMethod(cls, setOf("a0"), java.lang.Boolean.TYPE)
            ?.let { method ->
                logOnce("hook installed: QQCustomDialogWtihEmoticonInput.a0 (emo btn tint)")
                runCatching { module.deoptimize(method) }
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val dlg = chain.thisObject as? Dialog ?: return@intercept result
                        // a0 是弹窗构建/切换必经入口：直接把整窗莫奈化挂这里
                        runCatching {
                            forwardConfirmDialogMonetize(dlg, cl)
                        }
                        val decor = dlg.window?.decorView ?: return@intercept result
                        tintEmoButtonIn(decor, cl)
                    } catch (t: Throwable) {
                        // ignore
                    }
                    result
                }
            }
    }

/** QQ 快捷菜单容器布局类名（hookQuickMenuTheme 逐个 Class.forName 解析）。
 *  两套同名实现都要覆盖：
 *  - com.tencent.qqnt.aio.menu.ui.*：长按 AIO 消息的图标菜单
 *  - com.tencent.mobileqq.utils.dialogutils.*：BubblePopupWindow(长按会话等
 *    弹出的横排纯文字小浮层菜单) */
private val QUICK_MENU_UI_CLASSES = listOf(
    "com.tencent.qqnt.aio.menu.ui.QQCustomMenuExpandableLayout",
    "com.tencent.qqnt.aio.menu.ui.QQCustomMenuNoIconLayout",
    "com.tencent.qqnt.aio.menu.ui.QQCustomMenuLayout",
    "com.tencent.mobileqq.utils.dialogutils.QQCustomMenuExpandableLayout",
    "com.tencent.mobileqq.utils.dialogutils.QQCustomMenuNoIconLayout",
    "com.tencent.mobileqq.utils.dialogutils.QQCustomMenuLayout"
)

private var quickMenuLogCount = 0

/** 转发“选聊天”弹窗（ForwardRecentActivity）整体莫奈化：面板底色 + 整棵子树，
 *  decor.post 到主线程执行，确保弹窗首帧已就绪。 */
private fun monetizeForwardPopup(activity: Activity, cl: ClassLoader) {
    val window = activity.window ?: return
    val decor = window.decorView ?: return
    decor.post {
        try {
            val dark = ThemeState.isNight(null, cl)
            val content = activity.findViewById(android.R.id.content) as? ViewGroup
            val root: View = if (content != null && content.childCount > 0) {
                content.getChildAt(0)
            } else {
                decor
            }
            val rootBg = root.background
            if (rootBg != null && !trySetPaintFilter(rootBg, TokenMapper.bgList(dark))) {
                try {
                    rootBg.mutate()
                    rootBg.setColorFilter(TokenMapper.bgList(dark), PorterDuff.Mode.SRC_IN)
                    rootBg.setTint(TokenMapper.bgList(dark))
                } catch (t: Throwable) {
                    // ignore
                }
            }
            forceMonetSubtree(decor, cl)
            if (!forwardDumpDone) {
                forwardDumpDone = true
                // 纯调试输出 dumpForwardTree(listOf(decor)) 按需求未随本批恢复，调用省略
            }
            logOnce("forward popup monetized")
        } catch (t: Throwable) {
            Log.w(TAG, "forward popup monetize failed", t)
        }
    }
}

/** 颜色转不透明(补 alpha 位)，用于亮度判断/取色比较。 */
private fun opaqueColor(c: Int): Int = (c and 0x00FFFFFF) or 0xFF000000.toInt()

/** 广度遍历 view 树(maxNodes 上限)，visit 返回 true 表示已处理并跳过其子树
 *  (等价于原循环里 continue 语义的调用侧自行判断即可——visit 内直接 return@
 *  walkViewTree 即为跳过后续处理)。 */
private inline fun walkViewTree(
    root: View,
    maxNodes: Int,
    crossinline visit: (View) -> Unit
) {
    val stack = java.util.ArrayDeque<View>()
    stack.add(root)
    var guard = 0
    while (stack.isNotEmpty() && guard < maxNodes) {
        val v = stack.removeFirst()
        guard++
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                stack.addLast(v.getChildAt(i))
            }
        }
        visit(v)
    }
}

private fun forceMonetSubtree(view: View?, cl: ClassLoader) {
    if (view == null) return
    val dark = ThemeState.isNight(null, cl)
    val scheme = MonetPalette.palette(dark)
    forceMonetSubtree(view, dark, scheme)
}

/** 递归整棵子树强制莫奈化：文本色按弹窗规则重映射，背景走通用兜底染色。 */
private fun forceMonetSubtree(view: View?, dark: Boolean, scheme: DynamicScheme) {
    if (view == null) return
    if (view is ViewGroup) {
        try {
            for (i in 0 until view.childCount) {
                forceMonetSubtree(view.getChildAt(i), dark, scheme)
            }
        } catch (t: Throwable) {
            // ignore
        }
    }
    if (view is TextView) {
        val current = view.currentTextColor
        val mapped = mapPopupTextColor(current, scheme)
        if (mapped != current) {
            setTextColorFast(view, mapped)
        }
    }
    view.background?.let { tintAnyDrawable(it, dark) }
}

/** 弹窗/浮层文字色映射：面板深色文字保持，过亮文字与纯黑兜底换 onSurface，
 *  其余走 TokenMapper.mapColor（保留原 alpha）。 */
private fun mapPopupTextColor(color: Int, scheme: DynamicScheme): Int {
    val alpha = color ushr 24
    if (alpha == 0) return color
    val opaque = opaqueColor(color)
    if (isSchemeColor(opaque, scheme.isDark)) {
        // 已经是角色色：onPrimary 这类角色色不动；其余低亮度角色色换 onSurface 提对比
        if (opaque != (0xFF000000.toInt() or (scheme.onPrimary and 0x00FFFFFF)) &&
            colorLuma(opaque) < 150
        ) {
            return (scheme.onSurface and 0x00FFFFFF) or (alpha shl 24)
        }
        return color
    }
    if (colorLuma(opaque) >= 140) return color
    if (opaque == 0xFF000000.toInt()) {
        return (scheme.onSurface and 0x00FFFFFF) or (alpha shl 24)
    }
    // TIM 品牌蓝(精确常量) -> primary,与"自己发送的气泡"/选中态一致
    if (opaque in TokenMapper.BRAND_BLUES) {
        return (scheme.primary and 0x00FFFFFF) or (alpha shl 24)
    }
    val mapped = TokenMapper.mapColor(null, opaque, scheme.isDark)
    return (mapped and 0x00FFFFFF) or (alpha shl 24)
}

/** TIM 启动遮罩(进入首页前的全屏浅色渐变)：SplashActivity.doOnCreate 把
 *  login.bj.a(context) 的 bf Drawable 设为 window 背景与 logo 容器背景。
 *  bf(context,false) 被代码强制 day 配色(浅渐变,因模块把 QQTheme pin 成
 *  light 更是浅色)；bf 支持 setColorFilter(转发给内部两个 Paint)。这里在
 *  bj.a 返回后按模块深色档平染,启动即深色、进入首页无浅色闪屏。 */
private fun hookSplashBackground(module: XposedModule, cl: ClassLoader) {
    val cls = try {
        Class.forName("com.tencent.mobileqq.login.bj", false, cl)
    } catch (t: Throwable) {
        Log.w(TAG, "login.bj not found", t)
        return
    }
    listOf("a", "b").forEach { mName ->
        findMethod(cls, setOf(mName), Context::class.java)
            ?.let { method ->
                logOnce("hook installed: login.bj.$mName (splash bg)")
                runCatching { module.deoptimize(method) }
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val d = result as? android.graphics.drawable.Drawable
                        if (d != null) {
                            val scheme = MonetPalette.palette(false)
                            val target = when {
                                MonetPalette.isAmoled() && scheme.isDark ->
                                    0xFF000000.toInt()
                                scheme.isDark -> scheme.surfaceContainer
                                else -> return@intercept result
                            }
                            runCatching {
                                d.mutate()
                                d.setColorFilter(target, PorterDuff.Mode.SRC_IN)
                                d.setTint(target)
                            }
                        }
                    } catch (t: Throwable) {
                        // ignore
                    }
                    result
                }
            }
    }
}

/** 转发弹窗 Dialog（setContentView / show 两个入口）弹出时整体莫奈化。 */
private fun hookForwardDialog(module: XposedModule, cl: ClassLoader) {
    try {
        val cls = Class.forName("com.tencent.mobileqq.forward.dialog.ForwardDialog", false, cl)
        findMethod(cls, setOf("setContentView"), INT_TYPE)
            ?.let { method ->
                logOnce("hook installed: ForwardDialog.setContentView")
                module.hook(method).intercept { chain ->
                    chain.proceed()
                    try {
                        forwardConfirmDialogMonetize(chain.thisObject as? Dialog, cl)
                    } catch (t: Throwable) {
                        Log.w(TAG, "ForwardDialog.setContentView monetize failed", t)
                    }
                    null
                }
            }
        findMethod(cls, setOf("show"))
            ?.let { method ->
                logOnce("hook installed: ForwardDialog.show")
                module.hook(method).intercept { chain ->
                    chain.proceed()
                    try {
                        forwardConfirmDialogMonetize(chain.thisObject as? Dialog, cl)
                    } catch (t: Throwable) {
                        Log.w(TAG, "ForwardDialog.show monetize failed", t)
                    }
                    null
                }
            }
    } catch (t: Throwable) {
        Log.w(TAG, "ForwardDialog not found", t)
    }
    // ForwardDialog 的输入条在 ForwardPreViewForShareDialog 里：切换表情/键盘时
    // 由 setEmoButtonImageResourceATag 换图并更新 desc，这里兜底把新图标染成
    // 与其它确认弹窗一致的 onSurface（避免补跑窗口结束后切键不染色）。
    try {
        val pv = Class.forName(
            "com.tencent.mobileqq.forward.preview.ForwardPreViewForShareDialog",
            false, cl
        )
        findMethod(pv, setOf("setEmoButtonImageResourceATag"), INT_TYPE, INT_TYPE)
            ?.let { method ->
                logOnce("hook installed: ForwardPreViewForShareDialog.setEmoButtonImageResourceATag")
                runCatching { module.deoptimize(method) }
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        (chain.thisObject as? View)?.let { v ->
                            tintEmoButtonIn(v, cl)
                        }
                    } catch (t: Throwable) {
                        // ignore
                    }
                    result
                }
            }
    } catch (t: Throwable) {
        Log.w(TAG, "ForwardPreViewForShareDialog not found", t)
    }
}

/** 面板 item 的 ViewHolder.itemView 与其中的 drawable(identity 弱引用)。
 *  面板 item 的父链里没有稳定的面板类名,无法按父链判定,因此在
 *  onBindViewHolder/onViewAttachedToWindow 时把这些 view 标记下来,
 *  之后 TIM 无论何时(实测在 bind 之后)往里塞黑图标,都能当场认出并染色。 */
/** 进程内是否出现过面板 item(未出现前,handleImage 的父链判定可整体跳过)。 */
@Volatile private var panelSeen = false

private val panelItemViews: MutableSet<View> =
    java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())
private val panelDrawables: MutableSet<Drawable> =
    java.util.Collections.newSetFromMap(java.util.WeakHashMap<Drawable, Boolean>())

/** 已换过底板的面板入口容器（弱引用，每个 View 只处理一次）。 */
private val plusPlateViews: MutableSet<View> =
    java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

private var plusPlateLog = 0

private fun makeRoundShape(radius: Float, color: Int): GradientDrawable {
    val d = GradientDrawable()
    d.shape = GradientDrawable.RECTANGLE
    d.cornerRadius = radius
    d.setColor(color)
    return d
}

/** 自绘"+"面板入口底板：12dp 圆角 + surfaceContainerHigh；
 *  按下/选中态再叠一层低透明度 onSurface 蒙层，保留点击反馈。
 *  （刻意不用 lambda / 局部函数：R8 内联后合成 lambda 会踩坑。） */
private fun buildPlusItemPlate(density: Float): Drawable {
    val scheme = MonetPalette.palette(ThemeState.isNight(null, timClassLoader))
    val radius = 12f * density
    val base = scheme.surfaceContainerHigh
    val veil = (scheme.onSurface and 0x00FFFFFF) or (0x24 shl 24)
    val normal = makeRoundShape(radius, base)
    val activeBase = makeRoundShape(radius, base)
    val activeVeil = makeRoundShape(radius, veil)
    val active = LayerDrawable(arrayOf<Drawable>(activeBase, activeVeil))
    val sl = StateListDrawable()
    sl.addState(intArrayOf(android.R.attr.state_pressed), active)
    sl.addState(intArrayOf(android.R.attr.state_selected), active)
    sl.addState(intArrayOf(), normal)
    return sl
}

/** 修一个面板入口的圆角底板（e3z.xml 里 FrameLayout 的背景 selector）。
 *
 *  TIM 深色下该 selector 的普通态是 @color/qui_tui_common_fill_light_secondary，
 *  被 TokenMapper 映射成卡片色 BG_CARD(#001C2A)，与面板底**完全同色** → 12dp
 *  圆角格子看不见；而 selector 被皮肤引擎包过，递归拿不到 GradientDrawable 改色，
 *  所以这里直接把背景换成自绘底板。bind / 绘制前 / attach / 图标设置四条路都会调，
 *  plusPlateViews 保证每个 View 只处理一次。 */
private fun fixPlusItemPlate(v: View) {
    if (v is ImageView) return
    if (plusPlateViews.contains(v)) return
    if (v.background !is DrawableContainer) return
    plusPlateViews.add(v)
    runCatching {
        v.background = buildPlusItemPlate(v.resources.displayMetrics.density)
        if (plusPlateLog++ == 0) Log.i(TAG, "plus item plate replaced")
    }
}

private fun markPanelItem(itemView: View) {
    panelSeen = true
    panelItemViews.add(itemView)
    walkViewTree(itemView, 48) { v ->
        if (v is ImageView) {
            v.drawable?.let { panelDrawables.add(it) }
        }
    }
    if (panelItemViews.size > 256) panelItemViews.clear()
    if (panelDrawables.size > 512) panelDrawables.clear()
}

/** view 是否位于已标记的面板 item 内。 */
private fun isInPanelItem(view: View?): Boolean {
    if (panelItemViews.isEmpty()) return false
    var v: View? = view
    var depth = 0
    while (v != null && depth < 8) {
        if (panelItemViews.contains(v)) return true
        v = v.parent as? View
        depth++
    }
    return false
}

/** drawable 是否是面板 item 里出现过的实例(用于 Drawable 层 tint 改写)。 */
private fun isPanelDrawable(drawable: Drawable?): Boolean =
    drawable != null && panelDrawables.isNotEmpty() && panelDrawables.contains(drawable)

/** drawable 实例 → 资源名(Resources 层加载时记录,供绘制期查名)。 */
private val drawableNameMemo =
    java.util.Collections.synchronizedMap(java.util.WeakHashMap<Drawable, String>())

/** drawable 的 constantState 身份 → 资源名（复制品共享 constantState，用于兜底查名）。 */
private val drawableStateNameMemo =
    java.util.concurrent.ConcurrentHashMap<Int, String>()


/** 群聊头衔徽标(群主/管理员,TroopMemberLevelView2):
 *  徽标是自绘的(背景 drawable + 等级图 + 数字图 + VIP 动态特效),
 *  这里把背景与文字统一染成莫奈色;VIP 动态特效保留原样(染了会失去动效)。
 *  下面是该 View 的 Drawable 字段缓存(按类,用于清空 VIP 动效)。 */
private val troopLevelEffectFields =
    java.util.concurrent.ConcurrentHashMap<Class<*>, List<Field>>()

private fun hookTroopMemberLevel(module: XposedModule, cl: ClassLoader) {
    val cls = runCatching {
        Class.forName("com.tencent.qqnt.aio.nick.memberlevel.TroopMemberLevelView2", false, cl)
    }.getOrNull() ?: return
    val infoCls = runCatching {
        Class.forName("com.tencent.qqnt.aio.nick.f", false, cl)
    }.getOrNull() ?: return
    val drawableCls = runCatching {
        Class.forName("com.tencent.qqnt.aio.nick.e", false, cl)
    }.getOrNull() ?: return
    findMethod(cls, setOf("setTroopMemberLevel"), infoCls, drawableCls)
        ?.let { method ->
            logOnce("hook installed: TroopMemberLevelView2.setTroopMemberLevel")
            runCatching { module.deoptimize(method) }
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                try {
                    val view = chain.thisObject as? View
                    if (view != null) {
                        // 关闭 VIP 动态头衔特效:VIP 用户的头衔会挂一层
                        // FastDynamicDrawable(由 info.i() 的动效 URL 创建,
                        // 在 dispatchDraw 里绘制)。这里按类型把它清空,
                        // 使群主/管理员头衔与普通用户一样是静态背景。
                        runCatching {
                            troopLevelEffectFields.getOrPut(view.javaClass) {
                                view.javaClass.declaredFields.filter { f ->
                                    Drawable::class.java.isAssignableFrom(f.type)
                                }.onEach { it.isAccessible = true }
                            }.forEach { f ->
                                val d = f.get(view) as? Drawable
                                if (d != null &&
                                    d.javaClass.name.contains("DynamicDrawable")
                                ) {
                                    f.set(view, null)
                                }
                            }
                        }
                        val scheme = MonetPalette.palette(
                            ThemeState.isNight(null, timClassLoader)
                        )
                        // 徽标底色 -> primary,文字 -> onPrimary
                        view.background?.let { bg ->
                            runCatching {
                                bg.mutate()
                                bg.setColorFilter(scheme.primary, PorterDuff.Mode.SRC_IN)
                                bg.setTint(scheme.primary)
                            }
                        }
                        if (view is TextView) {
                            view.setTextColor(scheme.onPrimary)
                        }
                    }
                } catch (t: Throwable) {
                    // ignore
                }
                result
            }
        }
}

/** 用户详情页(点头像进入的资料卡)头部:昵称与账号改用与其它字段
 *  (性别/生日/所在地)一致的次要文字色 onSurfaceVariant。
 *  昵称在布局里是 qui_..._text_primary(映射后为亮色 onSurface),
 *  与其它的 _text_secondary 不一致。 */
private fun hookProfileHeaderText(module: XposedModule, cl: ClassLoader) {
    val cls = runCatching {
        Class.forName(
            "com.tencent.mobileqq.profilecard.base.view.TimProfileHeaderView",
            false, cl
        )
    }.getOrNull() ?: return
    val infoCls = runCatching {
        Class.forName("com.tencent.mobileqq.profilecard.data.ProfileCardInfo", false, cl)
    }.getOrNull() ?: return
    var logCount = 0

    fun tintHeader(view: View?) {
        val v = view ?: return
        val scheme = MonetPalette.palette(ThemeState.isNight(null, timClassLoader))
        if (!scheme.isDark) return
        val nickId = runCatching {
            v.resources.getIdentifier("nickname", "id", "com.tencent.tim")
        }.getOrNull() ?: 0
        if (nickId != 0) {
            (v.findViewById(nickId) as? TextView)?.setTextColor(scheme.onSurfaceVariant)
        }
    }

    // onUpdate 在父类是 abstract、由子类实现,参数类型可能不完全一致:
    // 按方法名从整条继承链上找(不写死参数类型)
    val updateMethod = runCatching {
        cls.methods.firstOrNull { it.name == "onUpdate" && it.parameterTypes.size == 2 }
            ?: cls.declaredMethods.firstOrNull {
                it.name == "onUpdate" && it.parameterTypes.size == 2
            }
    }.getOrNull()
    updateMethod
        ?.let { method ->
            logOnce("hook installed: TimProfileHeaderView.onUpdate (header text)")
            runCatching { module.deoptimize(method) }
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    tintHeader(chain.thisObject as? View)
                    if (logCount++ < 4) {
                        Log.i(TAG, "profile header text -> onSurfaceVariant")
                    }
                }
                result
            }
        }
    val initMethod = runCatching {
        cls.methods.firstOrNull { it.name == "onInit" && it.parameterTypes.size == 1 }
    }.getOrNull()
    initMethod
        ?.let { method ->
            logOnce("hook installed: TimProfileHeaderView.onInit (header text)")
            runCatching { module.deoptimize(method) }
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                runCatching { tintHeader(chain.thisObject as? View) }
                result
            }
        }
}

/** 资料卡(用户详情页)内部 view:不做账号亮色处理,保持与其它字段一致。 */
private fun isInProfileCardUi(view: View?): Boolean {
    var cur: View? = view
    var depth = 0
    while (cur != null && depth < 8) {
        val n = cur.javaClass.name
        if (n.contains("ProfileCard") || n.contains("profilecard") ||
            n.contains("ProfileHeader")
        ) return true
        cur = cur.parent as? View
        depth++
    }
    return false
}

/** 是否"账号类文本"(纯数字,或 QQ:/QQ号:/QID: 等带前缀形式)。 */
private fun looksLikeAccountText(s: String): Boolean {
    if (s.length !in 5..24) return false
    if (s.count { it.isDigit() } < 5) return false
    return s.all {
        it.isDigit() || it == ':' || it == '：' || it == ' ' ||
            it == 'Q' || it == 'q' || it == '号' || it == 'I' || it == 'D'
    }
}

/** 长数字文本(QQ 号等)在 attach 时兜底:布局里静态写的文本不经过
 *  setText,只在挂载时补齐染色(不透明 onSurface)。 */
private fun hookLongNumberText(module: XposedModule) {
    findMethod(TextView::class.java, setOf("onAttachedToWindow"))
        ?.let { method ->
            logOnce("hook installed: TextView.onAttachedToWindow (long number text)")
            runCatching { module.deoptimize(method) }
            module.hook(method).intercept { chain ->
                                    // 第三方模块注入界面:整体跳过染色
                                    if (isThirdPartyUiActive()) return@intercept chain.proceed()
                val result = chain.proceed()
                try {
                    val tv = chain.thisObject as? TextView
                    val t = tv?.text?.toString()?.trim()
                    if (tv != null && t != null && looksLikeAccountText(t) &&
                        !isInProfileCardUi(tv)
                    ) {
                        val scheme = MonetPalette.palette(
                            ThemeState.isNight(null, timClassLoader)
                        )
                        if (scheme.isDark) {
                            // 账号文字与"对方气泡内文字"同色:不透明 onSurface,
                            // 并清掉 View 级 alpha(TIM 对次要信息会用 alpha<1)
                            tv.alpha = 1f
                            tv.setTextColor(scheme.onSurface)
                        }
                    }
                } catch (t: Throwable) {
                    // ignore
                }
                result
            }
        }
}

/** 会话列表顶部"已登录 XXX"提示条(LoginDevicesBannerProcessor):
 *  其文字颜色由 TIM 指定为 qui_tui_common_text_secondary(次要文字),
 *  按要求改为与"对方气泡内文字"一致的 onSurface。 */
private fun hookLoginDeviceBanner(module: XposedModule, cl: ClassLoader) {
    val cls = runCatching {
        Class.forName(
            "com.tencent.mobileqq.activity.recent.bannerprocessor.LoginDevicesBannerProcessor",
            false, cl
        )
    }.getOrNull() ?: return
    val bannerCls = runCatching {
        Class.forName("com.tencent.mobileqq.banner.a", false, cl)
    }.getOrNull() ?: return
    // banner.a 里承载视图的字段(混淆名会变,按类型找 View 字段)
    val viewField = runCatching {
        bannerCls.declaredFields.firstOrNull {
            View::class.java.isAssignableFrom(it.type)
        }?.also { it.isAccessible = true }
    }.getOrNull()

    fun tintTexts(bannerArg: Any?) {
        val v = runCatching { viewField?.get(bannerArg) as? View }.getOrNull() ?: return
        val scheme = MonetPalette.palette(ThemeState.isNight(null, timClassLoader))
        // 文字颜色与"对方气泡内文字"一致:不透明的 onSurface
        walkViewTree(v, 32) { child ->
            if (child is TextView) {
                child.setTextColor(scheme.onSurface)
            }
        }
    }

    // 文字与颜色实际在 updateBanner 里设置(initBanner 阶段还没有文字)
    findMethod(cls, setOf("updateBanner"), bannerCls, android.os.Message::class.java)
        ?.let { method ->
            logOnce("hook installed: LoginDevicesBannerProcessor.updateBanner (text)")
            runCatching { module.deoptimize(method) }
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                runCatching { tintTexts(chain.getArg(0)) }
                result
            }
        }
}

/** 面板容器绘制前兜底(解决"第一次打开黑、收起再展开才正常"):
 *  TIM 在 item attach 之后、首帧绘制之前把个别图标涂黑,而我们在
 *  bind/attach 时采样到的还是亮色,于是判定跳过。这里在面板容器
 *  (PlusPanelContainerGroup/PlusPanel)每次 dispatchDraw 之前遍历子树,
 *  把"当前实际已变暗"的图标栅格化替换 —— 替换发生在绘制前,首次即正常。 */

/** pluspanel 容器类名判定结果缓存（dispatchDraw 是每帧热路径，避免反复查字符串）。 */
private val plusPanelClassMemo =
    java.util.Collections.synchronizedMap(java.util.WeakHashMap<Class<*>, Boolean>())

private fun isPlusPanelClass(cls: Class<*>): Boolean {
    plusPanelClassMemo[cls]?.let { return it }
    val n = cls.name
    val r = n.contains("pluspanel") || n.contains("PlusPanel")
    plusPanelClassMemo[cls] = r
    return r
}

private fun hookPanelDispatch(module: XposedModule) {
    var logCount = 0
    // 每个面板容器只在前几帧处理(避免每帧遍历子树影响渲染性能),
    // 3 帧足够覆盖"bind 后 / 首帧绘制后才被 TIM 涂黑"两种情况
    val frames = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<ViewGroup, Int>()
    )
    findMethod(ViewGroup::class.java, setOf("dispatchDraw"), Canvas::class.java)
        ?.let { method ->
            logOnce("hook installed: ViewGroup.dispatchDraw (panel icons)")
            runCatching { module.deoptimize(method) }
            module.hook(method).intercept { chain ->
                try {
                    if (!panelSeen) return@intercept chain.proceed()
                    val vg = chain.thisObject as? ViewGroup
                    if (vg != null && !isThirdPartyUiActive()) {
                        if (panelSeen && isPlusPanelClass(vg.javaClass)) {
                            val done = frames[vg] ?: 0
                            if (done >= 3) {
                                return@intercept chain.proceed()
                            }
                            frames[vg] = done + 1
                            walkViewTree(vg, 200) { v ->
                                // 入口底板同样在绘制前兜一道
                                fixPlusItemPlate(v)
                                if (v is ImageView) {
                                    val d = v.drawable
                                    if (d != null && !rasterizedPanelIcons.contains(d)) {
                                        val out = rasterizeIconUniform(d)
                                        if (out != null) {
                                            rasterizedPanelIcons.add(out)
                                            v.setImageDrawable(out)
                                            if (logCount++ < 20) {
                                                Log.i(
                                                    TAG,
                                                    "panel icon fixed before draw " +
                                                        d.javaClass.simpleName
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    // ignore
                }
                chain.proceed()
            }
        }
}

/** 面板入口底板的第三条兜底：item 里的 FrameLayout **attach 到窗口**时处理 ——
 *  此刻 inflate 早已结束、背景必然就绪（bind / 绘制前两条路在面板第一次展开时
 *  可能跑在背景设置之前）。 */
/** 老式标题栏左侧按钮（TIM/QWallet 的 R.id.ivTitleBtnLeft）与右侧图标：
 *  它们是 **TextView/ImageView**（不是 Button），图标走 background 或
 *  compound drawable，深色皮肤下 TIM 常给纯黑 —— 压在顶栏底上等于看不见
 *  （实测钱包设置页返回键 #000000 on 顶栏 #001C2A）。这里统一提亮成
 *  顶栏前景色 onSurface。 */
private fun fixTitleBarIcon(v: View) {
    val fg = MonetPalette.palette(ThemeState.isNight(null, timClassLoader)).onSurface
    try {
        v.background?.let { bg ->
            bg.mutate()
            bg.setColorFilter(fg, PorterDuff.Mode.SRC_IN)
            bg.setTint(fg)
        }
        if (v is TextView) {
            for (d in v.compoundDrawables) {
                d?.mutate()
                d?.setColorFilter(fg, PorterDuff.Mode.SRC_IN)
                d?.setTint(fg)
            }
        }
        if (titleBarLog++ < 6) {
            Log.i(
                TAG,
                "titlebar left btn: class=" + v.javaClass.simpleName +
                    " bg=" + v.background?.javaClass?.simpleName +
                    " -> #" + Integer.toHexString(fg)
            )
        }
    } catch (t: Throwable) {
        // ignore
    }
}

private fun hookTitleBarLeftButton(module: XposedModule) {
    findMethod(View::class.java, setOf("onAttachedToWindow"))
        ?.let { method ->
            logOnce("hook installed: View.onAttachedToWindow (titlebar left btn)")
            runCatching { module.deoptimize(method) }
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                try {
                    val v = chain.thisObject as? View
                    val id = v?.id ?: View.NO_ID
                    // 预筛：只有 app 资源 id(0x7f...) 才去查名字；entryName()
                    // 自带缓存，避免每次 attach 都走 Resources 反射
                    if (v != null && id != View.NO_ID && (id ushr 24) == 0x7f &&
                        !isThirdPartyUiActive()
                    ) {
                        val nm = entryName(v.resources, id)
                        if (nm == "ivTitleBtnLeft" || nm == "ivTitleBtnRightImage") {
                            fixTitleBarIcon(v)
                        }
                    }
                } catch (t: Throwable) {
                    // ignore
                }
                result
            }
        }
}

private fun hookPlusPanelPlate(module: XposedModule) {
    findMethod(View::class.java, setOf("onAttachedToWindow"))
        ?.let { method ->
            logOnce("hook installed: View.onAttachedToWindow (plus plate)")
            runCatching { module.deoptimize(method) }
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                if (panelSeen) {
                    try {
                        val v = chain.thisObject as? View
                        if (v is FrameLayout) fixPlusItemPlate(v)
                    } catch (t: Throwable) {
                        // ignore
                    }
                }
                result
            }
        }
}








/** “+”扩展面板(照片/拍照/通话/文件/收藏/红包/收钱…)的入口图标:
 *  TIM 在 item bind 时按浅色主题把图标 tint 成黑色(实测"收藏/文件/收钱"
 *  先亮后黑),Resources 层染色会被它覆盖。这里在 bind / attach 之后
 *  强制覆盖:单色图形 → onSurface,彩色图标不动。 */
private fun hookPlusPanelIcons(module: XposedModule, cl: ClassLoader) {
    val adapterCls = runCatching {
        Class.forName("com.tencent.qqnt.pluspanel.adapter.a", false, cl)
    }.getOrNull() ?: return
    val holderCls = runCatching {
        Class.forName("com.tencent.qqnt.pluspanel.adapter.c", false, cl)
    }.getOrNull() ?: return

    fun tintItem(holder: Any?) {
        // 项目无 recyclerview 编译依赖:反射取 ViewHolder.itemView 字段
        val item = runCatching {
            val f = holder?.javaClass?.getField("itemView")
            f?.get(holder) as? View
        }.getOrNull() ?: return
        markPanelItem(item)
        // 关键:bind/attach 之后直接栅格化替换图标(而非仅 tint)。
        // 此前这里只做 tint,TIM 在 attach 后还会覆盖一次,导致"第一次打开
        // 是黑的、收起再展开才正常"(第二次复用不再重新 bind/覆盖)。
        walkViewTree(item, 48) { v ->
            // 入口的 12dp 圆角底板（FrameLayout 的 selector 背景，见 fixPlusItemPlate）
            fixPlusItemPlate(v)
            if (v is ImageView) {
                val d = v.drawable
                if (d != null && !rasterizedPanelIcons.contains(d)) {
                    val out = rasterizeIconUniform(d)
                    if (out != null) {
                        rasterizedPanelIcons.add(out)
                        v.setImageDrawable(out)
                    }
                }
            }
        }
    }

    findMethod(adapterCls, setOf("onBindViewHolder"), holderCls, INT_TYPE)
        ?.let { method ->
            logOnce("hook installed: pluspanel onBindViewHolder (icon tint)")
            runCatching { module.deoptimize(method) }
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                try {
                    tintItem(chain.getArg(0))
                } catch (t: Throwable) {
                    // ignore
                }
                result
            }
        }
    findMethod(adapterCls, setOf("onViewAttachedToWindow"), holderCls)
        ?.let { method ->
            logOnce("hook installed: pluspanel onViewAttachedToWindow (icon tint)")
            runCatching { module.deoptimize(method) }
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                try {
                    tintItem(chain.getArg(0))
                } catch (t: Throwable) {
                    // ignore
                }
                result
            }
        }
}

/** QQ 快捷菜单（长按消息）主题化：把三个 QQCustomMenu* 容器类解析出来，命中任一
 *  的 View 挂 onAttachedToWindow 后调度整棵子树染色。 */
private fun hookQuickMenuTheme(module: XposedModule, cl: ClassLoader) {
    val classes = QUICK_MENU_UI_CLASSES.mapNotNull { name ->
        runCatching { Class.forName(name, false, cl) }.getOrNull()
    }
    if (classes.isEmpty()) {
        Log.w(TAG, "QQCustomMenu* layouts not found")
        return
    }
    findMethod(View::class.java, setOf("onAttachedToWindow"))
        ?.let { method ->
            logOnce("hook installed: View.onAttachedToWindow (quick menu theme)")
            runCatching { module.deoptimize(method) }
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                try {
                    val view = chain.thisObject as? View
                    if (view != null) {
                        if (classes.any { it.isInstance(view) }) {
                            // 菜单 attach 即染色一次(原 40 次/10s 轮跑经实验证明非必需)
                            runCatching {
                                forceMonetQuickMenu(view)
                            }
                        }
                        // 会话行滑动/长按菜单按钮：渲染器 chats.core.adapter.c.a.c
                        // 代码创建(setTextColor(-1) 白字 + 彩色 webp 底)。
                        // 左滑(SwipeMenuLayout 内) → primary 底+onPrimary 字；
                        // 长按正上方的横排文字浮层(无彩色底) → 文字染 onSurface，
                        // 复用重设白色时延时补染。
                        if (view is TextView) {
                            val menuTxt = runCatching {
                                view.text?.toString()?.trim()
                            }.getOrNull()
                            if (menuTxt == "删除" || menuTxt == "置顶" ||
                                menuTxt == "取消置顶" || menuTxt == "标为未读" ||
                                menuTxt == "标为已读"
                            ) {
                                var inSwipe = false
                                var p0: android.view.ViewParent? = view.parent
                                var d0 = 0
                                while (p0 != null && d0 < 6) {
                                    if (p0.javaClass.name ==
                                        "com.tencent.qqnt.widget.SwipeMenuLayout"
                                    ) {
                                        inSwipe = true
                                        break
                                    }
                                    p0 = p0.parent
                                    d0++
                                }
                                val scheme = MonetPalette.palette(
                                    ThemeState.isNight(null, timClassLoader)
                                )
                                if (inSwipe) {
                                    runCatching {
                                        view.background?.let { bg ->
                                            bg.mutate()
                                            bg.setColorFilter(
                                                scheme.primary, PorterDuff.Mode.SRC_IN
                                            )
                                            bg.setTint(scheme.primary)
                                        }
                                        view.setTextColor(scheme.onPrimary)
                                        logOnce("swipe menu btn monetized ($menuTxt)")
                                    }
                                } else {
                                    runCatching {
                                        val col = opaqueColor(view.currentTextColor)
                                        if (col == 0xFFFFFFFF.toInt() ||
                                            col == scheme.onSurface
                                        ) {
                                            view.setTextColor(scheme.onSurface)
                                            logOnce("chat float txt monetized ($menuTxt)")
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "quick menu attach failed", t)
                }
                result
            }
        }
}


/** 徽标数字染色：数字 TextView 若处于 primary 底(自身背景、父容器背景或
 *  兄弟 ImageView 的 background/drawable 呈 primary 样)且为白字 → 染
 *  onPrimary 并返回 true；非白字视为已处理也返回 true；未命中返回 false
 *  (调用方延时重试)。 */
private fun tintBadgeDigitText(tv: TextView, scheme: DynamicScheme): Boolean {
    val cur = opaqueColor(tv.currentTextColor)
    if (cur != 0xFFFFFFFF.toInt()) return true
    val primFull = scheme.primary or 0xFF000000.toInt()
    fun isPrimaryColor(c0: Int): Boolean {
        val op = opaqueColor(c0)
        return kotlin.math.abs((op ushr 16 and 0xFF) - (primFull ushr 16 and 0xFF)) < 45 &&
            kotlin.math.abs((op ushr 8 and 0xFF) - (primFull ushr 8 and 0xFF)) < 45 &&
            kotlin.math.abs((op and 0xFF) - (primFull and 0xFF)) < 45
    }
    fun dIsPrimary(d: android.graphics.drawable.Drawable?): Boolean {
        if (d == null) return false
        return when (d) {
            is android.graphics.drawable.ColorDrawable -> isPrimaryColor(d.color)
            is android.graphics.drawable.GradientDrawable -> {
                val g0 = runCatching { d.color?.defaultColor }.getOrNull() ?: 0
                isPrimaryColor(g0)
            }
            else -> d.colorFilter != null || runCatching {
                val dom = sampleBitmapColorOfDrawable(d) ?: return@runCatching false
                isPrimaryColor(dom)
            }.getOrDefault(false)
        }
    }
    fun vIsPrimary(v: View): Boolean {
        if (dIsPrimary(v.background)) return true
        return v is ImageView && dIsPrimary(v.drawable)
    }
    if (vIsPrimary(tv)) {
        runCatching {
            tv.setTextColor(scheme.onPrimary)
        }
        return true
    }
    var pv: android.view.ViewParent? = tv.parent
    var depth = 0
    while (pv is ViewGroup && depth < 3) {
        val g = pv as ViewGroup
        if (dIsPrimary(g.background)) {
            runCatching {
                tv.setTextColor(scheme.onPrimary)
            }
            return true
        }
        for (k in 0 until g.childCount) {
            val c = g.getChildAt(k)
            if (c === tv) continue
            if (c is ImageView && vIsPrimary(c)) {
                runCatching {
                    tv.setTextColor(scheme.onPrimary)
                }
                return true
            }
        }
        pv = pv.parent
        depth++
    }
    return false
}

private var loginPageBgLogCount = 0

private var loginBgSelfLogCount = 0

private var badgeDigitLogCount2 = 0

/** 登录/添加账号页在 Activity.onResume 时调度深色化(attach context 解包不可靠，
 *  改用 onResume 入口；页面内容可能后加载，多轮补跑)。 */
private var appliedThemeGen = -1L

/** Activity.onResume 综合处理：
 *  ① 登录/账号页(LoginActivity/AccountManageActivity…)深色化
 *  ② 配色代次刷新：模块设置里切换配色后(调色板重建、generation++)，回到 TIM
 *     resume 时对当前界面整体重染——文字/背景按新 scheme 重映射，RecyclerView
 *     列表 notifyDataSetChanged 让其按新配色重建(inflate 资源重走 hook)，
 *     QUIBadge 角标重算。 */

private fun hookResumeRefresh(module: XposedModule, cl: ClassLoader) {
    findMethod(Activity::class.java, setOf("onResume"))
        ?.let { method ->
            logOnce("hook installed: Activity.onResume (login+palette refresh)")
            // 页面类名必须在 onCreate(setContentView/inflate) 之前就更新:
            // 第三方模块设置页(QAuxiliary 的 SettingsUiFragmentHostActivity)在
            // onCreate 里 inflate,此时 onResume 还没跑,若只靠 onResume 更新,
            // 染色时看到的仍是上一个(TIM)页面 -> 第三方豁免失效。
            findMethod(Activity::class.java, setOf("onCreate"), android.os.Bundle::class.java)
                ?.let { m ->
                    logOnce("hook installed: Activity.onCreate (page name for exemptions)")
                    runCatching { module.deoptimize(m) }
                    module.hook(m).intercept { chain ->
                        runCatching {
                            (chain.thisObject as? Activity)?.let {
                                activeUiClassName = it.javaClass.name
                            }
                        }
                        chain.proceed()
                    }
                }
            runCatching { module.deoptimize(method) }
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                try {
                    val act = chain.thisObject as? Activity ?: return@intercept result
                    val n = act.javaClass.name
                    // 记录当前顶层页面:第三方模块注入的界面(QAuxiliary 等)
                    // 据此整体豁免染色
                    activeUiClassName = n
                    if (n.endsWith("LoginActivity") ||
                        n.endsWith("AccountManageActivity") ||
                        n.endsWith("AccountActivity")
                    ) {
                        val dec = act.window?.decorView ?: return@intercept result
                        runCatching {
                            loginPageMonetizePass(dec, cl)
                        }
                    }
                    // 配色代次变化 → 全界面换新配色（首次启动只记录代次不刷）
                    val gen = MonetPalette.generation()
                    if (appliedThemeGen < 0) {
                        appliedThemeGen = gen
                        return@intercept result
                    }
                    if (gen != appliedThemeGen) {
                        appliedThemeGen = gen
                        val dec2 = act.window?.decorView ?: return@intercept result
                        Log.i(TAG, "palette refresh on resume: gen=$gen")
                        runCatching {
                            forceMonetSubtree(dec2, cl)
                        }
                        // 列表整体重建：让条目 inflate 重新走资源 hook(新配色)
                        runCatching {
                            walkViewTree(dec2, 3000) { v ->
                                if (v.javaClass.name ==
                                    "androidx.recyclerview.widget.RecyclerView"
                                ) {
                                    runCatching {
                                        val m = v.javaClass.getMethod("getAdapter")
                                        m.isAccessible = true
                                        val ad = m.invoke(v)
                                        if (ad != null) {
                                            val n2 = runCatching {
                                                ad.javaClass.getMethod(
                                                    "notifyDataSetChanged"
                                                )
                                            }.getOrNull()
                                            n2?.let { it.isAccessible = true; it.invoke(ad) }
                                        }
                                    }
                                } else if (v.javaClass.name ==
                                    "com.tencent.mobileqq.quibadge.QUIBadge"
                                ) {
                                    runCatching { forceQuiBadge(v) }
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    // ignore
                }
                result
            }
        }
}

/** 登录/添加账号页单次深色化：页面根/decor 背景(浅色 ColorDrawable/Gradient)
 *  平染 surfaceContainer → 整树莫奈化(文本/控件)。 */
private fun loginPageMonetizePass(decor: View, cl: ClassLoader) {
    try {
        val scheme = MonetPalette.palette(false)
        if (!scheme.isDark) return
        val target = if (MonetPalette.isAmoled()) {
            0xFF000000.toInt()
        } else {
            scheme.surfaceContainer
        }
        val content = decor.findViewById(android.R.id.content) as? ViewGroup
        val candidates = arrayOf(decor, content, content?.getChildAt(0))
        for (c in candidates) {
            val b = c?.background ?: continue
            try {
                when (b) {
                    is android.graphics.drawable.ColorDrawable -> {
                        val cur = opaqueColor(b.color)
                        if (colorLuma(cur) >= 120) {
                            b.mutate()
                            b.color = target
                        }
                    }
                    is android.graphics.drawable.GradientDrawable -> {
                        val g0 = runCatching {
                            b.color?.defaultColor
                        }.getOrNull() ?: 0
                        val op = opaqueColor(g0)
                        if (g0 == 0 || colorLuma(op) >= 120) {
                            b.mutate()
                            b.setColor(target)
                        }
                    }
                    else -> {
                        // 位图/九宫格等浅色大底：SRC_IN 平染(保形)
                        runCatching {
                            b.mutate()
                            b.setColorFilter(target, PorterDuff.Mode.SRC_IN)
                            b.setTint(target)
                        }
                    }
                }
            } catch (t: Throwable) {
                // ignore
            }
        }
        // 登录页分层：输入框(EditText 及其带底容器)必须与页面底区分——
        // 输入框的带底祖先(或自身) → surfaceContainerHigh；页面底大块
        // (近白 Gradient/ColorDrawable，宽高>=300) → surfaceContainer。
        runCatching {
            walkViewTree(decor, 600) { v ->
                // 输入框容器：自身带底则用自身，否则向上找最近带背景的祖先
                if (v is android.widget.EditText) {
                    var host: View? = null
                    if (v.background != null) {
                        host = v
                    } else {
                        var pv: android.view.ViewParent? = v.parent
                        var d0 = 0
                        while (pv is View && d0 < 3) {
                            if (pv.background != null) {
                                host = pv
                                break
                            }
                            pv = pv.parent
                            d0++
                        }
                    }
                    if (host != null) {
                        val hb = host.background
                        val hi = scheme.surfaceContainerHigh
                        try {
                            when (hb) {
                                is android.graphics.drawable.GradientDrawable -> {
                                    hb.mutate()
                                    hb.setColor(hi)
                                }
                                is android.graphics.drawable.ColorDrawable -> {
                                    hb.mutate()
                                    hb.color = hi
                                }
                                else -> {
                                    hb.mutate()
                                    hb.setColorFilter(hi, PorterDuff.Mode.SRC_IN)
                                    hb.setTint(hi)
                                }
                            }
                            if (loginPageBgLogCount++ < 8) {
                                Log.i(
                                    TAG,
                                    "login input host on ${host.javaClass.name} -> " +
                                        "#${Integer.toHexString(hi)}"
                                )
                            }
                        } catch (t: Throwable) {
                            // ignore
                        }
                    }
                    return@walkViewTree
                }
                val b = v.background ?: return@walkViewTree
                try {
                    var nearWhite = false
                    when (b) {
                        is android.graphics.drawable.GradientDrawable -> {
                            val g0 = runCatching {
                                b.color?.defaultColor
                            }.getOrNull() ?: 0
                            nearWhite = colorLuma(
                                opaqueColor(g0)
                            ) >= 220
                        }
                        is android.graphics.drawable.ColorDrawable -> {
                            val c0 = opaqueColor(b.color)
                            nearWhite = colorLuma(c0) >= 220
                        }
                        else -> {}
                    }
                    if (nearWhite) {
                        val w0 = v.width
                        val h0 = v.height
                        val isBig = w0 >= 300 && h0 >= 300
                        if (isBig && w0 > 0 && h0 > 0) {
                            if (b is android.graphics.drawable.GradientDrawable) {
                                b.mutate()
                                b.setColor(target)
                            } else {
                                b.mutate()
                                (b as android.graphics.drawable.ColorDrawable)
                                    .color = target
                            }
                            if (loginPageBgLogCount++ < 8) {
                                Log.i(
                                    TAG,
                                    "login page big bg on ${v.javaClass.name} -> " +
                                        "#${Integer.toHexString(target)}"
                                )
                            }
                        }
                    }
                } catch (t: Throwable) {
                    // ignore
                }
            }
        }
        forceMonetSubtree(decor, cl)
    } catch (t: Throwable) {
        Log.w(TAG, "login page monetize failed", t)
    }
}

/** 快捷菜单整棵子树强制染色：面板底（根 View 背景）→ 实色化；子树里过亮的文字
 *  换 onSurface、亮色模式下的浅图标换 onSurfaceVariant。 */
private fun forceMonetQuickMenu(root: View?) {
    if (root == null || !root.isAttachedToWindow) return
    try {
        val scheme = MonetPalette.palette(ThemeState.isNight(null, timClassLoader))
        val panelColor = if (MonetPalette.isAmoled()) {
            0xFF000000.toInt()
        } else if (scheme.isDark) {
            scheme.surfaceContainerHigh
        } else {
            scheme.surfaceBright
        }
        val textColor = scheme.onSurface
        val iconColor = scheme.onSurfaceVariant
        var handledBg = 0
        var handledText = 0
        var handledIcon = 0
        walkViewTree(root, 400) { v ->
            // 背景实色化：根(菜单面板自身)无条件处理(避免深色 root 被浅色门槛
            // 挡掉造成 AIO 长按消息菜单丢染色)；非 root：ColorDrawable 浅色块
            // → panelColor、1~3px 细条(项间分隔线) → outlineVariant；
            // StateList/Gradient 浅 → panelColor。
            val bgV = v.background
            if (bgV != null) {
                if (v === root) {
                    if (forceSolidDrawableColor(bgV, panelColor)) {
                        handledBg++
                        if (quickMenuLogCount++ < 5) {
                            Log.i(
                                TAG,
                                "quick menu bg ${bgV.javaClass.simpleName} -> " +
                                    "#${Integer.toHexString(panelColor)}"
                            )
                        }
                    }
                } else if (bgV is android.graphics.drawable.ColorDrawable) {
                    val cur = runCatching { bgV.color }.getOrNull() ?: 0
                    val op = opaqueColor(cur)
                    val w0 = v.width
                    val h0 = v.height
                    val thin = (w0 in 1..3 && h0 > 3) || (h0 in 1..3 && w0 > 3)
                    if (thin) {
                        // 分隔线(原色中灰如 #515151，luma 不过浅色门槛也要染) →
                        // outlineVariant
                        if (op != scheme.outlineVariant) {
                            runCatching {
                                bgV.mutate()
                                bgV.color = scheme.outlineVariant
                                handledBg++
                                if (quickMenuLogCount++ < 4) {
                                    Log.i(TAG, "quick menu div tinted")
                                }
                            }
                        }
                    } else if (w0 > 0 && h0 > 0 && colorLuma(op) >= 140) {
                        runCatching {
                            bgV.mutate()
                            bgV.color = panelColor
                            handledBg++
                            if (quickMenuLogCount++ < 5) {
                                Log.i(
                                    TAG,
                                    "quick menu bg block -> #${Integer.toHexString(panelColor)}"
                                )
                            }
                        }
                    }
                } else if (bgV is android.graphics.drawable.StateListDrawable ||
                    bgV is android.graphics.drawable.GradientDrawable
                ) {
                    // dialogutils 菜单项按钮背景：StateList(白 normal)/圆角
                    // Gradient 块；浅色才染(避免把已深 pressed 态弄花)
                    val g0 = if (bgV is android.graphics.drawable.GradientDrawable) {
                        runCatching { bgV.color?.defaultColor }.getOrNull() ?: 0
                    } else {
                        0xFFFFFFFF.toInt()
                    }
                    val op = opaqueColor(g0)
                    if (g0 == 0 || colorLuma(op) >= 140) {
                        if (forceSolidDrawableColor(bgV, panelColor)) {
                            handledBg++
                            if (quickMenuLogCount++ < 5) {
                                Log.i(
                                    TAG,
                                    "quick menu bg item ${bgV.javaClass.simpleName} -> " +
                                        "#${Integer.toHexString(panelColor)}"
                                )
                            }
                        }
                    }
                }
            }
            if (v is TextView) {
                val color = v.currentTextColor
                val opaque = opaqueColor(color)
                if (colorLuma(opaque) >= 200 &&
                    opaque != (opaqueColor(textColor))
                ) {
                    v.setTextColor(textColor)
                    handledText++
                }
            }
            // 菜单按钮图标：素材多为深灰/白 glyph，常带白色/皮肤 colorFilter
            // （之前 colorFilter==null 条件把它们全部跳过，图标保持原样白）。
            // 深色/AMOLED 面板 → 染成与文字同色（onSurface），浅色面板 → onSurfaceVariant
            if (v is ImageView) {
                val d = v.drawable
                if (d != null) {
                    val target = if (scheme.isDark) textColor else iconColor
                    runCatching {
                        d.mutate()
                        d.colorFilter = null
                        d.setColorFilter(target, PorterDuff.Mode.SRC_IN)
                        d.setTint(target)
                        handledIcon++
                    }
                }
            }
        }
        if (quickMenuLogCount++ < 8) {
            Log.i(
                TAG,
                "quick menu monetized bg=$handledBg text=$handledText icon=$handledIcon " +
                    "panel=#${Integer.toHexString(panelColor)} dark=${scheme.isDark}"
            )
        }
        // 弹窗装饰层（面板圆角底 + 底部指向消息的小三角）不在内容树里：
        // 它们在 popup 根窗口的背景 LayerDrawable 上，这里从 rootView 向下找
        // “背景是 LayerDrawable”的容器，整层染成面板色，让三角与面板融为一体。
        try {
            val top = root.rootView ?: return
            if (top !== root) {
                val stack2 = java.util.ArrayDeque<View>()
                stack2.add(top)
                var c2 = 0
                while (stack2.isNotEmpty() && c2 < 1500) {
                    val v2 = stack2.removeFirst()
                    c2++
                    if (v2 is ViewGroup) {
                        for (i in 0 until v2.childCount) {
                            val ch = v2.getChildAt(i)
                            // 已处理的内容子树不再重复扫
                            if (ch !== root && ch !== v2) stack2.addLast(ch)
                        }
                    }
                    val bg2 = v2.background
                    if (bg2 != null && v2 !== root) {
                        val changed = when {
                            bg2 is ColorDrawable -> {
                                val cur = opaqueColor(bg2.color)
                                colorLuma(cur) >= 150 &&
                                    forceSolidDrawableColor(bg2, panelColor)
                            }
                            bg2 is GradientDrawable -> {
                                val g0 = runCatching {
                                    bg2.color?.defaultColor
                                }.getOrNull() ?: 0
                                val op = opaqueColor(g0)
                                (g0 == 0 || colorLuma(op) >= 140) &&
                                    forceSolidDrawableColor(bg2, panelColor)
                            }
                            bg2 is LayerDrawable ->
                                forceSolidDrawableColor(bg2, panelColor)
                            bg2 is android.graphics.drawable.StateListDrawable ->
                                forceSolidDrawableColor(bg2, panelColor)
                            else -> false
                        }
                        if (changed) {
                            if (quickMenuLogCount++ < 8) {
                                Log.i(
                                    TAG,
                                    "quick menu layer bg ${v2.javaClass.simpleName} " +
                                        "${bg2.javaClass.simpleName} -> " +
                                        "#${Integer.toHexString(panelColor)}"
                                )
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "quick menu layer scan failed", t)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "force quick menu failed", t)
    }
}

/** 把 drawable 强制成实色（递归容器子项），失败则退回 SRC_IN 染色；成功返回 true。 */
private fun forceSolidDrawableColor(drawable: Drawable?, color: Int): Boolean {
    if (drawable == null) return false
    if (drawable is GradientDrawable) {
        drawable.mutate()
        drawable.setColor(color)
        drawable.invalidateSelf()
        return true
    }
    if (drawable is ColorDrawable) {
        drawable.mutate()
        drawable.setColor(color)
        drawable.invalidateSelf()
        return true
    }
    if (drawable is DrawableContainer) {
        var handled = false
        try {
            val state = drawable.constantState as? DrawableContainer.DrawableContainerState
            state?.children?.forEach { child ->
                if (child != null && forceSolidDrawableColor(child, color)) handled = true
            }
        } catch (t: Throwable) {
            // ignore
        }
        if (handled) drawable.invalidateSelf()
        return handled
    }
    if (drawable is LayerDrawable) {
        var handled = false
        for (i in 0 until drawable.numberOfLayers) {
            if (forceSolidDrawableColor(drawable.getDrawable(i), color)) handled = true
        }
        if (handled) drawable.invalidateSelf()
        return handled
    }
    if (trySetPaintFilter(drawable, color)) return true
    try {
        drawable.mutate()
        drawable.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        drawable.setTint(color)
        drawable.invalidateSelf()
        return true
    } catch (t: Throwable) {
        return false
    }
}

// ============================================================================
// block2.txt —— 从 Java 反编译恢复 Kotlin（恢复产物，供并入 TimMonetHooks.kt）
// 权威真值：recover_ref/TimMonetHooks.java（行号以下方各段标注为准）
// 并入位置：app/src/main/java/com/timmonet/hooks/TimMonetHooks.kt 的 object TimMonetHooks { ... } 体内
//           （object 内函数顺序无关紧要，可与现存活函数任意排列）
//
// 若并入时需要补 import（与文件头现有 import 合并）：
//   import android.text.Spannable          // recolorTextViews / recolorSourceBinding 用到
//   import com.materialkolor.scheme.DynamicScheme   // 仅当保留 bubbleHost 参数类型；文件现只 import 了 Hct
//
// 依赖说明（同名调用，均不应在本块内重新定义）：
//   - monetizeForwardPopup(activity, cl) / forceMonetSubtree(view, cl)：
//       同文件其它恢复块负责的函数（Java 1009-1087 的 lambda 直接同名调用）。
//   - trySetPaintFilter / findMethod / cachedMethod / cachedDeclaredMethod /
//     cachedFieldByType / logOnce：目标文件现存活 helper。
//   - gradientColorOf(drawable): Int?：目标文件头部（约 172-196 行）已有同实现版本（对应 Java 2350-2390），
//     本块按 Java 行号 2211-2391 区间虽含其定义，但为避免重复声明，合并时跳过，
//     本块其余函数（bubbleHost 等）直接调用现存活同名函数即可。
//
// ⚠️ 已知无法完整恢复的部分（见下文具体说明）：
//   - recolorReplyText（Java 2211-2217 仅剩 JADX 未反编译占位，287 条指令正文缺失）；
//   - bubbleHost（Java 2301-2348 为 JADX 寄存器级部分 dump，仅能按 dump 尽力还原，终值有歧义）。
// ============================================================================

// 【本块新增计数字段】并入时放到字段区（同文件其余计数变量风格）；若文件已存在同名则删除本行。
private var timelineRecolorCount = 0

// ============================================================================
// hookForwardRecentTheme —— Java 1009-1087
// 转发页（ForwardRecent）：doOnCreate 打开弹层后整棵弹层树 monetize 成调色板；
//   ForwardRecentListAdapter.getView 每行子树整体递归强刷一次。
// （Java 1059-1074 $lambda$0$0 / 1075-1087 $lambda$1$0$0 已内联为 intercept lambda）
// ============================================================================
private fun hookForwardRecentTheme(module: XposedModule, cl: ClassLoader) {
    try {
        val activityCls = Class.forName("com.tencent.mobileqq.activity.ForwardRecentActivity", false, cl)
        findMethod(activityCls, setOf("doOnCreate"), Bundle::class.java)
            ?.let { method ->
                logOnce("hook installed: ForwardRecentActivity.doOnCreate")
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val activity = chain.thisObject as? Activity
                        if (activity != null) {
                            monetizeForwardPopup(activity, cl)
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "monetize forward popup failed", t)
                    }
                    result
                }
            }
        try {
            val cls = Class.forName("com.tencent.mobileqq.adapter.ForwardRecentListAdapter", false, cl)
            findMethod(cls, setOf("getView"), INT_TYPE, View::class.java, ViewGroup::class.java)
                ?.let { method ->
                    logOnce("hook installed: ForwardRecentListAdapter.getView")
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val view = result as? View
                            if (view != null) {
                                forceMonetSubtree(view, cl)
                            }
                        } catch (t: Throwable) {
                            // ignore
                        }
                        result
                    }
                }
        } catch (t: Throwable) {
            Log.w(TAG, "ForwardRecentListAdapter not found", t)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "ForwardRecentActivity not found", t)
    }
}

// ============================================================================
// hookAlbumTimelineText —— Java 1808-1932
// 相册时间线 TimelineView：方法 a(int,String,int) 与 d() 返回后、setPhotoList(List)
//   返回后（post 到主线程）对整棵子树强制按调色板重设日期文字色 / 气泡背景。
// （Java 1870-1930 三个 $lambda 已内联为 intercept lambda）
// ============================================================================
private fun hookAlbumTimelineText(module: XposedModule, cl: ClassLoader) {
    try {
        val cls = Class.forName("com.tencent.qqnt.qbasealbum.album.view.TimelineView", false, cl)
        findMethod(cls, setOf("a"), INT_TYPE, String::class.java, INT_TYPE)
            ?.let { method ->
                logOnce("hook installed: TimelineView.a (album date text)")
                module.hook(method).intercept { chain ->
                    chain.proceed()
                    try {
                        val view = chain.thisObject as? View
                        if (view != null) {
                            forceTimelineText(view, cl)
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "timeline text fix failed", t)
                    }
                    null
                }
            }
        findMethod(cls, setOf("d"))
            ?.let { method ->
                logOnce("hook installed: TimelineView.d (album date text)")
                module.hook(method).intercept { chain ->
                    chain.proceed()
                    try {
                        val view = chain.thisObject as? View
                        if (view != null) {
                            view.post { forceTimelineText(view, cl) }
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "timeline d fix failed", t)
                    }
                    null
                }
            }
        try {
            val listType = Class.forName("java.util.List", false, cl)
            findMethod(cls, setOf("setPhotoList"), listType)
                ?.let { method ->
                    logOnce("hook installed: TimelineView.setPhotoList (album date text)")
                    module.hook(method).intercept { chain ->
                        chain.proceed()
                        try {
                            val view = chain.thisObject as? View
                            if (view != null) {
                                view.post { forceTimelineText(view, cl) }
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "timeline setPhotoList fix failed", t)
                        }
                        null
                    }
                }
        } catch (t: Throwable) {
            Log.w(TAG, "TimelineView.setPhotoList hook failed", t)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "TimelineView not found", t)
    }
}

// ============================================================================
// forceTimelineText —— Java 1933-1964
// 递归遍历时间线子树：气泡背景 → primary（forceBubbleColor），其上文字 → onPrimary。
// 两个 id 是 QQ 相册时间线气泡/日期文字资源 id（2131308278 / 2131308279）。
// ============================================================================
private fun forceTimelineText(view: View?, cl: ClassLoader) {
    if (view == null) return
    val palette = MonetPalette.palette(ThemeState.isNight(null, cl))
    val primary = palette.primary
    val onPrimary = palette.onPrimary
    if (view is ViewGroup) {
        val childCount = view.childCount
        for (i in 0 until childCount) {
            val child = view.getChildAt(i)
            when (child.id) {
                2131308278 -> forceBubbleColor(child, primary)
                2131308279 -> if (child is TextView) child.setTextColor(onPrimary)
            }
            if (child is TextView && child.background != null) {
                child.setTextColor(onPrimary)
                forceBubbleColor(child, primary)
            }
            forceTimelineText(child, cl)
        }
    }
}

// ============================================================================
// forceBubbleColor —— Java 1965-1998
// 把时间线气泡背景染成 primary：先走 trySetPaintFilter（命中则完成），
// 否则 mutate + SRC_IN 滤镜 + tint。前 10 次打日志。
// ============================================================================
private fun forceBubbleColor(view: View, color: Int) {
    val bg = view.background ?: return
    if (trySetPaintFilter(bg, color)) {
        view.invalidate()
        if (timelineRecolorCount++ < 10) {
            Log.i(TAG, "album timeline bubble bg -> #${Integer.toHexString(color)}")
        }
        return
    }
    try {
        bg.mutate()
        bg.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        bg.setTint(color)
        view.invalidate()
        if (timelineRecolorCount++ < 10) {
            Log.i(TAG, "album timeline bubble bg -> #${Integer.toHexString(color)}")
        }
    } catch (t: Throwable) {
        // ignore
    }
}

// ============================================================================
// hookForceLight —— Java 1999-2103
// 强制浅色：DarkModeManager.f() → false；QQTheme.isNowThemeIsNight() → false、
//   isVasTheme() → false、getCurrentThemeId() → "2971"（伪装成旧默认主题 id）。
// （Java 2081-2103 四个 $lambda 已内联为 intercept lambda）
// ============================================================================
private fun hookForceLight(module: XposedModule, cl: ClassLoader) {
    try {
        val cls = Class.forName(DARK_MODE_MANAGER, false, cl)
        findMethod(cls, setOf("f"))
            ?.let { method ->
                logOnce("hook installed: $DARK_MODE_MANAGER.f")
                module.hook(method).intercept { false }
            }
    } catch (t: Throwable) {
        Log.w(TAG, "DarkModeManager not found", t)
    }
    try {
        val cls = Class.forName(QQ_THEME, false, cl)
        findMethod(cls, setOf("isNowThemeIsNight"))
            ?.let { method ->
                logOnce("hook installed: $QQ_THEME.isNowThemeIsNight")
                module.hook(method).intercept { false }
            }
        findMethod(cls, setOf("getCurrentThemeId"))
            ?.let { method ->
                logOnce("hook installed: $QQ_THEME.getCurrentThemeId")
                module.hook(method).intercept { "2971" }
            }
        findMethod(cls, setOf("isVasTheme"))
            ?.let { method ->
                logOnce("hook installed: $QQ_THEME.isVasTheme")
                module.hook(method).intercept { false }
            }
    } catch (t: Throwable) {
        Log.w(TAG, "QQTheme not found", t)
    }
}

// ============================================================================
// hookAioReply —— Java 2104-2210（recolorReplyText 的两个调用点在 2186-2199 已内联）
// AIO 回复组件 AIOReplyComponent：proceed 后统一 recolorReplyText。
//   - 同步文本方法：V2(int) / W2(ColorStateList)
//   - 异步方法：I3(msg.u, ReplyElement)；b4(msg.u, ReplyElement, MsgRecord)，
//     仅当 MsgRecord 类也加载成功（参数全非空）才 hook。
//   Class.forName 三个消息类型逐一 try/catch 降级为 null（保持 Java 原样）。
// ============================================================================
private fun hookAioReply(module: XposedModule, cl: ClassLoader) {
    try {
        val cls = Class.forName(AIO_REPLY, false, cl)
        for (methodName in listOf("V2", "W2")) {
            val found = if (methodName == "V2") {
                findMethod(cls, setOf(methodName), INT_TYPE)
            } else {
                findMethod(cls, setOf(methodName), ColorStateList::class.java)
            }
            if (found != null) {
                logOnce("hook installed: $AIO_REPLY.$methodName (reply text)")
                module.hook(found).intercept { chain ->
                    val result = chain.proceed()
                    recolorReplyText(chain, cl, "via $methodName")
                    result
                }
            }
        }
        val uType = try {
            Class.forName("com.tencent.mobileqq.aio.msg.u", false, cl)
        } catch (t: Throwable) {
            null
        }
        val replyElementType = try {
            Class.forName("com.tencent.qqnt.kernel.nativeinterface.ReplyElement", false, cl)
        } catch (t: Throwable) {
            null
        }
        val msgRecordType = try {
            Class.forName("com.tencent.qqnt.kernel.nativeinterface.MsgRecord", false, cl)
        } catch (t: Throwable) {
            null
        }
        if (uType == null || replyElementType == null) return
        for ((name, paramTypes) in listOf(
            "I3" to arrayOf<Class<*>?>(uType, replyElementType),
            "b4" to arrayOf<Class<*>?>(uType, replyElementType, msgRecordType)
        )) {
            val nonNullTypes = paramTypes.filterNotNull().toTypedArray()
            if (nonNullTypes.size == paramTypes.size) {
                findMethod(cls, setOf(name), *nonNullTypes)
                    ?.let { method ->
                        logOnce("hook installed: $AIO_REPLY.$name (reply async)")
                        module.hook(method).intercept { chain ->
                            val result = chain.proceed()
                            recolorReplyText(chain, cl, "via async $name")
                            result
                        }
                    }
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "AIOReplyComponent not found", t)
    }
}

/** 引用区里的跳转箭头(按 drawable 名含 arrow 判定)统一成引用文字色。 */
/** 引用块根视图。
 *
 *  AIOReplyComponent.V1() 返回的是引用**内容文字**那个 AIOMsgTextView（5p.xml 里的 s2k），
 *  而跳转箭头是它的兄弟/父级（ReplyTextRelativeLayout 那一层）里的图标，所以在
 *  V1() 里遍历永远找不到。这里往上找带 Reply 的祖先（找不到就退 2 层）。
 */
private fun replyBlockRoot(view: View): View {
    var current: View = view
    repeat(4) {
        val parent = current.parent as? View ?: return current
        current = parent
        val name = current.javaClass.name
        if (name.contains("Reply", ignoreCase = true)) return current
        if (name.contains("RecyclerView")) return view
    }
    return current
}

/** 把颜色写到这条祖先链上**所有**含 Reply 的容器，而不是只写最外层那一个。
 *
 *  recolorReplyText 是从引用文字往上找块，setImageDrawable 是从箭头往上找块，
 *  两边的层数/起点不同，写一个 key 很容易对不上(对不上就每次都读不到颜色 →
 *  箭头只能等补帧 → 闪)。全链写入后，读取端爬到哪一层都能拿到。 */
private fun stashReplyBlockColor(view: View, color: Int) {
    var cur: View? = view
    var hops = 0
    while (cur != null && hops < 16) {
        val name = cur.javaClass.name
        if (name.contains("RecyclerView")) break
        if (name.contains("Reply", ignoreCase = true)) replyBlockColors[cur] = color
        cur = cur.parent as? View
        hops++
    }
}

/** 从引用区子视图往上找已记过颜色的引用块；返回 (块, 颜色)。 */
private fun replyBlockColorOf(view: View): Pair<View, Int>? {
    var cur: View? = view
    var hops = 0
    while (cur != null && hops < 16) {
        val name = cur.javaClass.name
        if (name.contains("RecyclerView")) break
        if (name.contains("Reply", ignoreCase = true)) {
            val c = replyBlockColors[cur]
            if (c != null) return cur to c
        }
        cur = cur.parent as? View
        hops++
    }
    return null
}

/** 最近的含 Reply 祖先（用于没记过颜色时按气泡判侧别）。 */
private fun replyBlockAncestor(view: View): View? {
    var cur: View? = view
    var hops = 0
    while (cur != null && hops < 16) {
        val name = cur.javaClass.name
        if (name.contains("RecyclerView")) return null
        if (name.contains("Reply", ignoreCase = true)) return cur
        cur = cur.parent as? View
        hops++
    }
    return null
}

private fun recolorReplyJumpIcon(view: View, color: Int) {
    logReplyIconInventory(view)
    stashReplyBlockColor(view, color)
    applyReplyJumpIcon(view, color, "now")
    // 引用区里有几个 ImageView 在我们跑的时候 drawable 还是 null(TIM 后面才
    // setImageResource，加载时又被图标兜底染成 onSurface)，所以再补一帧。
    // 只补一次，不做轮询/重试。
    runCatching {
        view.post {
            runCatching { applyReplyJumpIcon(view, color, "post") }
        }
    }
}

/** 给引用区里"跳转箭头"类的图标上色。reason 仅用于日志。 */
private fun applyReplyJumpIcon(view: View, color: Int, reason: String) {
    var applied = 0
    try {
        walkViewTree(view, 200) { v ->
            when (v) {
                is ImageView -> {
                    val d = v.drawable ?: return@walkViewTree
                    if (isJumpArrowDrawable(d, v)) {
                        runCatching {
                            val replaced = rasterizeIconColor(d, color)
                            if (replaced != null) {
                                replyTintedDrawables[replaced] = true
                                v.setImageDrawable(replaced)
                            } else {
                                setJumpArrowColor(d, color)
                            }
                            v.alpha = 1f
                            v.invalidate()
                            applied++
                        }
                    }
                }
                is TextView -> {
                    var changed = false
                    v.compoundDrawables.forEach { d ->
                        if (d != null && isJumpArrowDrawable(d, v)) {
                            runCatching {
                                setJumpArrowColor(d, color)
                                changed = true
                            }
                        }
                    }
                    if (changed) {
                        v.invalidate()
                        applied++
                    }
                }
                else -> Unit
            }
        }
    } catch (t: Throwable) {
        // ignore
    }
    if (applied > 0 && replyArrowLogCount++ < 40) {
        Log.i(
            TAG,
            "reply jump icon tinted($reason) -> #" + Integer.toHexString(color) + " x" + applied
        )
    }
}

/** 引用块视图 → 该引用块的文字色（由 recolorReplyText 填，供 setImageDrawable
 *  那一刻立刻上色用，避免等补帧时闪一帧原色）。 */
private val replyBlockColors =
    java.util.Collections.synchronizedMap(java.util.WeakHashMap<View, Int>())

/** 我们自己栅格化出来的箭头位图 —— 防止 setImageDrawable hook 递归处理自己。 */
private val replyTintedDrawables =
    java.util.Collections.synchronizedMap(java.util.WeakHashMap<Drawable, Boolean>())

private var replyIconSetLogCount = 0

/** 在 setImageDrawable 那一刻推引用块该用什么色：与 recolorReplyText 同一套判定 ——
 *  AMOLED 统一 onSurface；否则按气泡背景判自方(primary 气泡 → onPrimary)/
 *  对方(surface 系 → onSurface)。不是猜色，判不出来返回 null。 */
private fun inferReplyColor(block: View): Int? = try {
    val cl = block.context?.classLoader
    val scheme = MonetPalette.palette(ThemeState.isNight(null, cl))
    if (MonetPalette.isAmoled()) {
        scheme.onSurface
    } else {
        when (bubbleHost(block, scheme)) {
            true -> scheme.onPrimary
            false -> scheme.onSurface
            null -> null
        }
    }
} catch (t: Throwable) {
    null
}

/** setImageDrawable 时就把引用跳转箭头换成目标色。@return true 表示已处理 */
private fun handleReplyJumpIcon(view: ImageView, drawable: Drawable): Boolean {
    val block = replyBlockAncestor(view) ?: return false
    // ① 引用块文字色已经算过 → 直接用它；② 还没算过 → 用气泡背景判侧别
    // (与 recolorReplyText 同一套判定)；③ 连侧别都判不出来才先隐藏，等那遍上色。
    val known = replyBlockColorOf(view)
    val stash = known?.second
    val color = stash ?: inferReplyColor(known?.first ?: block)
    if (color == null) {
        view.alpha = 0f
        view.postDelayed({
            runCatching { if (view.alpha == 0f) view.alpha = 1f }
        }, 200L)
        if (replyIconSetLogCount++ < 40) {
            Log.i(
                TAG,
                "reply icon set hidden(no color) block=" + block.javaClass.simpleName +
                    " w=" + view.width + " h=" + view.height
            )
        }
        return true
    }
    val replaced = rasterizeIconColor(drawable, color)
    if (replaced != null) {
        replyTintedDrawables[replaced] = true
        view.setImageDrawable(replaced)
        view.alpha = 1f
    }
    if (replyIconSetLogCount++ < 40) {
        Log.i(
            TAG,
            "reply icon set block=" + block.javaClass.simpleName +
                " from=" + (if (stash != null) "stash" else "bubble") +
                " -> #" + Integer.toHexString(color) + " ok=" + (replaced != null)
        )
    }
    return true
}

/** 把图标栅格化成"指定色 + 保留原 alpha/明暗"的新位图 drawable。
 *
 *  引用区的跳转箭头是皮肤位图(qui_tui_icon_set_top_tiny_allwhite_primary，
 *  带 allwhite 令牌)，皮肤引擎会在**绘制时**按令牌重新上色，所以只改 drawable
 *  内部 Paint 会被盖掉 —— 换成我们自己生成的位图后就没有人能再改它了
 *  (面板图标当初也是这么处理的)。 */
private fun rasterizeIconColor(drawable: Drawable, color: Int): Drawable? {
    return try {
        var iw = drawable.intrinsicWidth
        var ih = drawable.intrinsicHeight
        if (iw <= 0 || ih <= 0) {
            val b = drawable.bounds
            iw = b.width()
            ih = b.height()
        }
        if (iw <= 0 || ih <= 0 || iw > 240 || ih > 240) return null
        val bmp = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val saved = Rect(drawable.bounds)
        drawable.setBounds(0, 0, iw, ih)
        drawable.draw(canvas)
        drawable.bounds = saved
        val px = IntArray(iw * ih)
        bmp.getPixels(px, 0, iw, 0, 0, iw, ih)
        var core = 0
        for (i in px.indices) {
            val a = (px[i] ushr 24) and 0xFF
            if (a < 8) {
                px[i] = 0
                continue
            }
            core++
            val src = px[i]
            val lum = (((src ushr 16) and 0xFF) * 299 + ((src ushr 8) and 0xFF) * 587 +
                (src and 0xFF) * 114) / 1000
            val k = 0.35f + 0.65f * (lum / 255f)
            val r = (((color ushr 16) and 0xFF) * k).toInt().coerceIn(0, 255)
            val g = (((color ushr 8) and 0xFF) * k).toInt().coerceIn(0, 255)
            val b = ((color and 0xFF) * k).toInt().coerceIn(0, 255)
            px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        if (core < 4) return null
        val out = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, iw, 0, 0, iw, ih)
        BitmapDrawable(Resources.getSystem(), out)
    } catch (t: Throwable) {
        null
    }
}

/** 给跳转箭头上色。
 *
 *  注意 TIM 的皮肤位图 drawable(SkinnableBitmapDrawable) 的着色落在内部
 *  Paint.colorFilter 上（我们的图标兜底就是这么染的），只调 setTint 盖不掉，
 *  所以这里优先改内部 Paint，其次才退回 setTint + setColorFilter。
 */
private fun setJumpArrowColor(d: Drawable, color: Int) {
    val state = runCatching {
        d.javaClass.getDeclaredField("mBitmapState").also { it.isAccessible = true }.get(d)
    }.getOrNull()
    if (state != null) {
        val paint = runCatching {
            state.javaClass.getDeclaredField("mPaint").also { it.isAccessible = true }
                .get(state) as? Paint
        }.getOrNull()
        if (paint != null) {
            paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            d.invalidateSelf()
            return
        }
    }
    d.mutate()
    d.setTint(color)
    d.setColorFilter(color, PorterDuff.Mode.SRC_IN)
}

/** 跳转箭头判定。
 *
 *  只按资源名不可靠：drawableNameMemo 是按 drawable **实例**登记的，而 TIM 的
 *  皮肤引擎常常把 drawable 复制一份再交给 ImageView，身份就对不上了。
 *  所以：名字拿得到就按名字（含 arrow 命中、图片类排除），拿不到就按尺寸 ——
 *  引用区里 16dp 级别的小图标就是跳转箭头，引用缩略图要大得多。
 */
private fun isJumpArrowDrawable(d: Drawable, host: View?): Boolean {
    if (replyTintedDrawables.containsKey(d)) return false // 已是我们生成的位图
    val name = drawableNameMemo[d] ?: runCatching {
        d.constantState?.let { drawableStateNameMemo[System.identityHashCode(it)] }
    }.getOrNull()
    if (name != null) {
        if (name.contains("arrow") || name.contains("set_top")) return true
        if (name.contains("pic") || name.contains("photo") || name.contains("thumb") ||
            name.contains("cover") || name.contains("avatar") || name.contains("emoji") ||
            name.contains("face") || name.contains("bubble")
        ) {
            return false
        }
    }
    val view = host ?: return false
    // 引用区里名字拿不到的其它图片控件(缩略图/气泡底图)不要当成箭头
    val hostName = view.javaClass.simpleName
    if (hostName.contains("Async") || hostName.contains("Bubble") || hostName.contains("Round")) {
        return false
    }
    val density = view.resources.displayMetrics.density
    val maxPx = (24f * density).toInt()
    val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else view.width
    val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else view.height
    return w in 1..maxPx && h in 1..maxPx
}

/** 一次性诊断：把引用区里看到的图标(名字/尺寸)列出来，便于定位跳转箭头。 */
private fun logReplyIconInventory(view: View) {
    if (replyIconInventoryCount >= 5) return
    replyIconInventoryCount++
    val density = view.resources.displayMetrics.density
    val items = ArrayList<String>()
    var nodes = 0
    walkViewTree(view, 400) { v ->
        nodes++
        val nameOf = { d: Drawable ->
            drawableNameMemo[d] ?: runCatching {
                d.constantState?.let { drawableStateNameMemo[System.identityHashCode(it)] }
            }.getOrNull() ?: "?"
        }
        when (v) {
            is ImageView -> items.add(
                "IV${v.width}x${v.height}/${v.javaClass.simpleName}" +
                    " d=${v.drawable?.intrinsicWidth}x${v.drawable?.intrinsicHeight}" +
                    " name=${v.drawable?.let(nameOf) ?: "null"}"
            )
            is TextView -> v.compoundDrawables.forEachIndexed { i, d ->
                if (d != null) {
                    items.add("TV$i d=${d.intrinsicWidth}x${d.intrinsicHeight} name=${nameOf(d)}")
                }
            }
            else -> Unit
        }
    }
    Log.i(
        TAG,
        "reply icon inventory #$replyIconInventoryCount density=$density nodes=$nodes root=" +
            view.javaClass.simpleName + " :: " + items.joinToString(" | ")
    )
}

private var replyIconInventoryCount = 0

private var replyArrowLogCount = 0

private fun recolorReplyText(chain: XposedInterface.Chain, cl: ClassLoader, reason: String) {
    try {
        val obj = chain.thisObject ?: return
        // 气泡根视图：AIOReplyComponent.V1() -> View
        val view = runCatching {
            cachedMethod(obj.javaClass, "V1")?.invoke(obj) as? View
        }.getOrNull() ?: return
        val scheme = MonetPalette.palette(ThemeState.isNight(null, cl))
        val host = replyIsSelf(obj) ?: bubbleHost(view, scheme) ?: false
        // AMOLED 黑：气泡纯黑，引用区文字与对方统一 onSurface、链接统一亮主色，
        // 避免原 onPrimary 深青在纯黑上无对比度
        val amoled = MonetPalette.isAmoled()
        val color = if (amoled) {
            scheme.onSurface
        } else if (host) {
            scheme.onPrimary
        } else {
            scheme.onSurface
        }
        // 非 AMOLED：自己气泡引用区链接 = 直接发送气泡的链接色（页面最亮面）
        val linkColor = when {
            amoled -> scheme.primary
            host -> if (scheme.isDark) scheme.surfaceBright else scheme.surface
            else -> scheme.primary
        }
        recolorTextViews(view, color, linkColor)
        // 引用区右上角"跳转到引用消息"的箭头：TIM 用的是通用列表箭头
        // (qui_tui_list_arrow_right_primary)，我们的图标兜底会把它统一染成
        // onSurface，于是自己气泡里就和引用文字(onPrimary)不一致 —— 这里按
        // 气泡方向单独给它上色（含 TextView 的 compound drawable 形式）。
        recolorReplyJumpIcon(replyBlockRoot(view), color)
        recolorSourceBinding(obj, color, linkColor, cl)
        val i = replyLogCount++
        if (i < 30) {
            val finalColor = firstTextViewColor(view)
            val bindingColor = firstSourceBindingColor(obj, cl)
            Log.i(
                TAG,
                "reply text $reason " + (if (host) "host" else "guest") +
                    " applied=#" + Integer.toHexString(color) +
                    " final=#" + Integer.toHexString(finalColor ?: 0) +
                    " binding=#" + Integer.toHexString(bindingColor ?: 0)
            )
        }
    } catch (t: Throwable) {
        Log.w(TAG, "tint reply text failed", t)
    }
}

// ============================================================================
// firstSourceBindingColor —— Java 2219-2251
// 从组件里找“源绑定对象”（com.tencent.mobileqq.aio.b.m 类型字段）：
//   沿类继承链向上，遍历 TextView 类型字段，返回第一个 TextView 当前文字色；读不到返回 null。
// ============================================================================
private fun firstSourceBindingColor(component: Any, cl: ClassLoader): Int? {
    try {
        val bindingType = Class.forName("com.tencent.mobileqq.aio.b.m", false, cl)
        val bindingField = cachedFieldByType(component.javaClass, bindingType)
        if (bindingField != null) {
            bindingField.isAccessible = true
            val binding = bindingField.get(component)
            if (binding != null) {
                val seen = HashSet<Field>()
                var cls: Class<*>? = binding.javaClass
                while (cls != null) {
                    for (field in cls.declaredFields) {
                        if (seen.add(field) && TextView::class.java.isAssignableFrom(field.type)) {
                            field.isAccessible = true
                            val tv = field.get(binding) as? TextView
                            if (tv != null) return tv.currentTextColor
                        }
                    }
                    cls = cls.superclass
                }
            }
        }
        return null
    } catch (t: Throwable) {
        return null
    }
}

// ============================================================================
// firstTextViewColor —— Java 2253-2270
// 深度优先找子树里第一个 TextView 的当前文字色；没有则 null。
// ============================================================================
private fun firstTextViewColor(view: View?): Int? {
    if (view == null) return null
    if (view is TextView) return view.currentTextColor
    if (view is ViewGroup) {
        val childCount = view.childCount
        for (index in 0 until childCount) {
            val color = firstTextViewColor(view.getChildAt(index))
            if (color != null) return color
        }
    }
    return null
}

// ============================================================================
// replyIsSelf —— Java 2272-2291
// 反射问组件：h2() 取消息项 → isSelf() 是否为本人消息；读不到返回 null。
// ============================================================================
private fun replyIsSelf(component: Any): Boolean? {
    val method = cachedDeclaredMethod(component.javaClass, "h2") ?: return null
    return try {
        val msgItem = method.invoke(component) ?: return null
        cachedMethod(msgItem.javaClass, "isSelf")?.invoke(msgItem) as? Boolean
    } catch (t: Throwable) {
        null
    }
}

// ============================================================================
// bubbleHost —— Java 2301-2348（JADX 部分反编译：寄存器级 dump + 缺失 return null 块）
// ⚠️ 尽力还原，未达到“完全一致”保证：
//   * 语义（按 dump）：自 view 向上最多 16 层；每层背景取纯色 gradientColorOf：
//       == scheme.primary → true（命中已 monetize 的气泡宿主）；
//       == surface / surfaceBright / surfaceContainerHigh → false；
//     其余继续向上找。
//   * 循环自然退出（view 为空 / 超 16 层 / 父级不是 View）→ 寄存器 r2=0 处 JADX 标
//     “missing block: return null”，故按 return null（Boolean?）处理；若拿到 smali
//     请复核此分支究竟返回 null 还是 false。
// ============================================================================
private fun bubbleHost(view: View?, scheme: com.materialkolor.scheme.DynamicScheme): Boolean? {
    var cur: View? = view
    var depth = 0
    while (cur != null && depth < 16) {
        val color = gradientColorOf(cur.background)
        if (color != null) {
            if (color == scheme.primary) return true
            if (color == scheme.surface ||
                color == scheme.surfaceBright ||
                color == scheme.surfaceContainerHigh ||
                (MonetPalette.isAmoled() && color == 0xFF000000.toInt())
            ) {
                return false
            }
        }
        cur = cur.parent as? View
        depth++
    }
    return null
}

// ============================================================================
// recolorTextViews —— Java 2392-2415
// 遍历（子）树：把每个 TextView 文字色 / 链接色覆写为给定颜色，并清掉
// 残留的 ForegroundColorSpan（避免旧 span 再压过新色）。
// ============================================================================
private fun recolorTextViews(view: View?, color: Int, linkColor: Int) {
    if (view == null) return
    if (view is TextView) {
        view.setTextColor(color)
        view.setLinkTextColor(linkColor)
        val text = view.text
        if (text is Spannable) {
            for (span in text.getSpans(0, text.length, ForegroundColorSpan::class.java)) {
                text.removeSpan(span)
            }
        }
    }
    if (view is ViewGroup) {
        val childCount = view.childCount
        for (index in 0 until childCount) {
            recolorTextViews(view.getChildAt(index), color, linkColor)
        }
    }
}

// ============================================================================
// recolorSourceBinding —— Java 2416-2487
// 组件里的“源绑定对象”（com.tencent.mobileqq.aio.b.m 类型字段）：
//   沿继承链找全部 TextView 类型字段，逐个覆写文字/链接色并清 ForegroundColorSpan。
//   任意异常一律静默（Java 原样：catch (Throwable) {}）。
// ============================================================================
private fun recolorSourceBinding(component: Any, color: Int, linkColor: Int, cl: ClassLoader) {
    try {
        val bindingType = Class.forName("com.tencent.mobileqq.aio.b.m", false, cl)
        val bindingField = cachedFieldByType(component.javaClass, bindingType)
        if (bindingField != null) {
            bindingField.isAccessible = true
            val binding = bindingField.get(component) ?: return
            val seen = HashSet<Field>()
            var cls: Class<*>? = binding.javaClass
            while (cls != null) {
                for (field in cls.declaredFields) {
                    if (seen.add(field) && TextView::class.java.isAssignableFrom(field.type)) {
                        field.isAccessible = true
                        val tv = field.get(binding) as? TextView
                        if (tv != null) {
                            tv.setTextColor(color)
                            tv.setLinkTextColor(linkColor)
                            val text = tv.text
                            if (text is Spannable) {
                                for (span in text.getSpans(0, text.length, ForegroundColorSpan::class.java)) {
                                    text.removeSpan(span)
                                }
                            }
                        }
                    }
                }
                cls = cls.superclass
            }
        }
    } catch (t: Throwable) {
        // ignore
    }
}

// ============================================================================
// 块3（block 3）恢复内容 — 忠实译自 recover_ref/TimMonetHooks.java
//   isExactSummaryBadge（2503-2528）/ containsAny（2529-2537）
//   indexOfIgnoreCase + lowerAscii（2538-2568）/ tintPillBackground（2569-2585）
//   tintTextViewBadge（2586-2625）/ probeWhiteNumberText（2626-2633）
//   hookSummaryBadge（2663-2723，lambda 2724-2816 内联）
// 约定：本块只含以下函数定义，粘入 TimMonetHooks object 体内即可（imports 已齐）。
// ============================================================================

/** 群摘要角标"精确文案"命中（有人@我/有新文件 带不带方括号 4 串 + 正则兜底）。 */
private fun isExactSummaryBadge(t: String): Boolean = when (t) {
    "有人@我" -> true
    "[有人@我]" -> true
    "有新文件" -> true
    "[有新文件]" -> true
    else -> Regex("^\\[?[0-9+]+条(新)?消息\\]?$").matches(t)
}

/** 任一 needle 在 text 中出现（ASCII 忽略大小写）即返回 true。 */
private fun containsAny(text: CharSequence, vararg needles: String): Boolean {
    for (needle in needles) {
        if (indexOfIgnoreCase(text, needle) >= 0) {
            return true
        }
    }
    return false
}

/** 手动 ASCII-only 忽略大小写查找（只折 A-Z），返回命中起点或 -1。 */
private fun indexOfIgnoreCase(text: CharSequence, needle: String): Int {
    if (needle.isEmpty() || text.length < needle.length) {
        return -1
    }
    val last = text.length - needle.length
    var start = 0
    while (start <= last) {
        var matched = true
        for (i in needle.indices) {
            if (lowerAscii(text[start + i]) != lowerAscii(needle[i])) {
                matched = false
                break
            }
        }
        if (matched) return start
        start++
    }
    return -1
}

/** 只对 ASCII A-Z 做小写折叠（其它字符原样返回）。 */
private fun lowerAscii(c: Char): Char =
    if ('A' <= c && c < '[') (c.code + 32).toChar() else c

/** 胶囊背景染色：已有背景 mutate + SRC_IN + tint；无背景且 createIfMissing 时新建圆角底。 */
private fun tintPillBackground(view: View?, bgColor: Int, createIfMissing: Boolean) {
    if (view == null) {
        return
    }
    val bg = view.background
    if (bg != null) {
        bg.mutate()
        bg.setColorFilter(bgColor, PorterDuff.Mode.SRC_IN)
        bg.setTint(bgColor)
    } else if (createIfMissing) {
        val gd = GradientDrawable()
        gd.setCornerRadius(view.resources.displayMetrics.density * 10.0f)
        gd.setColor(bgColor)
        view.setBackground(gd)
    }
}

/** TextView 角标按种类染色：撤回=跟行背景灰字；摘要=primary 胶囊 + onPrimary 字。 */
private fun tintTextViewBadge(tv: TextView?, text: CharSequence?) {
    val kind = summaryBadgeKind(text)
    if (tv == null || kind == SummaryBadgeKind.NONE) {
        return
    }
    try {
        val dark = ThemeState.isNight(null, timClassLoader)
        val scheme = MonetPalette.palette(dark)
        when (kind) {
            SummaryBadgeKind.RECALL -> {
                // 撤回提示长条：背景跟随所在聊天条目，文字用 onSurfaceVariant
                tintPillBackground(
                    tv,
                    findRowCardColor(tv, dark, text ?: ""),
                    createIfMissing = false
                )
                tv.setTextColor(scheme.onSurfaceVariant)
                logOnce("recall pill follows row bg: " + text)
                return
            }
            SummaryBadgeKind.SUMMARY -> {
                if (tv.background == null) {
                    return
                }
                // 只染已有背景，不新建胶囊（防止误给普通 TextView 加底色）
                tintPillBackground(tv, scheme.primary, createIfMissing = false)
                tv.setTextColor(scheme.onPrimary)
                logOnce("summary badge monetized: " + text)
                return
            }
            SummaryBadgeKind.NONE -> return
        }
    } catch (t: Throwable) {
        Log.w(TAG, "tint text badge failed", t)
    }
}

/**
 * 白色数字角标文本探测。
 *
 * ⚠️ 反编译正文丢失（jadx 对该方法 dump 失败，只剩抛错桩），暂以空实现占位
 * （保留调用点以免热路径崩溃）；功能待字节码级恢复。
 */
private fun probeWhiteNumberText(tv: TextView?, text: CharSequence?) {
    // 白字纯数字(1~4位)在 primary 圆点底上 → onPrimary(徽标数字晚 bind
    // 场景：attach 时文本为空，等 setText 写入数字后再判)。
    if (tv == null || text == null) return
    val s = text.toString().trim()
    // 账号类文本(QQ 号等,含 "QQ: 123..." 形式):改成不透明的 onSurface,
    // 与"对方气泡内文字"一致(实测"我的"页 QQ 号原为次要文字色@55% alpha)
    if (looksLikeAccountText(s)) {
        runCatching {
            val scheme = MonetPalette.palette(ThemeState.isNight(null, timClassLoader))
            if (scheme.isDark) {
                tv.alpha = 1f
                tv.setTextColor(scheme.onSurface)
            }
        }
    }
    if (s.isEmpty() || s.length > 4 || !s.all { it.isDigit() }) return
    try {
        val scheme = MonetPalette.palette(ThemeState.isNight(null, timClassLoader))
        tintBadgeDigitText(tv, scheme)
    } catch (t: Throwable) {
        // ignore
    }
}

/**
 * 群摘要角标 hook：TextView.setText 两种变体（CharSequence,BufferType 与单参
 * CharSequence）+ View.setBackgroundResource（cja/cj9/jng/cjb/jni 五个背景资源）。
 */
private fun hookSummaryBadge(module: XposedModule) {
    // TextView.setText(CharSequence, BufferType)：主要入口
    findMethod(
        TextView::class.java, setOf("setText"),
        CharSequence::class.java, TextView.BufferType::class.java
    )?.let { method ->
        logOnce("hook installed: TextView.setText (summary badge)")
        runCatching { module.deoptimize(method) }
        module.hook(method).intercept { chain ->
            val result = chain.proceed()
            val view = chain.thisObject as? TextView
            val arg = chain.getArg(0) as? CharSequence
            probeWhiteNumberText(view, arg)
            remapDarkSpans(view)
            tintTextViewBadge(view, arg)
            tintTodoRedText(view, arg)
            // 诊断调用（fixWarmIconSpans / warmTextColorDiag / dumpRowTree）已决定删除，略过
            result
        }
    }
    // TextView.setText(CharSequence)：单参变体（Java 该 hook 无 logOnce，照抄）
    findMethod(TextView::class.java, setOf("setText"), CharSequence::class.java)
        ?.let { method ->
            runCatching { module.deoptimize(method) }
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                val view = chain.thisObject as? TextView
                val arg = chain.getArg(0) as? CharSequence
                probeWhiteNumberText(view, arg)
                remapDarkSpans(view)
                tintBadgeIfMatch(chain)
                tintTodoRedText(view, arg)
                // 诊断调用（fixWarmIconSpans / warmTextColorDiag / dumpRowTree）已决定删除，略过
                result
            }
        }
    // View.setBackgroundResource：命中 5 个已知角标背景资源名（cja/cj9/jng/cjb/jni）
    // 则背景染 primary、TextView 文字染 onPrimary。
    val badgeDrawables = setOf("cja", "cj9", "jng", "cjb", "jni")
    findMethod(View::class.java, setOf("setBackgroundResource"), INT_TYPE)
        ?.let { method ->
            logOnce("hook installed: View.setBackgroundResource (summary badge)")
            runCatching { module.deoptimize(method) }
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                val view = try {
                    chain.thisObject as? View
                } catch (t: Throwable) {
                    Log.w(TAG, "tint badge pill failed", t)
                    null
                }
                if (view == null) {
                    return@intercept result
                }
                val resId = chain.getArg(0) as Int
                // 资源 id 高位为 1（系统资源）不处理
                if (resId shr 24 == 1) {
                    return@intercept result
                }
                val name = entryName(view.resources, resId)
                if (name != null && name in badgeDrawables) {
                    val scheme = MonetPalette.palette(ThemeState.isNight(null, timClassLoader))
                    val background = view.background
                    if (background != null) {
                        background.mutate()
                        background.setColorFilter(scheme.primary, PorterDuff.Mode.SRC_IN)
                        background.setTint(scheme.primary)
                    }
                    val textView = view as? TextView
                    if (textView != null) {
                        textView.setTextColor(scheme.onPrimary)
                    }
                    logOnce("summary badge pill tinted ($name)")
                }
                result
            }
        }
}

// ============================================================================
// 恢复备注（供合并方阅读，非代码）：
// 1) remapDarkSpans：现 Kotlin 文件无此定义（被误删），hookSummaryBadge 两个
//    setText 拦截体内仍按 Java 顺序调用 remapDarkSpans(view)——正文需要块5恢复，
//    本块不自行实现。
// 2) tintTodoRedText：现文件只有计数 var（todoRedLogCount/todoSeenLogCount），
//    函数正文缺失（Java 2634-2661），由对应恢复块补齐；本块拦截体内按 Java 顺序
//    保留其调用。
// 3) probeWhiteNumberText：Java 反编译失败只剩抛错桩，本块未臆造正文（见函数上方
//    注释），需另找来源恢复真实实现后再合并，勿在热路径抛错。
// 4) 已删除诊断函数 fixWarmIconSpans / warmTextColorDiag / dumpRowTree 的调用
//    未翻译（规则 6）。
// ============================================================================

    // ------------------------------------------------------------------
    // 聊天列表摘要高亮前缀（“有人@我 / 群待办 / xx条新消息”等红/橙前缀字符）：
    // item.v() 取摘要对象 → 摘要.d() 取当前前缀色 → 摘要.h(primary) 染成主色；
    // 条目参数 params.b() 拿 Context，用于读 TIM 错误红资源 0x7f060b6d 对照。
    // 命中错误红/通知红色系的前缀才染色，其余非错误红前缀保持原色。
    // ------------------------------------------------------------------
    private fun hookChatsSummaryHighlight(module: XposedModule, cl: ClassLoader) {
        val cls = try {
            Class.forName("com.tencent.qqnt.chats.biz.summary.highlight.a.c", false, cl)
        } catch (t: Throwable) {
            Log.w(TAG, "chats summary highlight not found", t)
            return
        }
        val itemType = try {
            Class.forName("com.tencent.qqnt.chats.core.adapter.b.g", false, cl)
        } catch (t: Throwable) {
            Log.w(TAG, "chats item type not found", t)
            return
        }
        val paramsType = try {
            Class.forName("com.tencent.qqnt.chats.c.a.c", false, cl)
        } catch (t: Throwable) {
            Log.w(TAG, "chats params type not found", t)
            return
        }
        findMethod(cls, setOf("b"), itemType, paramsType)
            ?.let { method ->
                logOnce("hook installed: chats summary highlight color")
                runCatching { module.deoptimize(method) }
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val item = chain.getArg(0)
                        val params = chain.getArg(1)
                        if (item == null || params == null) return@intercept result
                        val summary = cachedMethod(item.javaClass, "v")?.invoke(item)
                        if (summary == null) return@intercept result
                        // 当前前缀字符颜色（summary.d()），读不到就不动
                        val prefixColor = try {
                            cachedMethod(summary.javaClass, "d")?.invoke(summary) as? Int
                        } catch (t: Throwable) {
                            Log.w(TAG, "tint chats summary highlight failed", t)
                            return@intercept result
                        } ?: return@intercept result
                        val context = cachedMethod(params.javaClass, "b")?.invoke(params) as? Context
                        if (context == null) return@intercept result
                        val errorColor = try {
                            context.resources.getColor(0x7f060b6d)
                        } catch (t: Throwable) {
                            0
                        }
                        val opaque = (prefixColor and 0xFFFFFF) or 0xFF000000.toInt()
                        if (!isErrorRed(opaque) &&
                            (errorColor == 0 || opaque != ((errorColor and 0xFFFFFF) or 0xFF000000.toInt()))
                        ) {
                            // 非错误红前缀：保留原色，前 12 次打日志用于排查
                            if (summaryHighlightLogCount++ >= 12) return@intercept result
                            Log.i(
                                TAG,
                                "chats summary prefix keep #" + Integer.toHexString(prefixColor) +
                                    " summary=" + summary.javaClass.simpleName
                            )
                            return@intercept result
                        }
                        // 错误红/通知红前缀 → primary
                        val scheme = MonetPalette.palette(ThemeState.isNight(null, cl))
                        cachedMethod(summary.javaClass, "h", Integer::class.java)
                            ?.invoke(summary, scheme.primary)
                        if (summaryHighlightLogCount++ >= 12) return@intercept result
                        Log.i(
                            TAG,
                            "chats summary prefix #" + Integer.toHexString(prefixColor) + " -> #" +
                                Integer.toHexString(scheme.primary) + " summary=" +
                                summary.javaClass.simpleName
                        )
                    } catch (t: Throwable) {
                        Log.w(TAG, "tint chats summary highlight failed", t)
                    }
                    result
                }
            }
    }

    // ============================================================================
    // 资料卡内容区 / 加好友按钮 / 开关 / 未读气泡（Java 3284-4353）
    // ----------------------------------------------------------------------------
    // hookProfileContentCard 全家桶（setBackground 替换亮底、ProfileCardAdapter.getView
    // 行背景、attach/detach 的 profileUiCount 计数与三次延迟兜底、rebuildProfileContent
    // 后整树强制刷卡），加好友按钮背景/文字、Switch 轨道与滑块、UnreadBubbleVB 的
    // “回到最新”双色位图与未读数气泡，以及资料卡行文字/spans 的深色归一。
    //
    // 注意：recover_ref 里 recolorBackBottomBitmap / forceProfilePageCards / solidColorOf
    // 被 jadx 以 “Method dump skipped, instructions count” 跳过（428/524/310 条指令）。
    // 这三个函数体是依据同一份源码编译的 debug 构建产物
    // （app/build/intermediates/project_dex_archive/debug/.../TimMonetHooks.dex，
    // 与 recover_ref 完全同版：同样含 remapDarkSpans / fixProfileRowTexts）经
    // jadx --show-bad-code + Fernflower 重新反编译恢复的，与调用方语义一致。
    // mapPopupTextColor 已由其它恢复块加入，此处直接调用不再重复定义。
    // ============================================================================

    /** 资料卡内容页整组钩子：内容卡背景强制 surfaceBright，行背景/文字/子卡统一重染。 */
    private fun hookProfileContentCard(module: XposedModule, cl: ClassLoader) {
        try {
            val profileContentCls = Class.forName(
                "com.tencent.mobileqq.profilecard.base.view.ProfileContentView",
                false,
                cl
            )
            // 内容卡表面：scheme.surfaceBright 纯色渐变（AMOLED 黑时压成纯黑）
            val cardFactory: () -> Drawable = {
                GradientDrawable().apply {
                    setColor(
                        MonetPalette.amoledBlack(
                            MonetPalette.palette(ThemeState.isNight(null, cl)).surfaceBright
                        )
                    )
                }
            }
            // View.setBackground：资料卡激活期间，把“空背景/近白纯色底”替换成 surfaceBright
            findMethod(View::class.java, setOf("setBackground"), Drawable::class.java)
                ?.let { method ->
                    logOnce("hook installed: View.setBackground (profile card force)")
                    runCatching { module.deoptimize(method) }
                    module.hook(method).intercept { chain ->
                        if (profileUiCount <= 0) return@intercept chain.proceed()
                        val view = chain.thisObject as? View
                        val incoming = chain.getArg(0) as? Drawable
                        // 近白判定：无新背景（null）或背景为 luma>=235 的纯色
                        val nearWhite = if (incoming == null) {
                            true
                        } else {
                            solidColorOf(incoming)?.let {
                                colorLuma(it or 0xFF000000.toInt()) >= 235
                            } ?: false
                        }
                        if (view != null && nearWhite && isProfileContent(view, profileContentCls)) {
                            chain.proceed(arrayOf<Any>(cardFactory()))
                        } else {
                            chain.proceed()
                        }
                    }
                }
            // ProfileCardAdapter.getView：行背景（容器/图层/普通 drawable）重染 + 行文字修复
            val adapterCls = try {
                Class.forName("com.tencent.mobileqq.profilecard.ProfileCardAdapter", false, cl)
            } catch (t: Throwable) {
                null
            }
            if (adapterCls != null) {
                findMethod(
                    adapterCls,
                    setOf("getView"),
                    INT_TYPE,
                    View::class.java,
                    ViewGroup::class.java
                )?.let { method ->
                    logOnce("hook installed: ProfileCardAdapter.getView (profile row bg)")
                    runCatching { module.deoptimize(method) }
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        val row = try {
                            result as? View
                        } catch (t: Throwable) {
                            Log.w(TAG, "profile row bg failed", t)
                            null
                        }
                        val bg = row?.background
                        if (row == null || bg == null) return@intercept result
                        val scheme = MonetPalette.palette(ThemeState.isNight(null, cl))
                        when (bg) {
                            is DrawableContainer -> recolorProfileCardContainer(bg, scheme)
                            is LayerDrawable -> {
                                for (i in 0 until bg.numberOfLayers) {
                                    bg.getDrawable(i)?.let { recolorDrawable(it, scheme) }
                                }
                            }
                            else -> recolorDrawable(bg, scheme)
                        }
                        fixProfileRowTexts(row, scheme)
                        result
                    }
                }
            }
            // View.onAttachedToWindow：内容卡本体挂载 → 计数 + 立即刷底 + 三次延迟兜底
            findMethod(View::class.java, setOf("onAttachedToWindow"))
                ?.let { method ->
                    logOnce("hook installed: View.onAttachedToWindow (profile card bg)")
                    runCatching { module.deoptimize(method) }
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        val view = chain.thisObject as? View
                        if (view == null) return@intercept result
                        if (profileContentCls.isInstance(view)) {
                            profileUiCount++
                            try {
                                view.setBackground(cardFactory())
                                // attach 即刷一次子树(延时兜底经实验证明非必需)
                                forceProfilePageCards(view, cl)
                            } catch (t: Throwable) {
                                Log.w(TAG, "profile content card bg failed", t)
                            }
                        }
                        result
                    }
                }
            // rebuildProfileContent：内容重建后整棵子树重刷一遍卡片
            val cardInfoType = try {
                Class.forName("com.tencent.mobileqq.profilecard.data.ProfileCardInfo", false, cl)
            } catch (t: Throwable) {
                null
            }
            if (cardInfoType != null) {
                findMethod(profileContentCls, setOf("rebuildProfileContent"), cardInfoType)
                    ?.let { method ->
                        logOnce("hook installed: ProfileContentView.rebuildProfileContent (card bg)")
                        module.hook(method).intercept { chain ->
                            val result = chain.proceed()
                            try {
                                forceProfilePageCards(chain.thisObject as? View, cl)
                            } catch (t: Throwable) {
                                Log.w(TAG, "profile subtree cards failed", t)
                            }
                            result
                        }
                    }
            }
            // View.onDetachedFromWindow：内容卡卸载 → 计数回落
            findMethod(View::class.java, setOf("onDetachedFromWindow"))
                ?.let { method ->
                    logOnce("hook installed: View.onDetachedFromWindow (profile card bg)")
                    runCatching { module.deoptimize(method) }
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        val view = try {
                            chain.thisObject as? View
                        } catch (t: Throwable) {
                            null
                        }
                        if (view != null &&
                            profileContentCls.isInstance(view) &&
                            profileUiCount > 0
                        ) {
                            profileUiCount--
                        }
                        result
                    }
                }
        } catch (t: Throwable) {
            Log.w(TAG, "ProfileContentView not found", t)
        }
    }

    /** “加好友”按钮：识别文字后把背景按按压态双色重染，文字换 onSurface。 */
    private fun hookProfileAddFriendButton(module: XposedModule, cl: ClassLoader) {
        try {
            val quiButtonCls = Class.forName(
                "com.tencent.biz.qui.quibutton.QUIButton",
                false,
                cl
            )
            findMethod(quiButtonCls, setOf("setType"), INT_TYPE)
                ?.let { method ->
                    logOnce("hook installed: QUIButton.setType (profile add friend bg)")
                    runCatching { module.deoptimize(method) }
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        val view = try {
                            chain.thisObject as? View
                        } catch (t: Throwable) {
                            Log.w(TAG, "profile add friend button failed", t)
                            null
                        }
                        if (view == null) return@intercept result
                        val textView = view as? TextView
                        val text = textView?.text?.toString()
                        if (text != null && text.contains("加")) {
                            if (profileBtnLogCount++ < 8) {
                                Log.i(
                                    TAG,
                                    "profile add friend probe text='$text' ctx=" +
                                        view.context.javaClass.name +
                                        " inProfile=" + isProfileActivityView(view)
                                )
                            }
                        }
                        if (text == "加好友" && textView != null && isProfileActivityView(view)) {
                            val dark = ThemeState.isNight(null, cl)
                            val scheme = MonetPalette.palette(dark)
                            textView.background?.let { bg ->
                                recolorProfileAddFriendBg(
                                    bg,
                                    TokenMapper.bgCard(dark),
                                    TokenMapper.bgList(dark)
                                )
                            }
                            textView.setTextColor(scheme.onSurface)
                            logOnce("profile add friend button -> surfaceBright")
                        }
                        result
                    }
                }
        } catch (t: Throwable) {
            Log.w(TAG, "QUIButton not found", t)
        }
    }

    /** 视图是否位于资料卡页面（context 链 Activity 类名 / profilecard 命名判定）。 */
    private fun isProfileActivityView(view: View): Boolean {
        var ctx: Context? = view.context
        var depth = 0
        while (ctx != null && depth < 8) {
            if (ctx is Activity) {
                val name = ctx.javaClass.name
                if (name.contains("Profile", ignoreCase = true) ||
                    name.contains("profilecard", ignoreCase = true)
                ) {
                    return true
                }
            }
            ctx = (ctx as? android.content.ContextWrapper)?.baseContext
            depth++
        }
        if (insideProfilePage(view)) return true
        return insideProfileRootTree(view)
    }

    /** 沿父链到根后 BFS 子树找 profilecard 系容器（兜底判定资料卡页）。 */
    private fun insideProfileRootTree(view: View): Boolean {
        var root: View = view
        var parent: View? = view.parent as? View
        while (parent != null) {
            root = parent
            parent = parent.parent as? View
        }
        val stack = java.util.ArrayDeque<View>()
        stack.add(root)
        var count = 0
        while (stack.isNotEmpty() && count < 600) {
            val current = stack.removeFirst()
            count++
            if (current.javaClass.name.contains("profilecard")) return true
            if (current is ViewGroup) {
                for (i in 0 until current.childCount) {
                    stack.addLast(current.getChildAt(i))
                }
            }
        }
        return false
    }

    /** 沿父链（最多 20 层）找 profilecard 系容器。 */
    private fun insideProfilePage(view: View): Boolean {
        var parent: View? = view.parent as? View
        var depth = 0
        while (parent != null && depth < 20) {
            if (parent.javaClass.name.contains("profilecard")) return true
            parent = parent.parent as? View
            depth++
        }
        return false
    }

    /** 加好友按钮背景：容器子项按“后两个 normal、其余 pressed”分发，纯色直接替换。 */
    private fun recolorProfileAddFriendBg(drawable: Drawable?, normal: Int, pressed: Int) {
        if (drawable == null) return
        drawable.mutate()
        when (drawable) {
            is GradientDrawable -> {
                recolorButtonState(drawable, normal)
                return
            }
            is ColorDrawable -> {
                drawable.setColor(normal)
                drawable.invalidateSelf()
                return
            }
            is DrawableContainer -> {
                val state = drawable.constantState as? DrawableContainer.DrawableContainerState
                    ?: return
                val children = state.children ?: return
                val n = state.childCount
                for (i in 0 until n) {
                    children[i]?.let { child ->
                        recolorButtonState(child, if (i < n - 2) pressed else normal)
                    }
                }
                drawable.invalidateSelf()
            }
        }
    }

    /** 单颗状态按钮（渐变/纯色/其它）统一上色，渐变同步反射替换描边画笔与描边 CSL。 */
    private fun recolorButtonState(child: Drawable, color: Int) {
        child.mutate()
        when (child) {
            is GradientDrawable -> {
                child.setColor(color)
                // mStrokePaint：描边画笔直接同步
                try {
                    val paint = cachedField(child.javaClass, "mStrokePaint")
                        ?.also { it.isAccessible = true }
                        ?.get(child) as? Paint
                    paint?.setColor(color)
                } catch (t: Throwable) {
                    // ignore
                }
                // mGradientState.mStrokeColors：若为 ColorStateList 则整体替换为纯色 CSL
                try {
                    val gradientState = cachedField(child.javaClass, "mGradientState")
                        ?.also { it.isAccessible = true }
                        ?.get(child)
                    if (gradientState != null) {
                        val strokeColors = cachedField(gradientState.javaClass, "mStrokeColors")
                            ?.also { it.isAccessible = true }
                        if (strokeColors != null &&
                            strokeColors.get(gradientState) is ColorStateList
                        ) {
                            strokeColors.set(gradientState, ColorStateList.valueOf(color))
                        }
                    }
                } catch (t: Throwable) {
                    // ignore
                }
            }
            is ColorDrawable -> child.setColor(color)
            else -> {
                child.setColorFilter(color, PorterDuff.Mode.SRC_IN)
                child.setTint(color)
            }
        }
        child.invalidateSelf()
    }

    /** QQ 自定义 Switch：drawableStateChanged 后按勾选态重染轨道（primary/surfaceContainerHighest）与滑块。 */
    private fun hookSwitchColors(module: XposedModule, cl: ClassLoader) {
        try {
            val switchCls = Class.forName("com.tencent.widget.Switch", false, cl)
            val getTrack = cachedMethod(switchCls, "getTrackDrawable")
            val getThumb = cachedMethod(switchCls, "getThumbDrawable")
            findMethod(switchCls, setOf("drawableStateChanged"))
                ?.let { method ->
                    logOnce("hook installed: Switch.drawableStateChanged (switch colors)")
                    runCatching { module.deoptimize(method) }
                    module.hook(method).intercept { chain ->
                                            // 第三方模块注入界面:整体跳过染色
                                            if (isThirdPartyUiActive()) return@intercept chain.proceed()
                        val result = chain.proceed()
                        val view = try {
                            chain.thisObject as? View
                        } catch (t: Throwable) {
                            null
                        }
                        if (view == null) return@intercept result
                        val checked = (view as? android.widget.Checkable)?.isChecked ?: false
                        try {
                            val scheme = MonetPalette.palette(ThemeState.isNight(null, cl))
                            if (switchColorLogCount++ < 4) {
                                Log.i(
                                    TAG,
                                    "switch colors primary=#" +
                                        Integer.toHexString(scheme.primary) +
                                        " onPrimary=#" +
                                        Integer.toHexString(scheme.onPrimary) +
                                        " scHighest=#" +
                                        Integer.toHexString(scheme.surfaceContainerHighest) +
                                        " outline=#" +
                                        Integer.toHexString(scheme.outline)
                                )
                            }
                            val track = getTrack?.invoke(view) as? Drawable
                            val thumb = getThumb?.invoke(view) as? Drawable
                            recolorSwitchTrack(
                                track, scheme.primary,
                                MonetPalette.amoledBlack(scheme.surfaceContainerHighest)
                            )
                            tintSwitchThumb(thumb, if (checked) scheme.onPrimary else scheme.outline)
                        } catch (t: Throwable) {
                            Log.w(TAG, "switch colors failed", t)
                            return@intercept result
                        }
                        result
                    }
                }
        } catch (t: Throwable) {
            Log.w(TAG, "Switch not found", t)
        }
    }

    /** 轨道背景：容器最后一子项（通常为“关”态轨道）用 unchecked，其余用 checked。 */
    private fun recolorSwitchTrack(drawable: Drawable?, checked: Int, unchecked: Int) {
        if (drawable == null) return
        if (drawable is DrawableContainer) {
            val state = drawable.constantState as? DrawableContainer.DrawableContainerState
                ?: return
            val children = state.children ?: return
            val n = state.childCount
            for (i in 0 until n) {
                children[i]?.let { child ->
                    recolorButtonState(child, if (i == n - 1) unchecked else checked)
                }
            }
            return
        }
        recolorButtonState(drawable, checked)
    }

    /** 开关滑块：容器递归逐层染；叶节点 SRC_IN + tint。 */
    private fun tintSwitchThumb(drawable: Drawable?, color: Int) {
        if (drawable == null) return
        if (drawable is DrawableContainer) {
            val state = drawable.constantState as? DrawableContainer.DrawableContainerState
                ?: return
            val children = state.children ?: return
            for (child in children) {
                if (child != null) tintSwitchThumb(child, color)
            }
            return
        }
        drawable.mutate()
        drawable.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        drawable.setTint(color)
        drawable.invalidateSelf()
    }

    /** UnreadBubbleVB：W1=“回到最新”气泡位图逐像素双簇重染，X1=未读数文字 onPrimary。 */
    private fun hookUnreadBubble(module: XposedModule, cl: ClassLoader) {
        try {
            val bubbleCls = Class.forName(
                "com.tencent.mobileqq.aio.reserve1.unreadbubble.UnreadBubbleVB",
                false,
                cl
            )
            val getText = cachedDeclaredMethod(bubbleCls, "U1")
            // W1：气泡本身（含子位图视图）重建
            findMethod(bubbleCls, setOf("W1"))
                ?.let { method ->
                    logOnce("hook installed: UnreadBubbleVB.W1 (back bottom bubble)")
                    runCatching { module.deoptimize(method) }
                    module.hook(method).intercept { chain ->
                        // proceed 前先登记子树与内层 TextView，避免通用 setBackground 拦截误伤
                        runCatching {
                            val thiz = chain.thisObject
                            protectUnreadSubtree(thiz as? View)
                            val innerView = getText?.invoke(thiz) as? View
                            if (innerView != null) {
                                protectedUnreadViewIds.add(System.identityHashCode(innerView))
                            }
                        }
                        val result = chain.proceed()
                        val bubble = try {
                            getText?.invoke(chain.thisObject) as? TextView
                        } catch (t: Throwable) {
                            Log.w(TAG, "back bottom bubble failed", t)
                            null
                        }
                        if (bubble == null) return@intercept result
                        val scheme = MonetPalette.palette(ThemeState.isNight(null, cl))
                        bubble.setTextColor(scheme.onPrimary)
                        val background = bubble.background
                        if (unreadBubbleLogCount++ < 5) {
                            val bgName = background?.javaClass?.name ?: "null"
                            Log.i(TAG, "unread bubble W1 bg=$bgName")
                        }
                        var bitmap: Bitmap? = null
                        when (background) {
                            is android.graphics.drawable.BitmapDrawable -> bitmap = background.bitmap
                            else -> {
                                if (background?.javaClass?.name ==
                                    "com.tencent.theme.SkinnableBitmapDrawable"
                                ) {
                                    val f = cachedField(background.javaClass, "mBitmap")
                                        ?.also { it.isAccessible = true }
                                    bitmap = f?.get(background) as? Bitmap
                                }
                            }
                        }
                        if (bitmap != null) {
                            bubble.setBackground(
                                recolorBackBottomBitmap(
                                    bitmap!!,
                                    bubble.resources,
                                    scheme.primary,
                                    scheme.onPrimary
                                )
                            )
                        }
                        result
                    }
                }
            // X1：未读数角标文字
            findMethod(bubbleCls, setOf("X1"))
                ?.let { method ->
                    logOnce("hook installed: UnreadBubbleVB.X1 (unread count bubble)")
                    runCatching { module.deoptimize(method) }
                    module.hook(method).intercept { chain ->
                        runCatching {
                            val thiz = chain.thisObject
                            protectUnreadSubtree(thiz as? View)
                            val innerView = getText?.invoke(thiz) as? View
                            if (innerView != null) {
                                protectedUnreadViewIds.add(System.identityHashCode(innerView))
                            }
                        }
                        val result = chain.proceed()
                        val tv = try {
                            getText?.invoke(chain.thisObject) as? TextView
                        } catch (t: Throwable) {
                            Log.w(TAG, "unread count bubble failed", t)
                            null
                        }
                        if (tv != null) {
                            tv.setTextColor(
                                MonetPalette.palette(ThemeState.isNight(null, cl)).onPrimary
                            )
                        }
                        result
                    }
                }
            // 任何气泡视图挂载后再次整棵登记（回收重挂期间父链断裂也能按身份命中）
            findMethod(View::class.java, setOf("onAttachedToWindow"))
                ?.let { method ->
                    logOnce("hook installed: View.onAttachedToWindow (unread bubble protect)")
                    runCatching { module.deoptimize(method) }
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val view = chain.thisObject as? View
                            if (view != null) {
                                val name = view.javaClass.name
                                if (name.contains("UnreadBubble") ||
                                    name.contains("unreadbubble")
                                ) {
                                    view.post { protectUnreadSubtree(view) }
                                }
                            }
                        } catch (t: Throwable) {
                            // ignore
                        }
                        result
                    }
                }
        } catch (t: Throwable) {
            Log.w(TAG, "UnreadBubbleVB not found", t)
        }
    }

    /** “回到最新”/未读气泡双色位图重染：亮/暗两簇 k-means（8 轮），
     *  主体簇（像素占多数者，圆/底）→ primary，图形簇（少数者，箭头）→ onPrimary；
     *  单簇/低对比时整图 → primary。返回带 null SRC_OVER 滤色的 BitmapDrawable。
     *  注：recover_ref 该函数 dump 被跳过，此实现按其同版 debug dex 反编译恢复。 */
    private fun recolorBackBottomBitmap(
        source: Bitmap,
        res: Resources,
        primary: Int,
        onPrimary: Int
    ): Drawable {
        val copy = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(copy.width * copy.height)
        copy.getPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
        val lumas = IntArray(pixels.size)
        var minLuma = Int.MAX_VALUE
        var maxLuma = 0
        var opaqueCount = 0
        for (pixel in pixels) {
            if ((pixel ushr 24) != 0) {
                val luma = colorLuma(pixel and 0xFFFFFF)
                lumas[opaqueCount] = luma
                opaqueCount++
                if (luma < minLuma) minLuma = luma
                if (luma > maxLuma) maxLuma = luma
            }
        }
        var c1 = 0.0
        var c2 = 0.0
        var n1 = 0
        var n2 = 0
        var twoClusters = false
        if (maxLuma - minLuma >= 40 && opaqueCount >= 8) {
            val sorted = lumas.copyOf(opaqueCount)
            sorted.sort()
            c1 = sorted[Math.max(0, opaqueCount / 30)].toDouble()
            c2 = sorted[opaqueCount - 1].toDouble()
            // 2-means，最多 8 轮；质心位移 <0.5 即视为收敛不再移动
            for (iter in 0 until 8) {
                var sum1 = 0.0
                var count1 = 0
                var sum2 = 0.0
                var count2 = 0
                for (k in 0 until opaqueCount) {
                    val l = lumas[k]
                    if (Math.abs(l - c1) <= Math.abs(l - c2)) {
                        sum1 += l
                        count1++
                    } else {
                        sum2 += l
                        count2++
                    }
                }
                n1 = count1
                n2 = count2
                val nc1 = if (count1 > 0) sum1 / count1 else c1
                val nc2 = if (count2 > 0) sum2 / count2 else c2
                if (!(Math.abs(nc1 - c1) < 0.5 && Math.abs(nc2 - c2) < 0.5)) {
                    c1 = nc1
                    c2 = nc2
                }
            }
            twoClusters = n1 * 100 >= opaqueCount * 3 && n2 * 100 >= opaqueCount * 3
        }
        // 图形（箭头）通常是较小的那簇；两簇都成立时才按“主体→primary、图形→onPrimary”分配
        val arrowIsCluster1 = twoClusters && n1 < n2
        for (i in pixels.indices) {
            val alpha = pixels[i] ushr 24
            if (alpha != 0) {
                val l = colorLuma(pixels[i] and 0xFFFFFF)
                val belongs1 = !twoClusters || Math.abs(l - c1) <= Math.abs(l - c2)
                val target: Int = when {
                    arrowIsCluster1 -> if (belongs1) onPrimary else primary
                    twoClusters -> if (belongs1) primary else onPrimary
                    else -> primary
                }
                pixels[i] = (alpha shl 24) or (target and 0xFFFFFF)
            }
        }
        copy.setPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
        val out = android.graphics.drawable.BitmapDrawable(res, copy)
        out.setColorFilter(PorterDuffColorFilter(0, PorterDuff.Mode.SRC_OVER))
        return out
    }

    /** 当前/祖先链（最多 12 层）是否命中指定内容卡类型。 */
    private fun isProfileContent(view: View, cls: Class<*>): Boolean {
        var current: View? = view
        var depth = 0
        while (current != null && depth < 12) {
            if (cls.isInstance(current)) return true
            current = current.parent as? View
            depth++
        }
        return false
    }

    /** 资料卡行文字深色修复（仅深色方案）：低对比/半透明文字换 onSurfaceVariant，纯黑系走角色色映射。 */
    private fun fixProfileRowTexts(root: View?, scheme: DynamicScheme) {
        if (root == null || !scheme.isDark) return
        val stack = java.util.ArrayDeque<View>()
        stack.add(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 200) {
            val view = stack.removeFirst()
            guard++
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    stack.addLast(view.getChildAt(i))
                }
            }
            if (view is TextView) {
                val text = view.text
                if (text != null && text.length != 0) {
                    val tc = view.currentTextColor
                    val alpha = tc ushr 24
                    val luma = colorLuma((tc and 0xFFFFFF) or 0xFF000000.toInt())
                    if (alpha != 0 && (alpha != 255 || luma < 150)) {
                        if (alpha < 255) {
                            setTextColorFast(view, scheme.onSurfaceVariant)
                        } else {
                            val mapped = mapPopupTextColor(tc, scheme)
                            if (mapped != tc) {
                                setTextColorFast(view, mapped)
                            }
                            remapDarkSpans(view)
                        }
                    }
                }
            }
        }
    }

    /** 资料卡整树强制刷底：先找到 PullToZoomHeaderListView 容器，BFS 全子树；
     *  ProfileCellView/ProfileContentView 卡片用专用容器重染，其余容器仅处理亮色（>=235）层。 */
    private fun forceProfilePageCards(view: View?, cl: ClassLoader) {
        if (view == null) return
        var listRoot: View = view
        var parent: View? = view.parent as? View
        while (parent != null) {
            if (parent.javaClass.name ==
                "com.tencent.mobileqq.profilecard.base.view.PullToZoomHeaderListView"
            ) {
                listRoot = parent
            }
            parent = parent.parent as? View
        }
        val scheme = MonetPalette.palette(ThemeState.isNight(null, cl))
        val stack = java.util.ArrayDeque<View>()
        stack.add(listRoot)
        var count = 0
        while (stack.isNotEmpty() && count < 300) {
            val current = stack.removeFirst()
            count++
            if (current.background != null) {
                if (profileTreeDumpCount++ < 24) {
                    val name = current.javaClass.name
                    if (name.contains("profilecard") ||
                        current.background is DrawableContainer
                    ) {
                        val bg = current.background
                        val solid = bg?.let { solidColorOf(it) }
                        val hex = solid?.let { Integer.toHexString(it) } ?: "?"
                        Log.i(
                            TAG,
                            "profile tree: ${current.javaClass.name} bg=" +
                                bg!!.javaClass.simpleName + "#" + hex
                        )
                    }
                }
            }
            if (current is ViewGroup) {
                for (i in 0 until current.childCount) {
                    stack.addLast(current.getChildAt(i))
                }
            }
            if (current !== listRoot) {
                val bg = current.background
                if (bg != null) {
                    val solid = solidColorOf(bg)
                    val name = current.javaClass.name
                    val isProfileCard =
                        name.contains("ProfileCellView") || name.contains("ProfileContentView")
                    if (isProfileCard ||
                        (solid != null && colorLuma(solid or 0xFF000000.toInt()) >= 235)
                    ) {
                        if (profileSubtreeLogCount++ < 20) {
                            val hex = solid?.let { Integer.toHexString(it) } ?: "?"
                            Log.i(
                                TAG,
                                "profile subtree: ${current.javaClass.name} #$hex -> card"
                            )
                        }
                        when (bg) {
                            is DrawableContainer ->
                                if (isProfileCard) {
                                    recolorProfileCardContainer(bg, scheme)
                                } else {
                                    recolorContainer(bg, scheme)
                                }
                            is LayerDrawable -> {
                                for (i in 0 until bg.numberOfLayers) {
                                    bg.getDrawable(i)?.let { recolorDrawable(it, scheme) }
                                }
                            }
                            else -> recolorDrawable(bg, scheme)
                        }
                    }
                }
            }
        }
        fixProfileRowTexts(listRoot, scheme)
    }

    /** 资料卡容器：只把最后一个非空子层（通常为真正的卡片背景）重染。 */
    private fun recolorProfileCardContainer(container: DrawableContainer, scheme: DynamicScheme) {
        val state = container.constantState as? DrawableContainer.DrawableContainerState
            ?: return
        val children = state.children ?: return
        for (i in state.childCount - 1 downTo 0) {
            val child = children[i]
            if (child != null) {
                recolorDrawable(child, scheme)
                return
            }
        }
    }

    /** 取 drawable 的纯色（渐变/纯色/位图采样/皮肤可缩放）；容器先查末位非空子项再全量递归。 */
    private fun solidColorOf(drawable: Drawable): Int? {
        return when (drawable) {
            is GradientDrawable -> try {
                drawable.color?.defaultColor
            } catch (t: Throwable) {
                null
            }
            is ColorDrawable -> colorOfColorDrawable(drawable)
            is DrawableContainer -> {
                val state = drawable.constantState as? DrawableContainer.DrawableContainerState
                    ?: return null
                val children = state.children ?: return null
                if (children.isEmpty()) return null
                // 先试“最后一个非空子项”（通常才是当前实际绘制层），失败再正序全量递归
                var lastChild: Drawable? = null
                for (i in children.indices.reversed()) {
                    val child = children[i]
                    if (child != null) {
                        lastChild = child
                        break
                    }
                }
                if (lastChild != null) {
                    solidColorOf(lastChild)?.let { return it }
                }
                for (child in children) {
                    child?.let { solidColorOf(it)?.let { c -> return c } }
                }
                null
            }
            is LayerDrawable -> {
                for (i in 0 until drawable.numberOfLayers) {
                    drawable.getDrawable(i)?.let { d ->
                        solidColorOf(d)?.let { return it }
                    }
                }
                null
            }
            is android.graphics.drawable.BitmapDrawable -> sampleBitmapColor(drawable.bitmap)
            is android.graphics.drawable.NinePatchDrawable -> renderSample(drawable)
            else -> {
                if (drawable.javaClass.name.startsWith("com.tencent.theme.Skinnable")) {
                    renderSample(drawable)
                } else {
                    null
                }
            }
        }
    }

    /** 通用容器：仅当子项为近白（luma>=235）纯色背景时替换为 surfaceBright。 */
    private fun recolorContainer(container: DrawableContainer, scheme: DynamicScheme) {
        val state = container.constantState as? DrawableContainer.DrawableContainerState
            ?: return
        val children = state.children ?: return
        for (child in children) {
            if (child != null) {
                val solid = solidColorOf(child) ?: continue
                if (colorLuma(solid or 0xFF000000.toInt()) >= 235) {
                    recolorDrawable(child, scheme)
                }
            }
        }
    }

    /** 通用背景 drawable：渐变/纯色设 surfaceBright，其它 SRC_IN + tint。 */
    private fun recolorDrawable(drawable: Drawable, scheme: DynamicScheme) {
        when (drawable) {
            is GradientDrawable -> {
                drawable.mutate()
                drawable.setColor(MonetPalette.amoledBlack(scheme.surfaceBright))
            }
            is ColorDrawable -> {
                drawable.mutate()
                drawable.setColor(MonetPalette.amoledBlack(scheme.surfaceBright))
            }
            else -> {
                drawable.mutate()
                drawable.setColorFilter(
                    MonetPalette.amoledBlack(scheme.surfaceBright), PorterDuff.Mode.SRC_IN
                )
                drawable.setTint(MonetPalette.amoledBlack(scheme.surfaceBright))
            }
        }
    }

    /** 深色方案下把纯黑/低亮 ForegroundColorSpan 整体换 onSurface（保留 span 区间与 flags）。 */
    private fun remapDarkSpans(view: TextView?) {
        if (view == null) return
        val scheme = MonetPalette.palette(ThemeState.isNight(null, timClassLoader))
        if (!scheme.isDark) return
        val text = view.text ?: return
        if (text !is Spannable) return
        try {
            val spans = text.getSpans(0, text.length, ForegroundColorSpan::class.java)
            for (span in spans) {
                val fc = span.foregroundColor
                val opaque = (fc and 0xFFFFFF) or 0xFF000000.toInt()
                if (fc == 0xFF000000.toInt() ||
                    ((fc ushr 24) != 0 && opaque != -1 && colorLuma(opaque) < 70)
                ) {
                    val start = text.getSpanStart(span)
                    val end = text.getSpanEnd(span)
                    val flags = text.getSpanFlags(span)
                    text.removeSpan(span)
                    text.setSpan(ForegroundColorSpan(scheme.onSurface), start, end, flags)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "fix black spans failed", t)
        }
    }

    // quibadge.c（QUIBadge 取色 c，Context 参数版方法）：proceed 后若返回值
    // 是 int 颜色则重写——f()/h() 替换为 primary，i()/j() 替换为 onPrimary。
    private var quibadgeRawLog = 0

    private fun hookQuiBadge(module: XposedModule, cl: ClassLoader) {
        val cls = try {
            Class.forName(QUI_BADGE, false, cl)
        } catch (t: Throwable) {
            Log.w(TAG, "quibadge.c not found", t)
            return
        }
        for (methodName in listOf("f", "h")) {
            findMethod(cls, setOf(methodName), Context::class.java)
                ?.let { method ->
                    logOnce("hook installed: $QUI_BADGE.$methodName")
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        if (result !is Int) return@intercept result
                        if (quibadgeRawLog++ < 20) {
                            Log.i(
                                TAG,
                                "quibadge.c.$methodName raw=#" + Integer.toHexString(result)
                            )
                        }
                        // 按 TIM **原色**区分状态：普通未读是红(#F74C30)、免打扰是灰。
                        // 这两个方法只带 Context，拿不到 viewType，但不影响——
                        // 原色本身就把状态带出来了：
                        //   红(有色相) -> primary + onPrimary
                        //   灰(无彩色) -> secondaryContainer + onPrimary（同为亮色，
                        //                与普通未读一眼能分开，且字看得清）
                        val scheme = MonetPalette.palette(ThemeState.isNight(null, cl))
                        val r = (result shr 16) and 0xFF
                        val g = (result shr 8) and 0xFF
                        val b = result and 0xFF
                        if (maxOf(r, g, b) - minOf(r, g, b) <= 30) {
                            scheme.secondaryContainer
                        } else {
                            scheme.primary
                        }
                    }
                }
        }
        for (methodName in listOf("i", "j")) {
            findMethod(cls, setOf(methodName), Context::class.java)
                ?.let { method ->
                    logOnce("hook installed: $QUI_BADGE.$methodName")
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        if (result is Int) {
                            MonetPalette.palette(ThemeState.isNight(null, cl)).onPrimary
                        } else {
                            result
                        }
                    }
                }
        }
    }

    /** 红点判定取样缓存(drawable identity → 主色)；内容不变,与调色板无关。 */
    private val redDotDominantMemo = HashMap<Int, Int>()

    private var mineCardLogCount = 0

    /** 栅格统计：返回 intArrayOf(warm 数, core 数, 总面积)；尺寸超限/异常返回 null。 */
    private fun glyphStats(drawable: Drawable): IntArray? {
        return try {
            var iw = drawable.intrinsicWidth
            var ih = drawable.intrinsicHeight
            val bounds = drawable.bounds
            if (bounds.width() > 0 && bounds.height() > 0) {
                iw = bounds.width()
                ih = bounds.height()
            }
            if (iw <= 0 || ih <= 0 || iw > 200 || ih > 200) return null
            val bitmap = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val saved = Rect(bounds)
            drawable.setBounds(0, 0, iw, ih)
            drawable.draw(canvas)
            drawable.bounds = saved
            val total = iw * ih
            val px = IntArray(total)
            bitmap.getPixels(px, 0, iw, 0, 0, iw, ih)
            var core = 0
            var warm = 0
            for (c in px) {
                val a = (c ushr 24) and 0xFF
                if (a < 110) continue
                core++
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                if (r > g && g >= b && (r - g) >= 40 && (g - b) >= 25 && (r - b) >= 90) warm++
            }
            intArrayOf(warm, core, total)
        } catch (t: Throwable) {
            null
        }
    }

    /** “+”扩展面板宿主:item 父链含 PlusPanelContainerGroup / PlusPanel /
     *  RadioGroup;面板 item 位于 QQViewPager 内,故父链出现 QQViewPager
     *  且深度≥2 也认定为面板(表情面板/相册已由其它分支排除)。 */
    private fun isPlusPanelHost(view: View?): Boolean {
        var v: View? = view
        var depth = 0
        var sawViewPager = false
        while (v != null && depth < 8) {
            val n = v.javaClass.name
            if (n.contains("pluspanel") || n.contains("PlusPanel")) return true
            if (n.contains("QQViewPager")) sawViewPager = true
            v = v.parent as? View
            depth++
        }
        return sawViewPager && depth >= 3
    }


    /** 采样统计:返回 intArrayOf(不透明像素, 暗像素, 彩色像素, 平均亮度)。
     *  暗 = luma < 110;彩色 = max-min > 40;缩略到长边 ≤120 后统计。 */
    private fun glyphColorStats(drawable: Drawable): IntArray? {
        return try {
            var iw = drawable.intrinsicWidth
            var ih = drawable.intrinsicHeight
            val b = drawable.bounds
            if (b.width() > 0 && b.height() > 0) {
                iw = b.width()
                ih = b.height()
            }
            if (iw <= 0 || ih <= 0 || iw > 400 || ih > 400) return null
            val longSide = maxOf(iw, ih)
            if (longSide > 120) {
                val s = 120f / longSide
                iw = maxOf(1, (iw * s).toInt())
                ih = maxOf(1, (ih * s).toInt())
            }
            val bmp = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val saved = Rect(drawable.bounds)
            drawable.setBounds(0, 0, iw, ih)
            drawable.draw(canvas)
            drawable.bounds = saved
            val px = IntArray(iw * ih)
            bmp.getPixels(px, 0, iw, 0, 0, iw, ih)
            var core = 0
            var dark = 0
            var colorful = 0
            var lumaSum = 0L
            for (c in px) {
                val a = (c ushr 24) and 0xFF
                if (a < 110) continue
                core++
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val bl = c and 0xFF
                val luma = (r * 299 + g * 587 + bl * 114) / 1000
                lumaSum += luma
                if (luma < 110) dark++
                if (maxOf(r, g, bl) - minOf(r, g, bl) > 40) colorful++
            }
            if (core < 12) return null
            intArrayOf(core, dark, colorful, (lumaSum / core).toInt())
        } catch (t: Throwable) {
            null
        }
    }

    /** 红包类图标（名字里带 hongbao/redpacket 的红包图形）→ 保持 TIM 原版配色。 */
    private fun isHongbaoIconDrawable(drawable: Drawable): Boolean {
        val name = drawableNameMemo[drawable] ?: runCatching {
            drawable.constantState?.let { drawableStateNameMemo[System.identityHashCode(it)] }
        }.getOrNull() ?: return false
        val n = name.lowercase()
        return n.contains("hongbao") || n.contains("redpacket") || n.contains("red_packet") ||
            n.contains("_hb_") || n.contains("hb_icon")
    }

    /** 小红点（ImageView 图片形式，如联系人页群通知/新朋友红点、闪烁红点）→ primary。 */
    private fun hookImageViewRedDot(module: XposedModule) {
        fun handleImage(view: ImageView?, drawable: Drawable?) {
            if (view == null || drawable == null) return
            if (isThirdPartyUiActive()) return
            if (isEmoticonHost(view)) return
            if (isMonetExemptUi(view)) return
            // 调色板/颜色选择器色块：红/橙是"画笔颜色"本身，不是未读红点，
            // 一旦按小红点单色化成 primary，红色画笔就没了
            if (isEditorShapeSwatch(drawable) || isColorSwatchHost(view)) {
                if (colorSwatchLogCount++ < 3) {
                    Log.i(TAG, "color swatch kept original on " + view.javaClass.simpleName)
                }
                return
            }
            // 引用区跳转箭头：TIM 是绑定之后才 setImageResource 的，如果等我们的
            // 补帧上色，中间会先画一帧原色(allwhite 白) —— 这里在设置的那一刻就
            // 换成自己生成的位图；颜色优先用引用块已算好的，其次按气泡背景判侧别。
            // 预筛要和 applyReplyJumpIcon 一致(名字 + 尺寸)，只看名字会漏掉那些
            // 名字查不到的皮肤 drawable(日志里显示为 name=?)。
            if (isJumpArrowDrawable(drawable, view) && handleReplyJumpIcon(view, drawable)) return
            // “+”扩展面板(照片/拍照/通话/文件/红包/收钱…)的入口图标:
            // 个别插件图标(如“收钱”)在深色下仍是原生黑色位图,对比度过低,
            // 这里只兜底染"暗色单色图形",彩色图标原样保留
            if (panelSeen && (isPlusPanelHost(view) || isInPanelItem(view))) {
                // 顺带把入口底板也修一遍：图标 setImageDrawable 时 item 已完全 bind
                var pv: View? = view.parent as? View
                var pd = 0
                while (pv != null && pd < 3) {
                    fixPlusItemPlate(pv)
                    pv = pv.parent as? View
                    pd++
                }
                // 面板图标统一亮色:直接栅格化重染并替换 drawable,
                // 不依赖 TIM 的 tint/主题行为(此前加载期染色会被 TIM 用
                // XML tint 或换图覆盖,导致个别图标始终纯黑)
                if (!rasterizedPanelIcons.contains(drawable)) {
                    val out = rasterizeIconUniform(drawable)
                    if (out != null) {
                        rasterizedPanelIcons.add(out)
                        view.setImageDrawable(out)
                    }
                }
                return
            }
            try {
                // 红包类图标(红包面板的"拼手气/普通/专属/语音/口令红包"等)保持 TIM
                // 原版配色：它们是红底彩色图形，会被下面的"小红点"判定认成红点、
                // 单色化成 primary 纯色块(设备日志：red dot image ... #fff74c31 ->
                // #ff6cd6ff)。
                if (isHongbaoIconDrawable(drawable)) return
                val iw = drawable.intrinsicWidth
                val ih = drawable.intrinsicHeight
                var dominant: Int? = null
                // 取样结果按 drawable 实例缓存：RecyclerView 滚动时同一图标
                // 反复 setImage 不再重复全像素取样
                // 红点只能是"小红点"：按 dp 限尺寸 —— 原来写死 200px(≈67dp)，
                // 44dp 的红包图标也落在里面被当红点。
                val maxDotPx = (24f * view.resources.displayMetrics.density).toInt()
                val domMemo = redDotDominantMemo[System.identityHashCode(drawable)]
                if (iw <= maxDotPx && ih <= maxDotPx && iw > 0 && ih > 0) {
                    if (drawable.colorFilter == null) {
                        if (domMemo != null) {
                            dominant = domMemo
                        } else {
                            dominant = sampleBitmapColorOfDrawable(drawable)
                            if (dominant != null) {
                                if (redDotDominantMemo.size > 1024) {
                                    redDotDominantMemo.clear()
                                }
                                redDotDominantMemo[System.identityHashCode(drawable)] =
                                    dominant
                            }
                        }
                    } else {
                        dominant = null
                    }
                } else if (iw <= 0 && ih <= 0) {
                    when (drawable) {
                        is ColorDrawable ->
                            dominant = colorOfColorDrawable(drawable)
                        is GradientDrawable ->
                            dominant = runCatching { drawable.color?.defaultColor }.getOrNull()
                        else -> Unit
                    }
                }
                if (dominant == null) return
                if (!isErrorRed(dominant or 0xFF000000.toInt())) {
                    return
                }
                val scheme = MonetPalette.palette(ThemeState.isNight(null, timClassLoader))
                drawable.mutate()
                drawable.setColorFilter(scheme.primary, PorterDuff.Mode.SRC_IN)
                drawable.setTint(scheme.primary)
                val i = redDotImgLogCount++
                if (i < 10) {
                    Log.i(
                        TAG,
                        "red dot image on ${view.javaClass.name} " +
                            "#${Integer.toHexString(dominant)} -> " +
                            "#${Integer.toHexString(scheme.primary)} " +
                            "d=${drawable.javaClass.simpleName}"
                    )
                }
                // 徽标数字：红点(未读)圆点旁的纯数字白字(联系人页“新朋友/
                // 群通知”等) → 复用统一 helper(onPrimary)，与底栏角标一致。
                runCatching {
                    var hostP: android.view.ViewParent? = view.parent
                    var depth = 0
                    while (hostP is ViewGroup && depth < 2) {
                        val g = hostP as ViewGroup
                        for (k in 0 until g.childCount) {
                            val c = g.getChildAt(k)
                            if (c === view || c !is TextView || c.isClickable) continue
                            val tx = runCatching {
                                c.text?.toString()?.trim()
                            }.getOrNull()
                            if (tx.isNullOrEmpty() || tx.length > 4) continue
                            if (!tx.all { it.isDigit() }) continue
                            if (tintBadgeDigitText(c, scheme)) {
                                if (badgeDigitLogCount2 < 8) {
                                    badgeDigitLogCount2++
                                    Log.i(TAG, "badge digit onPrimary ($tx)")
                                }
                            }
                        }
                        hostP = hostP.parent
                        depth++
                    }
                }
            } catch (t: Throwable) {
                // ignore
            }
        }
        findMethod(ImageView::class.java, setOf("setImageDrawable"), Drawable::class.java)
            ?.let { method ->
                logOnce("hook installed: ImageView.setImageDrawable (red dot)")
                runCatching { module.deoptimize(method) }
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        handleImage(
                            chain.thisObject as? ImageView,
                            chain.getArg(0) as? Drawable
                        )
                    } catch (t: Throwable) {
                        // ignore
                    }
                    result
                }
            }
        findMethod(ImageView::class.java, setOf("setImageResource"), INT_TYPE)
            ?.let { method ->
                logOnce("hook installed: ImageView.setImageResource (red dot)")
                runCatching { module.deoptimize(method) }
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val view = chain.thisObject as? ImageView
                        handleImage(view, view?.drawable)
                        val i = redDotImgLogCount
                        if (i < 15 && view != null) {
                            val resId = chain.getArg(0) as Int
                            if (resId != 0 && resId shr 24 == 0x7f) {
                                val name = entryName(view.resources, resId)
                                val i2 = redDotNameLogCount++
                                if (i2 < 12) {
                                    Log.i(TAG, "image resource $name on ${view.javaClass.name}")
                                }

                                if (name != null &&
                                    (name.contains("tips_dot") || name.contains("_dot"))
                                ) {
                                    val i3 = redDotTreeLogCount++
                                    if (i3 < 6) {
                                        val sb = StringBuilder("dot tree ")
                                        var p: View? = view
                                        var d = 0
                                        while (p != null && d < 4) {
                                            sb.append(p.javaClass.simpleName)
                                            if (p is ViewGroup && p.childCount <= 4) {
                                                for (ci in 0 until p.childCount) {
                                                    val c = p.getChildAt(ci)
                                                    if (c !== view && c is TextView && c.text.isNotEmpty()) {
                                                        sb.append("{txt='").append(c.text)
                                                            .append("' col=#")
                                                            .append(
                                                                Integer.toHexString(
                                                                    c.currentTextColor or 0xFF000000.toInt()
                                                                )
                                                            ).append('}')
                                                    }
                                                }
                                            }
                                            sb.append(" <- ")
                                            p = p.parent as? View
                                            d++
                                        }
                                        Log.i(TAG, sb.toString())
                                    }
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        // ignore
                    }
                    result
                }
            }
    }

    /** QUIBadge 自绘角标字段缓存（按类）。 */
    private class QuiBadgeFields(
        val text: Field?,
        val viewType: Field?,
        val textPaint: Field?,
        val bgPaint: Field?,
        val customTextColor: Field?,
        val customBgDrawable: Field?
    )

    private val quiBadgeFieldsCache = ConcurrentHashMap<Class<*>, QuiBadgeFields>()

    private val viewPostInvalidate: Method? = runCatching {
        View::class.java.getMethod("postInvalidate")
    }.getOrNull()

    /** QUIBadge 自绘附标强制莫奈色：f()/h() 背景 primary 系列钩子之外的兜底。 */
    /** QUIBadge 已处理记录:view -> 配色代次(避免每帧 onDraw 重复处理)。 */
    private val quiBadgeForceMemo = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, Long>()
    )
    private var quiBadgeSkipLog = 0

    private var quiBadgeForceLog = 0

    /** 角标状态已变：清掉"代次记忆"，让下一次 onDraw 兜底按新状态重新上色。 */
    private fun invalidateBadgeTint(view: Any?) {
        if (view is View) quiBadgeForceMemo.remove(view)
    }

    private fun forceQuiBadge(view: Any?) {
        if (view == null) return
        // onDraw 每帧都会调用,而本函数要反射读 6 个字段并计算行卡颜色。
        // 同一配色代次内同一 badge 只处理一次；角标状态变化时由 QUIBadge 的
        // state setter hook 调 invalidateBadgeTint() 把这条记忆清掉,
        // 于是下一帧会重新处理一次,之后继续短路。
        if (view is View) {
            val gen = MonetPalette.generation()
            if (quiBadgeForceMemo[view] == gen) {
                if (quiBadgeSkipLog < 3) {
                    quiBadgeSkipLog++
                    Log.i(TAG, "quibadge skip (same generation $gen)")
                }
                return
            }
            quiBadgeForceMemo[view] = gen
        }
        try {
            val dark = ThemeState.isNight(null, timClassLoader)
            val scheme = MonetPalette.palette(dark)
            val fields = quiBadgeFieldsCache.getOrPut(view.javaClass) {
                QuiBadgeFields(
                    findFieldDeep(view.javaClass, "mText"),
                    findFieldDeep(view.javaClass, "mViewType"),
                    findFieldDeep(view.javaClass, "mTextPaint"),
                    findFieldDeep(view.javaClass, "mBgPaint"),
                    findFieldDeep(view.javaClass, "mCustomTextColor"),
                    findFieldDeep(view.javaClass, "mCustomBgDrawable")
                ).also { h ->
                    h.text?.isAccessible = true
                    h.viewType?.isAccessible = true
                    h.textPaint?.isAccessible = true
                    h.bgPaint?.isAccessible = true
                    h.customTextColor?.isAccessible = true
                    h.customBgDrawable?.isAccessible = true
                }
            }
            val text = (fields.text?.get(view) as? String) ?: ""
            val viewType = (fields.viewType?.get(view) as? Int) ?: -1
            val isRecall = text.contains("撤回") || text.contains("recall") ||
                text.contains("Recall") || text.contains("removed")
            // 免打扰判定：viewType 之外再兜一道"原背景是不是灰的"。TIM 里普通未读
            // 是红底(#F74C30)、免打扰是灰底；只靠 viewType 会漏(实测普通未读
            // vt=2，而这里原来只认 1/3/8，于是免打扰的也被算成普通，整列角标
            // 一起变成 primary)。quiBadgeForceMemo 保证同一代次只处理一次，
            // 所以这里读到的 mBgPaint.color 还是 TIM 原色。
            val origBg = (fields.bgPaint?.get(view) as? Paint)?.color ?: 0
            val origR = (origBg shr 16) and 0xFF
            val origG = (origBg shr 8) and 0xFF
            val origB = origBg and 0xFF
            val origGray = origBg != 0 &&
                maxOf(origR, origG, origB) - minOf(origR, origG, origB) <= 24 &&
                colorLuma(origBg) in 90..235
            val isMuted = viewType == 1 || viewType == 3 || viewType == 8 || origGray
            val rowColor = findRowCardColor(view as? View, dark, text)
            val bgColor: Int
            val fgColor: Int
            if (isRecall) {
                bgColor = rowColor
                fgColor = scheme.onSurfaceVariant
            } else if (isMuted) {
                bgColor = if (colorClose(rowColor, TokenMapper.bgCard(dark))) {
                    scheme.secondaryContainer
                } else {
                    TokenMapper.guestBubble(dark)
                }
                fgColor = scheme.onSurface
            } else {
                bgColor = scheme.primary
                fgColor = scheme.onPrimary
            }
            val textPaint = fields.textPaint?.get(view) as? Paint
            val bgPaint = fields.bgPaint?.get(view) as? Paint
            var customBg = fields.customBgDrawable?.get(view) as? Drawable
            // 背景已是目标色（渐变纯色一致）或自带滤镜 → 视为已就绪
            val customBgOk: Boolean
            if (customBg == null) {
                customBgOk = true
            } else if (customBg is GradientDrawable &&
                customBg.color?.defaultColor == bgColor
            ) {
                customBgOk = true
            } else if (customBg.colorFilter != null) {
                customBgOk = true
            } else {
                customBgOk = false
            }
            if (textPaint?.color == fgColor && bgPaint?.color == bgColor &&
                (fields.customTextColor?.get(view) as? Int) == fgColor && customBgOk
            ) {
                return
            }
            textPaint?.setColor(fgColor)
            bgPaint?.setColor(bgColor)
            fields.customTextColor?.set(view, fgColor)
            if (customBg != null) {
                customBg.mutate()
                if (customBg is GradientDrawable) {
                    customBg.setColor(bgColor)
                } else {
                    customBg.setColorFilter(bgColor, PorterDuff.Mode.SRC_IN)
                    customBg.setTint(bgColor)
                }
            }
            val v = view as? View
            if (v != null && v.background != null) {
                val viewBg = v.background
                viewBg.mutate()
                viewBg.setColorFilter(bgColor, PorterDuff.Mode.SRC_IN)
                viewBg.setTint(bgColor)
            }
            if (quiBadgeForceLog++ < 40) Log.i(
                TAG,
                "quibadge force muted=$isMuted orig=#" +
                    Integer.toHexString(origBg) + " vt=$viewType '$text' bg=#" +
                    Integer.toHexString(bgColor) + " fg=#" + Integer.toHexString(fgColor)
            )
            viewPostInvalidate?.invoke(view)
        } catch (t: Throwable) {
            Log.w(TAG, "force quibadge failed", t)
        }
    }

    /** quibadge.a.a 的资源取色方法（a/e/g/h）→ 按方法名映射主色/字色。 */
    private fun hookQuiBadgeResource(module: XposedModule, cl: ClassLoader) {
        try {
            val cls = Class.forName("com.tencent.mobileqq.quibadge.a.a", false, cl)
            for (methodName in listOf("a", "e", "g", "h")) {
                findMethod(cls, setOf(methodName))
                    ?.let { method ->
                        logOnce("hook installed: quibadge.a.a.$methodName")
                        module.hook(method).intercept { chain ->
                            val result = chain.proceed()
                            if (result is Int) {
                                if (methodName == "h") {
                                    MonetPalette.palette(ThemeState.isNight(null, cl)).onPrimary
                                } else {
                                    MonetPalette.palette(ThemeState.isNight(null, cl)).primary
                                }
                            } else {
                                result
                            }
                        }
                    }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "quibadge.a.a not found", t)
        }
    }

    /** QUIBadge 自绘附标控件（状态 setter / updateCustomStyle / onDraw）→ forceQuiBadge。 */
    private fun hookQuiBadgeView(module: XposedModule, cl: ClassLoader) {
        try {
            val cls = Class.forName("com.tencent.mobileqq.quibadge.QUIBadge", false, cl)
            findMethod(cls, setOf("n"), Integer::class.java, Drawable::class.java)
                ?.let { method ->
                    logOnce("hook installed: QUIBadge.updateCustomStyle")
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        invalidateBadgeTint(chain.thisObject)
                        result
                    }
                }
            // 角标状态 setter 是"状态真的变了"的唯一可靠信号：清掉 onDraw 兜底的
            // "代次记忆"，让下一次绘制重新上色（那时 viewType/mText 已绑好、
            // view 也已布局，findRowCardColor 才探得准）。
            // 不能在 setter 里直接上色：那一刻 item view 还没布局，行卡色会探错。
            // 这同时修掉了"首次 onDraw 发生在绑定之前 → 免打扰被判成普通未读 →
            // 永远停在 primary"的问题（未读角标全变成一个色的根因）。
            val stateSetters = setOf(
                "setreddot", "setrednum", "setredtext", "setrednumwithicon",
                "setgraydot", "setgraynum", "setgraytext", "setgraynumwithicon",
                "seticon", "setaiobarnum"
            )
            for (method in cls.declaredMethods) {
                if (method.name.lowercase() !in stateSetters) continue
                val params = method.parameterTypes
                if (!params.all {
                        it == Int::class.javaPrimitiveType ||
                            it == String::class.java ||
                            Drawable::class.java.isAssignableFrom(it)
                    }
                ) {
                    continue
                }
                runCatching { method.isAccessible = true }
                logOnce("hook installed: QUIBadge.${method.name} (state tint)")
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    invalidateBadgeTint(chain.thisObject)
                    result
                }
            }
            findMethod(cls, setOf("onDraw"), Canvas::class.java)
                ?.let { method ->
                    logOnce("hook installed: QUIBadge.onDraw")
                    runCatching { module.deoptimize(method) }
                    module.hook(method).intercept { chain ->
                                            // 第三方模块注入界面:整体跳过染色
                                            if (isThirdPartyUiActive()) return@intercept chain.proceed()
                        forceQuiBadge(chain.thisObject)
                        chain.proceed()
                    }
                }
        } catch (t: Throwable) {
            Log.w(TAG, "QUIBadge not found", t)
        }
    }

    /** 摘要角标种类：撤回召回条 / 摘要胶囊 / 无。 */
    private enum class SummaryBadgeKind { NONE, RECALL, SUMMARY }

    private fun summaryBadgeKind(text: CharSequence?): SummaryBadgeKind {
        if (text == null || text.isEmpty() || text.length < 2 || text.length > 96) {
            return SummaryBadgeKind.NONE
        }
        if (containsAny(text, "撤回", "recall", "removed", "revoke")) {
            return SummaryBadgeKind.RECALL
        }
        if (isExactSummaryBadge(text.toString().trim())) {
            return SummaryBadgeKind.SUMMARY
        }
        return SummaryBadgeKind.NONE
    }

    /** AIO 群待办通知条 / 聊天列表里红色"[群待办]"类文字标签：红字 → primary。 */
    private fun tintTodoRedText(tv: TextView?, text: CharSequence?) {
        if (tv == null || text == null || text.length > 64) return
        val s = text.toString()
        if (!s.contains("待办") && !s.contains("[群") && !s.contains("群待办")) return
        try {
            val scheme = MonetPalette.palette(ThemeState.isNight(null, timClassLoader))
            val opaque = opaqueColor(tv.currentTextColor)
            if (isErrorRed(opaque)) {
                tv.setTextColor(scheme.primary)
                val i = todoRedLogCount++
                if (i < 8) {
                    Log.i(
                        TAG,
                        "todo red text -> primary '${s.take(20)}' on ${tv.javaClass.name}"
                    )
                }
            } else {
                val i2 = todoSeenLogCount++
                if (i2 < 10) {
                    Log.i(
                        TAG,
                        "todo text seen '${s.take(24)}' col=#" + Integer.toHexString(opaque) +
                            " on ${tv.javaClass.name}"
                    )
                }
            }
        } catch (t: Throwable) {
            // ignore
        }
    }


    private fun sampleBitmapColorOfDrawable(drawable: Drawable): Int? {
        if (drawable is android.graphics.drawable.BitmapDrawable) {
            return sampleBitmapColor(drawable.bitmap)
        }
        return renderSample(drawable)
    }

    /** 递归取渐变/容器 drawable 的纯色值。 */
    private fun gradientColorOf(drawable: Drawable?): Int? {
        if (drawable is GradientDrawable) {
            return try {
                drawable.color?.defaultColor
            } catch (t: Throwable) {
                null
            }
        }
        if (drawable is LayerDrawable) {
            for (i in 0 until drawable.numberOfLayers) {
                gradientColorOf(drawable.getDrawable(i))?.let { return it }
            }
            return null
        }
        if (drawable is DrawableContainer) {
            val state = drawable.constantState as? DrawableContainer.DrawableContainerState
                ?: return null
            val children = state.children ?: return null
            for (child in children) {
                gradientColorOf(child)?.let { return it }
            }
        }
        return null
    }

    @Volatile
    private var badgeRowGeneration = -1L

    /** 找到附标所在聊天条目的卡片背景色（置顶卡片 / 普通列表）。 */
    private fun findRowCardColor(view: View?, dark: Boolean, text: CharSequence): Int {
        if (view == null) return TokenMapper.bgList(dark)
        val generation = MonetPalette.generation()
        if (badgeRowGeneration != generation) {
            badgeRowCache.clear()
            badgeRowGeneration = generation
        }
        val key = System.identityHashCode(view) xor text.hashCode()
        badgeRowCache[key]?.let { return it }
        val listColor = TokenMapper.bgList(dark)
        val cardColor = TokenMapper.bgCard(dark)
        var color = listColor
        var sawAnySurface = false
        var v: View? = view
        repeat(10) {
            v = v?.parent as? View ?: return@repeat
            val bg = v.background ?: return@repeat
            if (bg === view.background) return@repeat
            val sample = renderSample(bg) ?: return@repeat
            // 优先精确命中置顶/普通两种卡片色，防止把页面上其它 surface 层
            // （例如列表滚动容器）误当成聊天条目背景。
            when {
                colorClose(sample, cardColor) -> {
                    color = cardColor
                    return@repeat
                }
                colorClose(sample, listColor) -> {
                    color = listColor
                    return@repeat
                }
                isSurfaceColor(sample, dark) && !sawAnySurface -> {
                    color = sample
                    sawAnySurface = true
                }
            }
        }
        badgeRowCache[key] = color
        if (badgeRowCache.size > 4096) badgeRowCache.clear()
        return color
    }

    /** 渲染采样色与目标色接近（容忍九宫格/皮肤位图渲染时的轻微偏移）。 */
    private fun colorClose(sample: Int, target: Int): Boolean {
        val sr = (sample shr 16) and 0xFF
        val sg = (sample shr 8) and 0xFF
        val sb = sample and 0xFF
        val tr = (target shr 16) and 0xFF
        val tg = (target shr 8) and 0xFF
        val tb = target and 0xFF
        return Math.abs(sr - tr) <= 12 &&
            Math.abs(sg - tg) <= 12 &&
            Math.abs(sb - tb) <= 12
    }

    private fun isSurfaceColor(color: Int, dark: Boolean): Boolean {
        val scheme = MonetPalette.palette(dark)
        val opaque = opaqueColor(color)
        // AMOLED 纯黑面也属于表面
        if (MonetPalette.isAmoled() && opaque == 0xFF000000.toInt()) return true
        return opaque == scheme.surface ||
            opaque == scheme.surfaceBright ||
            opaque == scheme.surfaceDim ||
            opaque == scheme.surfaceContainer ||
            opaque == scheme.surfaceContainerLow ||
            opaque == scheme.surfaceContainerLowest ||
            opaque == scheme.surfaceContainerHigh ||
            opaque == scheme.surfaceContainerHighest ||
            opaque == scheme.surfaceVariant
    }

    // ------------------------------------------------------------------
    // View.setBackground：无论背景来自内联颜色、代码 setBackgroundColor，
    // 还是皮肤位图，最终都会经过这里，按颜色本身兜底染色
    // ------------------------------------------------------------------

    /** 当前顶层页面类名(Activity.onResume 维护)。 */
    @Volatile private var activeUiClassName: String? = null

    /** TIM/QQ 自身界面的包前缀(实测 Activity 均为 com.tencent.*,如
     *  SplashActivity/QPublicFragmentActivity/TimFriendProfileCardActivity/
     *  AboutActivity;mqq/com.qzone/com.qq 为历史前缀,一并放行)。 */
    private val TIM_UI_PREFIXES = listOf(
        "com.tencent.", "mqq", "com.qzone", "com.qq", "cooperation."
    )

    /** 当前顶层页面是否第三方模块注入的界面(QAuxiliary 的
     *  io.github.qauxv.activity.SettingsUiFragmentHostActivity、TAssistant 等)。
     *  这些模块把自己的设置页放进 TIM 进程,我们的全局 hook 会把它们的 UI
     *  也染上配色 —— 这里整体豁免,保证它们保持自身配色。
     *
     *  这是**唯一**的页面级豁免:图片编辑/浏览页(aelight.camera /
     *  richmediabrowser / pic)不再豁免,跟其它 TIM 页面一样正常染色。 */
    @Volatile private var thirdPartyPageCache: String? = null

    @Volatile private var thirdPartyResultCache = false

    private fun isThirdPartyUiActive(): Boolean {
        val n = activeUiClassName ?: return false
        // 16 个 hook 入口都会问这个问题：页面没变就直接用上次结果
        if (n === thirdPartyPageCache) return thirdPartyResultCache
        var third = true
        for (p in TIM_UI_PREFIXES) {
            if (n.startsWith(p)) {
                third = false
                break
            }
        }
        thirdPartyPageCache = n
        thirdPartyResultCache = third
        return third
    }

    /** 表情/贴纸面板宿主(EmoticonImageView 等):其中的图是 emoji/贴纸本体,
     *  红点/暖色单色启发式在这里一律误判,必须整体跳过。 */
    private fun isEmoticonHost(view: View?): Boolean {
        var v: View? = view
        var depth = 0
        while (v != null && depth < 6) {
            val n = v.javaClass.name
            if (n.contains("Emoticon") || n.contains("emoticon") ||
                n.contains("Sticker") || n.contains("sticker") ||
                n.contains("Emoji") || n.contains("emoji")
            ) return true
            v = v.parent as? View
            depth++
        }
        return false
    }

    /** 通用豁免：“回到最新”双色位图气泡。 */
    private fun isMonetExemptUi(view: View?): Boolean =
        isUnreadBubbleView(view)

    /** 调色板/颜色选择器宿主（涂鸦画笔色块、文字颜色、色条等）。
     *
     *  里面的红/橙是"用户要用的颜色"本身，不是未读红点：被小红点判定
     *  单色化成 primary 会让红色画笔直接失效（实测 EditPicActivity 涂鸦
     *  调色板 GradientDrawable #fff95251 -> #ff6cd6ff）。 */
    private fun isColorSwatchHost(view: View?): Boolean {
        var v: View? = view
        var depth = 0
        while (v != null && depth < 8) {
            val n = v.javaClass.name
            if (n.contains("SelectColorLayout") ||
                n.contains("ColorSelection") ||
                (n.contains("Doodle") && n.contains("Color"))
            ) {
                return true
            }
            v = v.parent as? View
            depth++
        }
        return false
    }

    /** 图片编辑页里用 shape（非位图）画的红色小块 = 调色板色块（画笔颜色），
     *  不是未读红点 —— 真红点实测全是 BitmapDrawable。
     *  实测涂鸦色块：选中态 R.drawable.4_ 是 18dp 纯红、普通态 49 是 22dp
     *  带 2dp 灰描边；18dp 纯红采样出来就是红 → 命中 isErrorRed → 被单色化，
     *  22dp 带描边那个采样后不够红才逃过 —— 这正是"只有选中红色才变 primary"。 */
    private fun isEditorShapeSwatch(drawable: Drawable): Boolean {
        if (drawable is BitmapDrawable) return false
        if (drawable is android.graphics.drawable.NinePatchDrawable) return false
        val page = activeUiClassName ?: return false
        return page.startsWith("com.tencent.aelight.camera") ||
            page.contains("aeeditor") || page.contains("EditPic")
    }

    private fun hexCss(color: Int): String {
        val v = color and 0x00FFFFFF
        return "#" + String.format(java.util.Locale.US, "%06X", v)
    }

    /** “回到最新”/未读气泡（UnreadBubbleVB）内部：其图标是双色位图（圆+箭头），
     *  由 hookUnreadBubble 像素级单独染色，必须排除在通用单色 SRC_IN 之外，
     *  否则通用染色会把箭头与圆一起染成同色导致箭头消失。 */
    private val protectedUnreadViewIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    private fun isUnreadBubbleView(view: View?): Boolean {
        if (view == null) return false
        if (protectedUnreadViewIds.isEmpty() &&
            !view.javaClass.name.contains("UnreadBubble") &&
            !view.javaClass.name.contains("unreadbubble")
        ) {
            return false
        }
        if (view.javaClass.name.contains("UnreadBubble") ||
            view.javaClass.name.contains("unreadbubble") ||
            protectedUnreadViewIds.contains(System.identityHashCode(view))
        ) {
            return true
        }
        var current: View? = view.parent as? View
        var depth = 0
        while (current != null && depth < 10) {
            val name = current.javaClass.name
            if (name.contains("UnreadBubble") || name.contains("unreadbubble") ||
                protectedUnreadViewIds.contains(System.identityHashCode(current))
            ) {
                return true
            }
            current = current.parent as? View
            depth++
        }
        return false
    }

    /** W1/X1 处理时登记气泡整棵子树；回收/重挂期间父链断裂也能按身份命中。 */
    private fun protectUnreadSubtree(root: View?) {
        if (root == null) return
        val stack = java.util.ArrayDeque<View>()
        stack.add(root)
        var count = 0
        while (stack.isNotEmpty() && count < 80) {
            val current = stack.removeFirst()
            count++
            protectedUnreadViewIds.add(System.identityHashCode(current))
            if (current is ViewGroup) {
                for (i in 0 until current.childCount) {
                    stack.addLast(current.getChildAt(i))
                }
            }
        }
        if (protectedUnreadViewIds.size > 1024) protectedUnreadViewIds.clear()
    }

    /** 资料卡页面是否激活（激活期间才允许全局 setBackground 拦截器做采样判断）。 */
    @Volatile
    private var profileUiCount = 0

    /** 深色底上的深色文字 -> 提亮。
     *
     *  XML inflate 时 AOSP TextView 会直接把颜色写进 mTextColor，绕过
     *  setTextColor(int)/setTextColor(ColorStateList)，所以钱包页那些
     *  #1E1E1E 文字两条 setTextColor hook 都覆盖不到，在莫奈深色面上几乎
     *  看不见。这里在 attach 时按实际对比度兜底：只处理**无彩色**、亮度低、
     *  且祖先链上确实有深色背景的文字；彩色文字与浅底上的文字一概不碰。
     */
    private fun hookTextContrast(module: XposedModule) {
        // 注意挂 View.onAttachedToWindow：TextView 自己没重写这个方法，
        // findMethod(TextView::class.java, ...) 会返回 null、hook 根本装不上。
        findMethod(View::class.java, setOf("onAttachedToWindow"))
            ?.let { method ->
                logOnce("hook installed: View.onAttachedToWindow (contrast fix)")
                runCatching { module.deoptimize(method) }
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val tv = chain.thisObject as? TextView
                        if (tv != null && !isThirdPartyUiActive()) {
                            fixLowContrastText(tv)
                            // 卡片/列表底色往往是 attach 之后才被染深的，那时
                            // 这一次检查看到的还是浅底、会被跳过 —— 单次 post
                            // 到下一帧再判一次（不轮询）。
                            tv.post { runCatching { fixLowContrastText(tv) } }
                        }
                    } catch (t: Throwable) {
                        // ignore
                    }
                    result
                }
            }
    }

    private var textContrastLogCount = 0

    private fun fixLowContrastText(tv: TextView) {
        // ⚠️ 必须用**实际生效**的配色判断明暗：MonetPalette.palette() 内部走
        // effectiveDark()，而 ThemeState.isNight() 在"只设了模块内配色、没跟随
        // 系统深色模式"时会返回 false —— 设备实测就是 dark=false 让这条兜底
        // 全部早退，钱包页那些深灰文字一个都没提亮。
        val dark = MonetPalette.palette(
            ThemeState.isNight(null, timClassLoader)
        ).isDark
        val c = tv.currentTextColor
        val opaque = c or 0xFF000000.toInt()
        val r = (opaque shr 16) and 0xFF
        val g = (opaque shr 8) and 0xFF
        val b = opaque and 0xFF
        val chroma = maxOf(r, g, b) - minOf(r, g, b)
        val luma = colorLuma(opaque)
        if (!dark) return
        if (chroma > 24) return // 彩色文字不碰
        // 只按亮度判断"已经够亮"，不再用 isSchemeColor 过滤：
        // 纯黑 #000000 会命中方案里某个面角色(surfaceDim/container 系在深色下
        // 可以就是纯黑)，于是钱包设置页那些黑字全被当成"已染"跳过。
        // 映射本身是幂等的，重复处理无害。
        if (luma >= 170) return
        // 祖先链上第一个有背景的 view：只有它确实是深色，才算"深底深字"
        var bgLuma = -1
        var p: View? = tv.parent as? View
        var depth = 0
        while (p != null && depth < 8) {
            val bc = colorOfDrawable(p.background)
            if (bc != 0) {
                bgLuma = colorLuma(bc or 0xFF000000.toInt())
                break
            }
            p = p.parent as? View
            depth++
        }

        // 只要祖先链上没有**亮色**背景(luma>140)就提亮：
        // 深色底(#003045 luma=36)、透明底(bgLuma=-1)都算；
        // 白卡片上的黑字(luma=255)保持不动，那是正常的。
        if (bgLuma <= 140) {
            val scheme = MonetPalette.palette(true)
            tv.setTextColor(if (luma < 70) scheme.onSurface else scheme.onSurfaceVariant)

        }
    }

    /** 状态栏颜色：TIM 在钱包页把状态栏硬设成插件资源里的品牌浅蓝(#A1CAFD)，
     *  我们的取色映射(只认无彩色与精确品牌蓝)覆盖不到，于是状态栏是浅蓝、
     *  下面顶栏是深色，两截。深色配色下把**亮色**状态栏统一到主题面色
     *  (暗色状态栏一律不动，聊天列表那种本来就对)。 */
    private fun hookStatusBar(module: XposedModule) {
        runCatching {
            // ⚠️ Window.setStatusBarColor 是抽象方法，挂 android.view.Window 上
            // 根本不会被调用（实测 hook 装了但一次都没触发）—— 实现在 PhoneWindow。
            val windowCls = Class.forName("com.android.internal.policy.PhoneWindow")
            findMethod(windowCls, setOf("setStatusBarColor"), INT_TYPE)
                ?.let { method ->
                    logOnce("hook installed: Window.setStatusBarColor (dark normalize)")
                    module.hook(method).intercept { chain ->
                        try {
                            val c = chain.getArg(0) as Int
                            val scheme = MonetPalette.palette(
                                ThemeState.isNight(null, timClassLoader)
                            )
                            if (scheme.isDark && colorLuma(c or 0xFF000000.toInt()) > 140) {
                                val mapped = TokenMapper.bgCard(true)
                                if (statusBarLogCount++ < 8) {
                                    Log.i(
                                        TAG,
                                        "status bar #" + Integer.toHexString(c) +
                                            " -> #" + Integer.toHexString(mapped)
                                    )
                                }
                                return@intercept chain.proceed(arrayOf<Any>(mapped))
                            }
                        } catch (t: Throwable) {
                            // ignore
                        }
                        chain.proceed()
                    }
                }
        }.onFailure { Log.w(TAG, "setStatusBarColor hook failed", it) }
    }

    private var statusBarLogCount = 0

    /** 系统深浅色切换 → 强停 TIM。
     *
     *  本进程注册 ComponentCallbacks：系统级配置变化（含深色模式）时由系统直接
     *  回调 onConfigurationChanged —— 即时、不用轮询、也没有启动宽限期的延迟。
     *  （TIM 自己改配置不走这个回调，所以不像读 app.resources 那样抖动误判。）
     *
     *  只有"跟随系统"的自动模式才需要重启：那时配色确实跟着系统变了；
     *  固定浅色/深色时系统怎么切都不影响配色。 */
    private fun registerSystemNightCallback() {
        val app = runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? android.app.Application
        }.getOrNull() ?: run {
            // 模块加载常常早于 Application 创建（这时 currentApplication() 是
            // null，注册会直接失败 —— 实测就是因此完全没注册上）。隔 500ms 重试。
            android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed({ registerSystemNightCallback() }, 500L)
            return
        }
        systemNightLast = runCatching {
            app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        }.getOrDefault(-1)
        runCatching {
            app.registerComponentCallbacks(object : android.content.ComponentCallbacks2 {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    runCatching { onSystemConfigChanged(newConfig) }
                }

                override fun onLowMemory() {
                    // ignore
                }

                override fun onTrimMemory(level: Int) {
                    // ignore
                }
            })
        }
        Log.i(TAG, "night callbacks registered, base=$systemNightLast")
    }

    /** 系统配置变化回调：只在"日夜位真的变了"且模块处于自动模式时强停 TIM。 */
    private fun onSystemConfigChanged(cfg: Configuration) {
        val night = cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val prev = systemNightLast
        systemNightLast = night
        if (prev == -1 || night == prev) return
        val mode = SettingsBridge.current.colorMode
        if (mode == ColorMode.SYSTEM || mode == ColorMode.MONET_SYSTEM) {
            Log.i(TAG, "system night $prev -> $night (mode=$mode), restarting TIM")
            SettingsBridge.killTimProcess()
        }
    }

    @Volatile private var systemNightLast = -1

    private var blackFixLog = 0

    @Volatile private var whiteGradLogged = false

    private var iconBtnLog = 0

    private var titleBarLog = 0

    private var tabIconLog = 0

    /** 被我们重建过的顶栏分段 tab 背景（用于识别这类 RadioButton 并改文字色）。 */
    private val headerTabDrawables: MutableSet<Drawable> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<Drawable, Boolean>())

    /** 顶栏分段 tab（文件发送页 最近/本机/收藏/微云）：文字色来自
     *  skin_title_segment_item_color（TIM 原版：选中=品牌蓝 #00A5E0 / 未选中=白），
     *  配的是"白底选中"；我们把选中底换成低对比暗色后，中蓝文字对比度太差
     *  （看着就是"黑的"）。这里按"背景是不是我们重建的那个 drawable"精准识别，
     *  选中 = primary（亮主色），未选中 = onSurface。 */
    private fun fixHeaderTabTextColor(v: View) {
        if (v !is android.widget.RadioButton) return
        // 背景是 selector（iu5/iu7），拿不到我们重建的 shape 实例，所以按
        // "文件管理器页面里的 RadioButton"识别（就是那排 最近/本机/收藏/微云）。
        val page = activeUiClassName ?: return
        if (!page.contains("filemanager")) return
        val scheme = MonetPalette.palette(ThemeState.isNight(null, timClassLoader))
        val color = if (v.isChecked) scheme.primary else scheme.onSurface
        if (v.currentTextColor != color) {
            v.setTextColor(color)
            if (tabIconLog++ < 6) {
                Log.i(TAG, "header tab text -> #" + Integer.toHexString(color) +
                    " checked=" + v.isChecked)
            }
        }
    }

    private fun hookHeaderTabText(module: XposedModule) {
        findMethod(
            android.widget.CompoundButton::class.java,
            setOf("setChecked"),
            java.lang.Boolean.TYPE
        )?.let { method ->
            logOnce("hook installed: CompoundButton.setChecked (header tab text)")
            runCatching { module.deoptimize(method) }
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                try {
                    (chain.thisObject as? View)?.let { fixHeaderTabTextColor(it) }
                } catch (t: Throwable) {
                    // ignore
                }
                result
            }
        }
        findMethod(View::class.java, setOf("onAttachedToWindow"))
            ?.let { method ->
                logOnce("hook installed: View.onAttachedToWindow (header tab text)")
                runCatching { module.deoptimize(method) }
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val v = chain.thisObject as? View
                        if (v is android.widget.CompoundButton) fixHeaderTabTextColor(v)
                    } catch (t: Throwable) {
                        // ignore
                    }
                    result
                }
            }
    }

    private fun hookViewBackground(module: XposedModule) {
        // 窗口级背景（弹窗/对话框的 mWindowBackground）不经过 View.setBackground，
        // 由 DecorView 自己绘制。转发“选聊天”弹窗的白色面板就是这个。
        runCatching {
            val decor = Class.forName("com.android.internal.policy.DecorView")
            findMethod(decor, setOf("setWindowBackground"), Drawable::class.java)
                ?.let { method ->
                    logOnce("hook installed: DecorView.setWindowBackground")
                    runCatching { module.deoptimize(method) }
                    module.hook(method).intercept { chain ->
                        chain.proceed()
                        val drawable = chain.getArg(0) as? Drawable
                        if (whiteSrcLogCount < 40) {
                            logWhiteSource(
                                "windowBackground d=" + drawable?.javaClass?.simpleName,
                                colorOfDrawable(drawable)
                            )
                        }
                        if (drawable != null) {
                            tintAnyDrawable(
                                drawable,
                                ThemeState.isNight(null, timClassLoader)
                            )
                        }
                        null
                    }
                }
        }.onFailure { Log.w(TAG, "DecorView.setWindowBackground hook failed", it) }

        findMethod(View::class.java, setOf("setBackground"), Drawable::class.java)
            ?.let { hookFrameworkMethod(module, it) { chain, result ->
                // “回到最新”双色位图气泡由专用 hook 处理，跳过通用单色染色
                val view = chain.thisObject as? View
                // 限次日志：先判额度再拼字符串/遍历父链（滚动时每条 setBackground
                // 都走这里，原来无条件构造 source 参数是笔白开销）
                if (whiteSrcLogCount < 40) {
                    logWhiteSource(
                        "setBackground d=" + (chain.getArg(0) as? Drawable)?.javaClass?.simpleName +
                            " on " + viewChainName(view),
                        colorOfDrawable(chain.getArg(0) as? Drawable)
                    )
                }
                // 第三方模块注入界面:整体跳过(含红色实底扫描)
                if (isMonetExemptUi(view) || isThirdPartyUiActive()) {
                    return@hookFrameworkMethod result
                }
                val drawable = chain.getArg(0) as? Drawable
                val dark = ThemeState.isNight(null, timClassLoader)
                if (drawable != null) {
                    // 诊断：近白渐变底记录宿主视图类，便于定位未命中的卡片
                    if (drawable is GradientDrawable && !whiteGradLogged) {
                        val solid = runCatching { drawable.color?.defaultColor }.getOrNull() ?: 0
                        if (colorLuma(solid or 0xFF000000.toInt()) >= 235) {
                            whiteGradLogged = true
                            logOnce(
                                "near-white gradient on ${chain.thisObject?.javaClass?.name} " +
                                    "#${Integer.toHexString(solid)}"
                            )
                        }
                    }
                    // 图标控件（Button/ImageButton）的**单色位图背景** = 图标图形本身，
                    // 深色下必须提亮成前景色(onSurface)。钱包页顶栏返回键就是
                    // Button + koh.png（112px 纯白箭头，资源名被混淆成 koh）：
                    // 以前统一走背景规则(白 → surfaceContainer #001C2A)，
                    // 压在顶栏底(#003045)上就是深压深、等于看不见。
                    if (drawable is android.graphics.drawable.BitmapDrawable &&
                        (view is android.widget.Button || view is android.widget.ImageButton) &&
                        drawable.colorFilter == null
                    ) {
                        val bmp = drawable.bitmap
                        val dominant = bmp?.let { sampleBitmapColor(it) }
                        if (dominant != null) {
                            val r0 = (dominant shr 16) and 0xFF
                            val g0 = (dominant shr 8) and 0xFF
                            val b0 = dominant and 0xFF
                            if (maxOf(r0, g0, b0) - minOf(r0, g0, b0) < 40) {
                                val fg = MonetPalette.palette(dark).onSurface
                                if (fg != dominant) {
                                    runCatching {
                                        drawable.mutate()
                                        drawable.colorFilter =
                                            PorterDuffColorFilter(fg, PorterDuff.Mode.SRC_IN)
                                        drawable.invalidateSelf()
                                    }
                                    if (iconBtnLog++ < 6) {
                                        Log.i(
                                            TAG,
                                            "icon-btn bitmap bg -> #" + Integer.toHexString(fg) +
                                                " on " + view.javaClass.simpleName
                                        )
                                    }
                                }
                                return@hookFrameworkMethod null
                            }
                        }
                    }
                    // 先走常规兜底染色（大部分白/灰/蓝在此处理，返回快）；
                    // 只有它没处理时才做红实底容器扫描，避免每个背景都递归进
                    // selector/layer 子项。
                    if (!tintAnyDrawable(drawable, dark)) {
                        // 登录页/启动页自绘渐变背景(login.fragment.p / login.bf)：
                        // 代码绘制多 Paint 渐变且实现 setColorFilter(透传 Paint)，
                        // 深浅无法读色，按类名直接平染模块深色档。
                        val dname = drawable.javaClass.name
                        if (dname == "com.tencent.mobileqq.login.fragment.p" ||
                            dname == "com.tencent.mobileqq.login.bf"
                        ) {
                            val scheme = MonetPalette.palette(false)
                            if (scheme.isDark) {
                                val tgt = if (MonetPalette.isAmoled()) {
                                    0xFF000000.toInt()
                                } else {
                                    scheme.surfaceContainer
                                }
                                runCatching {
                                    drawable.mutate()
                                    drawable.setColorFilter(tgt, PorterDuff.Mode.SRC_IN)
                                    drawable.setTint(tgt)
                                    if (loginBgSelfLogCount++ < 6) {
                                        Log.i(
                                            TAG,
                                            "login self-draw bg $dname -> " +
                                                "#${Integer.toHexString(tgt)}"
                                        )
                                    }
                                }
                                return@hookFrameworkMethod null
                            }
                        }
                        logOnce("setBackground unhandled: ${drawable.javaClass.name}")
                    }
                }
                null
            } }

        // 背景可能是 foreground（例如条目的按压/白色覆盖层）
        findMethod(View::class.java, setOf("setForeground"), Drawable::class.java)
            ?.let { hookFrameworkMethod(module, it) { chain, result ->
                val view = chain.thisObject as? View
                if (isMonetExemptUi(view) || isThirdPartyUiActive()) {
                    return@hookFrameworkMethod result
                }
                val drawable = chain.getArg(0) as? Drawable
                if (drawable != null) {
                    tintAnyDrawable(drawable, ThemeState.isNight(null, timClassLoader))
                }
                null
            } }

        // setBackgroundColor 在已有 ColorDrawable 时会直接改颜色、绕过 setBackground
        findMethod(View::class.java, setOf("setBackgroundColor"), INT_TYPE)
            ?.let { method ->
                logOnce("hook installed: android.view.View.setBackgroundColor")
                runCatching { module.deoptimize(method) }
                module.hook(method).intercept { chain ->
                                        // 第三方模块注入界面:整体跳过染色
                                        if (isThirdPartyUiActive()) return@intercept chain.proceed()
                    val view = chain.thisObject as? View
                    if (isMonetExemptUi(view) || isThirdPartyUiActive()) {
                        return@intercept chain.proceed()
                    }
                    val color = chain.getArg(0) as Int
                    val dark = ThemeState.isNight(null, timClassLoader)
                    // 代码直接 setBackgroundColor(红) 的未读数字气泡（联系人页 新朋友/
                    // 群通知 等）：红色实底一律转 primary，否则静默漏染。
                    var mapped: Int
                    val alpha = color ushr 24
                    if (alpha >= 0xE0 && isErrorRed(color or 0xFF000000.toInt())) {
                        mapped = MonetPalette.palette(dark).primary
                        if (redSolidBgLogCount++ < 10) {
                            Log.i(
                                TAG,
                                "red bg color on ${chain.thisObject?.javaClass?.name} " +
                                    "#${Integer.toHexString(color)} -> " +
                                    "#${Integer.toHexString(mapped)}"
                            )
                        }
                    } else {
                        mapped = if (isSchemeColor(color, dark)) {
                            color
                        } else {
                            TokenMapper.inlineBgColor(color, dark) ?: color
                        }
                    }
                    if (dark && mapped != color && (color and 0x00FFFFFF) == 0x00FFFFFF &&
                        whiteBgLogCount++ < 30
                    ) {
                        Log.i(
                            TAG,
                            "white bg via setBackgroundColor on " +
                                "${chain.thisObject?.javaClass?.name} -> #${Integer.toHexString(mapped)}"
                        )
                    }
                    if (mapped == color) {
                        chain.proceed()
                    } else {
                        chain.proceed(arrayOf<Any>(mapped))
                    }
                }
            }
    }

    /** 实例级完成标记：同一 drawable 实例在同一调色板代次内只真正染色一次。
     *  转发页等场景 TIM 会对同一实例反复 setBackground/行绑定，重复调用
     *  实测仍带 ~ms 级固定开销，查表可把重复成本降到 O(1)。 */
    private val drawableTintMemo = java.util.concurrent.ConcurrentHashMap<Int, Long>()


    /** 文字改色直写字段（绕过 setTextColor 的 hook 链与框架开销），失败退回 API。 */
    private fun setTextColorFast(tv: TextView, color: Int) {
        try {
            val f1 = cachedField(tv.javaClass, "mTextColor")?.also { it.isAccessible = true }
            val f2 = cachedField(tv.javaClass, "mCurTextColor")?.also { it.isAccessible = true }
            if (f1 != null || f2 != null) {
                f1?.set(tv, ColorStateList.valueOf(color))
                f2?.set(tv, color)
                // 直写字段绕过了 TextView.updateTextColors()，必须同步 Paint，
                // 否则自绘/动画型 TextView（AnimationTextView 等）仍按旧色绘制
                try {
                    tv.getPaint().color = color
                } catch (t2: Throwable) {
                    // ignore
                }
                tv.invalidate()
                return
            }
        } catch (t: Throwable) {
            // fall through to API
        }
        tv.setTextColor(color)
    }

    /** 兜底染色：皮肤位图 / 纯色 / 渐变 / 容器（selector、layer）递归处理。 */
    private fun tintAnyDrawable(drawable: Drawable, dark: Boolean): Boolean {
        // 第三方模块注入界面:整体保持原样
        if (isThirdPartyUiActive()) return false
        // 面板图标的白色圆角底必须保持白色(黑图标的可读性载体)
        if (iconPlateMemo.isNotEmpty() && iconPlateMemo.contains(drawable)) return false
        val gen = MonetPalette.generation()
        val key = System.identityHashCode(drawable)
        drawableTintMemo[key]?.let { if (it == gen) return true }
        val result = tintAnyDrawableImpl(drawable, dark)
        if (result) {
            if (drawableTintMemo.size > 4096) drawableTintMemo.clear()
            drawableTintMemo[key] = gen
        }
        return result
    }

    private fun tintAnyDrawableImpl(drawable: Drawable, dark: Boolean): Boolean {
        if (tintSkinDrawableByBitmap(drawable)) return true
        if (drawable.javaClass.name == "android.graphics.drawable.RippleDrawable") {
            var handled = false
            try {
                for (field in drawable.javaClass.declaredFields) {
                    if (Drawable::class.java.isAssignableFrom(field.type)) {
                        field.isAccessible = true
                        val child = field.get(drawable) as? Drawable
                        if (child != null && tintAnyDrawable(child, dark)) handled = true
                    }
                }
            } catch (t: Throwable) {
                // ignore
            }
            return handled
        }
        if (drawable is ColorDrawable) {
            val original = colorOfColorDrawable(drawable)
            if (original == 0) return false
            // 已经是莫奈配色方案里的颜色（说明已被资源名正确染过），不再二次映射
            if (isSchemeColor(original, dark)) return true
            val mapped = TokenMapper.bgColorForDrawable(original, dark) ?: return false
            if (mapped != original) {
                drawable.mutate()
                drawable.color = mapped
                logOnce("view bg #${Integer.toHexString(original)} -> #${Integer.toHexString(mapped)}")
            }
            return true
        }
        if (drawable is GradientDrawable) {
            // 单色底
            val solid = runCatching { drawable.color?.defaultColor }.getOrNull()
            if (solid != null) {
                if (isSchemeColor(solid, dark)) return true
                val mapped = TokenMapper.bgColorForDrawable(solid, dark) ?: return false
                if (mapped != solid) {
                    drawable.mutate()
                    drawable.setColor(mapped)
                    logOnce(
                        "gradient bg #${Integer.toHexString(solid)} -> " +
                            "#${Integer.toHexString(mapped)}"
                    )
                }
                return true
            }
            // 多色渐变（setColors(int[])，此时 color 为 null）：钱包页
            // QWalletHomeAppsLayout 的白色渐变底就走这条，以前整块直接跳过，
            // 于是那片白底一直没被染。逐色按面色映射。
            val colors = runCatching { drawable.colors }.getOrNull() ?: return false
            if (colors.isEmpty()) return false
            val out = IntArray(colors.size)
            var changed = false
            for (i in colors.indices) {
                val c = colors[i]
                val m = if (isSchemeColor(c, dark)) {
                    c
                } else {
                    TokenMapper.bgColorForDrawable(c, dark) ?: c
                }
                out[i] = m
                if (m != c) changed = true
            }
            if (changed) {
                drawable.mutate()
                drawable.colors = out
                logOnce("gradient colors on " + drawable.javaClass.simpleName + " remapped")
            }
            return changed
        }
        if (drawable is DrawableContainer) {
            var handled = false
            try {
                val state = drawable.constantState as? DrawableContainer.DrawableContainerState
                state?.children?.forEach { child ->
                    if (child != null && tintAnyDrawable(child, dark)) handled = true
                }
            } catch (t: Throwable) {
                // ignore
            }
            return handled
        }
        if (drawable is LayerDrawable) {
            var handled = false
            try {
                for (i in 0 until drawable.numberOfLayers) {
                    val child = drawable.getDrawable(i) ?: continue
                    if (tintAnyDrawable(child, dark)) handled = true
                }
            } catch (t: Throwable) {
                // ignore
            }
            return handled
        }
        // 普通位图（token_bg 白图等）：转发弹窗面板底就走这里，之前没有分支，
        // 导致白色位图背景完全没被染。SRC_IN 保留位图 alpha（圆角）。
        if (drawable is android.graphics.drawable.BitmapDrawable) {
            if (drawable.colorFilter != null) return true
            val bitmap = drawable.bitmap ?: return false
            val dominant = sampleBitmapColor(bitmap) ?: return false
            val nm = drawableNameMemo[drawable]
            // 顶栏返回键/箭头这类**前景图标**位图：白色在深色主题下必须提亮成
            // onSurface。以前统一走背景规则（白 → surfaceContainer #001C2A），
            // 压在钱包页顶栏(#003045)上就是深压深，等于看不见。
            if (nm != null && (nm.contains("back") || nm.contains("arrow"))) {
                val fg = MonetPalette.palette(ThemeState.isNight(null, timClassLoader)).onSurface
                if (fg != dominant) {
                    drawable.mutate()
                    drawable.colorFilter = PorterDuffColorFilter(fg, PorterDuff.Mode.SRC_IN)
                    drawable.invalidateSelf()
                }
                if (backIconLog++ < 6) {
                    Log.i(TAG, "back icon -> #" + Integer.toHexString(fg) + " (" + nm + ")")
                }
                return true
            }
            val mapped = TokenMapper.bgColorForDrawable(dominant, dark) ?: return false
            if (mapped != dominant) {
                drawable.mutate()
                drawable.colorFilter = PorterDuffColorFilter(mapped, PorterDuff.Mode.SRC_IN)
                drawable.invalidateSelf()
                if (bitmapBgLog++ < 12) {
                    Log.i(
                        TAG,
                        "bitmap bg #" + Integer.toHexString(dominant) + " -> #" +
                            Integer.toHexString(mapped) + " name=" + nm
                    )
                }
            }
            return true
        }
        if (drawable is android.graphics.drawable.NinePatchDrawable) {
            if (drawable.colorFilter != null) return true
            val dominant = renderSample(drawable) ?: return false
            val mapped = TokenMapper.inlineBgColor(dominant, dark) ?: return false
            if (mapped != dominant) {
                drawable.mutate()
                drawable.colorFilter = PorterDuffColorFilter(mapped, PorterDuff.Mode.SRC_IN)
                drawable.invalidateSelf()
                logOnce("ninepatch bg #${Integer.toHexString(dominant)} -> #${Integer.toHexString(mapped)}")
            }
            return true
        }
        return false
    }

    /** 判断颜色是否已经是当前莫奈方案里的角色色（说明已被资源名路径染过）。 */
    private fun isSchemeColor(color: Int, dark: Boolean): Boolean {
        val scheme = MonetPalette.palette(dark)
        val opaque = opaqueColor(color)
        // AMOLED 纯黑面是我们输出的表面，也算“已莫奈”
        if (MonetPalette.isAmoled() && opaque == 0xFF000000.toInt()) return true
        return opaque == scheme.primary ||
            opaque == scheme.onPrimary ||
            opaque == scheme.primaryContainer ||
            opaque == scheme.onPrimaryContainer ||
            opaque == scheme.surface ||
            opaque == scheme.surfaceBright ||
            opaque == scheme.surfaceDim ||
            opaque == scheme.surfaceContainer ||
            opaque == scheme.surfaceContainerLow ||
            opaque == scheme.surfaceContainerLowest ||
            opaque == scheme.surfaceContainerHigh ||
            opaque == scheme.surfaceContainerHighest ||
            opaque == scheme.onSurface ||
            opaque == scheme.onSurfaceVariant ||
            opaque == scheme.surfaceVariant ||
            opaque == scheme.outline ||
            opaque == scheme.outlineVariant
    }

    /** 皮肤位图背景（聊天列表条目的白/灰 9-patch 等）：按位图主色映射。 */
    /** 皮肤 drawable 若带滤色：滤色源为红色系（如 #FF4766 红气泡）→ 换成 primary。
     *  返回 1=已换色 / 0=滤色非红(保持) / -1=无滤色。 */
    private fun redSkinFilterOf(paint: Paint?, drawable: Drawable): Int {
        val filter = paint?.colorFilter ?: return -1
        val src = try {
            val f = cachedField(filter.javaClass, "mSrcColor")
            f?.also { it.isAccessible = true }
            f?.get(filter) as? Int
        } catch (t: Throwable) {
            null
        }
        if (src == null) return 0
        val opaque = opaqueColor(src)
        if ((src ushr 24) >= 0xE0 && isErrorRed(opaque)) {
            val primary = MonetPalette.palette(
                ThemeState.isNight(null, timClassLoader)
            ).primary
            paint.colorFilter = PorterDuffColorFilter(primary, PorterDuff.Mode.SRC_IN)
            drawable.invalidateSelf()
            logOnce(
                "skin red filter #${Integer.toHexString(src)} -> #${Integer.toHexString(primary)}"
            )
            return 1
        }
        return 0
    }

    /** 位图背景主色 → 目标色：红色系（未读数字气泡等红九宫格）→ primary，
     *  其余走既有内联规则（白→卡片色阶、蓝→primary、灰保留）。 */
    private fun mappedBitmapBgColor(dominant: Int, dark: Boolean): Int? {
        val opaque = opaqueColor(dominant)
        if (isErrorRed(opaque)) {
            return MonetPalette.palette(dark).primary
        }
        return TokenMapper.inlineBgColor(opaque, dark)
    }

    private fun tintSkinDrawableByBitmap(drawable: Drawable?): Boolean {
        if (drawable == null) return false
        return try {
            when (drawable.javaClass.name) {
                "com.tencent.theme.SkinnableBitmapDrawable" -> {
                    val stateField = cachedField(drawable.javaClass, "mBitmapState") ?: return false
                    stateField.isAccessible = true
                    val state = stateField.get(drawable) ?: return false
                    val paintField = cachedField(state.javaClass, "mPaint") ?: return false
                    paintField.isAccessible = true
                    val paint = paintField.get(state) as? Paint ?: return false
                    // 已被命名染色路径处理过（例如 bg_bottom_standard_bg 先按名字染成
                    // surfaceContainer），不再用位图采样结果覆盖，否则会把正确颜色
                    // 二次染成卡片色，导致设置页背景和选项列表同色。
                    // 但红气泡类皮肤靠滤色上色（#FF4766 等），滤色源为红时换 primary。
                    val redSt = redSkinFilterOf(paint, drawable)
                    if (redSt == 1) return true
                    val hadFilterB = redSt == 0
                    // 先查 colorFilter 再采样位图：聊天列表滚动时同一 drawable 会反复
                    // setBackground，已染色的情况下绝不能每帧再 getPixel 采样。
                    val bmpField = cachedField(state.javaClass, "mBitmap") ?: return false
                    bmpField.isAccessible = true
                    val dominant = sampleBitmapColor(bmpField.get(state) as? Bitmap) ?: return false
                    val opaqueD2 = opaqueColor(dominant)
                    if (hadFilterB && !isErrorRed(opaqueD2)) return true
                    val mapped = mappedBitmapBgColor(
                        dominant,
                        ThemeState.isNight(null, timClassLoader)
                    ) ?: return false
                    paint.setColorFilter(PorterDuffColorFilter(mapped, PorterDuff.Mode.SRC_IN))
                    drawable.invalidateSelf()
                    logOnce("skin bitmap bg #${Integer.toHexString(dominant)} -> #${Integer.toHexString(mapped)}")
                    true
                }
                "com.tencent.theme.SkinnableNinePatchDrawable" -> {
                    val paint = runCatching {
                        cachedMethod(drawable.javaClass, "getPaint")?.invoke(drawable) as? Paint
                    }.getOrNull()
                    val redSt2 = redSkinFilterOf(paint, drawable)
                    if (redSt2 == 1) return true
                    val hadFilter = redSt2 == 0
                    var dominant: Int? = null
                    runCatching {
                        val state = drawable.constantState ?: return@runCatching
                        val bmpField = cachedFieldByType(state.javaClass, Bitmap::class.java)
                            ?: return@runCatching
                        bmpField.isAccessible = true
                        dominant = sampleBitmapColor(bmpField.get(state) as? Bitmap)
                    }
                    if (dominant == null) {
                        dominant = renderSample(drawable)
                    }
                    dominant ?: return false
                    val opaqueD = opaqueColor(dominant)
                    // 带既有滤色但滤色非红：只有当素材本身是红色时才覆写，
                    // 避免二次染坏白/灰皮肤图标。
                    if (hadFilter && !isErrorRed(opaqueD)) return true
                    val mapped = mappedBitmapBgColor(
                        dominant,
                        ThemeState.isNight(null, timClassLoader)
                    ) ?: return false
                    if (paint != null) {
                        paint.setColorFilter(PorterDuffColorFilter(mapped, PorterDuff.Mode.SRC_IN))
                    } else {
                        drawable.mutate()
                        drawable.setColorFilter(mapped, PorterDuff.Mode.SRC_IN)
                        drawable.setTint(mapped)
                    }
                    drawable.invalidateSelf()
                    logOnce("skin ninepatch bg #${Integer.toHexString(dominant)} -> #${Integer.toHexString(mapped)}")
                    true
                }
                "android.graphics.drawable.NinePatchDrawable" -> {
                    val paint = cachedMethod(drawable.javaClass, "getPaint")
                        ?.invoke(drawable) as? Paint ?: return false
                    if (paint.colorFilter != null) return true
                    val dominant = renderSample(drawable) ?: return false
                    val mapped = mappedBitmapBgColor(
                        dominant,
                        ThemeState.isNight(null, timClassLoader)
                    ) ?: return false
                    paint.setColorFilter(PorterDuffColorFilter(mapped, PorterDuff.Mode.SRC_IN))
                    drawable.invalidateSelf()
                    logOnce("ninepatch bg #${Integer.toHexString(dominant)} -> #${Integer.toHexString(mapped)}")
                    true
                }
                else -> false
            }
        } catch (t: Throwable) {
            false
        }
    }

    /** 按字段类型沿继承链查找（规避混淆后的字段名）。 */
    private fun findFieldByTypeDeep(cls: Class<*>?, type: Class<*>): Field? {
        var current: Class<*>? = cls
        while (current != null) {
            current.declaredFields.firstOrNull { it.type == type }?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun sampleBitmapColor(bitmap: Bitmap?): Int? {
        if (bitmap == null) return null
        return try {
            val w = bitmap.width
            val h = bitmap.height
            val xs = intArrayOf(w / 4, w / 2, 3 * w / 4)
            val ys = intArrayOf(h / 4, h / 2, 3 * h / 4)
            var r = 0
            var g = 0
            var b = 0
            var count = 0
            for (x in xs) {
                for (y in ys) {
                    val pixel = bitmap.getPixel(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1))
                    if (pixel ushr 24 > 200) {
                        r += (pixel shr 16) and 0xFF
                        g += (pixel shr 8) and 0xFF
                        b += pixel and 0xFF
                        count++
                    }
                }
            }
            if (count == 0) null
            else (0xFF shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
        } catch (t: Throwable) {
            null
        }
    }

    /** 把 drawable 渲染到小位图采样主色（不依赖内部字段）。 */
    private fun renderSample(drawable: Drawable): Int? {
        return try {
            val iw = drawable.intrinsicWidth
            val ih = drawable.intrinsicHeight
            val w = if (iw > 0) maxOf(4, minOf(16, iw)) else 16
            val h = if (ih > 0) maxOf(4, minOf(16, ih)) else 16
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val saved = drawable.copyBounds()
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            drawable.bounds = saved
            try {
                sampleBitmapColor(bitmap)
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------
    // AIO 气泡文字（修复深色模式下“浅色气泡 + 浅色文字”的对比度问题）
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 聊天输入框（“说点什么…”）：背景色跟随对方消息气泡（guestBubble），
    // 绕开 TIM 皮肤系统把它染成主色（深色下 #6CD6FF 过亮）的问题。
    // 只作用于 AIOEditText 自身（输入框），旁边 + / 语音 / 表情所在
    // 的整条输入栏背景不在本 hook 范围，保持原样。
    // ------------------------------------------------------------------

    private fun hookAioEditText(module: XposedModule, cl: ClassLoader) {
        val cls = try {
            Class.forName("com.tencent.mobileqq.aio.input.edit.AIOEditText", false, cl)
        } catch (t: Throwable) {
            Log.w(TAG, "AIOEditText not found", t)
            return
        }
        // 与对方消息气泡完全同色：深色=surfaceBright、浅色=surface（含 AMOLED 语义）
        val inputColor: () -> Int = {
            TokenMapper.guestBubble(ThemeState.isNight(null, cl))
        }
        findMethod(View::class.java, setOf("setBackground"), Drawable::class.java)
            ?.let { method ->
                logOnce("hook installed: View.setBackground (input field force)")
                module.hook(method).intercept { chain ->
                    if (cls.isInstance(chain.thisObject)) {
                        val density = (chain.thisObject as View).resources.displayMetrics.density
                        val gd = GradientDrawable()
                        gd.cornerRadius = density * 10f
                        gd.setColor(inputColor())
                        chain.proceed(arrayOf<Any>(gd))
                    } else {
                        chain.proceed()
                    }
                }
            }
        findMethod(View::class.java, setOf("setBackgroundColor"), INT_TYPE)
            ?.let { method ->
                logOnce("hook installed: View.setBackgroundColor (input field force)")
                module.hook(method).intercept { chain ->
                    if (cls.isInstance(chain.thisObject)) {
                        chain.proceed(arrayOf<Any>(inputColor()))
                    } else {
                        chain.proceed()
                    }
                }
            }

        // “说点什么...”占位文字：统一用次级文字色 onSurfaceVariant（略淡于正文）
        val hintColor: () -> Int = {
            MonetPalette.palette(ThemeState.isNight(null, cl)).onSurfaceVariant
        }
        findMethod(TextView::class.java, setOf("setHintTextColor"), INT_TYPE)
            ?.let { method ->
                logOnce("hook installed: TextView.setHintTextColor (input hint)")
                runCatching { module.deoptimize(method) }
                module.hook(method).intercept { chain ->
                    if (cls.isInstance(chain.thisObject)) {
                        chain.proceed(arrayOf<Any>(hintColor()))
                    } else {
                        chain.proceed()
                    }
                }
            }
        findMethod(
            TextView::class.java,
            setOf("setHintTextColor"),
            ColorStateList::class.java
        )?.let { method ->
            logOnce("hook installed: TextView.setHintTextColor(CSL) (input hint)")
            runCatching { module.deoptimize(method) }
            module.hook(method).intercept { chain ->
                if (cls.isInstance(chain.thisObject)) {
                    chain.proceed(arrayOf<Any>(ColorStateList.valueOf(hintColor())))
                } else {
                    chain.proceed()
                }
            }
        }

        // 兜底：XML 属性路径可能在构造器内直接设置 hint 色、不经过
        // setHintTextColor 方法，挂载后再强制刷一次（幂等）。
        findMethod(View::class.java, setOf("onAttachedToWindow"))
            ?.let { method ->
                logOnce("hook installed: View.onAttachedToWindow (input hint force)")
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val view = chain.thisObject as? View
                        if (view != null && cls.isInstance(view)) {
                            view.post {
                                (view as? TextView)?.setHintTextColor(hintColor())
                            }
                        }
                    } catch (t: Throwable) {
                        // ignore
                    }
                    result
                }
            }
    }

    private fun hookAioBubbleText(module: XposedModule, cl: ClassLoader) {
        val cls = try {
            Class.forName(AIO_UTILS, false, cl)
        } catch (t: Throwable) {
            Log.w(TAG, "AIO utils not found", t)
            return
        }

        // int h(Context)：自己发出的气泡文字颜色
        findMethod(cls, setOf("h"), Context::class.java)
            ?.let { method ->
                logOnce("hook installed: $AIO_UTILS.h")
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    if (result is Int) {
                        MonetPalette.palette(ThemeState.isNight(null, cl)).onPrimary
                    } else {
                        result
                    }
                }
            }

        // int f(Context)：收到的气泡文字颜色
        findMethod(cls, setOf("f"), Context::class.java)
            ?.let { method ->
                logOnce("hook installed: $AIO_UTILS.f")
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    if (result is Int) {
                        MonetPalette.palette(ThemeState.isNight(null, cl)).onSurface
                    } else {
                        result
                    }
                }
            }
    }

    // ------------------------------------------------------------------
    // AIO 气泡背景：直接在气泡 drawable 创建点强制颜色，不再依赖
    // getColor / token 的解析链路（某些主题下该链路会漏掉或解析成页面背景色）
    // e(Context,TimBubbleStyle,float)=对方气泡；g(...)=自己气泡
    // ------------------------------------------------------------------

    private fun hookAioBubbleBg(module: XposedModule, cl: ClassLoader) {
        val cls = try {
            Class.forName(AIO_UTILS, false, cl)
        } catch (t: Throwable) {
            Log.w(TAG, "AIO utils not found for bubble bg", t)
            return
        }
        val styleType = try {
            Class.forName("com.tencent.mobileqq.aio.msglist.holder.skin.TimBubbleStyle", false, cl)
        } catch (t: Throwable) {
            Log.w(TAG, "TimBubbleStyle not found", t)
            return
        }
        val floatType = Float::class.javaPrimitiveType!!
        var bubbleBgCalls = 0

        for ((methodName, guest) in listOf("e" to true, "g" to false)) {
            findMethod(cls, setOf(methodName), Context::class.java, styleType, floatType)
                ?.let { method ->
                    logOnce("hook installed: $AIO_UTILS.$methodName (bubble bg)")
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val drawable = result as? Drawable
                            if (drawable != null) {
                                val scheme = MonetPalette.palette(ThemeState.isNight(null, cl))
                                val color = when {
                                    // AMOLED 黑：自己/对方气泡都纯黑
                                    MonetPalette.isAmoled() -> 0xFF000000.toInt()
                                    guest ->
                                        if (scheme.isDark) scheme.surfaceBright else scheme.surface
                                    else -> scheme.primary
                                }
                                forceGradientColor(drawable, color)
                                val applied = gradientColorOf(drawable)
                                if (bubbleBgCalls++ < 10) {
                                    Log.i(
                                        TAG,
                                        "bubble bg $methodName guest=$guest style=${chain.getArg(1)} " +
                                            "drawable=${drawable.javaClass.name} -> " +
                                            "#${Integer.toHexString(color)} " +
                                            "applied=#${applied?.let { Integer.toHexString(it) } ?: "?"}"
                                    )
                                }
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "force bubble bg failed", t)
                        }
                        result
                    }
                }
        }

        // 兜底：气泡皮肤统一入口 i(Context, AIOMsgItem, int)。
        // 如果某个主题/消息类型根本没给气泡设置背景（透明 → 透出页面底色），
        // 在这里直接注入正确颜色的 GradientDrawable；有背景则强制改色。
        val msgItemType = try {
            Class.forName("com.tencent.mobileqq.aio.msg.AIOMsgItem", false, cl)
        } catch (t: Throwable) {
            null
        }
        if (msgItemType != null) {
            findMethod(cls, setOf("i"), Context::class.java, msgItemType, INT_TYPE)
                ?.let { method ->
                    logOnce("hook installed: $AIO_UTILS.i (bubble skin)")
                    var skinCalls = 0
                    val isSelfMethod = cachedMethod(msgItemType, "isSelf")
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val msgItem = chain.getArg(1)
                            val isSelf = (isSelfMethod?.invoke(msgItem) as? Boolean) ?: false
                            val scheme = MonetPalette.palette(ThemeState.isNight(null, cl))
                            val color = if (MonetPalette.isAmoled()) {
                                // AMOLED 黑：双方气泡都纯黑
                                0xFF000000.toInt()
                            } else if (isSelf) {
                                scheme.primary
                            } else {
                                if (scheme.isDark) scheme.surfaceBright else scheme.surface
                            }

                            // 同时强制气泡皮肤里的文字颜色（正文/链接）。
                            // 某些消息 k() 不会设置文字色（例如带 VAS 气泡属性的消息），
                            // 会退回 XML 默认浅色，导致亮色模式下正文几乎不可见。
                            runCatching {
                                val cInfo = cachedMethod(result?.javaClass, "e")?.invoke(result)
                                if (cInfo != null) {
                                    // AMOLED 黑：双方文字统一 onSurface、链接统一亮主色
                                    val textColor = when {
                                        MonetPalette.isAmoled() -> scheme.onSurface
                                        isSelf -> scheme.onPrimary
                                        else -> scheme.onSurface
                                    }
                                    val linkColor = when {
                                        MonetPalette.isAmoled() -> scheme.primary
                                        isSelf ->
                                            if (scheme.isDark) scheme.surfaceBright else scheme.surface
                                        else -> scheme.primary
                                    }
                                    cachedMethod(cInfo.javaClass, "g", Integer::class.java)
                                        ?.invoke(cInfo, textColor)
                                    cachedMethod(cInfo.javaClass, "h", ColorStateList::class.java)
                                        ?.invoke(cInfo, ColorStateList.valueOf(textColor))
                                    cachedMethod(cInfo.javaClass, "j", ColorStateList::class.java)
                                        ?.invoke(cInfo, ColorStateList.valueOf(linkColor))
                                }
                            }

                            // AIOBubbleSkinInfo.a() -> BackgroundImageInfo
                            val bgInfo = cachedMethod(result?.javaClass, "a")?.invoke(result)
                            // BackgroundImageInfo.a() -> Drawable
                            val drawable = cachedMethod(bgInfo?.javaClass, "a")
                                ?.invoke(bgInfo) as? Drawable
                            if (skinCalls++ < 20) {
                                Log.i(
                                    TAG,
                                    "bubble skin #$skinCalls item=${msgItem?.javaClass?.name} " +
                                        "isSelf=$isSelf bg=${drawable?.javaClass?.name ?: "null"} " +
                                        "applied=#${gradientColorOf(drawable)?.let { Integer.toHexString(it) } ?: "?"}"
                                )
                            }
                            if (drawable != null) {
                                forceGradientColor(drawable, color)
                            } else {
                                // 透明气泡：直接加载 TIM 官方九宫格气泡皮肤
                                // （skin_aio_friend/user_bubble_nor_simple），
                                // 保留官方圆角、内边距和消息间距，只靠命名规则染上莫奈色。
                                val context = chain.getArg(0) as? Context
                                val resName = if (isSelf) {
                                    "skin_aio_user_bubble_nor_simple"
                                } else {
                                    "skin_aio_friend_bubble_nor_simple"
                                }
                                val resId = context?.resources
                                    ?.getIdentifier(resName, "drawable", context.packageName)
                                    ?: 0
                                if (context != null && resId != 0) {
                                    val official = context.resources.getDrawable(resId, context.theme)
                                    cachedMethod(bgInfo?.javaClass, "b", Drawable::class.java)
                                        ?.invoke(bgInfo, official)
                                    logOnce("bubble bg injected (official ninepatch)")
                                }
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "force bubble skin failed", t)
                        }
                        result
                    }
                }
        }
    }

    /** 把气泡背景（可能是 GradientDrawable 或包了它的 LayerDrawable）染成目标色。 */
    private fun forceGradientColor(drawable: Drawable, color: Int) {
        if (drawable is GradientDrawable) {
            drawable.mutate()
            drawable.setColor(color)
            return
        }
        if (drawable is LayerDrawable) {
            for (i in 0 until drawable.numberOfLayers) {
                drawable.getDrawable(i)?.let { forceGradientColor(it, color) }
            }
            return
        }
        if (drawable is DrawableContainer) {
            runCatching {
                val state = drawable.constantState as? DrawableContainer.DrawableContainerState
                state?.children?.forEach { child ->
                    if (child != null) forceGradientColor(child, color)
                }
            }
        }
    }

    private fun tintBadgeIfMatch(chain: XposedInterface.Chain) {
        val text = chain.getArg(0) as? CharSequence ?: return
        tintTextViewBadge(chain.thisObject as? TextView, text)
    }

    // ------------------------------------------------------------------
    // 群摘要附标实际用 SingleLineTextView（自定义 View，非 TextView）渲染：
    // 钩它的 setText/setTextColor，命中附标文本就染 primary + onPrimary。
    // ------------------------------------------------------------------

    private fun hookSingleLineBadge(module: XposedModule, cl: ClassLoader) {
        val cls = try {
            Class.forName("com.tencent.widget.SingleLineTextView", false, cl)
        } catch (t: Throwable) {
            Log.w(TAG, "SingleLineTextView not found", t)
            return
        }
        val getTextMethod = runCatching { cls.getMethod("getText") }.getOrNull()
        val setTextColorMethod = runCatching {
            cls.getMethod("setTextColor", INT_TYPE)
        }.getOrNull()
        findMethod(cls, setOf("setText"), CharSequence::class.java)
            ?.let { method ->
                logOnce("hook installed: SingleLineTextView.setText")
                runCatching { module.deoptimize(method) }
                module.hook(method).intercept { chain ->
                                        // 第三方模块注入界面:整体跳过染色
                                        if (isThirdPartyUiActive()) return@intercept chain.proceed()
                    val result = chain.proceed()
                    val text = runCatching {
                        getTextMethod?.invoke(chain.thisObject)?.toString()
                    }.getOrNull() ?: chain.getArg(0)?.toString()
                    if (summaryBadgeKind(text) != SummaryBadgeKind.NONE) {
                        forceSingleLineBadge(chain.thisObject, text, setTextColorMethod)
                    }
                    result
                }
            }
        findMethod(cls, setOf("setTextColor"), INT_TYPE)
            ?.let { method ->
                logOnce("hook installed: SingleLineTextView.setTextColor")
                runCatching { module.deoptimize(method) }
                module.hook(method).intercept { chain ->
                                        // 第三方模块注入界面:整体跳过染色
                                        if (isThirdPartyUiActive()) return@intercept chain.proceed()
                    val view = chain.thisObject
                    val color = chain.getArg(0) as Int
                    val text = runCatching {
                        getTextMethod?.invoke(view)?.toString()
                    }.getOrNull()
                    when (summaryBadgeKind(text)) {
                        SummaryBadgeKind.RECALL -> {
                            val scheme = MonetPalette.palette(ThemeState.isNight(null, cl))
                            chain.proceed(arrayOf<Any>(scheme.onSurfaceVariant))
                        }
                        SummaryBadgeKind.SUMMARY -> {
                            val scheme = MonetPalette.palette(ThemeState.isNight(null, cl))
                            chain.proceed(arrayOf<Any>(scheme.onPrimary))
                            tintSingleLineBadgeIcon(view, scheme.onPrimary)
                        }
                        SummaryBadgeKind.NONE -> {
                            if (color != 0xFF000000.toInt() &&
                                color != 0xFFFFFFFF.toInt()
                            ) {
                                chain.proceed()
                            } else {
                                val scheme = MonetPalette.palette(ThemeState.isNight(null, cl))
                                if (scheme.isDark) {
                                    chain.proceed(arrayOf<Any>(scheme.onSurface))
                                } else {
                                    chain.proceed()
                                }
                            }
                        }
                    }
                }
            }
    }

    private fun forceSingleLineBadge(view: Any?, text: String?, setTextColorMethod: Method?) {
        if (view == null) return
        try {
            val dark = ThemeState.isNight(null, timClassLoader)
            val scheme = MonetPalette.palette(dark)
            val v = view as? View
            when (summaryBadgeKind(text)) {
                SummaryBadgeKind.RECALL -> {
                    tintPillBackground(
                        v,
                        findRowCardColor(v, dark, text ?: ""),
                        createIfMissing = false
                    )
                    setTextColorMethod?.invoke(view, scheme.onSurfaceVariant)
                }
                SummaryBadgeKind.SUMMARY -> {
                    // 同上：只染已有背景，不新建胶囊；图标跟随附标色 primary。
                    tintPillBackground(v, scheme.primary, createIfMissing = false)
                    if (v?.background != null) {
                        setTextColorMethod?.invoke(view, scheme.onPrimary)
                    }
                    tintSingleLineBadgeIcon(view, scheme.primary)
                }
                SummaryBadgeKind.NONE -> Unit
            }
        } catch (t: Throwable) {
            Log.w(TAG, "force single line badge failed", t)
        }
    }

    /**
     * 摘要附标左侧的小图标（新文件/@我/N条消息前的 “^” 形图标）存在
     * SingleLineTextView 私有的 mDrawables.mDrawableLeft 里，不走 TextView
     * compound drawable API；把它和附标前缀染成同一个 primary。
     */
    private fun tintSingleLineBadgeIcon(view: Any?, color: Int) {
        val v = view as? View ?: return
        try {
            val holderField = cachedField(v.javaClass, "mDrawables")
                ?.also { runCatching { it.isAccessible = true } } ?: return
            val holder = holderField.get(v) ?: return
            val leftField = cachedField(holder.javaClass, "mDrawableLeft")
                ?.also { runCatching { it.isAccessible = true } } ?: return
            val left = leftField.get(holder) as? Drawable ?: return
            left.mutate()
            left.setColorFilter(color, PorterDuff.Mode.SRC_IN)
            left.setTint(color)
            left.invalidateSelf()
            v.invalidate()
        } catch (t: Throwable) {
            Log.w(TAG, "tint single line badge icon failed", t)
        }
    }

    // ------------------------------------------------------------------
    // AIO 右上角“新文件 / 有人@我 / N条消息”导航角标：
    // 原版在 view.a.b(c, AIONavCorrelation) 里用 Color.parseColor 写死
    // #0099FF/白/灰，完全绕过 Resources。这里按语义重映射：
    // 与用户自己发的消息气泡一致：背景/描边=primary，文字=onPrimary。
    // ------------------------------------------------------------------

    private fun hookAioNavBadge(module: XposedModule, cl: ClassLoader) {
        val helperCls = try {
            Class.forName("com.tencent.mobileqq.aio.reserve1.navigation.view.a", false, cl)
        } catch (t: Throwable) {
            Log.w(TAG, "aio nav badge helper not found", t)
            return
        }
        val stateCls = try {
            Class.forName("com.tencent.mobileqq.aio.reserve1.navigation.view.c", false, cl)
        } catch (t: Throwable) {
            Log.w(TAG, "aio nav badge state not found", t)
            return
        }
        val correlationCls = try {
            Class.forName("com.tencent.mobileqq.aio.reserve1.navigation.AIONavCorrelation", false, cl)
        } catch (t: Throwable) {
            Log.w(TAG, "AIONavCorrelation not found", t)
            return
        }

        findMethod(helperCls, setOf("b"), stateCls, correlationCls)
            ?.let { method ->
                logOnce("hook installed: aio nav badge colors")
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val state = chain.getArg(0) ?: return@intercept result
                        val strong = chain.getArg(1)?.toString() == "STRONG"
                        val scheme = MonetPalette.palette(ThemeState.isNight(null, cl))
                        val intFields = state.javaClass.declaredFields
                            .filter { it.type == INT_TYPE }
                            .onEach { it.isAccessible = true }
                        // 与自己的消息气泡保持一致：背景=primary、文字=onPrimary、
                        // 描边=primary。a.b() 执行后只有这三个颜色字段非 0，
                        // 其余宽高/坐标字段仍是 0，必须跳过以免破坏布局。
                        val nightVariant = intFields.any { field ->
                            val value = field.get(state) as? Int ?: return@any false
                            value != 0 && value != -1 &&
                                !isBlueish(value) && colorLuma(value) < 0x50
                        }
                        for (field in intFields) {
                            val original = field.get(state) as? Int ?: continue
                            if (original == 0) continue
                            val isText = when {
                                strong -> original == -1
                                nightVariant -> original == -1
                                else -> isBlueish(original)
                            }
                            val mapped = if (isText) scheme.onPrimary else scheme.primary
                            if (mapped != original) field.set(state, mapped)
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "tint aio nav badge failed", t)
                    }
                    result
                }
            }

        // 角标的 “^” 形静态图标（6u/6v PNG）在 AioNavAnimView.N(state, Drawable)
        // 里挂到 state 上，绘制时走 drawable 自身颜色，不跟文字 Paint。这里在
        // 每次挂接后把它强制染成 onPrimary，和文字保持一致。
        val viewCls = try {
            Class.forName("com.tencent.mobileqq.aio.reserve1.navigation.view.n", false, cl)
        } catch (t: Throwable) {
            try {
                Class.forName(
                    "com.tencent.mobileqq.aio.reserve1.navigation.view.AioNavAnimView",
                    false,
                    cl
                )
            } catch (t2: Throwable) {
                null
            }
        }
        viewCls?.let { view ->
            Log.i(TAG, "aio nav badge view class resolved: ${view.name}")
            findMethod(view, setOf("N"), stateCls, Drawable::class.java)
                ?.let { method ->
                    logOnce("hook installed: aio nav badge icon tint")
                    runCatching { module.deoptimize(method) }
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val icon = chain.getArg(1) as? Drawable
                                ?: return@intercept result
                            val scheme = MonetPalette.palette(ThemeState.isNight(null, cl))
                            tintNavIcon(icon, scheme.onPrimary)
                        } catch (t: Throwable) {
                            Log.w(TAG, "tint aio nav badge icon failed", t)
                        }
                        result
                    }
                }
            // 公共入口 R(drawable x4)：资源加载回调把 4 个图标（浅/深静态 PNG +
            // 两个 lottie）一次挂进来，直接在这里把静态图标染成 onPrimary。
            findMethod(
                view,
                setOf("R"),
                Drawable::class.java,
                Drawable::class.java,
                Drawable::class.java,
                Drawable::class.java
            )?.let { method ->
                logOnce("hook installed: aio nav badge R (icon tint)")
                runCatching { module.deoptimize(method) }
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val scheme = MonetPalette.palette(ThemeState.isNight(null, cl))
                        for (i in 0 until 4) {
                            (chain.getArg(i) as? Drawable)?.let {
                                tintNavIcon(it, scheme.onPrimary)
                            }
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "tint aio nav badge R failed", t)
                    }
                    result
                }
            }
            // ApngDrawable / Lottie 的每一帧都是 canvas.drawBitmap(..., paint)，
            // paint 只有颜色、位图的 RGB 不会被替换，所以给 drawable 设 tint 无效；
            // 必须给视图的 fg Paint 加 SRC_IN ColorFilter 才能把蓝帧染成 onPrimary。
            // V/W 是每次应用状态时重设 Paint 颜色的入口，补滤镜在这里最稳。
            for (methodName in listOf("V", "W")) {
                findMethod(view, setOf(methodName), stateCls)
                    ?.let { method ->
                        logOnce("hook installed: aio nav badge paint filter ($methodName)")
                        runCatching { module.deoptimize(method) }
                        module.hook(method).intercept { chain ->
                            val result = chain.proceed()
                            try {
                                val scheme = MonetPalette.palette(ThemeState.isNight(null, cl))
                                val filter = PorterDuffColorFilter(
                                    scheme.onPrimary,
                                    PorterDuff.Mode.SRC_IN
                                )
                                val v = chain.thisObject as? View
                                    ?: return@intercept result
                                for (fieldName in listOf("u", "w")) {
                                    val f = cachedField(v.javaClass, fieldName)
                                        ?.also { runCatching { it.isAccessible = true } }
                                    (f?.get(v) as? Paint)?.colorFilter = filter
                                }
                                v.postInvalidate()
                            } catch (t: Throwable) {
                                Log.w(TAG, "tint aio nav badge paint failed", t)
                            }
                            result
                        }
                    }
            }
        }
    }

    private fun tintNavIcon(drawable: Drawable, color: Int) {
        runCatching {
            drawable.mutate()
            drawable.setColorFilter(color, PorterDuff.Mode.SRC_IN)
            drawable.setTint(color)
            drawable.invalidateSelf()
            if (navIconTintLogCount++ < 5) {
                Log.i(
                    TAG,
                    "aio nav icon tint ${drawable.javaClass.simpleName} -> " +
                        "#${Integer.toHexString(color)}"
                )
            }
        }
    }

    /** 原版导航角标文字用的饱和蓝（#0099FF/#0071FF/#0066CC）。 */
    private fun isBlueish(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return b > 0x80 && b > g + 0x20 && g > r + 0x20
    }

    private fun colorLuma(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    // ------------------------------------------------------------------
    // Ark（“聊天记录”合并转发卡片等）token 缓存：
    // ArkHelperImpl 会把 QUIUtil.getCurrentTokenMap() 的结果缓存在静态
    // tokenObject 里，主题/取色变化时不一定重建，导致卡片继续用旧的原版色。
    // 这里每次返回前都对缓存 JSON 再重映射一次，绕开缓存失效问题。
    // ------------------------------------------------------------------

    private fun hookArkToken(module: XposedModule, cl: ClassLoader) {
        val cls = try {
            Class.forName("com.tencent.mobileqq.ark.api.impl.ArkHelperImpl", false, cl)
        } catch (t: Throwable) {
            Log.w(TAG, "ArkHelperImpl not found", t)
            return
        }

        var configCalls = 0
        findMethod(cls, setOf("getCurrentAppConfig"))
            ?.let { method ->
                logOnce("hook installed: ArkHelperImpl.getCurrentAppConfig")
                module.hook(method).intercept { chain ->
                    var result = chain.proceed()
                    // 把当前登录账号 uin 注入配置：重打包后的 baseView.js 用它和
                    // QQ.GetContainerInfo().SendUin 比较，区分自己/别人的卡片。
                    try {
                        val raw = result as? String
                        if (raw != null && raw.startsWith("{")) {
                            val obj = JSONObject(raw)
                            try {
                                if (!obj.has("selfUin") ||
                                    obj.optString("selfUin").isEmpty()
                                ) {
                                    if (!selfUinResolved) {
                                        cachedSelfUin = resolveSelfUin(cl)
                                        selfUinResolved = true
                                    }
                                    cachedSelfUin?.let { obj.put("selfUin", it) }
                                }
                            } catch (t: Throwable) {
                                // ignore
                            }
                            result = obj.toString()
                        }
                    } catch (t: Throwable) {
                        // 注入失败不影响原配置
                    }
                    if (configCalls++ < 3) {
                        Log.i(
                            TAG,
                            "Ark getCurrentAppConfig #$configCalls -> " +
                                result?.toString()?.take(1600)
                        )
                    }
                    result
                }
            }

        var tokenCalls = 0
        findMethod(cls, setOf("getTokenObject"))
            ?.let { method ->
                logOnce("hook installed: ArkHelperImpl.getTokenObject")
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val json = result as? JSONObject ?: return@intercept result
                        val generation = MonetPalette.generation()
                        val cached = arkTokenCache
                        if (arkTokenCacheGen == generation && cached != null) {
                            return@intercept cached
                        }
                        val dark = MonetPalette.palette(ThemeState.isNight(null, cl)).isDark
                        val out = JSONObject()
                        val keys = json.keys()
                        while (keys.hasNext()) {
                            val key = keys.next() as String
                            var mapped = mapTokenString(key, json.optString(key), dark)
                            // 部分 Ark 卡片直接用纯白背景 token；深色下归一为卡片色。
                            // 仅对背景/填充/卡片类 key 兜底，避免误改白色文字与描边。
                            val keyL = key.lowercase()
                            if (dark &&
                                (keyL.contains("bg") ||
                                    keyL.contains("fill") ||
                                    keyL.contains("card"))
                            ) {
                                mapped = mapped.split(",").joinToString(",") { part ->
                                    val color = runCatching {
                                        Color.parseColor(part.trim())
                                    }.getOrNull()
                                    if (color != null && isNearWhite(color)) {
                                        colorToHex(TokenMapper.bgCard(true))
                                    } else {
                                        part
                                    }
                                }
                            }
                            out.put(key, mapped)
                        }
                        if (tokenCalls++ < 3) {
                            Log.i(
                                TAG,
                                "Ark token object #$tokenCalls remapped: " +
                                    "bubble_host_top=${out.optString("bubble_host_top")}, " +
                                    "bubble_host_bottom=${out.optString("bubble_host_bottom")}, " +
                                    "bubble_guest=${out.optString("bubble_guest")}, " +
                                    "fill_light_primary=${out.optString("fill_light_primary")}, " +
                                    "bg_nav_primary=${out.optString("bg_nav_primary")}, " +
                                    "text_primary=${out.optString("text_primary")}"
                            )
                            Log.i(
                                TAG,
                                "Ark token object caller:\n" +
                                    Log.getStackTraceString(Throwable()).take(1500)
                            )
                        }
                        arkTokenCache = out
                        arkTokenCacheGen = generation
                        out
                    } catch (t: Throwable) {
                        Log.w(TAG, "remap Ark token object failed", t)
                        result
                    }
                }
            }
    }

    // ------------------------------------------------------------------
    // “聊天记录”合并转发卡片（com.tencent.multimsg）：
    // 卡片底色是包内 baseView.js 硬编码的 0xFFFFFFFF，且只在老深色主题 id
    // 下切换成 0xFF262626。莫奈主题（2971）不认识 → 自己的卡片永远纯白。
    // 在 TIM 校验 .ark 签名前把包重打包（统一用对方气泡配色 bubble_guest
    // 实底 + guest 文字 token，按住不变色），校验直接放行。
    // 重打包失败则走原逻辑，不影响加载。
    // 群公告卡片（com.tencent.mannounce）同理：主题 2971 分支硬编码黑字，
    // 在验证签名前把包内 mannounce.lua 重打包为按 token 取色。
    // ------------------------------------------------------------------

    private fun hookArkPackagePatch(module: XposedModule, cl: ClassLoader) {
        val cls = try {
            Class.forName("com.tencent.ark.open.internal.ArkAppCGIMgr", false, cl)
        } catch (t: Throwable) {
            Log.w(TAG, "ArkAppCGIMgr not found", t)
            return
        }
        findMethod(cls, setOf("verifyAppPackage"), File::class.java, ByteArray::class.java)
            ?.let { method ->
                logOnce("hook installed: ArkAppCGIMgr.verifyAppPackage (ark patch)")
                module.hook(method).intercept { chain ->
                    val file = chain.getArg(0) as? File
                    if (file != null &&
                        (file.absolutePath.contains("com.tencent.multimsg") ||
                            file.absolutePath.contains("com.tencent.mannounce"))
                    ) {
                        if (ArkPackagePatcher.patchIfNeeded(file)) {
                            Log.i(TAG, "ark app verify bypassed: ${file.name}")
                            return@intercept true
                        }
                        Log.w(TAG, "ark app patch failed, fallback to original verify")
                    }
                    chain.proceed()
                }
            }

        // 兜底：有些分支（无签名 / debug 环境）根本不会调 verifyAppPackage，
        // 直接在 ArkAppMgr.checkAppSignature(AppPathInfo) 里、引擎读包之前打补丁。
        try {
            val mgr = Class.forName("com.tencent.ark.open.ArkAppMgr", false, cl)
            val pathInfo = Class.forName("com.tencent.ark.open.ArkAppMgr\$AppPathInfo", false, cl)
            findMethod(mgr, setOf("checkAppSignature"), pathInfo)
                ?.let { method ->
                    logOnce("hook installed: ArkAppMgr.checkAppSignature (ark patch)")
                    module.hook(method).intercept { chain ->
                        val info = chain.getArg(0)
                        val path = try {
                            pathInfo.getField("path").get(info) as? String
                        } catch (t: Throwable) {
                            null
                        }
                        if (path != null &&
                            (path.contains("com.tencent.multimsg") ||
                                path.contains("com.tencent.mannounce"))
                        ) {
                            if (ArkPackagePatcher.patchIfNeeded(File(path))) {
                                return@intercept true
                            }
                        }
                        chain.proceed()
                    }
                }
        } catch (t: Throwable) {
            Log.w(TAG, "ArkAppMgr.checkAppSignature hook failed", t)
        }
    }

    /** 通过反射拿当前登录账号 uin（AppRuntime.getCurrentAccountUin()）。 */
    private fun resolveSelfUin(cl: ClassLoader): String? {
        val candidates = listOf(
            fun(): Any? {
                val mobileQq = Class.forName("mqq.app.MobileQQ", false, cl)
                val sMobileQQ = mobileQq.getField("sMobileQQ").get(null)
                return mobileQq.getMethod("peekAppRuntime").invoke(sMobileQQ)
            },
            fun(): Any? {
                val baseApp = Class.forName("com.tencent.common.app.BaseApplicationImpl", false, cl)
                val app = baseApp.getMethod("getApplication").invoke(null)
                return app?.javaClass?.getMethod("getRuntime")?.invoke(app)
            }
        )
        for (getRuntime in candidates) {
            try {
                val runtime = getRuntime() ?: continue
                val uin = runtime.javaClass
                    .getMethod("getCurrentAccountUin")
                    .invoke(runtime) as? String
                if (!uin.isNullOrEmpty() && uin != "0") return uin
            } catch (t: Throwable) {
                // try next
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // 聊天列表胶囊渲染器（摘要附标与滑动菜单共用）：
    // 渲染出来的 TextView 一律 primary 背景 + onPrimary 文字。
    // ------------------------------------------------------------------

    private fun hookBadgeRenderers(module: XposedModule, cl: ClassLoader) {
        val tintPill: (TextView) -> Unit = { tv ->
            try {
                val scheme = MonetPalette.palette(ThemeState.isNight(null, timClassLoader))
                tv.background?.let { bg ->
                    bg.mutate()
                    bg.setColorFilter(scheme.primary, PorterDuff.Mode.SRC_IN)
                    bg.setTint(scheme.primary)
                }
                tv.setTextColor(scheme.onPrimary)
                logOnce("chats pill monetized (${tv.text})")
            } catch (t: Throwable) {
                Log.w(TAG, "tint chats pill failed", t)
            }
        }
        runCatching {
            val cls = Class.forName("com.tencent.qqnt.chats.core.adapter.c.a.c", false, cl)
            val itemCls = Class.forName("com.tencent.qqnt.chats.core.adapter.c.a", false, cl)
            findMethod(cls, setOf("b"), ViewGroup::class.java, itemCls)
                ?.let { method ->
                    logOnce("hook installed: ChatsPill.b")
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        (result as? TextView)?.let(tintPill)
                        result
                    }
                }
            findMethod(cls, setOf("a"), View::class.java, itemCls)
                ?.let { method ->
                    logOnce("hook installed: ChatsPill.a")
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        (chain.getArg(0) as? TextView)?.let(tintPill)
                        result
                    }
                }
        }
        runCatching {
            val cls = Class.forName("com.tencent.qqnt.chats.core.adapter.d.b", false, cl)
            findMethod(
                cls,
                setOf("d"),
                Class.forName("com.tencent.qqnt.chats.utils.preload.LayoutPreLoader\$b", false, cl)
            )?.let { method ->
                logOnce("hook installed: ChatsPill.d")
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    (result as? TextView)?.let(tintPill)
                    result
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 群公告列表页（WebView qun.qq.com/mannounce）跨端页面修正：
    // 页面为 HTML/JS 渲染，QQ 原生色表 hook 无法处理其 CSS 配对问题，因此
    // 在页面加载完成后注入一段 JS：标签（置顶/发给新成员…）→ primary 底 +
    // onPrimary 字；灰阶小字（发布者昵称/时间等）→ onSurfaceVariant；
    // “已读/已确认”旁的白色对钩（叶子/SVG/背景图 multiply/伪元素）→ primary。
    // 入口：捕获 QQ 浏览器 Activity 的 classloader → hook WebViewKernel 的
    // onPageFinished（按方法名+参数个数匹配，规避 X5 类型解析问题）。
    // ------------------------------------------------------------------

    private val mannounceHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var mannounceKernelHooked = false

    private var mannounceLoaderCaptured = false

    /** 从任意 classloader 安装 WebViewKernel.onPageFinished hook。 */
    private fun tryInstallKernelHook(module: XposedModule, loader: ClassLoader?) {
        if (loader == null || mannounceKernelHooked) return
        runCatching {
            val kernel = Class.forName(
                "com.tencent.mobileqq.webview.swift.WebViewKernel",
                false,
                loader
            )
            val method = kernel.declaredMethods.firstOrNull { m ->
                m.name == "onPageFinished" && m.parameterTypes.size == 2
            } ?: return
            method.isAccessible = true
            mannounceKernelHooked = true
            logOnce("hook installed: WebViewKernel.onPageFinished (mannounce web)")
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                try {
                    val view = chain.getArg(0)
                    val url = chain.getArg(1) as? String
                    if (view == null || url == null ||
                        !url.contains("qun.qq.com/mannounce")
                    ) {
                        return@intercept result
                    }
                    injectMannounceColors(view)
                } catch (t: Throwable) {
                    Log.w(TAG, "mannounce kernel recolor failed", t)
                }
                result
            }
        }.onFailure { /* 类未加载则等待 Activity 捕获后重试 */ }
    }

    private fun mannounceJs(): String {
        val scheme = MonetPalette.palette(ThemeState.isNight(null, timClassLoader))
        val tagBg = hexCss(scheme.primary)
        val tagFg = hexCss(scheme.onPrimary)
        val metaGray = hexCss(scheme.onSurfaceVariant)
        return """
            (function(){
              var TAG_BG='$tagBg', TAG_FG='$tagFg', META_GRAY='$metaGray';
              var tags=['置顶','发给新成员','已过期','撤回','全体成员','成员须知','群主公告','管理员公告'];
              function grayish(cs){
                try{
                  var m=cs.color.match(/[\d.]+/g);
                  if(!m||m.length<3)return false;
                  var r=+m[0],g=+m[1],b=+m[2];
                  var mx=Math.max(r,g,b), mn=Math.min(r,g,b);
                  return (mx-mn<=30)&&(mn>=85)&&(mx<=185);
                }catch(e){return false;}
              }
              function injectPseudoRule(doc,el,pseudo,selName){
                try{
                  var ps=doc.defaultView.getComputedStyle(el,pseudo);
                  if(!ps||ps.content==='none')return;
                  var sel='';
                  if(el.id){ sel='#'+el.id; }
                  else if(el.className&&(''+el.className).length){ sel='.'+(''+el.className).trim().split(/\s+/).join('.'); }
                  else { sel=el.tagName.toLowerCase(); }
                  var st=doc.getElementById('__tmReadStyle');
                  if(!st){ st=doc.createElement('style'); st.id='__tmReadStyle'; (doc.head||doc.documentElement).appendChild(st); }
                  st.textContent+=sel+pseudo+'{color:'+TAG_BG+'!important;stroke:'+TAG_BG+'!important;}';
                }catch(e){}
              }
              function iconify(doc,label){
                var scope=label;
                for(var up=0;up<3&&scope.parentElement;up++){ scope=scope.parentElement; }
                var kids=scope.querySelectorAll('*');
                for(var k=0;k<kids.length;k++){
                  var ch=kids[k];
                  var cs=doc.defaultView.getComputedStyle(ch);
                  var mm=cs.color.match(/[\d.]+/g);
                  var isWhite=false,isGray=false,svgWhite=false;
                  if(mm&&mm.length>=3){
                    var r=+mm[0],g=+mm[1],b=+mm[2];
                    isWhite=r>225&&g>225&&b>225;
                    isGray=Math.abs(r-g)<=14&&Math.abs(g-b)<=14&&r>=115&&r<=215;
                  }
                  var tag=ch.tagName;
                  var txt=(ch.textContent||'').trim();
                  var tiny=ch.childElementCount===0&&txt.length>0&&txt.length<=3;
                  var isSvg=tag==='path'||tag==='svg'||(ch.parentElement&&ch.parentElement.tagName==='svg');
                  if(isSvg){
                    try{
                      var fl=ch.getAttribute('fill')||(ch.parentElement?ch.parentElement.getAttribute('fill'):'');
                      svgWhite=/(255|fff|white)/i.test(fl||'');
                    }catch(e){}
                  }
                  if((isWhite||isGray||svgWhite)&&(tiny||isSvg)){
                    ch.style.setProperty('color',TAG_BG,'important');
                    if(isSvg){ ch.setAttribute('fill',TAG_BG); }
                  }
                  // 伪元素图标（::before/::after）
                  injectPseudoRule(doc,ch,'::before');
                  injectPseudoRule(doc,ch,'::after');
                  // 空内容 + 背景图（白色 PNG 图标）：multiply 混合成 primary
                  if(ch.childElementCount===0&&txt===''){
                    var bg=cs.backgroundImage;
                    if(bg&&bg!=='none'){
                      ch.style.setProperty('background-color',TAG_BG,'important');
                      ch.style.setProperty('background-blend-mode','multiply','important');
                    }
                  }
                }
              }
              function paintDoc(doc){
                var els=doc.querySelectorAll('span,div,b,em,label,i,a,p,li,time,font');
                for(var k=0;k<els.length;k++){
                  var e=els[k];
                  var t=(e.textContent||'').replace(/\s+/g,'').trim();
                  if(!t||t.length>60)continue;
                  if(tags.indexOf(t)>=0){
                    e.style.setProperty('background-color',TAG_BG,'important');
                    e.style.setProperty('color',TAG_FG,'important');
                  } else {
                    var direct='';
                    for(var j=0;j<e.childNodes.length;j++){
                      if(e.childNodes[j].nodeType===3){ direct+=e.childNodes[j].nodeValue||''; }
                    }
                    var dt=(direct||'').replace(/\s+/g,'');
                    if(dt.indexOf('已读')>=0||dt.indexOf('已确认')>=0){
                      iconify(doc,e);
                    }
                    // 灰阶小字叶子（发布者昵称/时间等）→ 次级文字色
                    if(e.childElementCount===0){
                      var cs=doc.defaultView.getComputedStyle(e);
                      var fs=parseFloat(cs.fontSize||'0');
                      if(grayish(cs)&&fs>0&&fs<=15&&dt.length>0&&dt.length<=40){
                        e.style.setProperty('color',META_GRAY,'important');
                      }
                    }
                  }
                }
                try{
                  if(!doc.__tmObs){
                    doc.__tmObs=new MutationObserver(function(){ paintDoc(doc); });
                    doc.__tmObs.observe(doc,{childList:true,subtree:true,characterData:true});
                  }
                }catch(e){}
              }
              var docs=[document];
              try{
                (function collect(w){
                  for(var i=0;i<w.frames.length;i++){
                    try{ var d=w.frames[i].document; if(d){ docs.push(d); collect(w.frames[i]); } }
                    catch(e){}
                  }
                })(window);
              }catch(e){}
              for(var di=0;di<docs.length;di++){ paintDoc(docs[di]); }
            })();
        """.trimIndent()
    }

    /** 反射执行注入（系统与 X5 的 ValueCallback 类型不同，逐一尝试，null 回调即可）。 */
    private fun injectMannounceColors(view: Any?) {
        if (view == null) return
        val loader = view.javaClass.classLoader
        val js = mannounceJs()
        val runnable = java.lang.Runnable {
            runCatching {
                val cbNames = listOf(
                    "android.webkit.ValueCallback",
                    "com.tencent.smtt.sdk.ValueCallback"
                )
                for (cbName in cbNames) {
                    val cb = runCatching {
                        Class.forName(cbName, false, loader)
                    }.getOrNull() ?: continue
                    val eval = cachedMethod(
                        view.javaClass,
                        "evaluateJavascript",
                        String::class.java,
                        cb
                    ) ?: continue
                    eval.isAccessible = true
                    eval.invoke(view, js, null)
                    return@Runnable
                }
            }
        }
        // 立即注入一次(多次延时注入经实验证明非必需)
        runnable.run()
    }

    private fun hookMannounceWeb(module: XposedModule) {
        // 捕获 QQ 浏览器 Activity 的 classloader（其插件 loader 才能解析 WebViewKernel）
        runCatching {
            findMethod(Activity::class.java, setOf("onResume"))
                ?.let { method ->
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val activity = chain.thisObject as? Activity ?: return@intercept result
                            val name = activity.javaClass.name
                            if ((name.contains("QQBrowser") || name.contains("WebView")) &&
                                !mannounceLoaderCaptured
                            ) {
                                mannounceLoaderCaptured = true
                                val loader = activity.javaClass.classLoader
                                mannounceHandler.post {
                                    tryInstallKernelHook(module, loader)
                                }
                            }
                        } catch (t: Throwable) {
                            // ignore
                        }
                        result
                    }
                }
        }
    }

    /** 灰条/高亮成员名 span（com.tencent.qqnt.k.c.a = HighlightClickableSpan）：
     *  updateDrawState 用写死常量色上色（成员名蓝 #FF4D94FF、灰 #FF8C8C8C），
     *  绕过资源/token 链。拦截绘制把固定色映射到莫奈：
     *  蓝 → primary；灰（深色下）→ onSurfaceVariant。 */
    private fun hookHighlightSpans(module: XposedModule) {
        runCatching {
            val cls = Class.forName("com.tencent.qqnt.k.c.a", false, timClassLoader)
            val paintType = Class.forName("android.text.TextPaint", false, cls.classLoader)
            findMethod(cls, setOf("updateDrawState"), paintType)
                ?.let { method ->
                    logOnce("hook installed: HighlightClickableSpan.updateDrawState")
                    module.hook(method).intercept { chain ->
                                            // 第三方模块注入界面:整体跳过染色
                                            if (isThirdPartyUiActive()) return@intercept chain.proceed()
                        val result = chain.proceed()
                        try {
                            val paint = chain.getArg(0) as? android.text.TextPaint
                            if (paint != null) {
                                val color = paint.color
                                val scheme = MonetPalette.palette(
                                    ThemeState.isNight(null, timClassLoader)
                                )
                                val mapped = when (color and 0x00FFFFFF) {
                                    0x004D94FF -> scheme.primary
                                    0x008C8C8C ->
                                        if (scheme.isDark) scheme.onSurfaceVariant else color
                                    else -> color
                                }
                                if (mapped != color) {
                                    paint.color = mapped
                                    if (highlightSpanLogCount++ < 8) {
                                        Log.i(
                                            TAG,
                                            "highlight span #${Integer.toHexString(color)} -> " +
                                                "#${Integer.toHexString(mapped)}"
                                        )
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "highlight span remap failed", t)
                        }
                        result
                    }
                }
        }.onFailure { Log.w(TAG, "HighlightClickableSpan not found", it) }
    }

    /** 文件气泡里的圆形操作按钮（下载/暂停等）：白底圆 + 深色箭头小位图，
     *  与“回到最新”气泡同构，双簇重染：白底→primary、深色图形→onPrimary。 */
    private val fileMonitored = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    private fun hookFileDownloadIcons(module: XposedModule) {
        runCatching {
            findMethod(View::class.java, setOf("onAttachedToWindow"))
                ?.let { method ->
                    logOnce("hook installed: View.onAttachedToWindow (file icons)")
                    runCatching { module.deoptimize(method) }
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val view = chain.thisObject as? View ?: return@intercept result
                            val name = view.javaClass.name
                            if ((name.contains("AIOFile") || name.contains("aiofile")) &&
                                fileMonitored.add(System.identityHashCode(view))
                            ) {
                                if (fileMonitored.size > 32) fileMonitored.clear()
                                // attach 即扫一次(延时重扫经实验证明非必需)
                                runCatching { scanFileIcons(view) }
                            }
                        } catch (t: Throwable) {
                            // ignore
                        }
                        result
                    }
                }
        }
    }

    private fun scanFileIcons(root: View?) {
        if (root == null) return
        try {
            val scheme = MonetPalette.palette(ThemeState.isNight(null, timClassLoader))
            val stack = java.util.ArrayDeque<View>()
            stack.add(root)
            var guard = 0
            while (stack.isNotEmpty() && guard < 400) {
                val view = stack.removeFirst()
                guard++
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) {
                        stack.addLast(view.getChildAt(i))
                    }
                }
                val iv = view as? ImageView ?: continue
                val d = iv.drawable ?: continue
                if (d.colorFilter != null) continue
                if (fileIconLogCount > 12) continue
                val bitmap = when (d) {
                    is android.graphics.drawable.BitmapDrawable -> d.bitmap
                    else -> {
                        if (d.javaClass.name == "com.tencent.theme.SkinnableBitmapDrawable") {
                            runCatching {
                                val f = cachedField(d.javaClass, "mBitmap")
                                f?.also { it.isAccessible = true }
                                f?.get(d) as? android.graphics.Bitmap
                            }.getOrNull()
                        } else null
                    }
                }
                if (bitmap == null || bitmap.width > 160 || bitmap.height > 160) continue
                val dominant = sampleBitmapColor(bitmap)
                if (dominant == null || !isNearWhite(dominant)) continue
                // 白底且包含深色图形（两簇）才处理：调用双簇像素重染
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                var minL = 255
                var maxL = 0
                for (px in pixels) {
                    if ((px ushr 24) == 0) continue
                    val l = colorLuma(px and 0xFFFFFF)
                    if (l < minL) minL = l
                    if (l > maxL) maxL = l
                }
                if (maxL - minL < 60) continue
                val tinted = recolorBackBottomBitmap(
                    bitmap,
                    root.resources,
                    scheme.primary,
                    scheme.onPrimary
                )
                iv.setImageDrawable(tinted)
                if (fileIconLogCount++ < 12) {
                    Log.i(
                        TAG,
                        "file icon tinted ${d.javaClass.simpleName} on " +
                            "${view.javaClass.name}"
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "scan file icons failed", t)
        }
    }

    private var fileIconLogCount = 0


    // ------------------------------------------------------------------
    // 深色模式下纯黑/纯白文字归一为 onSurface：
    // 修复转发搜索里用户名/群名纯黑、自己发的聊天记录纯白的问题。
    // 亮色模式不动（黑/白本来就是正常文字色）。
    // ------------------------------------------------------------------

    private fun hookDarkTextColors(module: XposedModule) {
        // void setTextColor(ColorStateList)：XML 的 android:textColor="?attr/xxx"
        // 在布局 inflate 时走这条 —— 钱包页"QQ红包 / 转账 / 金融理财 / 生活娱乐"
        // 那些深灰文字色就是这么设进去的，只 hook int 版本覆盖不到。
        findMethod(TextView::class.java, setOf("setTextColor"), ColorStateList::class.java)
            ?.let { method ->
                logOnce("hook installed: TextView.setTextColor(CSL) (dark normalize)")
                module.hook(method).intercept { chain ->
                    try {
                        val csl = chain.getArg(0) as? ColorStateList
                        if (csl != null && !csl.isStateful) {
                            val c = csl.defaultColor
                            val scheme = MonetPalette.palette(
                                ThemeState.isNight(null, timClassLoader)
                            )
                            if (scheme.isDark) {
                                val opaque = c or 0xFF000000.toInt()
                                val r = (opaque shr 16) and 0xFF
                                val g = (opaque shr 8) and 0xFF
                                val b = opaque and 0xFF
                                val grayish = maxOf(r, g, b) - minOf(r, g, b) <= 24
                                val luma = colorLuma(opaque)
                                if (grayish && luma < 170 && !isSchemeColor(c, true)) {
                                    val mapped = if (luma < 70) {
                                        scheme.onSurface
                                    } else {
                                        scheme.onSurfaceVariant
                                    }
                                    return@intercept chain.proceed(
                                        arrayOf<Any>(ColorStateList.valueOf(mapped))
                                    )
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        // ignore
                    }
                    chain.proceed()
                }
            }
        findMethod(TextView::class.java, setOf("setTextColor"), INT_TYPE)
            ?.let { method ->
                logOnce("hook installed: TextView.setTextColor (dark normalize)")
                runCatching { module.deoptimize(method) }
                module.hook(method).intercept { chain ->
                                        // 第三方模块注入界面:整体跳过染色
                                        if (isThirdPartyUiActive()) return@intercept chain.proceed()
                    val color = chain.getArg(0) as Int
                    val tvObj = chain.thisObject as? TextView
                    // 用户详情页(资料卡):TIM 会在数据到达后重设文字颜色,
                    // 因此在这里接管 —— 左侧标签(昵称/账号/入群时间…)与头部
                    // 名称/简介为亮色 onSurface;右侧取值(账号号等)为次要色
                    // onSurfaceVariant。
                    if (tvObj != null && tvObj.id != 0 && isInProfileCardUi(tvObj)) {
                        val idName = runCatching {
                            tvObj.resources.getResourceEntryName(tvObj.id)
                        }.getOrNull()
                        // 顶部简介(ivs)保持原样(暗色,按用户要求保留)
                        if (idName != null && idName != "ivs") {
                            val scheme = MonetPalette.palette(
                                ThemeState.isNight(null, timClassLoader)
                            )
                            if (scheme.isDark) {
                                // 资料卡内:右侧字段取值(qg3)用次要色,与
                                // "性别/生日/所在地"等一致;其余(头部名称、
                                // 左侧字段标签"昵称/账号/入群时间"等)用主文字色。
                                val target = if (idName == "qg3") {
                                    scheme.onSurfaceVariant
                                } else {
                                    scheme.onSurface
                                }
                                return@intercept chain.proceed(arrayOf<Any>(target))
                            }
                        }
                    }
                    // 红色"群待办/待办"文字标签 → primary（AIO 群待办通知条等）
                    val redOpaque = opaqueColor(color)
                    if (isErrorRed(redOpaque)) {
                        val view = chain.thisObject as? TextView
                        val t = view?.text?.toString().orEmpty()
                        if (t.contains("待办") || t.contains("[群")) {
                            val scheme = MonetPalette.palette(
                                ThemeState.isNight(null, timClassLoader)
                            )
                            return@intercept chain.proceed(arrayOf<Any>(scheme.primary))
                        }
                    }
                    val tvScheme = MonetPalette.palette(ThemeState.isNight(null, timClassLoader))
                    val opaqueColor = color or 0xFF000000.toInt()
                    val cr = (opaqueColor shr 16) and 0xFF
                    val cg = (opaqueColor shr 8) and 0xFF
                    val cb = opaqueColor and 0xFF
                    val grayish = maxOf(cr, cg, cb) - minOf(cr, cg, cb) <= 24
                    val textLuma = colorLuma(opaqueColor)
                    if (color == 0xFF000000.toInt() || color == 0xFFFFFFFF.toInt()) {
                        val view = chain.thisObject as? TextView
                        val scheme = tvScheme
                        if (scheme.isDark) {
                            // 主色胶囊（未读/附标等）里的文字应为 onPrimary，
                            // 与聊天列表未读气泡统一；其余黑/白文字仍归一到 onSurface。
                            // 纯色底（setBackgroundColor 的 ColorDrawable）也要认 primary。
                            val onPrimaryPill = view?.background?.let { bg ->
                                gradientColorOf(bg) == scheme.primary ||
                                    (bg is ColorDrawable && (runCatching {
                                        (bg as? ColorDrawable)?.let { colorOfColorDrawable(it) }
                                    }.getOrNull()?.let { it or 0xFF000000.toInt() }) ==
                                        (scheme.primary or 0xFF000000.toInt()))
                            } ?: false
                            chain.proceed(
                                arrayOf<Any>(if (onPrimaryPill) scheme.onPrimary else scheme.onSurface)
                            )
                        } else {
                            chain.proceed()
                        }
                    } else if (tvScheme.isDark && grayish && textLuma < 170 &&
                        !isSchemeColor(color, true)
                    ) {
                        // 深色配色下的深/中灰文字提亮：钱包页的"QQ红包 / 转账 /
                        // 金融理财 / 生活娱乐"这些都是 TIM 的深灰文字色(#1a1a1a、
                        // #333…)，在莫奈深色面上几乎看不见。只动**无彩色灰阶**
                        // (max-min≤24)，彩色文字与浅灰文字一概不碰。
                        val mapped = if (textLuma < 70) {
                            tvScheme.onSurface
                        } else {
                            tvScheme.onSurfaceVariant
                        }
                        chain.proceed(arrayOf<Any>(mapped))
                    } else {
                        chain.proceed()
                    }
                }
            }
    }

    // ------------------------------------------------------------------
    // 转发/联系人搜索条目：n(TextView, resId) 是统一的文字设色入口，
    // 按资源名强制映射（text_primary→onSurface，text_secondary→onSurfaceVariant）。
    // ------------------------------------------------------------------

    private fun hookSearchItemText(module: XposedModule, cl: ClassLoader) {
        runCatching {
            val cls = Class.forName("com.tencent.mobileqq.search.base.view.g", false, cl)
            findMethod(cls, setOf("n"), TextView::class.java, INT_TYPE)
                ?.let { method ->
                    logOnce("hook installed: SearchView.g.n (text color)")
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val tv = chain.getArg(0) as? TextView ?: return@intercept result
                            val resId = chain.getArg(1) as Int
                            val name = entryName(tv.resources, resId)
                            val mapped = TokenMapper.mapColor(
                                name,
                                tv.currentTextColor,
                                ThemeState.isNight(null, cl)
                            )
                            tv.setTextColor(mapped)
                        } catch (t: Throwable) {
                            Log.w(TAG, "search item text failed", t)
                        }
                        result
                    }
                }
        }
    }

    private fun isNearWhite(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return r >= 235 && g >= 235 && b >= 235
    }

    // ------------------------------------------------------------------
    // 主页面背景（深色模式下这里是写死的十六进制，绕过 Resources）
    // ------------------------------------------------------------------

    private fun hookResconfig(module: XposedModule, cl: ClassLoader) {
        val cls = try {
            Class.forName(RESCONFIG_A, false, cl)
        } catch (t: Throwable) {
            Log.w(TAG, "resconfig.a not found", t)
            return
        }

        // 静态 int a(Context) / b(Context) / c(Context)
        mapOf(
            "a" to "bg_page",
            "b" to "bg_page_secondary",
            "c" to "bg_nav_bottom"
        ).forEach { (methodName, tokenName) ->
            findMethod(cls, setOf(methodName), Context::class.java)
                ?.let { method ->
                    logOnce("hook installed: $RESCONFIG_A.$methodName")
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        if (result is Int) {
                            TokenMapper.mapColor(tokenName, result, ThemeState.isNight(null, cl))
                        } else {
                            result
                        }
                    }
                }
        }
    }

    // ------------------------------------------------------------------
    // drawable 染色（底栏图标、输入栏背景这类烘焙颜色的资源）
    // ------------------------------------------------------------------

    private fun hookDrawables(module: XposedModule) {
        val resourcesCls = Resources::class.java
        val themeCls = Resources.Theme::class.java

        // XML 布局/selector 里的 drawable 都经过包内私有 loadDrawable
        // API 34+ : Drawable loadDrawable(TypedValue, int, int, Theme)
        findMethod(
            resourcesCls,
            setOf("loadDrawable"),
            TypedValue::class.java,
            INT_TYPE,
            INT_TYPE,
            themeCls
        )?.let { hookFrameworkMethod(module, it) { chain, result ->
            val drawable = result as? Drawable
            if (drawable != null) {
                val resId = chain.getArg(1) as Int
                if (resId != 0 && resId shr 24 == 0x01) return@hookFrameworkMethod drawable
                val name = entryName(chain.thisObject as? Resources, resId)
                if (name != null) {
                    return@hookFrameworkMethod tintDrawable(name, drawable)
                } else {
                    tintInlineDrawable(drawable, chain.getArg(0) as? TypedValue)
                }
            } else {
                result
            }
        } }

        // API 31-33 : Drawable loadDrawable(TypedValue, int, Theme)
        findMethod(
            resourcesCls,
            setOf("loadDrawable"),
            TypedValue::class.java,
            INT_TYPE,
            themeCls
        )?.let { hookFrameworkMethod(module, it) { chain, result ->
            val drawable = result as? Drawable
            if (drawable != null) {
                val resId = chain.getArg(1) as Int
                if (resId != 0 && resId shr 24 == 0x01) return@hookFrameworkMethod drawable
                val name = entryName(chain.thisObject as? Resources, resId)
                if (name != null) {
                    return@hookFrameworkMethod tintDrawable(name, drawable)
                } else {
                    tintInlineDrawable(drawable, chain.getArg(0) as? TypedValue)
                }
            } else {
                result
            }
        } }

        findMethod(resourcesCls, setOf("getDrawable"), INT_TYPE)
            ?.let { hookFrameworkMethod(module, it) { chain, result ->
                val drawable = result as? Drawable
                if (drawable != null) {
                    val resId = chain.getArg(0) as Int
                    if (resId shr 24 == 0x01) return@hookFrameworkMethod drawable
                    val name = entryName(chain.thisObject as? Resources, resId)
                    return@hookFrameworkMethod tintDrawable(name, drawable)
                } else {
                    result
                }
            } }

        findMethod(resourcesCls, setOf("getDrawable"), INT_TYPE, themeCls)
            ?.let { hookFrameworkMethod(module, it) { chain, result ->
                val drawable = result as? Drawable
                if (drawable != null) {
                    val resId = chain.getArg(0) as Int
                    if (resId shr 24 == 0x01) return@hookFrameworkMethod drawable
                    val name = entryName(chain.thisObject as? Resources, resId)
                    return@hookFrameworkMethod tintDrawable(name, drawable)
                } else {
                    result
                }
            } }
    }

    /** 文件圆形按钮（矢量白圆+深图形）：按 intrinsic 尺寸栅格化 → 双簇重染。 */
    private fun tintFileCircleIcon(name: String, drawable: Drawable): Drawable? {
        return try {
            if (drawable.colorFilter != null) return drawable
            val res = Resources.getSystem()
            val density = res.displayMetrics.density
            var iw = drawable.intrinsicWidth
            var ih = drawable.intrinsicHeight
            if (iw <= 0 || ih <= 0) {
                iw = (54 * density).toInt()
                ih = iw
            }
            if (iw > 200 || ih > 200) return drawable
            val bitmap = android.graphics.Bitmap.createBitmap(
                iw, ih, android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            val saved = drawable.copyBounds()
            drawable.setBounds(0, 0, iw, ih)
            drawable.draw(canvas)
            drawable.bounds = saved
            val scheme = MonetPalette.palette(ThemeState.isNight(null, timClassLoader))
            val tinted = recolorBackBottomBitmap(
                bitmap,
                res,
                scheme.primary,
                scheme.onPrimary
            )
            if (fileCircleLogCount++ < 8) {
                Log.i(TAG, "file circle $name raster tinted ${iw}x${ih}")
            }
            tinted
        } catch (t: Throwable) {
            Log.w(TAG, "file circle tint failed: $name", t)
            null
        }
    }

    /** “我的”页功能宫格 12 个入口图标资源名。 */
    private val MINE_GRID_ICONS = setOf(
        "l4v", "l4r", "l51", "l4p", "l50", "l52",
        "l4x", "l4y", "l4z", "l4q", "l4w", "l4u"
    )

    /** 整体染成 primary（自带 filter 覆盖为纯色，保留图形 alpha）。 */
    private fun tintIconPrimary(drawable: Drawable): Drawable {
        return try {
            val scheme = MonetPalette.palette(ThemeState.isNight(null, timClassLoader))
            val color = scheme.primary
            if (drawable.colorFilter != null) {
                drawable.mutate()
                drawable.colorFilter = null
            }
            drawable.mutate()
            if (trySetPaintFilter(drawable, color)) {
                drawable
            } else {
                drawable.setColorFilter(color, PorterDuff.Mode.SRC_IN)
                drawable.setTint(color)
                drawable
            }
        } catch (t: Throwable) {
            drawable
        }
    }

    private var brandLogoLogCount = 0

    /** “关于 TIM 与帮助”页品牌大图（drawable kzy：透明底 + 亮蓝图形 + 白色 TIM 字样）
     *  → 双色重染：蓝系 → primary，白系 → onSurfaceVariant，抗锯齿按距离软混合。 */
    private fun tintBrandLogo(drawable: Drawable): Drawable? {
        return try {
            var iw = drawable.intrinsicWidth
            var ih = drawable.intrinsicHeight
            if (iw <= 0 || ih <= 0 || iw > 1000 || ih > 1000) return drawable
            val bmp = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val saved = Rect(drawable.bounds)
            drawable.setBounds(0, 0, iw, ih)
            drawable.draw(canvas)
            drawable.bounds = saved
            val scheme = MonetPalette.palette(ThemeState.isNight(null, timClassLoader))
            val primary = scheme.primary
            val light = scheme.onSurfaceVariant
            val pr = (primary shr 16) and 0xFF
            val pg = (primary shr 8) and 0xFF
            val pb = primary and 0xFF
            val lr = (light shr 16) and 0xFF
            val lg = (light shr 8) and 0xFF
            val lb = light and 0xFF
            val px = IntArray(iw * ih)
            bmp.getPixels(px, 0, iw, 0, 0, iw, ih)
            for (i in px.indices) {
                val c = px[i]
                val a = (c ushr 24) and 0xFF
                if (a < 12) {
                    px[i] = 0
                    continue
                }
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                val mx = maxOf(r, g, b)
                val mn = minOf(r, g, b)
                // 防误染兜底: 饱和且非蓝相的像素保持原样(同上)
                if (mx - mn >= 40 && !(b >= r && b >= g)) continue
                // 源图只有亮蓝与白两族：按到两族原型的距离软混合
                val db = ((r - 203) * (r - 203) + (g - 225) * (g - 225) +
                    (b - 253) * (b - 253)).toDouble()
                val dw = ((255 - r) * (255 - r) + (255 - g) * (255 - g) +
                    (255 - b) * (255 - b)).toDouble()
                val t = dw / (db + dw) // 0=蓝,1=白
                val nr = (pr + ((lr - pr) * t)).toInt()
                val ng = (pg + ((lg - pg) * t)).toInt()
                val nb = (pb + ((lb - pb) * t)).toInt()
                px[i] = (a shl 24) or (nr.coerceIn(0, 255) shl 16) or
                    (ng.coerceIn(0, 255) shl 8) or nb.coerceIn(0, 255)
            }
            bmp.setPixels(px, 0, iw, 0, 0, iw, ih)
            val out = BitmapDrawable(Resources.getSystem(), bmp)
            out.bounds = saved
            if (brandLogoLogCount++ < 3) {
                Log.i(
                    TAG,
                    "brand logo recolored ${iw}x${ih} blue->#" + Integer.toHexString(primary) +
                        " white->#" + Integer.toHexString(light)
                )
            }
            out
        } catch (t: Throwable) {
            null
        }
    }

    /** 栅格判定：透明底上 ≥85% 的不透明像素落在暖橙区间。结果按实例记忆化。 */
    private fun isWarmMonoGlyph(drawable: Drawable): Boolean {
        val id = System.identityHashCode(drawable)
        warmGlyphMemo[id]?.let { return it }
        if (warmGlyphMemo.size > 400) warmGlyphMemo.clear()
        val stats = glyphStats(drawable)
        val result = stats != null && stats[1] >= 12 &&
            stats[1] * 100 <= stats[2] * 72 && stats[1] * 100 >= stats[2] * 4 &&
            stats[0] * 20 >= stats[1] * 17
        warmGlyphMemo[id] = result
        return result
    }

    /** 摘要行（聊天列表/会话摘要）左复合图标：草稿铅笔 c0q 这类"暖橙单色小图形"。
     *
     *  v1.0 用 isWarmMonoGlyph 在 SingleLineTextView.setCompoundDrawables 上处理，
     *  7051d74d4 删除该启发式后，草稿铅笔又变回橙色（实测 #F67C01 未染色）。
     *  这里按同样判据恢复，宿主仍限定在摘要行的复合图标上 —— 别处的暖色小图
     *  （表情/贴纸/红包等彩色图形）彩噪高，不会落进这条判据。 */
    private fun hookSingleLineDrawables(module: XposedModule, cl: ClassLoader) {
        val two = arrayOf<Class<*>>(Drawable::class.java, Drawable::class.java)
        val four = arrayOf<Class<*>>(
            Drawable::class.java, Drawable::class.java,
            Drawable::class.java, Drawable::class.java
        )
        for (clsName in listOf(
            "com.tencent.qqnt.classadapter.SingleLineTextView",
            "com.tencent.widget.SingleLineTextView"
        )) {
            val cls = runCatching { Class.forName(clsName, false, cl) }.getOrNull() ?: continue
            for (params in listOf(two, four)) {
                findMethod(cls, setOf("setCompoundDrawables"), *params)
                    ?.let { method ->
                        logOnce("hook installed: $clsName.setCompoundDrawables(${params.size})")
                        runCatching { module.deoptimize(method) }
                        module.hook(method).intercept { chain ->
                            val result = chain.proceed()
                            try {
                                val d = chain.getArg(0) as? Drawable
                                if (d != null && isWarmMonoGlyph(d)) {
                                    tintDraftPencil(d)
                                    if (sltvIconLogCount++ < 5) {
                                        Log.i(
                                            TAG,
                                            "SLTV compound icon tinted (" +
                                                d.javaClass.simpleName + ")"
                                        )
                                    }
                                }
                            } catch (t: Throwable) {
                                // ignore
                            }
                            result
                        }
                    }
            }
        }
    }

    /** 草稿铅笔图标（c0q，橙色单色位图）→ primary（与自己发送的气泡同色）。 */
    private fun tintDraftPencil(drawable: Drawable): Drawable? {
        return try {
            val scheme = MonetPalette.palette(ThemeState.isNight(null, timClassLoader))
            val color = scheme.primary
            if (trySetPaintFilter(drawable, color)) {
                logOnce("draft pencil tinted -> #${Integer.toHexString(color)}")
                drawable
            } else {
                drawable.mutate()
                drawable.setColorFilter(color, PorterDuff.Mode.SRC_IN)
                drawable.setTint(color)
                logOnce("draft pencil tinted -> #${Integer.toHexString(color)}")
                drawable
            }
        } catch (t: Throwable) {
            null
        }
    }

    private var fileCircleLogCount = 0

    private val warmGlyphMemo = HashMap<Int, Boolean>()

    private var backIconLog = 0

    private var bitmapBgLog = 0

    private fun tintDrawable(name: String?, drawable: Drawable): Drawable {
        if (name == null) return drawable
        // 图标白底:登记后保持原样(通用兜底会按"白色→深色"把它改掉)
        if (isIconPlateName(name)) {
            iconPlateMemo.add(drawable)
            return drawable
        }
        runCatching {
            drawableNameMemo[drawable] = name
            // 皮肤引擎会把同一个资源复制成多个 drawable 实例交给不同 View，
            // 按实例查名字会 miss；constantState 在复制品之间是共享的，一并登记。
            drawable.constantState?.let {
                drawableStateNameMemo[System.identityHashCode(it)] = name
            }
        }
        // 第三方模块注入界面同上:保持其自身配色
        if (isThirdPartyUiActive()) return drawable
        // 相册/预览页选择控件(QUICheckBox)最外那圈白描边:见 checkBoxRingOverride
        checkBoxRingOverride(name, drawable)?.let { return it }
        // 文件气泡圆形操作按钮（下载 lbb / 暂停 lbd / 发送取消 lbc）：
        // 矢量“白圆+深色图形”，SRC_IN 单色会毁掉双色，必须栅格化双簇重染
        if (name == "lbb" || name == "lbd" || name == "lbc") {
            return tintFileCircleIcon(name, drawable) ?: drawable
        }
        // 通用“竖/横长版双族大图”（关于页品牌图）：
        // 资源短名随构建漂移（本地 jdl/jdm、设备 j0m…），按特征识别最稳：
        // 短名 + 长:短≥1.3 的大图 + 图像主体为蓝族+黑/白族 → 双色重染
        if (name.length <= 5) {
            val iw0 = drawable.intrinsicWidth
            val ih0 = drawable.intrinsicHeight
            if (iw0 in 120..900 && ih0 in 120..900) {
                val sh = minOf(iw0, ih0)
                val lo = maxOf(iw0, ih0)
                if (lo >= sh * 13 / 10 && isBrandLikeImage(drawable)) {
                    val out = tintBrandLogoV2(drawable)
                    logOnce("brand feature tint $name ${iw0}x${ih0} out=${out != null}")
                    if (out != null) return out
                }
            }
        }
        // QUI 顶栏分段 tab（文件发送页的 最近/本机/收藏/微云 等）：
        // 选中底的 drawable 用 @color/qui_common_text_nav_secondary —— 那是
        // "导航次要**文字**色"，被我们映射成 onSurface(#CCE9FF)，做成底就亮得
        // 刺眼；未选中的 1dp 描边也是同一个色。这里按 drawable 名重建两态：
        // 选中=低对比容器色，未选中=页面底+同色暗描边。
        if (name.contains("qui_header_tab_")) {
            val tabDark = ThemeState.isNight(null, timClassLoader)
            val sel = name.contains("selected")
            val line = TokenMapper.outlineVariant(tabDark)
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.RECTANGLE
            gd.setColor(if (sel) line else TokenMapper.bgPage(tabDark))
            // 原版只有"未选中"那态带描边（selected 只有 <solid>）。
            // 宽度用 1px：TIM 自己做了密度适配，用系统 density 去算 1dp 会偏粗。
            if (!sel) gd.setStroke(1, line)
            headerTabDrawables.add(gd)
            if (tabIconLog++ < 4) {
                Log.i(TAG, "header tab rebuilt: $name sel=$sel")
            }
            return gd
        }
        // 聊天列表草稿预览左侧的橙色铅笔图标（drawable c0q，唯一用途）：
        // 单色图形，整体 SRC_IN 成次级文字色
        if (name == "c0q") {
            return tintDraftPencil(drawable) ?: drawable
        }
        // “我的”页功能宫格入口图标（FunctionItemInfo：文件/收藏/待办/电话/日程/
        // 钱包/邮箱/小程序/好友动态/频道/游戏中心/经典农场）：
        // 统一整体 SRC_IN 成 primary，与自己发送的气泡底色一致。
        // （渲染点 tim/mine/b.java：ContextCompat.getDrawable(ctx, item.getIcon())）
        if (name in MINE_GRID_ICONS) {
            return tintIconPrimary(drawable)
        }
        // “关于 TIM 与帮助”页品牌大图：布局 about 的 ady XML 默认 src=jdl
        // （代码里的 jdm 是深浅变体），inflate 直接加载，在这层按运行时像素重染
        if (name == "jdl" || name == "jdm" || name == "kzy") {
            return tintBrandLogoV2(drawable) ?: drawable
        }
        if (!name.startsWith("qui_") &&
            !name.contains("skin_") &&
            !name.contains("list_item") &&
            !name.contains("item_sticky") &&
            !name.contains("unread_bg") &&
            name != "6u" &&
            name != "6v" &&
            name != "aea" &&
            name != "aeb" &&
            name != "aeq" &&
            name != "a9w" &&
            !name.contains("qq_profilecard_info_bg") &&
            !name.contains("qq_profilecard_foot_bg")
        ) return drawable
        val color = TokenMapper.tintColorFor(name, ThemeState.isNight(null, timClassLoader))
        if (color == null) {
            if (name.contains("bg_") || name.contains("card") || name.contains("tab_") ||
                name.contains("check") || name.contains("background")
            ) {
                logOnce("drawable no-rule: $name")
            }
            // 兜底:名字规则没覆盖的小图标(TIM 旧式/插件图标),若本体是
            // "单色暗图形",在深色配色下同样要变亮(实测"收藏/文件/收钱"
            // 等入口图标在深色面板上仍是原生黑色)。按资源名缓存判定,
            // 命中后每次都染(实例可能被 TIM 重新加载)。
            // 图标类资源(含 icon / chat_tool 命名):其最终颜色常由 XML
            // android:tint(浅色主题=黑)决定,本体采样是白色,任何"按明暗
            // 判定"的兜底都会放过它 —— 深色配色下直接覆盖 tint。
            if (isIconLikeName(name)) {
                return tintIconOnSurface(drawable, name)
            }
            // 名字规则一个都没命中：TIM 钱包页(qwallet 插件)用的就是 al3/2p 这类
            // 混淆短名 —— 页面根布局背景 al3(#FFFFFF)、顶栏 2p(#0099FF)、
            // QWalletHomeAppsLayout 的白色渐变底，全都落在这里被原样返回，
            // 所以钱包页一直是大片白底 + 品牌蓝顶栏。
            // 按 drawable 的**实际颜色**兜底(规则同 TokenMapper.inlineBgColor：
            // 精确品牌蓝 -> primary、白/浅灰 -> 面色)；只处理纯色/渐变底，
            // 位图不动(可能是照片/表情，染色会毁内容)。
            if (drawable is ColorDrawable || drawable is GradientDrawable) {
                if (tintAnyDrawable(drawable, ThemeState.isNight(null, timClassLoader))) {
                    logOnce("drawable color fallback: $name")
                }
            }
            return drawable
        }
        logOnce("tint drawable $name -> #${Integer.toHexString(color)}")
        return try {
            if (drawable is android.graphics.drawable.ColorDrawable) {
                drawable.color = color
                return drawable
            }
            // 旧版顶栏未读气泡底是半透明黑 shape，SRC_IN 会保留 18% 透明度，
            // 直接改成不透明 primary，和聊天列表未读气泡视觉一致。
            if (name.contains("aio_title_left_unread") && drawable is GradientDrawable) {
                (drawable.mutate() as GradientDrawable).setColor(color)
                return drawable
            }
            val mutated = drawable.mutate()
            if (trySetPaintFilter(mutated, color)) {
                mutated
            } else {
                mutated.apply {
                    setColorFilter(color, PorterDuff.Mode.SRC_IN)
                    setTint(color)
                }
            }
        } catch (t: Throwable) {
            drawable
        }
    }

    /** 面板图标白色圆角底(TIM R.drawable.ius)等"图标底盘"的实例:
     *  这类白底是浅色主题下黑色图标的可读性载体,必须保持白色,
     *  不能被"按颜色把白染成深色"的通用兜底改掉,否则黑图标压深底不可见。 */
    private val iconPlateMemo: MutableSet<Drawable> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<Drawable, Boolean>())

    /** 图标底盘资源名(短名随构建可能漂移,已知 ius/iut)。 */
    private fun isIconPlateName(name: String): Boolean = name == "ius" || name == "iut"

    /** 已栅格化重染的面板图标(避免 setImageDrawable 递归与重复处理)。 */
    private val rasterizedPanelIcons: MutableSet<Drawable> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<Drawable, Boolean>())


    private var uniformIconLog = 0

    /** 面板图标统一重染:把所有非透明像素画成 onSurface(保留 alpha),
     *  不区分本体颜色/明暗 —— 用户诉求是"面板入口图标颜色统一"。
     *  不依赖 view 是否已布局(直接用 intrinsic 尺寸),因此首次打开即生效。 */
    private fun rasterizeIconUniform(drawable: Drawable): Drawable? {
        return try {
            if (isThirdPartyUiActive()) return null
            var iw = drawable.intrinsicWidth
            var ih = drawable.intrinsicHeight
            if (iw <= 0 || ih <= 0) {
                val b = drawable.bounds
                iw = b.width()
                ih = b.height()
            }
            if (iw <= 0 || ih <= 0 || iw > 240 || ih > 240) return null
            // 浅色配色下不做任何处理(省掉位图与像素开销)
            val scheme = MonetPalette.palette(ThemeState.isNight(null, timClassLoader))
            if (!scheme.isDark) return null
            val bmp = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val saved = Rect(drawable.bounds)
            drawable.setBounds(0, 0, iw, ih)
            drawable.draw(canvas)
            drawable.bounds = saved
            val px = IntArray(iw * ih)
            bmp.getPixels(px, 0, iw, 0, 0, iw, ih)
            val out = IntArray(px.size)
            var core = 0
            var colorful = 0
            for (c in px) {
                val a = (c ushr 24) and 0xFF
                if (a < 8) continue
                core++
                val r0 = (c ushr 16) and 0xFF
                val g0 = (c ushr 8) and 0xFF
                val b0 = c and 0xFF
                if (maxOf(r0, g0, b0) - minOf(r0, g0, b0) > 60) colorful++
            }
            if (core < 8) return null
            // 彩色图标(红包/收款码等)保持原样
            if (colorful * 100 > core * 40) return null
            // 映射到"亮色系"但保留原有明暗层次:
            //   暗像素 -> onSurfaceVariant(稍暗的亮色)
            //   亮像素 -> onSurface(更亮)
            // 这样线条型(透明底+单色图形)与填充型(圆角底+图形)都能清晰
            // 可见且形状不丢:
            //   · 整体染成同一颜色 -> 填充型会变成纯色块
            //   · 把背景转透明 -> drawable 失效,TIM 回退成兜底图
            //     (qb_opensdk_app),两种都不行。
            val darkRgb = scheme.onSurfaceVariant and 0x00FFFFFF
            val lightRgb = scheme.onSurface and 0x00FFFFFF
            val dr = (darkRgb shr 16) and 0xFF
            val dg = (darkRgb shr 8) and 0xFF
            val db = darkRgb and 0xFF
            val lr = (lightRgb shr 16) and 0xFF
            val lg = (lightRgb shr 8) and 0xFF
            val lb = lightRgb and 0xFF
            // "整幅都暗"的线条图标（面板里 TIM 不给上色的"收钱/文件"这类纯黑图标）：
            // 走 lerp 会把 t≈0 的像素映到暗端 onSurfaceVariant，成品比 TIM 正常
            // 渲染的面板图标暗一截（实测 #87B0CC vs #C4E2F9，肉眼能看出色差）。
            // 这类图标整体提亮到亮端(onSurface)，与正常图标一致；填充型图标
            // （亮底板+暗图形）暗像素占比远达不到 90%，仍走 lerp 保留层次。
            var darkPx = 0
            for (c in px) {
                val a = (c ushr 24) and 0xFF
                if (a < 8) continue
                val r0 = (c ushr 16) and 0xFF
                val g0 = (c ushr 8) and 0xFF
                val b0 = c and 0xFF
                if ((r0 * 299 + g0 * 587 + b0 * 114) / 1000 < 90) darkPx++
            }
            val flatDark = darkPx * 10 >= core * 9
            for (i in px.indices) {
                val a = (px[i] ushr 24) and 0xFF
                if (a < 8) continue
                if (flatDark) {
                    out[i] = (a shl 24) or lightRgb
                    continue
                }
                val r0 = (px[i] ushr 16) and 0xFF
                val g0 = (px[i] ushr 8) and 0xFF
                val b0 = px[i] and 0xFF
                val t = (r0 * 299 + g0 * 587 + b0 * 114) / 1000
                val nr = dr + (lr - dr) * t / 255
                val ng = dg + (lg - dg) * t / 255
                val nb = db + (lb - db) * t / 255
                out[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
            bmp.setPixels(out, 0, iw, 0, 0, iw, ih)
            val nd = BitmapDrawable(Resources.getSystem(), bmp)
            nd.setBounds(saved)
            if (uniformIconLog++ < 20) {
                Log.i(
                    TAG,
                    "panel icon uniform ${iw}x${ih} core=$core colorful=" +
                        (colorful * 100 / core) + "% -> lerp"
                )
            }
            nd
        } catch (t: Throwable) {
            null
        }
    }

    /** 资源名 → 是否"单色暗图标"(0 未知/1 命中/2 不命中)。 */
    private var iconNameLog = 0


    /** 名字是否"图标类"(排除图片/头像/表情类与背景/形状/装饰类)。
     *  图标资源命名不统一:qui_tui_icon_image_primary 这类含 icon,
     *  但面板入口用的是 qui_tui_image_aio_seleter(selector,名字不含 icon),
     *  因此 qui_/chat_tool 前缀的无规则资源一律按图标处理。 */
    private fun isIconLikeName(name: String): Boolean {
        val l = name.lowercase()
        if (l.contains("emoji") || l.contains("face") || l.contains("sticker") ||
            l.contains("avatar") || l.contains("head") || l.contains("photo") ||
            l.contains("thumb") || l.contains("cover") || l.contains("banner") ||
            l.contains("qzone") || l.contains("video") || l.contains("gif") ||
            l.contains("_pic") || l.startsWith("img") || l.contains("screenshot")
        ) return false
        if (l.contains("bg") || l.contains("background") || l.contains("shape") ||
            l.contains("divider") || l.contains("line") || l.contains("mask") ||
            l.contains("shadow") || l.contains("corner") || l.contains("progress") ||
            l.contains("seek") || l.contains("border") || l.contains("stroke") ||
            l.contains("plate") || l.contains("button")
        ) return false
        // 控件部件(开关轨道/滑块、单选/复选等)与控件底/描边不是图标:它们的
        // 颜色由资源名规则或各控件自己的 hook 决定。若在这里被当图标整体染成
        // onSurface(SRC_IN),深色下会变成浅青 #CCE9FF —— 实测问题:开关轨道、
        // 相册选中图片的圆形序号底(qui_common_check_box_with_text_white_border)、
        // "原图"勾选底、按钮底(qui_tui_common_button,它的 solid 本来就按
        // qui_tui_button_bg_primary_default 映射成 primary)。注意 TIM 的复选框
        // 命名有 checkbox / check_box / check_ 多种写法,用 "check" 统一覆盖。
        if (l.contains("switch") || l.contains("track") || l.contains("thumb") ||
            l.contains("seekbar") || l.contains("radio") || l.contains("check") ||
            l.contains("toggle")
        ) return false
        return l.contains("icon") || l.startsWith("chat_tool") ||
            l.contains("_ic") || l.startsWith("qui_") ||
            l.contains("seleter") || l.contains("selector")
    }

    /** 选择控件(QUICheckBox)最外那圈白描边的莫奈化。
     *
     *  TIM 的 qui_common_check_box*white_border 选择器里，选中态是 vector
     *  (fill=@color/qui_button_bg_primary_default、stroke=@color/qui_common_icon_white)，
     *  未选中态是 fill=#4d000000 的同款白描边圆。填充已经按资源名映射成
     *  primary，但外圈那道白边在深浅两态下都保持纯白，和莫奈配色割裂。
     *
     *  这里不去改 vector 内部(反射改 mStrokeColor 太脆)，而是在原 drawable
     *  之上叠一圈描边把白圈盖掉：线宽按原 vector 的比例(viewport 48 /
     *  strokeWidth 2 = 控件宽的 1/24)随控件尺寸缩放，半径与原白圈完全重合。
     *  于是选中态描边 = primary(与填充同色，视觉统一)，未选中态描边 =
     *  outline(中性莫奈色，不抢眼)。
     */
    private fun checkBoxRingOverride(name: String, drawable: Drawable): Drawable? {
        if (!name.startsWith("qui_common_check_box")) return null
        if (!name.contains("white_border")) return null
        // 只处理"选择器"本身:checked/unchecked 子 vector 不叠加(它们由选择器统一覆盖)
        if (name.contains("checked")) return null
        return try {
            val scheme = MonetPalette.palette(ThemeState.isNight(null, timClassLoader))
            logOnce(
                "check box ring override $name -> checked #" +
                    Integer.toHexString(scheme.primary) + " / unchecked #" +
                    Integer.toHexString(scheme.outline)
            )
            // 原选择器只有 state_enabled=true 两个分支(禁用态什么都不画),
            // 叠加环也必须跟着禁用,否则禁用时会多出一圈圆环
            val ring = android.graphics.drawable.StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked),
                    RingStrokeDrawable(scheme.primary)
                )
                addState(
                    intArrayOf(android.R.attr.state_enabled),
                    RingStrokeDrawable(scheme.outline)
                )
                addState(intArrayOf(), ColorDrawable(0))
            }
            LayerDrawable(arrayOf(drawable, ring))
        } catch (t: Throwable) {
            null
        }
    }

    /** 只描一圈椭圆边、线宽 = bounds 宽度的 1/24(对应 TIM 原 vector:
     *  viewport 48 上 strokeWidth 2，即 24dp 控件上的 1dp)。 */
    private class RingStrokeDrawable(private val ringColor: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = ringColor
        }
        private val oval = android.graphics.RectF()

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val w = b.width() / 24f
            if (w <= 0f) return
            paint.strokeWidth = w
            val inset = w / 2f
            oval.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
            canvas.drawOval(oval, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** 兜底染色:图标整体染 onSurface(单色图形 SRC_IN 安全)。
     *  仅深色配色下生效,并同时覆盖 tint(压掉 XML android:tint)。 */
    private fun tintIconOnSurface(drawable: Drawable, name: String): Drawable {
        return try {
            val scheme = MonetPalette.palette(ThemeState.isNight(null, timClassLoader))
            if (!scheme.isDark) return drawable
            val c = scheme.onSurface
            if (iconNameLog++ < 200) {
                Log.i(
                    TAG,
                    "icon res tinted name=$name d=" + drawable.javaClass.simpleName +
                        " -> #" + Integer.toHexString(c)
                )
            }
            val mutated = drawable.mutate()
            if (trySetPaintFilter(mutated, c)) {
                mutated
            } else {
                mutated.apply {
                    setColorFilter(c, PorterDuff.Mode.SRC_IN)
                    setTint(c)
                }
            }
        } catch (t: Throwable) {
            drawable
        }
    }

    /** 内联颜色 drawable（resId=0）：按颜色本身映射到莫奈底色。 */
    private fun tintInlineDrawable(drawable: Drawable, typedValue: TypedValue?): Drawable {
        if (isThirdPartyUiActive()) return drawable
        val inlineColor = typedValue?.data ?: return drawable
        val mapped = TokenMapper.inlineBgColor(inlineColor, ThemeState.isNight(null, timClassLoader))
            ?: return drawable
        return try {
            if (drawable is android.graphics.drawable.ColorDrawable) {
                drawable.color = mapped
                drawable
            } else {
                val mutated = drawable.mutate()
                if (trySetPaintFilter(mutated, mapped)) {
                    mutated
                } else {
                    mutated.apply {
                        setColorFilter(mapped, PorterDuff.Mode.SRC_IN)
                        setTint(mapped)
                    }
                }
            }
        } catch (t: Throwable) {
            drawable
        }
    }

    /**
     * TIM 的 SkinnableBitmapDrawable.setColorFilter 会被 SkinBitmapFilter 拦截
     * （qui_common_bg_* 尤其如此），所以直接改它内部 BitmapState 的 Paint。
     */
    private fun trySetPaintFilter(drawable: Drawable, color: Int): Boolean {
        if (drawable.javaClass.name != "com.tencent.theme.SkinnableBitmapDrawable") return false
        return try {
            val stateField = cachedField(drawable.javaClass, "mBitmapState") ?: return false
            stateField.isAccessible = true
            val state = stateField.get(drawable) ?: return false
            val paintField = cachedField(state.javaClass, "mPaint") ?: return false
            paintField.isAccessible = true
            val paint = paintField.get(state) as? Paint ?: return false
            paint.setColorFilter(PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN))
            drawable.invalidateSelf()
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 沿继承链找字段：SkinnableColorStateList 等子类会 shadow 父类的 mColors。 */
    private fun findFieldDeep(cls: Class<*>?, name: String): Field? {
        var current: Class<*>? = cls
        while (current != null) {
            try {
                return current.getDeclaredField(name)
            } catch (t: Throwable) {
                // 继续向父类找
            }
            current = current.superclass
        }
        return null
    }

    /** 按类缓存字段查找结果，避免热路径上反复 getDeclaredField。 */
    private fun cachedField(cls: Class<*>?, name: String): Field? {
        if (cls == null) return null
        val key = "${cls.name}#$name"
        if (fieldCache.containsKey(key)) return fieldCache[key]
        val field = findFieldDeep(cls, name)
        fieldCache[key] = field
        if (fieldCache.size > 4096) fieldCache.clear()
        return field
    }

    private fun cachedFieldByType(cls: Class<*>?, type: Class<*>): Field? {
        if (cls == null) return null
        val key = "${cls.name}#@${type.name}"
        if (typeFieldCache.containsKey(key)) return typeFieldCache[key]
        val field = findFieldByTypeDeep(cls, type)
        typeFieldCache[key] = field
        if (typeFieldCache.size > 4096) typeFieldCache.clear()
        return field
    }

    private fun cachedMethod(cls: Class<*>?, name: String, vararg params: Class<*>): Method? {
        if (cls == null) return null
        val key = cls.name + "#" + name + "(" + params.joinToString(",") { it.name } + ")"
        if (methodCache.containsKey(key)) return methodCache[key]
        val method = runCatching { cls.getMethod(name, *params) }.getOrNull()
        if (method == null) return null
        methodCache[key] = method
        if (methodCache.size > 2048) methodCache.clear()
        return method
    }

    /** 沿继承链查找可访问的任意可见性方法（protected/private 也支持），并缓存。 */
    private fun cachedDeclaredMethod(cls: Class<*>?, name: String): Method? {
        if (cls == null) return null
        val key = cls.name + "#<decl>" + name
        if (methodCache.containsKey(key)) return methodCache[key]
        var current: Class<*>? = cls
        var method: Method? = null
        while (current != null && method == null) {
            method = runCatching {
                current.getDeclaredMethod(name).also { it.isAccessible = true }
            }.getOrNull()
            current = current.superclass
        }
        if (method == null) return null
        methodCache[key] = method
        if (methodCache.size > 2048) methodCache.clear()
        return method
    }

    // ------------------------------------------------------------------
    // Resources 直读路径（主界面顶栏/聊天列表/底栏、AIO 气泡等走这里）
    // ------------------------------------------------------------------

    private fun hookResources(module: XposedModule) {
        val resourcesCls = Resources::class.java
        val themeCls = Resources.Theme::class.java
        val typedArrayCls = TypedArray::class.java

        // int getColor(int)
        findMethod(resourcesCls, setOf("getColor"), INT_TYPE)
            ?.let { hookFrameworkMethod(module, it) { chain, result ->
                if (result is Int) {
                    val resId = chain.getArg(0) as Int
                    if (resId shr 24 == 0x01) return@hookFrameworkMethod result
                    val name = entryName(chain.thisObject as? Resources, resId)
                    remapColorByName(name, result)
                } else {
                    result
                }
            } }

        // int getColor(int, Theme)
        findMethod(resourcesCls, setOf("getColor"), INT_TYPE, themeCls)
            ?.let { hookFrameworkMethod(module, it) { chain, result ->
                if (result is Int) {
                    val resId = chain.getArg(0) as Int
                    if (resId shr 24 == 0x01) return@hookFrameworkMethod result
                    val name = entryName(chain.thisObject as? Resources, resId)
                    remapColorByName(name, result)
                } else {
                    result
                }
            } }

        // ColorStateList getColorStateList(int)
        findMethod(resourcesCls, setOf("getColorStateList"), INT_TYPE)
            ?.let { hookFrameworkMethod(module, it) { chain, result ->
                val csl = result as? ColorStateList
                if (csl != null) {
                    val resId = chain.getArg(0) as Int
                    if (resId shr 24 == 0x01) return@hookFrameworkMethod csl
                    val name = entryName(chain.thisObject as? Resources, resId)
                    remapColorStateListByName(resId, name, csl) ?: csl
                } else {
                    result
                }
            } }

        // ColorStateList getColorStateList(int, Theme)
        findMethod(resourcesCls, setOf("getColorStateList"), INT_TYPE, themeCls)
            ?.let { hookFrameworkMethod(module, it) { chain, result ->
                val csl = result as? ColorStateList
                if (csl != null) {
                    val resId = chain.getArg(0) as Int
                    if (resId shr 24 == 0x01) return@hookFrameworkMethod csl
                    val name = entryName(chain.thisObject as? Resources, resId)
                    remapColorStateListByName(resId, name, csl) ?: csl
                } else {
                    result
                }
            } }

        // 包内私有：ColorStateList loadColorStateList(TypedValue, int, Theme)
        // XML 属性、drawable 内部颜色都经过这里
        findMethod(
            resourcesCls,
            setOf("loadColorStateList"),
            TypedValue::class.java,
            INT_TYPE,
            themeCls
        )?.let { hookFrameworkMethod(module, it) { chain, result ->
            val csl = result as? ColorStateList
            if (csl != null) {
                val resId = chain.getArg(1) as Int
                if (resId shr 24 == 0x01) return@hookFrameworkMethod csl
                val name = entryName(chain.thisObject as? Resources, resId)
                remapColorStateListByName(resId, name, csl) ?: csl
            } else {
                result
            }
        } }

        // int TypedArray.getColor(int, int)：XML 里的内联十六进制颜色不经过 Resources，
        // 只能按颜色本身推断（纯黑白保持不动）
        // Drawable getDrawable(int)：android:background="?attr/xxx" 走的是这里 ——
        // 钱包页(TenpayActivity / QWalletToolFragmentActivity)的根布局白底、
        // 顶栏品牌蓝底、图标网格白底全都是这么拿到的，此前完全没经过任何染色
        // 路径，所以那页一直是大片白 + TIM 蓝。取出来按值兜底(只动纯色/渐变底)。
        for (params in listOf(arrayOf<Class<*>>(INT_TYPE), arrayOf<Class<*>>(INT_TYPE, themeCls))) {
            findMethod(typedArrayCls, setOf("getDrawable"), *params)
                ?.let { method ->
                    logOnce("hook installed: TypedArray.getDrawable (attr bg)")
                    hookFrameworkMethod(module, method) { chain, result ->
                        if (isThirdPartyUiActive()) return@hookFrameworkMethod result
                        val d = result as? Drawable ?: return@hookFrameworkMethod result
                        if (d is ColorDrawable || d is GradientDrawable) {
                            if (tintAnyDrawable(d, ThemeState.isNight(null, timClassLoader))) {
                                logOnce("attr drawable tinted " + d.javaClass.simpleName)
                            }
                        }
                        result
                    }
                }
        }

        findMethod(typedArrayCls, setOf("getColor"), INT_TYPE, INT_TYPE)
            ?.let { hookFrameworkMethod(module, it) { chain, result ->
                // 第三方模块注入界面(QAuxiliary 等,其 Material 组件/开关的颜色
                // 正是从这里读):整体跳过,保持它们自身配色
                if (isThirdPartyUiActive()) return@hookFrameworkMethod result
                if (result is Int) {
                    val index = chain.getArg(0) as Int
                    val type = (chain.thisObject as? TypedArray)?.getType(index) ?: -1
                    if (whiteSrcLogCount < 40) {
                        logWhiteSource("TypedArray.getColor type=$type", result)
                    }
                    // 资源引用（@color/xxx）：TIM 钱包主页的背景/顶栏就走这条 ——
                    // 布局写的 ?attr/a_2 解析到 @color/al3(#FFFFFF)、?attr/a_5 解析到
                    // @color/2p(#0099FF)，以前这里直接原样返回，于是钱包页一直是
                    // 大片白底 + 品牌蓝顶栏。按值兜底(只认精确品牌蓝与无彩色浅色)。
                    if (type == TypedValue.TYPE_STRING) {
                        val dark = ThemeState.isNight(null, timClassLoader)
                        val mapped = TokenMapper.inlineBgColor(result, dark)
                            ?: darkTextFallback(result, dark)
                            ?: result
                        if (mapped != result && attrColorLogCount++ < 30) {
                            Log.i(
                                TAG,
                                "attr color #" + Integer.toHexString(result) +
                                    " -> #" + Integer.toHexString(mapped)
                            )
                        }
                        mapped
                    } else {
                        val dark = ThemeState.isNight(null, timClassLoader)
                        val opaque = result or 0xFF000000.toInt()
                        val scheme = MonetPalette.palette(dark)
                        when {
                            // TIM 品牌蓝(精确常量,非色相猜测) -> primary:
                            // 相册选中序号、"原图"勾选等品牌底要与自己发送的气泡同色,
                            // 所以这里必须与主题无关地生效(浅色下同样是品牌底色)
                            opaque in TokenMapper.BRAND_BLUES -> {
                                logOnce(
                                    "brand blue inline #${Integer.toHexString(result)}" +
                                        " -> primary"
                                )
                                scheme.primary
                            }
                            // 内联纯白：TIM 里基本都是"底"(钱包页根布局、卡片、
                            // 各种面板背景)，按**面**色映射。以前这里和纯黑一起
                            // 归一成 onSurface(文字色)，结果白底依旧是白的 ——
                            // 钱包页那片纯白就是这么来的。
                            opaque == 0xFFFFFFFF.toInt() -> {
                                val mapped = TokenMapper.inlineBgColor(result, dark)
                                if (mapped != null && mapped != result) {
                                    logOnce(
                                        "inline white -> #" + Integer.toHexString(mapped)
                                    )
                                }
                                mapped ?: result
                            }
                            // 纯黑文字:深色模式下归一为 onSurface
                            dark && opaque == 0xFF000000.toInt() -> scheme.onSurface
                            // 其余:仅深色模式下的灰阶(白/浅灰底)做面色映射,
                            // 彩色一律保持原样(此前按颜色值盲目映射会改成更浅的面色)
                            dark -> TokenMapper.inlineBgColor(result, true) ?: result
                            else -> result
                        }
                    }
                } else {
                    result
                }
            } }
    }

    /** 框架方法 hook 统一入口：需要 deoptimize，并统一异常兜底。 */
    private fun hookFrameworkMethod(
        module: XposedModule,
        method: Method,
        body: (XposedInterface.Chain, Any?) -> Any?
    ) {
        logOnce("hook installed: ${method.declaringClass.name}.${method.name}")
        runCatching { module.deoptimize(method) }
        module.hook(method).intercept { chain ->
            val result = chain.proceed()
            try {
                body(chain, result)
            } catch (t: Throwable) {
                Log.e(TAG, "framework hook ${method.name} failed", t)
                result
            }
        }
    }

    private var attrColorLogCount = 0

    private var whiteSrcLogCount = 0

    /** 诊断：钱包页那片纯白背景到底是谁设的（限次）。 */
    private fun logWhiteSource(source: String, color: Int) {
        if (color == 0 || whiteSrcLogCount >= 40) return
        if ((color or 0xFF000000.toInt()) != 0xFFFFFFFF.toInt()) return
        whiteSrcLogCount++
        Log.i(TAG, "white source: $source")
    }

    private fun viewChainName(view: View?): String {
        if (view == null) return "?"
        val sb = StringBuilder(view.javaClass.simpleName)
        var p = view.parent
        var depth = 0
        while (p is View && depth < 5) {
            sb.append(" <- ").append(p.javaClass.simpleName)
            p = p.parent
            depth++
        }
        return sb.toString()
    }

    private fun colorOfDrawable(d: Drawable?): Int = when (d) {
        null -> 0
        is ColorDrawable -> colorOfColorDrawable(d)
        is GradientDrawable -> runCatching { d.color?.defaultColor }.getOrNull() ?: 0
        else -> 0
    }

    /** 深色配色下的纯黑/近黑 -> onSurface（TIM 浅色主题遗留的文字色），否则 null。 */
    private fun darkTextFallback(color: Int, dark: Boolean): Int? {
        val scheme = MonetPalette.palette(dark)
        if (!scheme.isDark) return null
        val op = color or 0xFF000000.toInt()
        val r = (op shr 16) and 0xFF
        val g = (op shr 8) and 0xFF
        val b = op and 0xFF
        if (maxOf(r, g, b) - minOf(r, g, b) > 24) return null
        return if (colorLuma(op) < 70) scheme.onSurface else null
    }

    private fun remapColorByName(name: String?, color: Int): Int {
        // 第三方模块注入界面(QAuxiliary 等):不改颜色
        if (isThirdPartyUiActive()) return color
        // 全透明色（皮肤包的占位色，运行时由皮肤引擎替换）不参与映射：
        // 否则会被下面的"纯黑 -> onSurface"兜底提亮成**不透明的亮色**
        // （手势密码九宫格的圆点外圈 ju/jr 就是全透明占位）。
        if ((color ushr 24) == 0) return color
        val dark = ThemeState.isNight(null, timClassLoader)
        // 资源名在 Android 里强制小写，直接 startsWith 免去每次 lowercase 分配。
        if (whiteSrcLogCount < 40) logWhiteSource("Resources.getColor name=$name", color)
        // 手势密码/图案锁页的九宫格：未选中的点用 qui_common_icon_primary 作填充，
        // 通用映射(icon_primary -> onSurface)会让 9 个大圆点全部变成最亮的
        // #CCE9FF（实测）—— 这一页它其实是"圆点底"而不是图标，用低对比的暗色。
        val page = activeUiClassName
        if (page != null && page.contains("GesturePWD") && name != null &&
            name.contains("icon_primary")
        ) {
            return TokenMapper.outlineVariant(dark)
        }
        val knownName = name != null &&
            (name.startsWith("qui_") ||
                name.startsWith("skin_black") ||
                name.startsWith("skin_gray") ||
                name.startsWith("skin_input_theme") ||
                // 沉浸式顶栏底（ImmersiveTitleBar -> skin_color_title_immersive_bar）：
                // 它是**背景**色，必须走名字规则，不能落到下面"纯黑 -> onSurface"的
                // 文字兜底（否则深色/AMOLED 下顶栏会变成亮的 #CCE9FF）
                name.startsWith("skin_color_title") ||
                name.startsWith("troop_aiosm"))
        if (!knownName) {
            // 名字不在白名单里。TIM 钱包页(qwallet 插件)就是这种：布局写的
            // android:background="?attr/a_2" 解析到 @color/al3(#FFFFFF)、顶栏
            // "?attr/a_5" 解析到 @color/2p(#0099FF)，这些混淆短名不匹配任何
            // 前缀，于是整页保持原版 —— 大片白底 + 品牌蓝顶栏。
            // 这里按**颜色值**兜底；规则很保守(只认精确品牌蓝常量与无彩色浅色)，
            // 见 TokenMapper.inlineBgColor。
            TokenMapper.inlineBgColor(color, dark)?.let { return it }
            // 深色配色下的纯黑/近黑：TIM 浅色主题留下的文字色，在莫奈深色面上
            // 几乎看不见(钱包设置页"金额隐私保护/安全锁/服务管理"就是)，
            // 按文字色归一为 onSurface。只认无彩色且很暗的，彩色一律不动。
            val scheme = MonetPalette.palette(dark)
            if (scheme.isDark) {
                val op = color or 0xFF000000.toInt()
                val r0 = (op shr 16) and 0xFF
                val g0 = (op shr 8) and 0xFF
                val b0 = op and 0xFF
                if (maxOf(r0, g0, b0) - minOf(r0, g0, b0) <= 24 && colorLuma(op) < 70) {
                    // 分隔线/边框/线类资源：深色皮肤给的就是"深色的线"，
                    // 归一成 onSurface 会变成一条亮线（表情面板底栏上方那条
                    // #CCE9FF 细线就是这么来的），改成深色下可见的线色。
                    if (blackFixLog++ < 20) {
                        Log.i(
                            TAG,
                            "black->? name=" + name + " #" + Integer.toHexString(op)
                        )
                    }
                    if (name != null && (name.contains("divider") ||
                            name.contains("separator") || name.contains("line") ||
                            name.contains("border"))
                    ) {
                        return TokenMapper.outlineVariant(dark)
                    }
                    return scheme.onSurface
                }
            }
            return color
        }
        val mapped = TokenMapper.mapColor(name, color, dark)
        if (name.startsWith("qui_") && name.contains("bg_") && mapped != color) {
            logOnce("color $name: #${Integer.toHexString(color)} -> #${Integer.toHexString(mapped)}")
        }
        return mapped
    }

    private fun remapColorStateListByName(resId: Int, name: String?, csl: ColorStateList): ColorStateList? {
        if (name == null) return null
        if (isThirdPartyUiActive()) return null
        // 顶栏分段 tab（文件发送页的 最近/本机/收藏/微云）的文字色 selector：
        // TIM 原版是"选中=品牌蓝 #00A5E0 / 未选中=白"，配的是"白底选中"；
        // 我们把选中底换成低对比暗色后，中蓝文字对比度太差。这里重建：
        // 选中/按下 = primary（亮主色），未选中 = onSurface。
        if (name.contains("title_segment_item")) {
            val scheme = MonetPalette.palette(ThemeState.isNight(null, timClassLoader))
            return ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(android.R.attr.state_selected),
                    intArrayOf(android.R.attr.state_pressed),
                    intArrayOf()
                ),
                intArrayOf(scheme.primary, scheme.primary, scheme.primary, scheme.onSurface)
            )
        }
        if (!name.startsWith("qui_") &&
            !name.startsWith("skin_black") &&
            !name.startsWith("skin_gray") &&
            !name.startsWith("skin_input_theme") &&
            !name.startsWith("troop_aiosm")
        ) return null
        return remapColorStateList(resId, csl, name, ThemeState.isNight(null, timClassLoader))
    }

    private fun entryName(resources: Resources?, resId: Int): String? {
        if (resources == null || resId == 0) return null
        val key = (System.identityHashCode(resources).toLong() shl 32) xor resId.toLong()
        if (entryNameCache.containsKey(key)) return entryNameCache[key]
        val name = try {
            resources.getResourceEntryName(resId)
        } catch (t: Throwable) {
            null
        }
        entryNameCache[key] = name
        if (entryNameCache.size > 8192) entryNameCache.clear()
        return name
    }

    private fun logOnce(message: String) {
        if (loggedOnce.add(message)) {
            Log.i(TAG, message)
            if (loggedOnce.size > 8192) loggedOnce.clear()
        }
    }

    /** token 取色记忆化：key=(resId<<1)|dark，palette 代次变化清空。 */
    private val tokenColorMemo = HashMap<Int, Int>()
    private var tokenColorGen = -1L

    private fun tokenColorMemoized(resId: Int, dark: Boolean, compute: () -> Int): Int {
        val gen = MonetPalette.generation()
        if (tokenColorGen != gen) {
            tokenColorGen = gen
            tokenColorMemo.clear()
        }
        val key = (resId shl 1) or (if (dark) 1 else 0)
        tokenColorMemo[key]?.let { return it }
        val v = compute()
        if (tokenColorMemo.size > 2048) tokenColorMemo.clear()
        tokenColorMemo[key] = v
        return v
    }

    // ------------------------------------------------------------------
    // QUI token 组件（NT 主界面、聊天页、设置页的主要取色入口）
    // ------------------------------------------------------------------

    private fun hookQuiTokenManager(module: XposedModule, cl: ClassLoader) {
        val cls = try {
            Class.forName(QUI_TOKEN_MANAGER, false, cl)
        } catch (t: Throwable) {
            Log.w(TAG, "QUITokenThemeManager not found", t)
            return
        }

        // int d(Context, int resId, int themeId)  -- getQuiColor
        findMethod(cls, setOf("d"), Context::class.java, INT_TYPE, INT_TYPE)
            ?.let { method ->
                logOnce("hook installed: $QUI_TOKEN_MANAGER.d")
                module.hook(method).intercept { chain ->
                                        // 第三方模块注入界面:整体跳过染色
                                        if (isThirdPartyUiActive()) return@intercept chain.proceed()
                    val original = chain.proceed()
                    if (original !is Int) return@intercept original
                    try {
                        val ctx = chain.getArg(0) as? Context
                        val resId = chain.getArg(1) as Int
                        val themeId = chain.getArg(2) as Int
                        MonetPalette.ensureInitialized(ctx)
                        // QQ NT 主取色入口(每帧大量调用):(resId,深色档) 记忆化,
                        // 免去每次 entryName 反查 + mapColor 计算;palette 代次
                        // 变化时自动失效。
                        tokenColorMemoized(resId, themeId == TOKEN_NIGHT) {
                            val name = entryName(ctx?.resources, resId)
                            TokenMapper.mapColor(name, original, themeId == TOKEN_NIGHT)
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "map getQuiColor failed", t)
                        original
                    }
                }
            }

        // ColorStateList e(Context, int resId, int themeId)  -- getQuiColorStateList
        findMethod(cls, setOf("e"), Context::class.java, INT_TYPE, INT_TYPE)
            ?.let { method ->
                logOnce("hook installed: $QUI_TOKEN_MANAGER.e")
                module.hook(method).intercept { chain ->
                                        // 第三方模块注入界面:整体跳过染色
                                        if (isThirdPartyUiActive()) return@intercept chain.proceed()
                    val result = chain.proceed()
                    val csl = result as? ColorStateList ?: return@intercept result
                    try {
                        val ctx = chain.getArg(0) as? Context
                        val resId = chain.getArg(1) as Int
                        val themeId = chain.getArg(2) as Int
                        MonetPalette.ensureInitialized(ctx)
                        val name = entryName(ctx?.resources, resId)
                        remapColorStateList(resId, csl, name, themeId == TOKEN_NIGHT) ?: csl
                    } catch (t: Throwable) {
                        Log.e(TAG, "map getQuiColorStateList failed", t)
                        csl
                    }
                }
            }
    }

    // ------------------------------------------------------------------
    // 经典 QQ 皮肤引擎（遗留页面）
    // ------------------------------------------------------------------

    private fun hookSkinEngine(module: XposedModule, cl: ClassLoader) {
        val cls = try {
            Class.forName(SKIN_ENGINE, false, cl)
        } catch (t: Throwable) {
            Log.w(TAG, "SkinEngine not found", t)
            return
        }

        // public int getColor(int resId)
        findMethod(cls, setOf("getColor"), INT_TYPE)
            ?.let { method ->
                logOnce("hook installed: $SKIN_ENGINE.getColor")
                module.hook(method).intercept { chain ->
                                        // 第三方模块注入界面:整体跳过染色
                                        if (isThirdPartyUiActive()) return@intercept chain.proceed()
                    val result = chain.proceed()
                    if (result !is Int) return@intercept result
                    try {
                        val resId = chain.getArg(0) as Int
                        val resources = resourcesOf(chain.thisObject)
                        val name = entryName(resources, resId)
                        TokenMapper.mapColor(name, result, ThemeState.isNight(null, cl))
                    } catch (t: Throwable) {
                        Log.e(TAG, "map SkinEngine.getColor failed", t)
                        result
                    }
                }
            }

        // SkinnableColorStateList loadColorStateList(int resId)
        findMethod(cls, setOf("loadColorStateList"), INT_TYPE)
            ?.let { method ->
                logOnce("hook installed: $SKIN_ENGINE.loadColorStateList")
                module.hook(method).intercept { chain ->
                                        // 第三方模块注入界面:整体跳过染色
                                        if (isThirdPartyUiActive()) return@intercept chain.proceed()
                    val result = chain.proceed()
                    val csl = result as? ColorStateList ?: return@intercept result
                    try {
                        val resId = chain.getArg(0) as Int
                        val resources = resourcesOf(chain.thisObject)
                        val name = entryName(resources, resId)
                        remapColorStateList(resId, csl, name, ThemeState.isNight(null, cl)) ?: csl
                    } catch (t: Throwable) {
                        Log.e(TAG, "map SkinEngine.loadColorStateList failed", t)
                        csl
                    }
                }
            }

        // Drawable getDefaultThemeDrawable(int resId)：皮肤位图（聊天列表条目、
        // 置顶条目等 skin_* 资源）都从这里加载，不经过 Resources.getDrawable
        findMethod(cls, setOf("getDefaultThemeDrawable"), INT_TYPE)
            ?.let { method ->
                logOnce("hook installed: $SKIN_ENGINE.getDefaultThemeDrawable")
                module.hook(method).intercept { chain ->
                                        // 第三方模块注入界面:整体跳过染色
                                        if (isThirdPartyUiActive()) return@intercept chain.proceed()
                    val result = chain.proceed()
                    val drawable = result as? Drawable
                    if (drawable != null) {
                        val resId = chain.getArg(0) as Int
                        val resources = resourcesOf(chain.thisObject)
                        val name = entryName(resources, resId)
                        return@intercept tintDrawable(name, drawable)
                    } else {
                        result
                    }
                }
            }

        // Drawable loadDrawable(int resId)：装皮肤包（简洁白/极致黑也是皮肤包）时
        // 的主加载入口，聊天列表条目背景走这里
        findMethod(cls, setOf("loadDrawable"), INT_TYPE)
            ?.let { method ->
                logOnce("hook installed: $SKIN_ENGINE.loadDrawable")
                module.hook(method).intercept { chain ->
                                        // 第三方模块注入界面:整体跳过染色
                                        if (isThirdPartyUiActive()) return@intercept chain.proceed()
                    val result = chain.proceed()
                    val drawable = result as? Drawable
                    if (drawable != null) {
                        val resId = chain.getArg(0) as Int
                        val resources = resourcesOf(chain.thisObject)
                        val name = entryName(resources, resId)
                        return@intercept tintDrawable(name, drawable)
                    } else {
                        result
                    }
                }
            }
    }

    // ------------------------------------------------------------------
    // QUIUtil token map（Hippy / JSI / WebView 页面取色）
    // ------------------------------------------------------------------

    private fun hookQuiUtil(module: XposedModule, cl: ClassLoader) {
        val cls = try {
            Class.forName(QUI_UTIL, false, cl)
        } catch (t: Throwable) {
            Log.w(TAG, "QUIUtil not found", t)
            return
        }

        findMethod(cls, setOf("getCurrentTokenMap"))
            ?.let { method ->
                logOnce("hook installed: $QUI_UTIL.getCurrentTokenMap")
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    val map = result as? Map<*, *> ?: return@intercept result
                    try {
                        val generation = MonetPalette.generation()
                        quiTokenCache?.let { cached ->
                            if (quiTokenCacheGen == generation) return@intercept cached
                        }
                        val dark = ThemeState.isNight(null, cl)
                        val out = HashMap<String, String>(map.size)
                        for ((key, value) in map) {
                            if (key !is String || value !is String) continue
                            out[key] = mapTokenString(key, value, dark)
                        }
                        // 采样常见标签/文字键（WebView 群公告列表等跨端页面消费此表）
                        if (quiTokenSampleLogCount++ < 8) {
                            val keys = listOf(
                                "brand_standard",
                                "fill_standard_brand",
                                "fill_light_brand",
                                "on_brand_primary",
                                "text_allwhite_primary",
                                "text_white",
                                "allwhite",
                                "text_primary",
                                "text_secondary",
                                "text_tertiary",
                                "fill_standard",
                                "bg_nav_primary"
                            )
                            val samples = keys.filter { out.containsKey(it) }
                                .joinToString(" ") { "$it=${out[it]}" }
                            Log.i(TAG, "QUIUtil token samples(${out.size}): $samples")
                        }
                        quiTokenCache = out
                        quiTokenCacheGen = generation
                        out
                    } catch (t: Throwable) {
                        Log.e(TAG, "map QUIUtil.getCurrentTokenMap failed", t)
                        map
                    }
                }
            }
    }

    // ------------------------------------------------------------------
    // SimpleTintManager / BusinessTintManager 的 token 色表：
    // Ark（合并消息“聊天记录”卡片等）、Hippy、脚本页面都从这里读色，
    // 之前只 hook 了 QUIUtil.getCurrentTokenMap()，Ark 直接读这个实例方法，
    // 导致“聊天记录”卡片仍是白色。这里把整张色表按 token 重映射。
    // ------------------------------------------------------------------

    private fun hookTintManagers(module: XposedModule, cl: ClassLoader) {
        for (clsName in listOf(SIMPLE_TINT, BUSINESS_TINT, QUI_TINT)) {
            runCatching {
                val cls = Class.forName(clsName, false, cl)
                findMethod(cls, setOf("getCurrentTintColorMap"))
                    ?.let { method ->
                        logOnce("hook installed: $clsName.getCurrentTintColorMap")
                        module.hook(method).intercept { chain ->
                            val result = chain.proceed()
                            val map = result as? Map<*, *> ?: return@intercept result
                            try {
                                val generation = MonetPalette.generation()
                                tintMapCache[clsName]?.let { cached ->
                                    if (cached.first == generation) return@intercept cached.second
                                }
                                val dark = ThemeState.isNight(null, cl)
                                val out = HashMap<String, String>(map.size)
                                for ((key, value) in map) {
                                    if (key !is String || value !is String) continue
                                    out[key] = mapTokenString(key, value, dark)
                                }
                                val sample = out["bg_nav_primary"]
                                logOnce(
                                    "$clsName tint map remapped: ${out.size} entries" +
                                        (sample?.let { ", bg_nav_primary=$it" } ?: "")
                                )
                                // 采样常见标签/文字相关键，便于定位 WebView（群公告列表等）
                                // 里标签底色与配套文字色的实际取值。
                                if (tintMapSampleLogCount++ < 8) {
                                    val keys = listOf(
                                        "brand_standard",
                                        "fill_standard_brand",
                                        "fill_light_brand",
                                        "on_brand_primary",
                                        "text_allwhite_primary",
                                        "text_white",
                                        "allwhite",
                                        "text_primary",
                                        "text_secondary",
                                        "fill_standard"
                                    )
                                    val samples = keys.filter { out.containsKey(it) }
                                        .joinToString(" ") { "$it=${out[it]}" }
                                    Log.i(TAG, "$clsName tint samples: $samples")
                                }
                                tintMapCache[clsName] = generation to out
                                out
                            } catch (t: Throwable) {
                                Log.e(TAG, "map $clsName tint map failed", t)
                                map
                            }
                        }
                    }
            }.onFailure { Log.w(TAG, "$clsName not found", it) }
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun resourcesOf(thisObject: Any?): Resources? {
        if (thisObject == null) return null
        return try {
            cachedField(thisObject.javaClass, "mResources")
                ?.let { it.isAccessible = true; it.get(thisObject) as? Resources }
        } catch (t: Throwable) {
            null
        }
    }

    private fun mapTokenString(key: String, value: String, dark: Boolean): String {
        val parts = value.split(",")
        val mapped = parts.map { part ->
            val trimmed = part.trim()
            val color = try {
                Color.parseColor(trimmed)
            } catch (t: Throwable) {
                return value
            }
            colorToHex(TokenMapper.mapColor(key, color, dark))
        }
        return mapped.joinToString(",")
    }

    private fun colorToHex(color: Int): String {
        val alpha = color ushr 24
        return if (alpha == 0xFF) {
            String.format(Locale.US, "#%06X", color and 0x00FFFFFF)
        } else {
            String.format(Locale.US, "#%08X", color)
        }
    }

    /** 从 ColorStateList 中取出 states/colors，重映射后重建（不污染原对象）。 */
    @Suppress("UNCHECKED_CAST")
    private fun remapColorStateList(
        resId: Int,
        csl: ColorStateList,
        name: String?,
        dark: Boolean
    ): ColorStateList? {
        val specs = try {
            val field = cachedField(csl.javaClass, "mStateSpecs") ?: return null
            field.isAccessible = true
            field.get(csl) as? Array<IntArray>
        } catch (t: Throwable) {
            null
        } ?: return null

        val colors = try {
            val field = cachedField(csl.javaClass, "mColors") ?: return null
            field.isAccessible = true
            field.get(csl) as? IntArray
        } catch (t: Throwable) {
            null
        } ?: return null

        // 三段位域分开存放，避免 dark 标志与颜色哈希最低位互相覆盖造成误命中。
        val cacheKey = (resId.toLong() shl 33) xor
            ((Arrays.hashCode(colors).toLong() and 0xFFFFFFFFL) shl 1) xor
            (if (dark) 0x1L else 0x0L)
        val generation = MonetPalette.generation()
        cslCache[cacheKey]?.let { cached ->
            if (cached.generation == generation) return cached.csl
        }

        val mapped = IntArray(colors.size) { index ->
            TokenMapper.mapColor(name, colors[index], dark)
        }
        val out = newSkinCsl(specs, mapped)
        cslCache[cacheKey] = CachedCsl(generation, out)
        if (cslCache.size > 16384) cslCache.clear()
        return out
    }

    private fun newSkinCsl(specs: Array<IntArray>, colors: IntArray): ColorStateList {
        val ctor = skinCslConstructor ?: try {
            val cls = Class.forName(SKINNABLE_CSL, false, timClassLoader)
            cls.getDeclaredConstructor(Array<IntArray>::class.java, IntArray::class.java)
                .also { it.isAccessible = true }
                .also { skinCslConstructor = it }
        } catch (t: Throwable) {
            null
        }
        return if (ctor != null) {
            try {
                ctor.newInstance(specs, colors) as ColorStateList
            } catch (t: Throwable) {
                ColorStateList(specs, colors)
            }
        } else {
            ColorStateList(specs, colors)
        }
    }

    /**
     * 按方法名 + 参数签名查找方法；名字对不上时退化为仅按参数签名匹配，
     * 以应对 TIM 混淆工具在不同版本间重命名方法的情况。
     */
    private fun findMethod(
        cls: Class<*>,
        names: Set<String>,
        vararg paramTypes: Class<*>
    ): Method? {
        val declared = runCatching { cls.declaredMethods }.getOrNull() ?: return null
        for (name in names) {
            declared.firstOrNull { it.name == name && Arrays.equals(it.parameterTypes, paramTypes) }
                ?.let { return it.also { runCatching { it.isAccessible = true } } }
        }
        declared.firstOrNull { Arrays.equals(it.parameterTypes, paramTypes) }
            ?.let { return it.also { runCatching { it.isAccessible = true } } }
        return null
    }
}
