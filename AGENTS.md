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
2. **View 层** —— `View.setBackground(Drawable)`（参数替换注册表 `bgArgReplacers`）、
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
- `.audit/` —— 一次完整代码审查的清单（重复代码 / 可通用化 / 正确性 / 性能）。
  **大部分已落地**；做新审查前先读它，避免重复报同一个问题
  （例如 `hookChatsUtils` 无条件覆盖"错误红"是**有意设计**，别再报）。
- `shots/` —— 验证用截图（含修复前后对照）。
