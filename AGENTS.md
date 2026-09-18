# AGENTS.md — TimMonet 架构速览

> 给接手这个仓库的协作者（AI 或人）的简明说明。
> **改动前请先读完「关键约定」与「已知的坑」** —— 里面每一条都是真实踩过的。

---

## 项目是什么

Xposed 模块（libxposed API 102），给 **TIM**（`com.tencent.tim`，QQ NT 架构）注入
Material You 调色与深色主题：hook TIM 的资源加载与 View 绘制，把它的亮色配色映射到
模块自己生成的莫奈调色板。

- 模块包名 `io.github.linx3141.timmonet`，minSdk 26 / targetSdk 35
- 宿主 `com.tencent.tim`（开发时对照 `TIM_4.1.0.apk`）

## 构建与部署（每次改完必做）

```bash
./gradlew :app:assembleRelease --offline
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am force-stop com.tencent.tim      # Xposed 改动必须重启宿主进程才生效
```

只想验证编译（快得多）：

```bash
./gradlew :app:compileReleaseKotlin --offline
```

## 代码地图

| 文件 | 职责 |
|---|---|
| `hooks/TimMonetHooks.kt`（~10k 行） | **全部 hook**：资源加载、View 背景/绘制、控件特判 |
| `core/MonetPalette.kt` | 调色板生成（种子 → DynamicScheme）、`isDarkNow()`、AMOLED 压黑 |
| `core/TokenMapper.kt` | 资源名/颜色 → 配色**角色**的映射；`BgResolver`（判据统一入口，同文件） |
| `core/ColorMath.kt` | **颜色位运算**（`opaque`/`keepAlpha`/`withAlpha`/`isTransparent`）、**保形改色**（`recolorInPlace`）、**实例级缓存**（`instanceCache`/`weakIdentitySet`） |
| `core/ThemeState.kt` | **已废弃**的兼容壳，仅转发 `MonetPalette.isDarkNow()`（现已无调用点，留着是因为它的 KDoc 记录了「坑 1」） |
| `core/SettingsBridge.kt` | TIM 进程侧读远端设置；配色变更时重启 TIM |
| `settings/*` | 设置读写与跨进程同步（模块 UI ↔ TIM 进程） |
| `ui/*` | 模块设置界面（Compose，Material Expressive） |
| `MainModule.kt` | LSPosed 入口：`onPackageReady` → `TimMonetHooks.install()` |

## 染色链路（理解这个才能改对）

1. **资源层** —— `Resources.getDrawable / getColor / getColorStateList /
   `loadDrawable`、`TypedArray.getDrawable / getColor`、`SkinEngine`（腾讯皮肤引擎）
   → 按**资源名**或**颜色值**映射到配色角色。
2. **View 层** —— `View.setBackground(Drawable)` **与 `View.setBackgroundDrawable(Drawable)`**
   （两者是独立方法，不互相转发；都接 `bgArgReplacers`，见「坑 17」）、
   `View.onAttachedToWindow`（`attachHandlers` 合并成的 dispatcher）、
   `ViewGroup.dispatchDraw`（每帧纠正，用于会被 TIM 覆盖的东西）。
3. **图片层** —— `ImageView.setImageDrawable` → 采样主色后重染
   （`rasterizeIconUniform` 等）。
4. **span 层（不在 View 树里）** —— 有些 UI 根本不是 View，而是
   `DynamicDrawableSpan` / `CompoundDrawable` 画进 `EditText`/`TextView` 的
   位图（引用条就是）。这一层**任何 View 判据都抓不到**，只能从它的
   写入入口做上下文限定 —— 见「坑 11」。

## 关键约定（新代码必须遵守）

### 判据：只有一个来源

- **深浅判定只能用 `MonetPalette.isDarkNow()`**，或者——当你要拿配色值的时候——
  `MonetPalette.palette().isDark`。它们都走 `effectiveDark()`（只看模块设置与系统），
  **不要**问 TIM 的 `QQTheme`，也**不要**再用 `ThemeState.isNight()`（见「坑 1」）。
  注意这两个值在"只设了模块内配色、没跟随系统深色"时**不一致**：
  `isDarkNow()` 可能 false 而 `palette()` 是深色档 —— 需要与取色同源时用后者。
- **亮度/彩度判据走 `BgResolver`**：`luma()` / `chromaSpan()` / `isGray()` /
  `isNearWhite()` / `isLightLeftover()` / `isDimGrayText()` / `foregroundForDimText()`。
  阈值用它导出的常量（`NEAR_WHITE`…`DARK`、`GRAY_SPAN`、`DIM_TEXT`、
  `DIM_TEXT_STRONG`、`ROLE_TOLERANCE`、`ALPHA_MIN`、`TONE_*`、`HCT_CHROMA_MAX`），
  **不要再写裸数字**。
- **两套颜色度量不可互换**：`BgResolver` 的 luma/RGB-span 与 TokenMapper 里的
  **HCT tone/chroma** 是两个尺度，同一个颜色可能一个判"亮"、一个判"彩"。
  新代码一律走 `BgResolver`；确实要 HCT 时用 `HCT_CHROMA_MAX` / `TONE_*` 常量。
- **`isNearWhite` 是逐通道判定**（R/G/B 三者都 ≥ 235），**不是** `luma >= 235`。
  需要 luma 尺度就显式写 `luma(c) >= BgResolver.NEAR_WHITE`，不要另起同名函数。
- **判断"这个颜色是否已经是我们输出的角色色"**用 `BgResolver.isSchemeColor(color)`
  （只判"是不是一块面"用 `isSurfaceColorOf`）。它们**没有** `dark` 形参 —— 历史上
  那个形参从不参与判据，是纯粹的误用陷阱。

### 颜色位运算与改色

- **alpha 位一律走 `ColorMath`**：`opaque(c)` 去 alpha、`alpha(c)` 取 alpha、
  `keepAlpha(mapped, source)` / `withAlpha(mapped, a)` 保留/指定 alpha、
  `isTransparent(c)` 判全透明。
  **不要**再手写 `(c and 0x00FFFFFF) or (0xFF000000.toInt())` 这类式子 ——
  漏掉 `and 0x00FFFFFF` 就是「坑 5」那种"半透明蒙层变不透明"的事故。
- **全透明色不参与任何映射**（alpha == 0 原样返回），入口用 `ColorMath.isTransparent`。
- **改背景色一律"就地改色"，不要 new 一个 drawable 顶上**：用
  `ColorMath.recolorInPlace(drawable, color)`。它会 `mutate()` 并**同时**写
  `setColorFilter` 与 `setTint` ——
  这不是冗余：TIM 的 `SkinnableBitmapDrawable` / `SkinnableNinePatchDrawable`
  **直接继承 `Drawable`**，只重写了 `setColorFilter`（转发给自己的 `Paint`），
  `setTint` 对它们完全无效；反过来普通 `BitmapDrawable` 走 tint 管线。
  少写任何一个都会有一半 drawable 静默不变色。
  手工 `GradientDrawable()` 替换会丢掉圆角/九宫格 insets/描边/多状态
  （「设置页卡片圆角被削平」就是这么来的）。
- **必须新建时**（原背景为 null 等）才建，并显式给 `cornerRadius`。

### 缓存

- **键用对象实例，不要用 `System.identityHashCode()` 存 Int**：对象被 GC 后哈希会被
  新对象复用，新对象就被当成"已处理"而跳过 —— 表现为"偶尔有个控件不变色"，
  随 GC 时机变化。用 `ColorMath.instanceCache<K,V>()` / `weakIdentitySet<T>()`
  （弱引用键 + 线程安全 + 不需要容量上限）。
- **热路径的缓存必须线程安全**：hook 可能跑在图片加载线程上。用
  `ConcurrentHashMap`、或上面两个工厂（都已加锁），**不要用裸 `HashMap`**。
- **不要写 `if (size > N) clear()`**：那会把刚写入的条目一起清掉。弱引用缓存不需要
  上限；确实要上限就用有界淘汰。
- **给同一个 View 加"每帧纠正"要带缓存判断**（例如 `d.colorFilter == null` 才重设），
  否则每帧白染。

### hook 的写法

- **先加廉价预筛**（类名 / id 高位 / 缓存判据）。热路径：
  `dispatchDraw`、`setBackground`、`onAttachedToWindow`、`getColor`。
- **新增 attach 处理器**用 `onViewAttached { v -> ... }` 注册，**不要**再单独 hook
  `View.onAttachedToWindow`（注册顺序 = 执行顺序）。
- **新增"替换 setBackground 入参"**用 `onSetBackgroundArg { v, incoming -> ... }`，
  返回非 null 的 drawable 才会替换（第一个非 null 生效）；返回 `incoming` 本身=
  只改色不换对象。
- `findMethod` 带**按参数类型兜底**：语义敏感的方法（`show`/`dismiss`、
  `loadDrawable`…）必须用 `findMethodStrict` / `findMethodStrictDeep`。
- **hook 函数不要留用不到的形参**：`install()` 里曾给 7 个函数传 `module` 而函数体
  从不使用，读起来像"它自己装了 hook"。
- **目标角色不要强行统一**：同一个白色，内联色（布局写死的 `@color`）给**卡片色**、
  drawable（控件自带的卡片面）给**面色** —— 这是语义差异，不是重复。

## 已知的坑（都真实踩过）

1. **`ThemeState.isNight()` 曾恒为 false**：`hookForceLight` 把
   `QQTheme.isNowThemeIsNight()` hook 成永远 false（为了让 TIM 走浅色资源），
   而 `ThemeState` 正是反射调它并**永久缓存** —— 于是所有 `if (isNight)` 分支都是
   死代码，几个通用兜底从来没运行过，表现为"同一个症状反复修不好、只能逐个打控件
   补丁"。现已改为转发 `isDarkNow()`（并已无调用点）。
2. **透明色 `#0` 会被当成"纯黑文字"提亮**：TIM 的 `?attr/xxx` 取值未定义时拿到 `#0`
   （RGB 也是 0），曾被 `dark && opaque == 0xFF000000 → onSurface` 兜底命中，
   于是"什么都不画"的地方显形成一条亮线。现已在 `TypedArray.getColor` 入口加
   透明短路（判据统一为 `ColorMath.isTransparent`）。
3. **Android 17 的 `PorterDuffColorFilter` 字段是 `mAdd`/`mMul`** ——
   历史沿革 `mSrcColor` → `mColor` → `mAdd`/`mMul`，只试老名字会静默失效
   （皮肤红气泡重染就是因此失效很久）。
4. **`findMethod` 兜底会挂错方法**：`QQCustomArkDialogForAio.show` 继承自 `Dialog`，
   不在 `declaredMethods` 里，兜底按"无参"抓到了它自己的 `dismiss` ——
   "弹窗显示时染背景"实际在**关闭时**才执行。
5. **AMOLED 压黑的 alpha 位运算写错过**：`0xFF000000.toInt() or (alpha shl 24)`
   恒等于 `0xFF000000`（`0xFF000000` 的 alpha 位已全 1），半透明蒙层/遮罩会变成
   **不透明纯黑**把内容盖死。正确写法是 `ColorMath.withAlpha(0xFF000000.toInt(), alpha)`。
   同一个函数里还用过 `Int.MIN_VALUE` 当"无映射"哨兵，而它恰好是合法的
   `0x80000000`（半透明黑）—— **不要在颜色域里用哨兵值**。
6. **`MonetPalette.palette(dark)` 的 `dark` 参数早就不用了**（深浅由设置决定），
   但 TokenMapper 的 memo 键里还留着它 —— 传错时"取色对、分支错"，
   表现为时好时坏、不像同一个 bug。现已只保留无参 `palette()`；
   `mapColor`/`tintColorFor` 的 `dark` **仍只参与缓存键**，传不同的深浅来源
   只会让缓存多存一份、结果不变（尚未清理，改动前先确认）。
7. **TIM 会在我们染色之后重新设置背景**（极光卡片、底部提示条都遇到过）——
   一次性染色会被覆盖，需要"attach 后再补一次"或"绘制时持续纠正"。
8. **"行为与日志脱节"最贵**：`fixTinySolidBg` 曾经构造了 `GradientDrawable` 却
   **从未 `setBackground`**，整条规则空转却照打"已改成页面底色"的日志，
   排查时极具误导。改完请确认"日志说的"和"代码做的"是同一件事。
9. **失败路径会击穿重试节流**：`MonetPalette.rebuild()` 失败时把 `initialized`
   置回 false，而节流条件写成 `if (initialized && now - lastAttemptAt < 5000)` ——
   连带要求 `initialized` 就等于"失败后永不节流"，每次取色都重投一次昂贵的重算。
   节流依据必须与成功标志**解耦**（现在只看 `lastAttemptAt`）。
10. **"先删后建"的文件替换会丢数据**：`ArkPackagePatcher` 曾经 `file.delete()`
    之后再 `rename`，第二次 rename 失败就永久毁掉原 `.ark`（只返回 false、无异常）。
    现在改成"先写 .bak → 失败可回滚"。
11. **有些"控件"根本不是 View**：输入框上方的引用条（含"取消引用"圆按钮）由
    `com.tencent.mobileqq.aio.i.d extends DynamicDrawableSpan` 实现 ——
    `InputReplyVBDelegate.s()` 把一个**临时 TextView** 画成 Bitmap，
    再 `editText.setCompoundDrawables(null, span.getDrawable(), …)` 塞进编辑框。
    所以它**不经过 View 树**，`setBackground` / `onAttachedToWindow` /
    `dispatchDraw` 全都无效（这个功能因此漏染了很久）。
    这类元素只能**从写入入口做上下文限定**（那里置 ThreadLocal 标志，
    在 `setCompoundDrawables` 里判断），不要试图在 View 树里找它。
    另外那个图标是**圆底+镂空叉**的合成位图，要"圆 primary + 叉 onPrimary"
    必须叠两层（详见 `hookReplyBarSpan` 的 KDoc）；尺寸要用 TIM 已算好的
    `bounds`，用 `intrinsicWidth` 会让按钮从 11dp 涨成 24dp。
12. **有些界面跑在独立进程里，`onViewAttached` 可能整体不触发**：支付密码弹窗
    位于 `com.tencent.tim:tool` 进程（`QWalletToolFragmentActivity`）。实测该进程里
    `View.onAttachedToWindow` 的 dispatcher 收不到任何回调（连"记录所有 EditText
    子类"的诊断都为空），但**资源层 hook 正常**（`Resources.getDrawable` 等照常命中）。
    所以在这类界面里：**能在资源层解决的就别指望 View 层**；资源层覆盖不到的
    （Canvas 自绘），要走**绘制层**（如 `Paint.setColor` / `Canvas.draw*`）。
    另外该弹窗带 **FLAG_SECURE**，`adb screencap` 全黑、必须让用户手动截图；
    且 `:tool` 进程**没有存储权限**，写 `/sdcard` 会静默失败 —— 调试只能靠 logcat
    （注意 TIM 日志量大，环形缓冲很快就冲掉，要抓就得马上抓）。
13. **短混淆名不在染色白名单里**：`tintDrawable` 有一道早退（只放行 `qui_`/`skin_`
    等前缀白名单）。支付密码弹窗用的 `dwa`/`dvi`/`dvj`/`a3h`/`dy4` 都是短混淆名，
    全部落到早退里被原样放行 —— 这就是它们长期没被染的原因。这类资源要在早退
    **之前**显式处理。
14. **颜色可能硬编码在 Canvas 代码里，资源层永远抓不到**：支付密码的 6 个格子由
    `PasswordEditText` 用 `canvas.draw*` + `mPaintBackground.setColor(-1184275)`
    （= `#FFEDEDED` 纯白）自绘。既不是资源、也不是 View 背景。
    可靠的拦截点是 **`Paint.setColor`**（无论怎么画，填色前必然设颜色）——
    按特征色匹配即可；它是热方法，所以不匹配时必须只做一次 int 比较就 `proceed()`。
15. **「内联白 → 面色」这条规则会误伤"前景符号"**：`TypedArray.getColor` 里那条
    "纯白按**面**色映射"（钱包页白底、卡片底靠它）是**无上下文**的 —— 它分不清
    "一块白底"和"一个白色的图标着色源"。通话界面的图标着色就是后者：
    `com.tencent.av.utils.av` 拿到的 ColorStateList 在被构造函数读走**之前**就已经
    被映射成 `#2B2B34`(surfaceContainer)，于是**图标和它自己的深色底同色**、几乎
    看不见。修法不是改那条通用规则，而是**按资源名**在 `av` 构造函数里把这个 CSL
    改回 `onSurface`（`AV_ICON_TINT_COLORS`：`rl`/`rm`/`rn`/`qm`/`qn`/`amp`/`b0x`）。
    同一个 `av` 类还负责按钮**底叠加层**（`b0w`/`ro`，半透明白→半透明面，映射是
    对的），所以只能按资源名区分，**不能按颜色值一刀切** —— 按值切会把按钮底一起
    改成实心亮块（真踩过）。
16. **Xposed 构造函数拦截器的执行时机**：拦截器体执行在**原始构造函数体之前**。
    所以"读/写构造函数里才初始化的字段"必须放在 `chain.proceed()` **之后** ——
    先写会被构造函数体随后覆盖（实测：写完立刻回读仍是旧值，构造函数里那次
    `onStateChange` 读到的还是 `#2b2b34`）。纠正字段后还要手动再调一次
    `onStateChange` 刷新 drawable 的 colorFilter。
17. **`setBackgroundDrawable` 是独立方法，不转发给 `setBackground`**：只 hook
    `View.setBackground(Drawable)` 会漏掉一整类背景。资料卡的卡面就是这么漏的 ——
    反编译 `ProfileCardAdapter.getContentView()`：
    `view2.setBackgroundDrawable(getProfileDrawable(i2));`
    这类背景从不经过 `bgArgReplacers`，于是**所有**按"设置背景"入口做的规则
    （替换入参 / 按名按值染色 / 定时补染）全都命中不到它。表现：资料卡的行保留
    inflater 的面色 `surfaceContainer`，与页面底**完全同色** → 卡片边界消失。
    **教训：排查"某个背景没被染"时，第一步就去反编译里搜它是用哪个 setter 设的**，
    而不是在现有 hook 上加探针 —— 这次在探针上耗了十几轮，而源码里一行
    `setBackgroundDrawable(...)` 就是答案。
18. **卡面不一定是"卡片类"那个 View**：资料卡页里 `ProfileContentView` 的几何是
    `1248x0`（**没在画**），真正铺满卡片区域的是 5 个
    `ProfileCellView [48,608 1248x168]` 行。按类名想当然地染"内容卡"会一无所获 ——
    定位卡面要看**几何 + 最终取色**，别只看类名。
19. **不要用固定延时"兜底"**：TIM 各行/各卡的加载时机不同，`postDelayed(300)` 这类
    兜底既兜不住快的也兜不住慢的（实测"颜色闪一下又消失"）。要挂在**设置背景的
    入口**上（`onSetBackgroundArg`），它与加载快慢无关、且覆盖后续每次重设。

20. **设置页列表行的面色 / 圆角会被"复用残留"破坏**（两个独立症状，同一个来源）。
    行的背景由 `QUIListItemBackgroundType.getBackground()` 生成，**每个分支用的颜色
    资源不同**（`qui_common_fill_light_secondary` 之类），其中
    `FullWidthWithTransparent` 用的是 `R.color.ajr` = `@color/fm` = **`#00000000` 全透明**
    （TIM 有意让下层分组卡片透出来）。
    - **颜色**：实测该工厂对设置页**每一行**返回同一个颜色
      （29 次调用全为 `#2B2B34` = `TokenMapper.bgCard(true)`）→ **同组各行本该同色**，
      所以"哪一档才对"有据可依：就是工厂的返回值，不要自己挑角色。
      但坏态下 `AccountManageView` / `SingleLineRedTouchView` 两类行会取到别的颜色。
      修法见 `hookQuiRowSurface()`：统一到 `bgCard`，**全透明的行原样放过**。
    - **圆角**：曾经的判断是"组内首/末行变成四角全圆，成因是 payload 局部重绑时
      TIM 不重设背景类型" —— **这个成因是错的，据此写的几何修正整套已经删除**
      （见下面「坑 26」）。反编译实证：圆角根本不由几何决定，而是
      `Group.java:215-222` 按数据算 `PositionType(Only/Top/Middle/Bottom/Other)`
      → `b.java:85-113` 映射成 `QUIListItemBackgroundType`（AllRound/TopRound/
      NoneRound/BottomRound/None）→ `QUIListItem.setBackgroundType()` 应用；
      而 `QUIListItemAdapter` 的 payload 路径**是转发到完整绑定的**
      （`QUIListItemAdapter.java:103-106`），类型每次绑定都会重设。
      也就是说 **TIM 自己算的就是对的**，坏掉的另有其人（见坑 26）。
21. **`palette()` 在真实调色板就绪前会返回"兜底方案"**：
    `MonetPalette.palette()` 里 `if (scheme != null) return scheme` 否则
    `fallbackScheme()` —— 用 `DEFAULT_SEED` 现搭的 TonalSpot 方案（**另一套颜色**）。
    实测同一条日志里 `palette()` 先返回兜底（`bgPage=#1d2024`）、8ms 后真实方案就绪
    （`bgPage=#181920`）。**这段时间内构建的控件会拿到与之后不一样的配色**
    （表现为同一页面里颜色不一致、重建后自愈）。排查"同页颜色不一致"时，
    先看 `palette rebuilt` 的时间戳是否**晚于**这些控件的构建时间。

22. **同一段"半透明纯黑"既是遮罩、又是占位文字色 —— 颜色值分不出来**。
    `#8c000000` 这种色有两个相反语义：
    - **遮罩/蒙层**（首页菜单背后的压暗层）→ 必须**原样保留**，一旦被换成不透明的
      `onSurface`，下方内容全被盖死（实测遮罩变实心亮片）。
    - **占位文字**（首页搜索栏的「搜索」）→ 必须**提亮**成 `onSurfaceVariant`，
      否则压在深色栏底上几乎看不见。
    实测两者的 alpha/RGB 完全同签名，**任何基于颜色值的判据都必然二选一错**。
    最终修法：
    - 颜色路径里对"半透明 + RGB 全黑"**原样放过**（`TokenMapper.resolve` 与
      `remapColorByName` 各一处短路）—— 保证遮罩不被刷实；
    - 文字的那一侧**改在 View 层兜底**：`hookNearBlackHint()` 只看"当前生效的
      hint 色是不是近黑"，与它从 XML 还是代码来无关。
    **方法论**：当同一个颜色值承载相反语义时，别在颜色层硬分，去**语义明确的层**
    （这里是 TextView 的 hint）判。
23. **inflate 期就定好的颜色，View 层的 setter 钩子全都看不到**：首页搜索栏的
    🔍 是 `SkinnableBitmapDrawable`，**既无 colorFilter 也无 tint**，从没进过
    `tintDrawable` 的白名单路径；「搜索」占位色同理（XML 的 `textColorHint`）。
    排查这类"某个控件就是没被染"时，**先 dump 它的最终状态**
    （TextView 的 `hintTextColors` / ImageView 的 `drawable.colorFilter`），
    拿不到再去追 setter —— 我在这上面先后白试了 `setHintTextColor`、
    `setImageDrawable`、`QUITokenThemeManager.k` 三条路径。
    修法也是在 View 层兜底（`hookSearchBarIcon()` / `hookNearBlackHint()`）。

24. **注入到 WebView 的 JS 里，`MutationObserver` + "回调内改 DOM" = 死循环**。
    群公告 H5（`web.qun.qq.com/mannounce`）的颜色注入脚本里，`paintDoc()` 做两件事：
    ① 全量 `querySelectorAll` + 逐元素 `getComputedStyle`；
    ② 末尾注册 `MutationObserver(doc, {childList,subtree,characterData})` → 回调里
    **同步**再调 `paintDoc`。而它自己又会写 DOM（`injectPseudoRule` 往
    `<style>.textContent += 规则`，正是 `characterData` 变更）—— 于是
    **观察者 → paintDoc → 写 style → 观察者** 构成死循环：JS 线程 100% 占满、
    样式表无限增长、每轮还强制重排 → **页面直接卡死**（用户报的"进群公告页很容易卡死"）。
    Node 里搭最小 DOM 仿真对比同一次页面变更：
    旧版观察者触发 **5561 次** / paintDoc **4774 次** / 样式写入 **5562 次**；
    新版 **1 次 / 2 次 / 2 次**。
    修法三件套（缺一不可）：
    - `paintDoc` 加**重入保护**（`doc.__tmBusy`）；
    - 观察者回调**不再同步执行**：只置 `__tmPending` 标记，真正的重扫放到
      `setTimeout(…,150)` 里做一次（同一批变更自然合并），并限制总轮数；
    - `injectPseudoRule` **按规则去重**（`seen[rule]`），只有内容真的变了才写 DOM —
      这既是打破循环的关键，也避免样式表无限膨胀。
    **通用教训**：往页面注入的 JS，只要同时满足"观察 DOM + 修改 DOM"，
    就必须**异步 + 去重 + 重入保护**，否则一定会自激。
    （验证方式：把注入的 JS 抠出来，用 Node 搭最小 DOM 仿真跑新旧两版对比 —
    不用装机、不用复现，几秒就能证伪。）

25. **同名 token 在"原生"和"跨端页面"里语义可能不同 —— 不能共用一条角色规则**。
    群公告 H5 的列表卡片和页面底变成了同一个颜色（卡片直接看不见）。页面自己的
    CSS 是实证（线上可取：`qq-web.cdn-go.cn/web.qun.qq.com_mannounce/…/index.css`）：
    ```css
    .announcement-main { background-color: var(--bg_bottom_standard); }  /* 页面底 */
    .list-item         { background-color: var(--bg_top_light); }        /* 卡片底 */
    ```
    而 `computeRole` 里 `bg_top_light -> BG_NAV_TINT`、`bg_bottom_standard -> BG_LIST`，
    **两者都解析成 `surfaceContainer`** → 卡片与页面底必然同色。
    那次改动（"顶栏底跟随页面色，免得深色下顶栏和内容割成两截"）本身没错，
    错在**没区分消费方**：同一个 token 名，原生侧是**顶栏底**、H5 侧是**卡片底**。
    修法：把跨端 token 表单独走一个入口 `TokenMapper.mapPageToken()`，
    按**页面 CSS 的语义**判（`bg_top_light -> BG_CARD`）；原生资源路径原样不动，
    顶栏不会被改回去（`mapTokenString()` 已切过去）。
    **通用教训**：`Resources.getColor`/`TypedArray` 那条路是"原生视图语义"，
    `QUIUtil.getCurrentTokenMap()`/tint map 那条路是"页面语义"，
    遇到"改了 A 就坏 B"时先问一句：**这两个消费方读的是不是一个键、语义是否相同**。
    判据优先取**消费方自己的样式定义**（这里是页面 CSS），别只依据名字猜。

26. **"判据过宽"会去打别的页面 —— 一个兜底把设置页所有行都改成了圆角卡片**。
    `isPlusPanelHost()` 原本是"向上 8 层里有 `pluspanel`/`PlusPanel` 类名"，
    后面还挂了一条兜底：**`sawViewPager && depth >= 3`**（途经 `QQViewPager` 就算）。
    而 TIM 很多**普通页面**（设置页等）的层级里也有 `QQViewPager` → 页面里的
    ImageView 被判成"面板项"，于是 `handleImage` 里那条分支把**祖先 3 层的背景
    整个换成了自绘的 12dp 全圆角底板**（`buildPlusItemPlate`，12dp×1.5=18px）
    → **设置页每一行都变成独立的全圆角卡片**。
    这个 bug 的排查价值在于它的"伪装"：
    - 调用点带 `panelSeen` 前置条件 → **只有先打开过"+"面板（如文件发送页）再进
      设置页才复现**，直接进设置页完全正常 → 看起来像"页面间的状态污染"；
    - TIM 侧的类型分配（用探针实测）**全是正确的**（AllRound/TopRound/NoneRound/
      BottomRound 成套）→ 看起来像"TIM 自己的设计"；
    - 只有含 ImageView 的行会被扫到 → 看起来像"部分行的问题"。
    结论：**兜底判据必须和主判据一样"身份明确"**。能用类名/资源名精确判定时，
    不要用"途经某个通用控件""尺寸像""颜色像"来兜底 —— 这类兜底迟早会在别的页面命中。
    修法：删掉 `QQViewPager` 兜底（只认类名），并给 `fixPlusItemPlate()` 加
    "不在 PlusPanel 里就 return" 的双保险。
    **同一条也适用于"改形状"**：我们是**染色**模块，圆角由 TIM 的数据层决定，
    不要用几何去覆盖它（曾经的 `hookQuiGroupRadii` 整套就是反例，已删除）。
    ⚠️ 同类问题在仓库里**不止一处**：事后做过一次全量审计，见
    `.audit/2026-09-18-loose-heuristics-and-delays.md`（列出 15 条，
    标注了已修/待处理与各自的高危级别）。**新增 hook 前请先读它**，
    尤其是"宽松判据 + 破坏性动作（替换背景 / SRC_IN 单色化 / alpha=0）"的组合。

## 调试手段

- **日志**：`adb logcat -v time -s TimMonet:*`
  （`-s` 不能漏，否则抓成全量日志）
- **二分定位回归**：`git checkout <commit>` → 编译装机 → 对照截图/取色，每轮约 2 分钟。
  本轮"设置页白线"就是这样定位到 `isNight` 那次改动的。
- **截屏取色**：`adb exec-out screencap -p > x.png`，再用 PIL 按行扫描颜色变化点。
- **找"看不见的元素"**：先猜它的**颜色等于哪个角色**（`#FFDBF3` 就是 `onSurface`），
  再按几何（全宽 / 高度 / 屏幕位置）或反查日志（`attr color #0 -> #ffdbf3`）定位。
- **uiautomator dump 对 TIM 无效**（返回 `null root node`），别在这上面浪费时间。

## 相关产物

- `tim_decompiled/` —— TIM 4.1.0 反编译（`sources/` + `resources/`）。
  改 TIM 侧行为前先在这里查实现（尤其混淆名与布局结构）。
  查"某个 drawable 认不认 setTint"这类问题**必须**看这里的实现：
  `com/tencent/theme/SkinnableBitmapDrawable.java` 只重写了 `setColorFilter`。
- `.audit/2026-09-18-loose-heuristics-and-delays.md` —— **判据过宽 / 定时兜底**专项审计
  （15 条，含已修与待处理）。新写身份判据、或要用"延时补一次"之前先看它。
- `.audit/` —— 一次完整代码审查的清单（重复代码 / 可通用化 / 正确性 / 性能）。
  **大部分已落地**；做新审查前先读它，避免重复报同一个问题
  （例如 `hookChatsUtils` 无条件覆盖"错误红"是**有意设计**，别再报）。
- `shots/` —— 验证用截图（含修复前后对照）。
