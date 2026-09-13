package com.timmonet.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.view.Window
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.timmonet.core.ModuleResources
import com.timmonet.core.SettingsBridge
import com.timmonet.ui.screen.ThemeScreen
import com.timmonet.ui.theme.AppSettings
import com.timmonet.ui.theme.ColorMode
import com.timmonet.ui.theme.TimMonetTheme

/**
 * 在宿主(TIM)进程里直接显示模块设置页（全屏 Dialog + ComposeView）。
 *
 * 为什么要跑在宿主进程里：Android 11+ 的包可见性把宿主对我们 app 的跨包访问
 * 全拦了——实测显式 startActivity 报 START_CLASS_NOT_FOUND、provider 报
 * Unknown authority（同一组件用 adb shell 起完全正常）。QAuxiliary / TAssistant
 * 的设置入口也都是把界面跑在宿主进程里，这里用同样的思路，只是不必替换
 * Instrumentation/PackageManager：用 Dialog 承载即可。
 *
 * 依赖两件事：
 * 1. [ModuleResources] 先把模块 APK 的资源注入宿主 Resources（界面里的 R.string）
 * 2. 自己提供 Lifecycle/SavedState/ViewModelStore 三个 owner（Compose 需要）
 */
class SettingsDialogHost private constructor(
    private val hostActivity: Activity
) : Dialog(hostActivity, com.timmonet.R.style.Theme_TimMonet_Dialog),
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    private var settings by mutableStateOf(SettingsBridge.readCurrent())

    /** 内容视图（进出动画直接作用在它上面，窗口级动画对非浮动 Dialog 不生效）。 */
    private var contentView: ComposeView? = null

    private var dismissing = false

    private var slideStarted = false

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore get() = store

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        savedStateController.performRestore(savedInstanceState)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        applyEdgeToEdge()
        setContentView(
            ComposeView(context).apply {
                contentView = this
                setViewTreeLifecycleOwner(this@SettingsDialogHost)
                setViewTreeSavedStateRegistryOwner(this@SettingsDialogHost)
                setViewTreeViewModelStoreOwner(this@SettingsDialogHost)
                setContent {
                    TimMonetTheme(appSettings = settings) {
                        // 状态栏/导航栏图标明暗跟随面板底色（面板深色时用亮图标）
                        val darkPanel = androidx.compose.material3.MaterialTheme
                            .colorScheme.surface.luminance() < 0.5f
                        androidx.compose.runtime.SideEffect {
                            val win = window ?: return@SideEffect
                            androidx.core.view.WindowInsetsControllerCompat(win, win.decorView)
                                .apply {
                                    isAppearanceLightStatusBars = !darkPanel
                                    isAppearanceLightNavigationBars = !darkPanel
                                }
                        }
                                                ThemeScreen(
                            colorMode = settings.colorMode,
                            keyColor = settings.keyColor,
                            paletteStyle = settings.paletteStyle,
                            colorSpec = settings.colorSpec,
                            amoledBlack = settings.amoledBlack,
                            onBack = { dismiss() },
                            onSetKeyColor = { update(settings.copy(keyColor = it)) },
                            onSetColorMode = { mode: ColorMode ->
                                update(settings.copy(colorMode = mode))
                            },
                            onSetAmoledBlack = { amoled ->
                                update(settings.copy(amoledBlack = amoled))
                            },
                            onSetColorStyle = { name ->
                                runCatching { PaletteStyle.valueOf(name) }.getOrNull()
                                    ?.let { update(settings.copy(paletteStyle = it)) }
                            },
                            onSetColorSpec = { name ->
                                runCatching {
                                    ColorSpec.SpecVersion.valueOf(name)
                                }.getOrNull()?.let { update(settings.copy(colorSpec = it)) }
                            },
                        )
                    
                    }
                }
            }
        )
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /**
     * 让面板像模块自己的设置页那样沉浸：内容铺到状态栏/导航栏底下。
     * Dialog 窗口默认不是 edge-to-edge（对比 Activity 会明显"不沉浸"），
     * 所以这里显式关掉 decorFitsSystemWindows、把两条系统栏设成透明，
     * 并让内容延伸进刘海/挖孔区域。
     */
    private fun applyEdgeToEdge() {
        val win = window ?: return
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(win, false)
        win.statusBarColor = android.graphics.Color.TRANSPARENT
        win.navigationBarColor = android.graphics.Color.TRANSPARENT
        // Dialog 是浮动窗口，仅 setDecorFitsSystemWindows 还不够：
        // 再显式要求"窗口铺满屏幕、decor 自行处理 inset"，并清掉窗口底色，
        // 否则系统栏那两条会露出宿主/对话框主题的底色（实测就是不沉浸的来源）。
        win.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
        )
        win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        win.setDimAmount(0f)
        win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            win.attributes = win.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams
                        .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            win.isStatusBarContrastEnforced = false
            win.isNavigationBarContrastEnforced = false
        }
    }

    override fun onStart() {
        super.onStart()
        animateIn()
    }

    /** 退场动画跑完再真正 dismiss（返回键/点遮罩都走这里）。 */
    override fun dismiss() {
        if (dismissing) return
        dismissing = true
        val view = contentView
        if (view == null || !view.isAttachedToWindow) {
            super.dismiss()
            return
        }
        // 与 QAuxiliary/TAssistant 那种"真页面"一致：向右推出、露出宿主
        view.animate()
            .translationX(screenWidth().toFloat())
            .setDuration(EXIT_MS)
            .setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f))
            .withEndAction { super.dismiss() }
            .start()
        // 动画被系统打断(视图移除)时兜底关掉，避免面板卡住
        view.postDelayed({ runCatching { super.dismiss() } }, EXIT_MS + 120L)
    }

    /**
     * 进场：从屏幕右侧整屏推入（侧边覆盖式，和 TIM 真页面/QAuxiliary/TAssistant
     * 的转场同款）。要点有两个：
     * 1. 首帧绘制前先把面板整体摆到屏幕右侧，否则第一帧就"已经到位"，看不到推入；
     * 2. 不能依赖 viewTreeObserver 的 preDraw —— 视图还没 attach 时注册的监听器
     *    会随 observer 一起被换掉，动画永远不开始，表现就是"弹出一个全透明层、
     *    点哪都没反应、返回才恢复"。这里改用 postOnAnimation + postDelayed 兜底，
     *    先到先得且幂等，保证动画一定会跑。
     */
    private fun animateIn() {
        val view = contentView ?: return
        view.translationX = screenWidth().toFloat()
        view.alpha = 1f
        view.postOnAnimation { slideIn(view) }
        view.postDelayed({ slideIn(view) }, 100L)
        // 最后兜底：万一回调都没跑到，也不能留下"透明层挡着点击"的状态
        view.postDelayed({
            if (!dismissing && view.translationX != 0f) {
                view.animate().cancel()
                view.translationX = 0f
                Log.w(TAG, "panel slide-in watchdog: snap into place")
            }
        }, 1000L)
    }

    /** 幂等：无论从哪条路先到，只启动一次推入动画。 */
    private fun slideIn(view: android.view.View) {
        if (slideStarted || dismissing) return
        slideStarted = true
        view.animate()
            .translationX(0f)
            .setDuration(ENTER_MS)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
            .start()
        Log.i(TAG, "panel slide in (${ENTER_MS}ms)")
    }

    private fun screenWidth(): Int = context.resources.displayMetrics.widthPixels

    override fun onStop() {
        super.onStop()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }

    private fun update(next: AppSettings) {
        settings = next
        // 写远端 prefs：TIM 侧的监听器会热刷新（配色相关改动按既有设计重启 TIM）
        SettingsBridge.write(next)
    }

    companion object {

        private const val TAG = "TimMonet"

        /** 侧边推入/推出的时长，接近 TIM 真页面的 activity 转场。 */
        private const val ENTER_MS = 260L
        private const val EXIT_MS = 240L

        /** 从任意 Context 里找出宿主 Activity。 */
        fun findActivity(context: Context?): Activity? {
            var current = context
            while (current is ContextWrapper) {
                if (current is Activity) return current
                current = current.baseContext
            }
            return current as? Activity
        }

        /** @return true 表示已在宿主进程里显示设置页 */
        fun show(context: Context?): Boolean {
            val activity = findActivity(context)
            if (activity == null || activity.isFinishing) {
                Log.w(
                    TAG,
                    "settings dialog: no host activity (ctx=${context?.javaClass?.name})"
                )
                return false
            }
            if (!ModuleResources.ensureInjected(activity.resources)) {
                Log.w(TAG, "settings dialog: module resources not injected")
                return false
            }
            return try {
                SettingsDialogHost(activity).show()
                true
            } catch (t: Throwable) {
                Log.e(TAG, "show settings dialog failed: ${t.javaClass.simpleName} ${t.message}")
                false
            }
        }
    }
}
