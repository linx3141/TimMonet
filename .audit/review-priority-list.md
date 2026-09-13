# TimMonet 染色补丁审查 —— 重复代码 / 可通用化清单

审查范围：`hooks/TimMonetHooks.kt`(9852 行)、`core/TokenMapper.kt`(578)、`core/MonetPalette.kt`(348)、`core/ThemeState.kt`(69)。
方法：全文通读 + 两份分区重复度审计（1–4900 / 4960–9852）+ 用仓库里的真机日志 `log.log`(118429 行，含 2328 行 `TimMonet` tag) 交叉验证结论。**未修改任何代码。**

---

## 0. 结论摘要

1. **9 个机制里有 4 个在当前代码里是死代码** —— 它们被 `ThemeState.isNight(...)` 门禁挡住，而这个函数在本进程里恒为 `false`（模块自己 hook 掉了它的数据源）。这解释了"为什么每修一个控件就要再加一个补丁"：几个真正的**通用**兜底（#4 亮背景压暗、#5 极小纯色底）从来没跑起来过，于是只能靠 #6/#7/#8 这类单控件补丁硬顶。
2. **6 处"值兜底"本质是同一件事**（输入一个颜色 → 判亮度/彩度 → 给一个面色），但各自用不同的颜色空间（HCT tone / BT.601 luma）、不同的阈值（120/140/160/170/220/235）、不同的目标角色（PAGE=`surfaceContainer`、CARD=`surfaceBright`、CONTAINER_HIGH）——**同一个白色背景经不同入口会得到不同颜色**。
3. **12 个 `View.onAttachedToWindow` hook、4 个 `View.setBackground` hook、2 个 `setBackgroundColor`、2 个 `ImageView.setImageDrawable`** —— 全部应各合并为 1 个带"修补器列表"的 dispatcher。
4. 重复代码可塌缩量：**≈700–900 行**（1–4900 区间约 350–360 行，4960–9852 区间约 350–540 行），另有 3 处 Bitmap 未 `recycle()`、1 处"写了从不读"的死状态。
5. 登记在案的缺口：`common_tips_bg_white/blue` 在 TIM 里被 2 个布局 + 1 处代码当**背景**使用，模块里**一条规则都没有**（用户列的清单里提到它，实际代码中不存在这个分支）。

---

## P0-1　门禁错了：4 个补丁根本没生效（先修这个，其它合并才有意义）

**位置**

| 机制 | 位置 | 门禁 |
|---|---|---|
| #4 亮背景一律压成页面底 | `TokenMapper.bgColorForDrawable` L185-189（唯一真正用 `dark` 参数的地方） | 由 L6248 / L8655 / L9167 传入的 `ThemeState.isNight(...)` 决定 |
| #5 fixTinySolidBg | L6003-6027（入口 L5903、L5986） | L6004 `if (!ThemeState.isNight(null, timClassLoader)) return` |
| #7 hookNoticeBarBg | L5949-5970 | L5959 `if (result != null && ThemeState.isNight(...))` |
| #8 hookPolarLightCard | L5917-5938 | L5930 `if (!ThemeState.isNight(...)) return@runCatching` |

**问题（可验证的因果链，不是猜测）**

1. `ThemeState.isNight()` 的首选数据源是 `QQTheme.isNowThemeIsNight()`（`ThemeState.kt` L17、L44-51）。
2. `hookForceLight` L2826-2830 把**同一个类同一个方法**硬钉成 `false`：
   ```kotlin
   val cls = Class.forName(QQ_THEME, false, cl)          // "com.tencent.mobileqq.utils.QQTheme"
   findMethod(cls, setOf("isNowThemeIsNight"))?.let { method ->
       module.hook(method).intercept { false }            // L2829
   ```
3. `ThemeState` 把首次结果永久缓存在 `cachedNight`（L22-29），**没有任何失效路径**（配色代次/配置变化都不清）。
4. 真机日志证明首次求值发生在钉死之后：`log.log` 里 pid 16785，`hook installed: ...QQTheme.isNowThemeIsNight` 在 `05:23:02.308`，而 02.241–02.308 之间**只有** `hook installed:` 行、没有任何取色日志；第一条依赖配色的输出是 `05:23:03.574` 的 `icon res tinted ... -> #ffcce9ff`（= 深色方案的 `onSurface`，说明模块当时处于深色档）。→ `ThemeState.isNight()` 返回 `false`，而调色板是深色。
5. 作者自己在 `fixLowContrastText` L5675-5681 已经踩过并单点修好了这个坑：
   > "`MonetPalette.palette()` 内部走 `effectiveDark()`，而 `ThemeState.isNight()` 在'只设了模块内配色、没跟随系统深色模式'时会返回 false —— 设备实测就是 dark=false 让这条兜底全部早退"
   同一类错误在另外 4 处没有修。

**推论（可直接复现的症状）**
- 极光卡片（`hookPolarLightCard`）门禁失效 → 走通用位图/名字路径 → 注释里写的"账号管理/账号安全卡片偏暗一档 (#380E33 vs #4A1843)"**至今仍然存在**。
- 文件页提示条（`fixNoticeBar` #6）之所以还生效，是因为它硬编码了 `MonetPalette.palette(true)`（L6044）绕开了门禁；但**任何其它页面**的亮色底条（如注释提到的 `#CDEEFE` 微云入口）没有任何机制覆盖 → 残留亮条。
- `TokenMapper.bgColorForDrawable` 的 `if (dark && luma > 170)` 分支是**不可达代码**：`bgColorForDrawable` 的全部调用点（L6375/6388/6411，均在 `tintAnyDrawableImpl` 内）都经由 `tintAnyDrawable(d, ThemeState.isNight(...))`，`dark` 恒为 false。

**具体改法**
1. `MonetPalette` 增加唯一真值入口 `fun isDarkNow(): Boolean = effectiveDark()`（`palette(x)` 的参数本来就是死参数，见 P0-2）。
2. 删除 `core/ThemeState.kt`（它只服务这一个判断），或把它整体改写成 `MonetPalette.isDarkNow()` 的转发并**去掉缓存**。
3. 全量替换（精确统计：`ThemeState.isNight` 共 **102 处**）：
   - **56 处写成 `MonetPalette.palette(ThemeState.isNight(...))` → 可以不动**（`palette` 忽略该参数，恰好拿到正确方案）；
   - **39 处把它当 `dark` 参数传给 `TokenMapper.*` → 结果不受影响，只需改语义/缓存键**（L212、L224、L6737、L8225、L8630、L8962、L9408、L9539、L9560 等），可顺手清理；
   - **必须改的 7 处**（返回值真的参与了判断）：
     - L5930 `hookPolarLightCard` 门禁
     - L5959 `hookNoticeBarBg` 门禁
     - L6004 `fixTinySolidBg` 门禁
     - L6248、L8655、L9167 三处 `tintAnyDrawable(drawable, ThemeState.isNight(...))` → `dark` 传入 `bgColorForDrawable` 后决定 L185 的 `luma>170` 分支
     - L1231 `forceMonetSubtree` 的 `val dark = ThemeState.isNight(null, cl)`（一路传到 L1242/L1255 → 同上）
   - L7434 `palette(isNight).isDark` 属于"意外正确"，改造后可直接用 `isDarkNow()`。
4. 顺手把 `bgColorForDrawable` 里 `dark` 的语义改成"来自 `MonetPalette.isDarkNow()`"，或干脆删掉参数、内部自己取。

**收益**：#4/#5/#7/#8 四个通用兜底当场复活，`fixNoticeBar`(#6)/`hookNoticeBarBg`(#7)/`hookPolarLightCard`(#8) 三个单控件补丁可以删除（见 P1-1）。
**风险**：低-中。复活 #5 后，深色下任何"亮色 + 不在方案内"的 view 背景都会变成 `bgPage`；它在 `View.setBackground`/`onAttachedToWindow` 上跑，命中面比现在宽得多。建议先只放开 #5 并在日志里保留 `bright bg -> page color`，跑一轮截图对比再放开 #4。

---

## P0-2　十个"值兜底"合并成一个：`resolveBackground(color, dark, evidence)`

**先直接回答"哪些机制本质是同一件事"**（编号沿用你的清单）：

- **A 组 = 「输入一个颜色 → 判亮/彩 → 给一个面色」，本质完全同一件事**：#2（`inlineBgColor` 值兜底）、#3（`remapColorByName` 里的纯黑兜底，其实就是"输入无彩暗色 → 给前景色"的同一台机器换了个输出槽）、#4（`bgColorForDrawable` 的 luma>170）、#5（`fixTinySolidBg`）。**#1 的名字规则是这台机器的高证据分支**（有资源名时不需要猜），#6/#7 是同一判定被"几何/单资源"重新实现的两份，#8 是同一判定被"view 类名"重新实现的一份。
- **B 组 = 「某个具体控件的底强制成某个角色」**：#6（提示条→`bgPage`）、#7（通知条→`bgPage`）、#8（极光卡→`bgCard`）三者目标不同但手法相同，都应被 A 组取代。
- **C 组 = 「按 drawable 名重建/特判」**：#9（`qui_header_tab_*`、`checkBoxRingOverride`、`lbb/lbd/lbc`、`c0q`、`MINE_GRID_ICONS`…）。这不是底色映射，是**重建 drawable**，应保留，但要收进同一张"名字 → 策略"表（现在是 `tintDrawable` 里 8 个平铺 `if`，L8559-8615）。

**A 组的 10 份实现清单如下**（输入、判据、输出高度重叠）：

| # | 位置 | 输入 | 判据 | 输出 |
|---|---|---|---|---|
| a | `TokenMapper.computeInlineBgColor` L127-152 | 内联颜色 / 混淆短名 | 品牌蓝常量 → primary；否则 `HCT.chroma<8 && tone>=60` | `BG_CARD`=`surfaceBright` |
| b | `TokenMapper.bgColorForDrawable` L162-197 | drawable 颜色 | 品牌蓝 → GUEST_BUBBLE；`dark && luma>170` → PAGE；`chroma<8`: `tone<50`→`surfaceContainerHigh` else `surfaceContainer` | 3 种 |
| c | `TokenMapper.inferByColor` + `grayToRole` L218-250 | 白名单内但无规则的名字 | 品牌蓝 → primary；`chroma<8`：`tone<4/>96` 不动，其余 6 段灰阶 | 6 种 |
| d | `mappedBitmapBgColor` L6554-6560 | 位图/九宫格采样值 | 红 → primary，否则转发 a | PRIMARY / CARD |
| e | `fixTinySolidBg` L6003-6027 | `view.background` | `!isSchemeColor && luma>170` | `BG_PAGE`=`surfaceContainer` |
| f | `loginPageMonetizePass` L2238/L2248/L2330 | 登录页背景 | `luma>=120` / `>=220` | `surfaceContainer` |
| g | `recolorContainer` L4640 / `recolorDrawable` L4648-4666 | 容器子项 | `luma>=235` | `surfaceBright` |
| h | `hookMineGrid` L712-717 / `hookProfileContentCard` L3836-3842 | 卡片底 | `luma>=235`（alpha 条件各写各的） | `surfaceBright` |
| i | `dialogMonetizePass` L963-967 / `tintEmoButtonIn` L1086-1090 | 小图标位图主色 | `luma<160` / `>=160` | `onSurfaceVariant` |
| j | `hookStatusBar` L5738 | 状态栏色 | `luma>140` | `bgCard` |

**问题（不只是重复，是结果不一致）**
- a 与 b 对**同一个 `#FFFFFF`** 给出**不同角色**：a→`BG_CARD`(`surfaceBright`)，b→`surfaceContainer`。于是"白色页面底"经 `Resources.getColor` 进来和经 `View.setBackground` 进来是两个颜色 —— 这正是 L488-492 注释里抱怨的"顶栏与下方割成两截"的同一类病。
- 同一个概念用了 3 种颜色度量：HCT `tone`（a/c）、BT.601 `luma`（b/e/f/g/h/j）、chanel-spread chroma（a/b/c/h）。`TokenMapper.kt` L185 甚至内联重写了一遍 `299/587/114`。
- "亮"的阈值有 7 个：120 / 140 / 160 / 170 / 220 / 235 /（HCT）tone 50-96。
- `NinePatchDrawable` 分支用 a（L6487），`BitmapDrawable` 分支用 b（L6469），`Skinnable*` 用 d→a（L6587/6619）——**同一个采样值走三个不同的兜底函数**。
- 角色枚举里 `BG_PAGE`/`BG_LIST`/`BG_AIO`/`BG_NAV_TINT` 四个值在 `resolve()` 里解析成**同一个** `surfaceContainer`（`TokenMapper.kt` L302-306），也就是说这套角色名对这四个语义**不携带信息**，却让调用方以为自己在区分语义。

**具体改法**

新建 `core/BgResolver.kt`，把判定收敛成一次纯函数调用：

```kotlin
enum class BgSlot { KEEP, PAGE, CONTAINER, CONTAINER_HIGH, CONTAINER_LOWEST, CARD, PRIMARY, GUEST_BUBBLE, TEXT }

/** 证据强度从高到低：NAME > TYPE > GEOMETRY > VALUE */
sealed interface BgEvidence {
    @JvmInline value class Name(val res: String) : BgEvidence     // 资源名 → TokenMapper.roleOf
    object Typed : BgEvidence                                     // drawable 类型已确定是"底"
    @JvmInline value class Geometry(val w: Int, val h: Int, val y: Int) : BgEvidence
    object Bare : BgEvidence                                      // 内联颜色，语义未知
}

/** 全局唯一的“背景值 → 角色”判定。所有入口（Resources/Context/setBackground/attach/dispatchDraw）都调它。 */
fun resolveBackground(color: Int, dark: Boolean, ev: BgEvidence): BgSlot
```

判定阶梯（**写在一处、按固定顺序**，替换现在散落的 10 份）：
1. alpha==0 → KEEP；`tokenFloorReserved`(品牌固定白/蒙层/mask) → KEEP
2. `isSchemeColor` → 已在方案内，返回当前角色
3. 证据是 `Name` 且 `roleOf(name)` 命中 → 该角色（现有 `computeRole` 原样搬进去）
4. 精确品牌常量 → 按证据类型给 `PRIMARY`(Bare/Typed) 或 `GUEST_BUBBLE`(drawable 底)
5. 彩色且**不亮** → KEEP（今日行为）
6. 其余（无彩 或 亮）→ 按 `tone` 一张表落到 `PAGE/CONTAINER/CARD`，**只保留一套阈值**

各调用点改为：
```kotlin
when (BgResolver.resolveBackground(c, dark, BgEvidence.Typed)) { ... }
```
- `computeInlineBgColor` / `bgColorForDrawable` / `inferByColor` 合并后**只留一个** `resolveBackground`（`inferByColor` 独有的是"纯黑纯白不动"，作为阶梯第 1.5 步表达）。
- `fixTinySolidBg` 变成 `resolveBackground(color, dark, Typed) == PAGE -> v.setBackground(GradientDrawable().apply{setColor(bgPage(dark))})`，与 `fixNoticeBar` 共用同一个 `applyPageBg(view)` 小工具（现在这段 4 行代码在 L6017-6020、L6047-6050、L6745-6748、L3818-3824 出现 4 次）。
- `recolorContainer`/`recolorDrawable`/`forceSolidDrawableColor`/`recolorButtonState`/`forceGradientColor` 五个"写色分发器"合并为 `applySolidColor(drawable, color)`（`recolorButtonState` L4103-4131 是严格超集，含 `mStrokePaint`/`mStrokeColors` 反射，取它做实现）。

**收益**：删除 ≈5 个函数体、10 处内联判据，颜色结果全局一致；新控件不匹配名字时**自动**落入同一兜底，不再需要新补丁。
**风险**：中。合并必然改变某些当前"因路径不同而恰好正确"的颜色。建议保留旧函数为 `@Deprecated` 薄壳，按 P0-1 复活门禁后逐页对比截图（仓库 `shots/` 已有基线）。

---

## P1-1　删掉 #6/#7/#8 三个单控件补丁，它们已被 #5 + 名字规则覆盖

| 位置 | 问题 |
|---|---|
| `fixNoticeBar` L6039-6079 + `hookAttachedNoticeBar` L6083-6102 + `hookPanelDispatch` L1767-1769 | ①几何判据 `width>=1250 && height in 100..140 && y in 250..380` 是**按 1344×2992 这台机器标定的**（`shots/*.png` 全是 1344×2992）：一行 1080px 宽的常见机型上 `width>=1250` **永不成立**，补丁整体失效。②它被 `dispatchDraw` **每帧**调用（L1767，作者注释 L6045 自认），命中后每帧 `walkViewTree(v,30)` 遍历子树找图标。③`hookAttachedNoticeBar` L6092-6097 的 `if/else` 两个分支**代码完全相同**（都是 `v.post { fixNoticeBar(v) }`），而 `fixNoticeBar` 自己又会重判几何 → 整个判断是空操作。 |
| `hookNoticeBarBg` L5949-5970 | 目标资源名 `qui_tui_common_bg_page*` 已被名字规则覆盖：`TokenMapper.computeRole` L483 `n.contains("bg_page") -> Role.BG_PAGE`。真机日志里就有 `tint drawable qui_tui_common_bg_page_bg -> #ff001c2a`。这个 hook 把**返回值**无条件 `setTint(bgPage)`，等于用单控件规则盖掉通用名字规则；门禁又是死的（P0-1）。 |
| `hookPolarLightCard` L5917-5938 | 按类名 `contains("PolarLight")` 统一成 `bgCard(true)`。它要解决的问题（同页卡片两色）在门禁失效时依旧存在；而正确修法是让极光位图走通用 `Typed` 兜底得到**同一个** CARD 角色，而不是加一个类名分支。 |

**改法**：复活 #5（`fixTinySolidBg`）后删除 `fixNoticeBar`/`hookAttachedNoticeBar`/`hookPanelDispatch` 里的提示条特判，以及 `hookNoticeBarBg`、`hookPolarLightCard`。若确实需要"某些卡片统一成 CARD"，把它表达为 `BgEvidence.Name` 的一条规则，而不是 view 类名 hook。
**收益**：删 ≈110 行 + 去掉每帧子树遍历 + 去掉一个 O(帧) 的热路径。
**风险**：中。删 #6 前必须确认真机上那条提示条能被 #5 覆盖（#5 依赖 `colorOfDrawable`，而该提示条是 `SkinnableNinePatchDrawable`，`colorOfDrawable` L9290-9295 只认 ColorDrawable/GradientDrawable → 会返回 0 → **不覆盖**）。所以顺序是：先给 `colorOfDrawable` 换成 `solidColorOf`（见 P2-1），再删 #6。

---

## P1-2　`setBackground` 挂了 4 次、`onAttachedToWindow` 挂了 12 次

**位置**

| 被 hook 的框架方法 | 安装点 |
|---|---|
| `View.setBackground(Drawable)` ×4 | L3827 `hookProfileContentCard`、L5976 `hookSetBackground`、L6133 `hookViewBackground`、L6739 `hookAioEditText` |
| `View.setBackgroundColor(int)` ×2 | L6254 `hookViewBackground`、L6754 `hookAioEditText` |
| `View.onAttachedToWindow()` ×12 | L1653、L1844、L1872、L1972、L3892、L4327、L5649、L5871、L5894、L6084、L6800、L7928（旧日志里还有第 13 个 `wallet window guard`） |
| `ImageView.setImageDrawable` ×2 | L5006 `hookImageViewRedDot.handleImage`、L5920 `hookPolarLightCard` |

**问题**
- 每次 `setBackground` / 每次 view attach，**12 个拦截体全部执行**（各自 `chain.proceed()` + 自己的字符串判断）。
- 结果依赖**安装顺序**：4 个 `setBackground` 拦截器都会在 `proceed()` 后改同一个 drawable 实例，谁最后改谁赢；`hookAioEditText` 还会 `chain.proceed(arrayOf(gd))` **替换入参**，让内层的通用兜底去处理它造出来的 drawable。
- `fixTinySolidBg` 在 `hookSetBackground`(L5986) 与 `hookAttachedTinyBg`(L5903) 里**被调用两次**；而它内部又调用 `v.setBackground(gd)`，形成**重入**：这个调用会再次穿过 4 个 setBackground 拦截器。当前只靠"替换色恰好是方案色 → `isSchemeColor` 命中 → 提前返回"避免无限递归；一旦有人把 `bgPage(true)` 换成非方案色，就是无限递归 + 每次新建 GradientDrawable → StackOverflow。
- L6259-6263 同一函数里连着两次判同一条件（复制粘贴残留）：
  ```kotlin
  if (isThirdPartyUiActive()) return@intercept chain.proceed()
  val view = chain.thisObject as? View
  if (isMonetExemptUi(view) || isThirdPartyUiActive()) { return@intercept chain.proceed() }
  ```

**改法**（这是"更通用的 hook 点"的核心答案）
1. **一个 attach 入口 + 修补器列表**：
   ```kotlin
   private interface ViewFixer { val id: String; fun onAttach(v: View) }
   private val attachFixers = listOf(TinySolidBgFixer, TextContrastFixer, HeaderTabTextFixer,
                                     InputHintFixer, FileIconFixer, ...)
   // 只安装一次 View.onAttachedToWindow
   ```
   作者自己在 L5888-5892 已经总结出正确方向（"前几轮分别挂在 tintDrawable / setBackgroundDrawable / setBackground 上都没命中……这里不再关心它是怎么设进来的，只看最终状态"）——**把 attach 时的"最终状态检查"提升为主机制，而不是第 5 个补丁**。
2. **一个 `setBackground` 入口**，内部按 view 类型分派：`AIOEditText` → 输入框修补器；`isProfileContent` → 资料卡修补器；其余 → 通用兜底。AIO 用 `proceed(arrayOf(gd))` 的"替换入参"手法改成"proceed 后改 drawable"，避免污染内层链。
3. `fixTinySolidBg` 里改为 `applyPageBg(v)`，并在最前面加 `if (tag == APPLIED_TAG) return` 的幂等标记，去掉对"颜色恰好等于方案色"的隐式依赖。
**收益**：框架方法拦截点从 20 个降到 3 个；每次 attach 的固定开销降到 1/12；顺序不确定性消失。
**风险**：中。合并需要给每个修补器加 `appliesTo(view)` 前置判断，否则会把仅对特定页面生效的修补器扩散到全局（例如 `hookAioEditText` 的 hint 强制只能对 `AIOEditText` 生效）。建议一个一个搬，每搬一个跑一轮。

---

## P1-3　"深灰文字 → 提亮"规则写了 5 遍，且守卫条件互相矛盾

| # | 位置 | 判据 | 守卫 |
|---|---|---|---|
| 1 | `remapColorByName` L9349-9373 | `spread<=24 && luma<70` → onSurface；名字含 divider/separator/line/border → outlineVariant | — |
| 2 | `darkTextFallback` L9298-9307 | `spread<=24 && luma<70` → onSurface | 无 divider 分支（比 #1 少一半逻辑，却作为独立函数存在） |
| 3 | `hookDarkTextColors` L8043-8059（CSL 分支） | `spread<=24 && luma<170 && !isSchemeColor` → `luma<70 ? onSurface : onSurfaceVariant` | **用 isSchemeColor 过滤** |
| 4 | `hookDarkTextColors` L8115-8157（int 分支） | 同上，多一个 `0xFF000000/0xFFFFFFFF` 特判走 onPrimary 胶囊 | **用 isSchemeColor 过滤** |
| 5 | `fixLowContrastText` L5674-5718 | 同 #3/#4，另加"祖先链要有深色背景" | **故意不用 isSchemeColor**，L5691-5694 注释说明"纯黑会命中方案里的面角色，于是黑字被当成已染跳过" |
| 6 | `mapPopupTextColor` L1260-1283 / `remapDarkSpans` L4669-4693 | 同族规则（文本色 / span 色） | — |

**问题**
- 同一个决策 6 份实现，三处阈值不同（`luma<70` vs `<170` vs `<140`），`isSchemeColor` 这个守卫在一处被明确论证为**有害**、在另两处仍然使用 —— 同一份规则内部自相矛盾。
- `darkTextFallback`(L9298) 与 `remapColorByName` L9349-9373 几乎是逐行副本（只差 divider 分支），却一个被 `TypedArray.getColor` 走 L9194 调用、一个走 L9371，两条路径对同一颜色可能给出不同结果。

**改法**：`TokenMapper` 增加
```kotlin
fun foregroundSlot(color: Int, dark: Boolean, isLine: Boolean): Int?   // null = 不动
```
把"spread/luma 阈值 + 线类特判 + 纯黑特判"收在一处；6 个调用点各自只负责"取色 → 调它 → 写回"。守卫统一采用 #5 的结论（**不用 isSchemeColor**，改用 `luma >= 170` 判"已够亮"，因为映射幂等）。
**收益**：删 ≈45 行；顺带修掉"长按菜单/钱包设置页文字提亮不一致"这类隐性差异。
**风险**：低。这里是纯函数抽取，语义可逐条对照。

---

## P2-1　"从 drawable 取纯色"有 3 个版本，应只留 `solidColorOf`

| 位置 | 能力 |
|---|---|
| `solidColorOf` L4582-4630 | **超集**：Gradient/Color/DrawableContainer(先末位非空再全量)/Layer/Bitmap/NinePatch/Skinnable* |
| `gradientColorOf` L5379-5402 | 子集（少 Color/Bitmap/NinePatch/Skinnable），调用点 L6897、L6982、L8130 |
| `colorOfDrawable` L9290-9295 | 更弱的子集（只 Color/Gradient），但被 **`fixTinySolidBg` L6012** 使用 → 这正是不覆盖 `SkinnableNinePatchDrawable` 提示条的原因 |

**改法**：删 `gradientColorOf`、`colorOfDrawable`，全部改调 `solidColorOf`；`fixTinySolidBg` 的入参判定随之增强。
**收益**：删 ≈25 行；直接解掉 P1-1 里"删 #6 之前必须先做"的阻塞项。
**风险**：低-中。`solidColorOf` 对 DrawableContainer 会拿"末位非空子项"，与 `gradientColorOf` 的"正序第一个命中"不同；调用点只有 3+2 个，逐个确认即可。

---

## P2-2　光栅化/取色样板：9 份 6 行 preamble，3 处 Bitmap 泄漏

**位置**：`computeBrandLogoV2` L488-493、`computeBrandLikeImage` L598-605、`rasterizeIconColor` L3128-3135、`glyphStats` L4769-4777、`glyphColorStats` L4831-4838、`renderSample` L6701-6706、`tintFileCircleIcon` L8330-8337、`tintBrandLogo` L8392-8397、`rasterizeIconUniform` L8730-8737。

**问题**：同样的 `intrinsic → guard → createBitmap(ARGB_8888) → Canvas → 存 bounds → setBounds(0,0,w,h) → draw → 还原 bounds`，9 处；尺寸上限 5 种（1000/700/400/240/200）；bounds 存取 3 种写法（`copyBounds()` / `Rect(drawable.bounds)` / `Rect(bounds)`）；采样 2 种（`getPixel` 3×3 / 整幅 `getPixels`）；**只有 `renderSample` L6709-6711 在 `finally` 里 `recycle()`**，`tintFileCircleIcon`/`tintBrandLogo`/`rasterizeIconUniform` 的大 bitmap 不回收。
另外"取主色"有 3 套互不相同的实现：`sampleBitmapColor`（9 点 `getPixel` + alpha>200 均值）、`renderSample`（≤16px 栅格化后转前者）、`sampleBitmapColorOfDrawable`（L5371-5376，仅为避免对 BitmapDrawable 做栅格化而存在，与 `sampleBitmapColor` 自身的空/类型处理重复）。

**改法**：
```kotlin
private inline fun <T> withRendered(d: Drawable, maxPx: Int, block: (Bitmap) -> T): T?   // 含 recycle
private fun dominantColorOf(d: Drawable, maxPx: Int = 16): Int?                          // 取代上面 3 个
private class PixelStats(core: Int, colorful: Int, meanLuma: Int, warm: Int, dark: Int)  // 取代 glyphStats/glyphColorStats
```
**收益**：删 ≈60–80 行 + 修 3 处泄漏（`rasterizeIconUniform` 每次面板绘制都可能分配 ≤240×240×4B）。
**风险**：低（纯工具抽取）；注意 `withRendered` 的 `recycle` 会让"把 bitmap 交给 `BitmapDrawable`"的路径失效（`rasterizeIconUniform` L8801 需要复制而非回收），所以 block 返回 Drawable 时要保持现状。

---

## P2-3　12 个 attach 之外，还有 12 个手写 view 树遍历

**位置**：共享 BFS `walkViewTree` L1209-1227（14 个调用点，`maxNodes` 取 30/32/48/200/400/600/3000 共 7 种值）；另有手写 BFS `insideProfileRootTree` L4045、`fixProfileRowTexts` L4456、`forceProfilePageCards` L4504、`forceMonetQuickMenu` 第二遍 L2507、`protectUnreadSubtree` L5621、`scanFileIcons` L7957（后者与 `walkViewTree` 逐行相同）；DFS 4 份：`forceMonetSubtree` L1241-1243、`forceTimelineText` L2763-2776、`recolorTextViews` L3423-3428、`hookMineGrid` 内的局部 `fun walk` L754-758；向上找祖先 23 处、深度上限 2/3/4/5/6/8/8/10 共 8 种（其中 4 处是同一段"Reply 祖先"循环：L2917-2927、L2934-2944、L2947-2961、L2964-2975）。

**附带 bug**：`walkViewTree` 的 KDoc L1206-1208 声称"`visit` 返回 true 表示已处理并跳过其子树"，但签名是 `(View) -> Unit`，且 L1220-1224 **先压子节点、后调 `visit`**（L1225），任何调用方都无法剪枝 —— `return@walkViewTree` 只跳过当前节点。统一遍历器之前必须先定义剪枝语义。

**改法**：补 `forEachAncestor(view, maxDepth) { }` / `firstAncestor(view, maxDepth) { pred }`；`walkViewTree` 增加 `prune: (View) -> Boolean` 参数并**把压子节点移到 `visit` 之后**；6 个手写 BFS/DFS 全部改调它，`maxNodes` 收敛为常量。
**收益**：删 ≈90–110 行；遍历语义一致（现在"最大 200 节点"和"最大 400 节点"的差异没有依据）。
**风险**：中。`maxNodes` 收敛会改变深层页面的覆盖范围，需要按页面回归。

---

## P2-4　名字规则与值兜底的**优先级倒挂**（补丁互相打架的最典型例子）

**位置**：`remapColorByName` L9328-9375。

```
knownName = name 以 qui_ / skin_black / skin_gray / skin_input_theme / skin_color_title / troop_aiosm 开头
if (!knownName) { inlineBgColor(...) → darkTextFallback(...) }      // 值兜底
else            { TokenMapper.mapColor(name, color, dark) }          // → roleOf 未命中时落 inferByColor
```

**问题**：`inferByColor` 对"无彩且 `tone>96`"的**近白直接原样返回**（L230 `if (tone < 4.0 || tone > 96.0) return color`），而 `inlineBgColor` 对近白映射成 `BG_CARD`。于是：
- 名字**不以** `qui_` 开头（混淆短名 `al3`/`2p`）的白色 → 被修好；
- 名字**以** `qui_` 开头但没命中任何规则的白色 → **保持白色**。
"更像我们自己人"的名字反而得到更差的处理，与白名单的意图相反。日志里 119 个 `hook installed:` 之外存在 `drawable no-rule: qui_tui_bottom_bar_background` 这样的记录，正是这一类。

**改法**：`remapColorByName` 收敛成一条链，不再按前缀分叉：
```kotlin
val role = name?.let { TokenMapper.roleOf(it) }
val mapped = when {
    role != null -> resolveRole(role, color, scheme)
    else -> TokenMapper.inlineBgColor(color, dark) ?: TokenMapper.foregroundSlot(color, dark, isLine(name)) ?: color
}
```
即"名字命中就按名字，否则一律走同一个值兜底"——白名单只用来决定**日志与缓存**，不再决定**走哪条规则**。
**收益**：消除"同名不同命"，`qui_*` 未命中规则的资源自动获得兜底；删 ≈20 行。
**风险**：中。会有一批以前"幸免"的 `qui_*` 近白背景变成面色 —— 这正是想要的效果，但需要一轮截图对比确认没有把图标底/蒙层带下水（`overlay_`/`text_white` 已有 KEEP 规则在 L529）。

---

## P3-1　`isSchemeColor` 与 `isSurfaceColor` 是同一件事的两份

`isSchemeColor` L6500-6522（17 个角色全比）与 `isSurfaceColor` L5462-5476（9 个角色）逐行同构，后者是前者子集，只被 `findRowCardColor` L5438 用一次。合并为 `isSchemeColor(color, dark, surfacesOnly = false)`。
**收益**：删 ≈15 行，顺带修掉"新增角色只加在一处"的漏改风险。**风险**：极低。

## P3-2　"亮"阈值与内联 luma 散落

`299/587/114` 被内联重写 4 处：`TokenMapper.kt` L185、`TimMonetHooks.kt` L6014、L8781、L8794（`colorLuma` L7358-7363 已存在）；"无彩"判定 10 处、容差 9 种（24/30/40/50/60…）；"亮"阈值 7 种。"近白 235"在 L714、L3840、L4537、L4640 写了 4 遍，其中 3 处用 `x or 0xFF000000.toInt()` 而绕过了 `opaqueColor` L1204。
**改法**：把阈值提成命名常量（`LUMA_BRIGHT_BG=170`、`LUMA_NEAR_WHITE=235`、`CHROMA_GRAY=8/24`），并统一用 `colorLuma`/`opaqueColor`/`isNearWhite`。
**收益**：删 ≈30 行；消除"为什么这里 170 那里 235"的反复试错。**风险**：极低（改名不改值）。

## P3-3　死状态与共用计数器（复制粘贴痕迹）

- `headerTabDrawables`（L5828）只在 L8593 写入、**从不被读取** → 删。
- `brandLogoLogCount`（L8383）被 `computeBrandLogoV2` L552 与 `tintBrandLogo` L8438 **共用**，两边互相把对方的日志额度吃掉；`tabIconLog`（L5823）同样被 L5846 与 L8594 共用。
- 48 个 `private var xxxLogCount = 0` 手写限流计数器 → 一个 `logAtMost(key, n, msg)`。
**收益**：删 ≈60 行。**风险**：极低。

## P3-4　登记的缺口（不是重复，是漏了）

- **`common_tips_bg_white` / `common_tips_bg_blue`**：TIM 里出现在 `layout/b0.xml`、`layout/gc.xml`、`layout/sl.xml` 的 `android:background` 与 `VideoStatusTipsBar.java:215` 的 `setBackgroundResource`，且被 `ResGuardIgnoreCollection` 列为**皮肤引擎不处理**的资源（即深色皮肤下仍是原色）。模块里**没有任何规则**：名字不以 `qui_`/`skin_` 开头，会被 `tintDrawable` L8616-8629 的白名单门禁**原样返回**。用户清单里提到的"common_tips_bg 分支"实际不存在。→ 应补一条 `common_tips_bg*` → `BG_CARD`（蓝底那支 → `PRIMARY_SOFT`），或让 P2-4 的改造自动覆盖它。
- `qui_tab_*` 只在 `TokenMapper.kt` L541-543 处理，没有走 `tintDrawable` 的短名/特征路径。
- `scanFileIcons` L7977 用 `cachedField(d.javaClass, "mBitmap")` 从 **drawable** 上取 `mBitmap`，而其它 3 处都从 `mBitmapState` 上取（L6582/L6606/L4278）→ 该分支很可能永不命中（`SkinnableBitmapDrawable` 自身无 `mBitmap` 字段）。需一次日志确认后删除或改写为 `solidColorOf(d)`。

---

## 建议的落地顺序（每步独立可验证）

1. **P0-1**：`MonetPalette.isDarkNow()` + 替换 7 处"用返回值做判断"的调用 → 观察 `bright bg -> page color` 是否开始打日志；截图确认极光卡片恢复一致。
2. **P2-1**：`colorOfDrawable`/`gradientColorOf` → `solidColorOf`（低风险，为下一步解锁）。
3. **P1-1**：删除 `fixNoticeBar` / `hookAttachedNoticeBar` / `hookNoticeBarBg` / `hookPolarLightCard` 与 `hookPanelDispatch` 里的提示条特判。
4. **P1-3**：抽 `foregroundSlot`，消掉 5 份文字规则（含 `darkTextFallback`）。
5. **P0-2**：落地 `BgResolver`，把 a–j 十个判据收敛成一个阶梯；`TokenMapper` 只保留 `roleOf`/`resolve`。
6. **P1-2**：合并 12 个 attach / 4 个 setBackground 为一个 dispatcher（修补器列表）。
7. **P2-2/P2-3/P3-***：工具化清理（`withRendered`、`forEachAncestor`、常量、死状态、计数器）。

前 3 步做完，用户清单里的 #4/#5/#6/#7/#8 会收敛成**一个** `resolveBackground(..., Typed)` + **一个** attach 兜底；#1 名字规则保留为阶梯里的"高证据"分支；#2 精确品牌蓝保留为常量分支；#3 的 `inferByColor` 被吸收；#9 的 `qui_header_tab_*` 与 `checkBoxRingOverride` 属于"重建 drawable"型（不是底色映射），可保留为 `tintDrawable` 里的具名策略，但应归入同一张"名字 → 策略"表，而不是散落的 `if`。

---

### 附：本次审查产出的文件
- `.audit/review-priority-list.md`（本文件）
- `.audit/duplication-audit-1-4900.md`（L1-4900 重复度明细，由子审计产出）
