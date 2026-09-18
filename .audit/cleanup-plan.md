# TimMonet 代码审查汇总（可执行清单）

> 来源：三份并行审查（`TimMonetHooks.kt` 1–5000 / 5000–10370 / core 与设置层）
> 原则：**能通用就通用，能干净就干净**。按"静默失效/静默错误"→"重复实现"→"清理"排序。

---

## 〇、2026-09-19 收尾：全量死代码扫描（已完成，改动见对应提交）

扫描口径：① 私有声明**可达性**（以私有体之外的代码为根沿调用图传播）；
② 形参在函数体（剔注释后）是否出现；③ 公开函数有无调用点；④ 资源/字符串是否被引用；
⑤ 编译器警告里的"恒真分支 / 冗余转换 / 多余断言"。结果与处置：

| 发现 | 处置 |
|---|---|
| `PAY_PWD_DOT_COLOR` 常量只有声明（`@Suppress("unused")`，注释说"只作记录"） | 删常量，色值记进 `hookPayPwdGridSetColor` 的注释 |
| `MonetPalette.palette(dark)` 重载零调用（`@Deprecated` 兼容壳） | 删；教训并入 KDoc + 坑 6 |
| `MonetPalette.seed()` / `context()` / `seedColor` 零调用 | 删（`appContext` 内部仍在用） |
| `ThemeState.kt` 整个文件零调用 | 删文件（教训在 AGENTS「坑 1」） |
| `TimMonetSettings.serialize/parse/PROVIDER_AUTHORITY` 零调用（无 provider 声明） | 删；类 KDoc 里那句 `[SettingsProvider]` 是悬空引用，已改写成"没有 ContentProvider" |
| `BgResolver.DIM_TEXT` 零引用（`DIM_TEXT_STRONG` 才是被用的那档） | 删，AGENTS 常量清单同步 |
| 未使用 import（`Hct`） | 删 |
| 未使用字符串 4 条（`settings_theme_mode_amoled`/`settings_auto_color`/`_summary`/`settings_apply_hint`） | 删（`apply_hint` 的内容还是过期的："无需重启"已不成立） |
| **未使用形参 22 个**（`darkTextFallback`/`dialogMonetizePass`/`monetizeForwardPopup`/`hookQuickMenuTheme`/`hookFileDownloadIcons`/`loginPageMonetizePass`/`findRowCardColor`/`mappedBitmapBgColor`/`mapTokenString`/`forwardConfirmDialogMonetize`/`hookResumeRefresh`/`installBackgroundInterceptor` 等） | 全部删除；顺带删掉因此变成孤儿的 9 个局部 `val dark = isDarkNow()` |
| **`dark` 形参整族**（`bgPage/bgList/bgCard/outlineVariant/inputBg/guestBubble` + `mapColor/tintColorFor/inlineBgColor/bgColorForDrawable/mapPageToken`）——只当缓存键或从未被读 | 全部去掉形参：**64 个调用点**同步改；`tintMemoLight/Dark` 合并为 `tintMemo`（M10/C12/C14 收口） |
| `tokenColorMemoized(resId, dark)` / `remapColorStateList(..., dark)`（hooks 内）同样只把 `dark` 当缓存键位 | 去掉形参与键里的 dark 位；`TOKEN_NIGHT` 常量随之无人用，删 |
| `patchInPlace(blockRegex)` 形参从未使用 | **不是纯死代码**：它本该在 MARKER 升版本时剥掉旧补丁块。已接上（见坑 10） |
| 注释位置错误：`Paint.setColor` 的 KDoc 挂在 `hookPayPwdPaints` 上、AIOEditText 的横幅注释挂在支付密码段 | 各自移回对应函数 |
| 编译器标记的冗余：2 处 `as ViewGroup`、2 处 `!!`、1 处 `?.`、1 处多余 cast | 删（另有 1 处 `decorView ?: return` 是平台类型恒真判断，作为防御保留） |

**同时确认"看起来可疑但不是死代码"的**：`applyReplyJumpIcon(reason)` / `recolorReplyText(reason)` /
`installBackgroundInterceptor(label)` / `tintFileCircleIcon(name)` / `logWhiteSource(source)`
—— 这 5 个形参都用在日志字符串里（脚本剔注释后仍误报，已逐条人工核实）；
`ColorMode.DARK_AMOLED`（旧值迁移用，保留）；`isLightLeftover(color, dark)`（唯一真用 dark 的判据）。

---


## 一、已修（本次审查中当场发现并修复）

### ✅ fixTinySolidBg 是空操作（规则从未生效，却打出"已改成页面底色"的日志）
- **位置**：`TimMonetHooks.kt` 6435-6457（修复前）
- **问题**：构造了 `GradientDrawable` 却**从未 `setBackground`** —— 整条"亮底压暗"规则一直在空转，只写日志。
- **成因**：加临时诊断脚本时误删了那行。
- **已改为**：就地改色（`d.mutate()` + `setColorFilter`/`setTint`），**保留原 drawable 的形状**（圆角/九宫格留白/多状态），日志降为 `logOnce`。
- **教训**：这类"行为与日志脱节"的 bug 排查成本最高 —— 日志说改了，实际没改。

---

## 二、高优先级：静默失效 / 静默错误（换设备或换 TIM 版本才爆，且不报错）

### H1. `scanFileIcons` 用**日志计数器**当功能闸门
- **位置**：8401 `if (fileIconLogCount > 12) continue`；8436 自增
- **问题**：日志额度判断被放在**染色逻辑之前** → 累计约 13 个图标后**永久不再染色**。
- **附带**：8373-8375 的去重集 `fileMonitored` 在 `size > 32` 时整体 `clear()`，连刚加入的自身一起清掉，去重同时失效。
- **改法**：日志额度只包 `Log.i`；去重集改有界 LRU 或 `WeakHashMap<View, Boolean>`，不要整体 clear。

### H2. `findMethod` 参数兜底 + 常量拦截器 = **挂错方法**
- **位置**：`hookForceLight` 2954-2995（6 处）、`hookForwardDialog` 1437-1448、`hookChatsUtils` 217-239、`hookForwardArkConfirm` 1107、`hookSplashBackground` 1389-1417、`hookAlbumTimelineText` 2774-2806
- **问题**：兜底"只按参数类型匹配、不看名字与返回类型"，而这些点又恰好是 **`intercept { false }` / `intercept { "2971" }` / `chain.proceed(arrayOf("2971"))` / 丢结果返回 null** 型拦截器。TIM 一改混淆名，`false` 会被返回给任意同参方法、任意 String 参数会被改写成 `"2971"`、非 void 方法会被返回 null。
- **已有先例**：`QQCustomArkDialogForAio.show` 曾挂到 `dismiss` 上。
- **改法**：凡"常量替换 / 改写参数 / 返回 null"一律用 `findMethodStrict`（继承链用 `findMethodStrictDeep`），MISS 时 `logOnce`。

### H3. 硬编码**数字资源 id**
- **位置**：`forceTimelineText` 2830-2832（`2131308278`/`2131308279` = `id/qa2`/`id/qa3`）；`hookChatsSummaryHighlight` 3979（`0x7f060b6d` = `color/qui_common_feedback_error`）；`hookTitleBarLeftButton` 1947（`(id ushr 24) == 0x7f`）
- **问题**：资源 id 每次重打包/混淆都会变。现在恰好对上**纯属版本巧合**；一旦偏移，2831 会把**别的 View 的背景**染成 primary（视觉污染），而不是"不生效"。
- **改法**：用已有的 `entryName()` / `getIdentifier()` 按**名字**取 id，集中成常量，取不到 `logOnce` 跳过；染色前再校验子 View 类型/文本。

### H4. 手工 `new Drawable` 替换原背景 → **丢圆角/padding/insets/皮肤令牌**
- **位置**：`fixPlusItemPlate` 1556 + `buildPlusItemPlate` 1526-1539（12dp 写死）、`tintPillBackground` 3754-3758（10dp 写死）、`cardFactory` 4041-4049、`fixThinBrightLine` 6263-6266、`fixNoticeBar` 6488-6491、气泡 4487-4495 + 4619
- **问题**：一律"新建矩形顶上"，原 drawable 的圆角、insets/padding、9-patch 拉伸、皮肤令牌全部丢失；12dp/10dp 是与某版 TIM 对齐的魔数。
- **已知事故**：设置页卡片四个圆角被削平。
- **改法**：抽 `applyBackgroundColorKeepingShape(v, color)` —— 优先 `mutate()` + `setTint`/`setColorFilter`；必须替换时先复制 `getCornerRadii()`/`getPadding()`。

### H5. `solidColorOf` 在多状态/容器 drawable 上取到的是"**最后一个 state**"的颜色
- **位置**：定义 4768-4816；调用点 6260、6430、6359
- **问题**：`DrawableContainer` 分支取"最后一个非空子项"（通常是 default/未选中态），`LayerDrawable` 取"第一层"，都与当前绘制态无关。
- **影响**：selector 的某个白色态被误判成"浅色遗留亮底" → 触发 H4 替换 → 误伤；反之漏判。全程静默。
- **改法**：新增 `currentStateColorOf()`：`StateListDrawable` 用 `current` 取当前子项，`LayerDrawable` 取最上层不透明层，**取不到返回 null**（宁可不处理）。

### H6. `onSetBackgroundArg` 把 `setBackground(null)`（清背景）当成"近白"替换成实色卡面
- **位置**：`hookProfileContentCard` 4051-4062（`cardFactory` 4041-4049；消费方 dispatcher 6162-6183）
- **问题**：`incoming == null` → `nearWhite = true` → 返回 `cardFactory()` → 任何"清空背景"变成一块 **surfaceBright 纯色矩形**（且无圆角）。另外 `profileUiCount` 只在 attach/detach 增减，页面驻留不 detach 时计数不回零 → 规则漏到别的页面。
- **改法**：`null` 直接 `return null`；门控改成"当前是否资料卡页"而不是全局计数。

### ~~H7. `hookChatsUtils` 无条件覆盖 TIM 算出的颜色~~ —— **设计如此，不处理**

> **2026-09-14 用户确认**：这是**有意的设计**。TIM 把"错误红"当成很多地方的**提示色**
> （不止未读），所以我们要无条件覆盖它，不能保留 TIM 的原值。
> **后续审查若再报这一条，直接跳过。**
- **位置**：217-240
- **问题**：只要结果是 Int 就整替换成 `bgList`/`bgCard`，丢弃 pressed/selected/"有人@我"红行等**状态色**；方法改名后兜底还会挂到"第一个无参方法"上。
- **改法**：以原返回值为**输入**做映射（仅当判定为亮色遗留时替换）；改 `findMethodStrict`。

### H8. 靠**类名子串** / **中文文案**识别控件
- **类名子串**：`PolarLight` ×3（5106/6285/6416）、`pluspanel`（1841/4990）、`Reply`（3140-3187）、`profilecard`（1739-1741 等）、`UnreadBubble`（4531）、`AIOFile`（8372）、`filemanager`（6036）、`GesturePWD`（9789）
- **中文文案**：`"发送给："`（970）、`"打开键盘"/"打开表情面板"`（1156）、`"删除/置顶/…"`（2063-2066）、`"有人@我/有新文件"`（3702-3706）、`"加"/"加好友"`（4185/4195）
- **问题**：换设备/版本/语言后整块静默失效（无 MISS 日志）；子串判据还会误伤（`Reply`/`profilecard` 这类过宽）。
- **改法**：抽 `uiRole()`（单点匹配 + 缓存，照 `plusPanelClassMemo` 1836-1844 的写法）；已知基类用 `Class.forName + isInstance`，子串只作兜底并限定包名前缀；文案集中成常量表并配 `logOnce`。

### H9. 裸像素几何阈值（文件里已有 `dpPx`/`isFullWidth` 却不用）
- **位置**：2404 `w0 >= 300 && h0 >= 300`、2475 `w0 in 1..3`、1046/1167 `in 1..96`、275 `loc[1] > h - 400`
- **问题**：`96px` 在 density 3.0 是 32dp、density 4 只有 24dp → 换设备要么漏染、要么把 36dp 图标当小图标；`1..3px` 在 4x 屏漏掉 1dp=4px。
- **改法**：统一 `View.dpPx()` / `isFullWidth()`；每条几何判据加 `logOnce("geometry miss …")` 让失效可见。

---

## 三、中优先级：重复实现 / 结构问题（"修一个坏一个"的根因）

### M1. 同一框架方法挂多个 hook
| 方法 | hook 数 | 位置 |
|---|---|---|
| `View.setBackground` | **3** | 6163、6302、6560 |
| `View.setBackgroundColor` | 2 | 6681、7190 |
| `ImageView.setImageDrawable` | 2 | 5192、6097 |
| `TextView.setText` | 2 | 3841-3858、3860-3874（单参重载内部会调双参 → **每次 setText 跑两遍**）|

- **问题**：热路径多层 `chain.proceed`，顺序随安装顺序变化；极光卡片被迫用 `postDelayed(150L)` 抢顺序（经验魔数）。
- **改法**：照 `attachHandlers` 模式建 `setBackgroundHandlers` / `imageDrawableHandlers` / `setTextHandlers`，合并 hook，删 `postDelayed`。

### M2. `DrawableContainer` 重染有 **8 个近似实现**
- **位置**：`forceSolidDrawableColor` 2624、`recolorProfileAddFriendBg` 4273、`recolorButtonState` 4302、`recolorSwitchTrack` 4399、`tintSwitchThumb` 4417、`recolorProfileCardContainer` 4754、`recolorContainer` 4819、`recolorDrawable` 4834；另有 **14 处**内联 `runCatching { color?.defaultColor }`
- **问题**：各自解析 `constantState/children`，选子项规则各异（最后一项/倒数第二起/全部），都不判空。
- **改法**：合并为 `walkContainer(d){}` + `solidColorOf()` + `applySolid(d, color)`，子项策略作为参数。

### M3. 亮度/近白/角色色判据多套各写一遍（违反项目约定）
- **位置**：2393 `>= 220` 与 2397 `LUMA_VERY_LIGHT`（同一函数两种写法）、4723/4826 `>= 235`、`isPrimaryColor` 2122-2127 自算 ±45、`isErrorRed` 205-211 自一套阈值、981/2098-2100/1365 直比 `0xFFFFFFFF`、8572 字面量 `170`、9765-9774 只认 `< 70`（丢 70..170 档）
- **改法**：全部改走 `BgResolver.luma/isNearWhite/isLightLeftover/chromaSpan`；`isPrimaryColor` 提升为公共 `isRoleColor(scheme, color)`；裸数字清零。

### M4. "灰暗文字提亮"有 **4 份实现**，阈值各异
- **位置**：5848-5892、8462-8497、8545-8584、9765-9774；另有局部 `isNearWhite`（逐通道 ≥235，8624-8629）与 `TokenMapper.isNearWhite`（luma ≥235）**语义分叉**
- **改法**：`BgResolver` 增加唯一入口 `isDimGrayText()` / `foregroundRoleFor()`，四处改调。

### M5. 账号文本（QQ 号）逻辑写了两遍
- **位置**：`hookLongNumberText` 1765-1783（attach 时机）与 `probeWhiteNumberText` 3817-3825（setText 时机）
- **改法**：抽 `tintAccountText(tv)`。

### M6. `firstSourceBindingColor` 与 `recolorSourceBinding` 重复遍历（前者只服务日志）
- **位置**：3531-3557 与 3653-3685；调用点 3512 只在 `i < 30` 的日志块里
- **改法**：合并 `forEachBindingTextView(...)`；日志改用已设置的颜色值。

### M7. 三个热点缓存是**裸 HashMap** 却被非主线程写入
- **位置**：`redDotDominantMemo` 4940、`warmGlyphMemo` 8964、`tokenColorMemo` 9900
- **依据**：同文件 6019-6021、9123-9125 已明确写过"hook 在图片加载线程执行"并加了锁 —— 这三处漏了。
- **改法**：`ConcurrentHashMap` 或 `synchronizedMap(WeakHashMap)` + 有界淘汰。

### M8. `identityHashCode` 作长期缓存键且不持有对象
- **位置**：`drawableTintMemo` 6736/6770-6775、`redDotDominantMemo` 4940/5117-5130、`warmGlyphMemo` 8964/8885-8892、`protectedUnreadViewIds` 5770/5782/5810、`fileMonitored` 8366/8373
- **对比**：同文件正确做法是 `WeakHashMap` 键对象本身（`rasterizeDenied` 9152、`iconPlateMemo` 9122、`noticeBarPainted` 6461）
- **影响**：GC 后哈希复用 → 新 drawable 被当成"已染过"跳过、无关 View 被永久豁免。
- **改法**：统一 `WeakHashMap` 键对象本身 + 有界淘汰。

### M9. 同一个转发/Ark 弹窗被 **5 条 hook** 反复整树重染
- **位置**：`hookArkDialogBg` 876-883、`hookForwardArkConfirm` 1077-1090 与 1106-1136、`hookEmoticonToggleBtn` 1213-1232、`hookForwardDialog` 1424-1449 → 全部调 `forwardConfirmDialogMonetize`(896) → `dialogMonetizePass`(929)
- **问题**：一次弹窗显示跑 3~4 轮、每轮 4 遍全树，无"已处理"标记；`forceMonetSubtree` 无上限递归(1328)。
- **改法**：只留一个入口 + 按 `MonetPalette.generation()` 标记去重；递归加上限。

### ~~M10. `MonetPalette.palette(dark)` 的参数早就不生效~~ ✅ 2026-09-19 已删（重载已不存在）
- **位置**：调用点 903、930、1398、2286；实现 `MonetPalette.kt` 147-153（实参被忽略，用 `effectiveDark()`）
- **问题**：读起来像"强制浅色方案"，实际跟随设置 —— 历史"时好时坏"型 bug 的温床。
- **改法**：删掉 `dark` 形参（改 `palette()`）。

### M11. `isSchemeColor` 与 `isSurfaceColor` 是两份重叠角色表
- **位置**：6927-6949 与 5648-5662；另外 `isSchemeColor(color, dark)` 的 `dark` 形参实际无效
- **改法**：只留 `isSchemeColor` + `SURFACE_ROLES` 集合，`isSurfaceColor` 改转发。

---

## 四、低优先级：清理

### L1. 临时诊断留在生产路径（**本轮我加的，需删除**）
- **位置**：`install` 342-343（`hookInputIconDiag` / `hookTranslucentBgDiag`）、2916-2949、6375-6407（6382 硬编码 `loc[1] !in 150..700 || v.width < 1000`）、`diagTranslucentBg`、261-289 的 `[bottom el]` 探针（对**每个 attach 的 View** 都 `getLocationOnScreen`）、`[bright-diag]`、`[card-zone]` 等
- **改法**：整段删除。保留的探针放到 `Log.isLoggable(TAG, Log.DEBUG)` 或设置项后面。

### L2. 死代码 / 过期注释
- `glyphColorStats` 5001-5046 从未调用（与 `glyphStats` 4945-4979 重复）
- `tintBrandLogo` 8819-8881 从未调用（实际用 `tintBrandLogoV2`），约 60 行
- `headerTabDrawables` 6018-6024 只写不读
- `isPanelDrawable` 1588 零调用
- `forwardDumpDone` 137-138 + 1276-1279（注释说 dump 已删）
- 未用 import：`ThemeState` 47、`Hct` 39
- `LUMA_NEAR_WHITE` 7798 只出现在注释里
- `if (true)` 残留开关：`hookQuickMenuTheme` 2047
- KDoc 与实现不符多处：6469-6474（旧几何）、5849-5852、`probeWhiteNumberText` 3803-3805
- `tabIconLog` 被 6041 与 9026 两个无关位置共用（一个先耗尽另一个永不打日志）
- `forceBubbleColor` 2852/2862 同一句日志写两遍

### L3. 其他
- 269-271：对每个 attach 的 View 无条件 `post` **三次**同一纠正 → 长列表滚动 3N 条消息
- 4137：为递减一个计数而全局 hook `View.onDetachedFromWindow` → 改挂 `OnAttachStateChangeListener`
- `injectPseudoRule` 第 4 参 `selName` 是死参数（8258-8286）
- `mannounce` 注入 JS：observer 每次变更全量扫描 + `<style>` 无界追加 + 中文标签硬编码（8136-8255）

---

## 五、建议实施顺序

| 批次 | 内容 | 理由 |
|---|---|---|
| **1** | H1（日志闸门）、H6（null 当近白）、L1（删临时诊断） | 都是**小改动**、修的是**明确 bug**、风险低 |
| **2** | H2（严格 findMethod）、H3（资源 id 按名字取） | 防"升级 TIM 后挂错方法/指错资源"，改动机械 |
| **3** | H5（currentStateColorOf）、M3/M4（判据统一到 BgResolver） | 判据层统一，是"能通用就通用"的核心 |
| **4** | M1（合并多 hook）、M2（合并 8 个容器重染） | 结构性重构，收益大但需要仔细验证 |
| **5** | H4（保留形状的背景改色）、M9（弹窗唯一入口） | 触及绘制行为，需逐项截图验证 |
| **6** | M7/M8（线程安全与缓存键）、L2/L3（清理） | 收尾 |

---

# 附录：core 与设置层审查（19 条）

> 来源：第三份并行审查（TokenMapper / MonetPalette / ThemeState / SettingsBridge /
> TimMonetSettings / RemoteSettingsWriter / MainModule）。

## 高

### C1. `TokenMapper.resolve()` 的 AMOLED 分支破坏 alpha 位（真 bug）
- **位置**：`TokenMapper.kt` 281-283
- **问题**：`return 0xFF000000.toInt() or (alpha shl 24)` —— `0xFF000000` 的 alpha 位已全 1，**或运算后恒等于 `0xFF000000`**，`alpha` 根本没保留。
- **对比**：`MonetPalette.kt` 192-197 的 `amoledBlack()` 写的是正确的 `alpha shl 24`。
- **影响**：AMOLED 下所有**半透明**表面色（蒙层/遮罩/`#80FFFFFF`）变成**不透明纯黑**。
- **改法**：`return alpha shl 24`（或共用 `amoledBlack()`）。

### C2. `palette(dark)` 的 `dark` 参数从未被读，但 ~20 个调用点靠它"侥幸正确"
- **位置**：`MonetPalette.kt` 147-153；受影响的缓存键 `TokenMapper.kt` 60/96/17-18；错值调用点 `TimMonetHooks.kt` 9941/9965（把 TIM 的 themeId 当深浅档）、5913/6110/6137/6265/6291/6368-6369/6438/6483/7888/9696（硬编码 `true`）、903/930/1398/2286/6631（硬编码 `false`）
- **问题**：参数被忽略，实际用 `effectiveDark()`；但 `bgColorForDrawable`(187) 又**真用**调用方的 `dark` 做"亮底压暗"分支 —— 颜色来自 A 来源、分支判断来自 B 来源。
- **影响**：一旦有人"修正"这个参数，会同时打挂 ~20 处调用点。
- **改法**：先提供无参 `palette()` 再逐步迁移；`bgColorForDrawable` 的判据改用 `scheme.isDark`。

### C3. `refreshMemoGeneration` 不清 `roleMemo`
- **位置**：`TokenMapper.kt` 565-577（只清 memo/inlineMemo/tintMemo×2），`roleMemo` 在 19/347
- **问题**：`computeRole` 依赖 `MonetPalette.isAmoled()`(406-412) → **AMOLED 开关切换后**气泡/气泡文字角色陈旧，只有重启进程才对。
- **改法**：`refreshMemoGeneration` 一并清 `roleMemo`。

### C4. "颜色→面色"仍是**三套判据**，`BgResolver` 自称统一入口却无人调用
- **位置**：`TokenMapper.kt` 141-143（tone≥60→卡片）、190-195（tone<50→containerHigh）、242-250（12/32/55/76/87）；"是否彩色"一处用 HCT `chroma≥8`、另一处用 `chromaSpan>40`(629)；"亮不亮"混用 **HCT tone** 与 **BT.601 luma**（尺度不可互换）
- **实例**：同一个 `#9A9A9A` 在三个入口分别得到 `outlineVariant` / `surfaceBright` / `surfaceContainer`（tone≈64、luma=154）
- **改法**：`BgResolver`(594-641) 作为唯一入口，TokenMapper 内部改调它。

## 中

### C5. 兜底 scheme 在**调用线程**同步构建
- **位置**：`MonetPalette.kt` 156-167
- **问题**：`palette()` 大量调用点在 `dispatchDraw`/`getColor`/`setBackground` —— 启动几百毫秒内首帧要背一次 scheme 构建（与 104-105 注释想避免的事相反）。

### C6. 取色失败后**永不退避** + 非 volatile
- **位置**：`MonetPalette.kt` 99-103（退避要求 `initialized == true`）、257（失败时置 `false`）
- **问题**：失败路径永不退避，热路径每次调用都 post 一次注定失败的重建；`initialized`/`listenerRegistered` 非 `@Volatile`，在 HandlerThread 写、任意线程读，无 happens-before。

### C7. `inlineMemo` 用 `Int.MIN_VALUE` 当哨兵 → 与合法值撞车
- **位置**：`TokenMapper.kt` 94-102 / 127-152
- **问题**：AMOLED 下 alpha 恰为 `0x80` 的浅色底经 147-148 重组**正好得到 `0x80000000`**（= `Int.MIN_VALUE`）→ 首次返回正确，之后同色一律当"无映射"返回 null；149-151 的 catch 又把异常**负缓存成永久 NO_MAPPING**（无日志）。
- **改法**：哨兵换成独立的包装类型或 `Long` 哨兵。

### C8. `RemoteSettingsWriter` 的 `pending` 推送成功后不清空 → **设置被静默回滚**
- **位置**：`RemoteSettingsWriter.kt` 22-23/34/84-101
- **问题**：服务重绑（XposedService 断连重绑/热重载）会把旧设置重推，而 `TimMonetSettings.kt` 87 把 revision 刷成最新 → 用户设置静默回滚。

### C9. `onBindTasks` 只遍历不移除 → 重复执行 + Activity 泄漏
- **位置**：`RemoteSettingsWriter.kt` 25/35
- **问题**：每次重绑重跑全部历史任务（放大 C8）；闭包捕获 `SettingsActivity` 的 lambda → **Activity 泄漏**；`runCatching` 无 `onFailure` 无日志。

### C10. `SettingsBridge.write()` 先写 prefs 再更新 `current` → `paletteChanged` 恒 false
- **位置**：`SettingsBridge.kt` 96-104（写序）、监听器 37-45（用 `next vs current` 差分判定"配色变更→killTimProcess"）
- **影响**：在宿主内（`SettingsDialogHost.kt` 242）改配色**恒不重启 TIM**，只剩热刷新（注释 54-55 自己承认"覆盖不到底栏/顶栏"）。

### C11. 静默 catch 清单（多数只是少条日志，但三处后果严重）
- **位置**：`TokenMapper.kt` 149-151/176-180/237-239；`MonetPalette.kt` 94-96/342-344/356-358；`SettingsBridge.kt` 66-68/70-72/87-89/97；`RemoteSettingsWriter.kt` 35/62-72
- **严重后果的三处**：
  - `appContext` 反射失败后**永久 null** → `systemDark()` 恒 false → **自动模式永远亮色**
  - `readCurrent` 失败静默返回旧 `current`
  - `write` 在 `prefs == null` 时静默 no-op

### ~~C12. `tintMemoLight/Dark` 两份缓存内容必然相同~~ ✅ 2026-09-19 已合并为单个 `tintMemo`
- **位置**：`TokenMapper.kt` 78-88
- **问题**：`resolve` 不依赖 `dark`，两份表逐项相同；83 行 `it == NO_MAPPING` 是**死分支**（85 行只存计算结果）。

## 低

### C13. 死代码（✅ 2026-09-19 全部落地：ThemeState / seed / context / serialize / parse 均已删）
- `ThemeState.kt` 16-20 零调用（仅剩一个未使用 import + 注释）
- `MonetPalette.kt` 56-57 + 133-136 `seedColor` 只被无人调用的 `seed()` 读；142-145 `context()` 无调用者
- `TokenMapper.kt` 637-640 `BgResolver.dimmed/card` 无调用者
- `TimMonetSettings.kt` 19 `PROVIDER_AUTHORITY`、103-112 `serialize`、115-141 `parse` 全无调用者；`AndroidManifest` 无 provider，13 行注释引用的 `[SettingsProvider]` 类不存在

### ~~C14. `mapColor` 的 `dark` 只参与缓存键~~；`name` 用 hashCode 折叠、null→0 与 hashCode==0 撞键（**后半未修**）
- **位置**：`TokenMapper.kt` 55-75

### C15. SPEC_2025 白名单重复
- **位置**：`MonetPalette.kt` 268-278 与 `ui/theme/Theme.kt` 33-43 的 `supportsSpec2025/effectiveFor` → UI 预览与运行时判据会漂移

### C16. 先发布 scheme 再 `paletteGeneration++` → 存在"新方案+旧代次"窗口
- **位置**：`MonetPalette.kt` 245-249
- **影响**：缓存按旧代次写入新配色 → 换壁纸后个别控件颜色不跟随

### C17. `bgRoleFixLog` 非 volatile 被并发 `++`
- **位置**：`TokenMapper.kt` 339/360（仅日志节流计数，良性但确为数据竞争）

### C18. `roleOf` 在查 `roleMemo` 之前先 `name.lowercase()` 分配
- **位置**：`TokenMapper.kt` 326-327（热路径白付一次分配）

### C19. UI 线程做 binder IPC
- **位置**：`RemoteSettingsWriter.kt` 76-77/84-101（`SettingsActivity.onCreate` 与各 `onSet*` 同步调用 `getRemotePreferences`/读写）→ 设置页掉帧/ANR 风险

## core 层总结

最严重的是**"判据没有单一来源"**：

1. `palette(dark)` 参数被忽略 → ~20 个调用点靠"参数反正没用"侥幸正确，并让缓存键带上一个与结果无关的位；
2. 同一个"颜色→面色"问题在**三个入口**用 **HCT tone / BT.601 luma 两套不可互换的度量**各自设阈值（同色三种结果）；
3. 叠加 `resolve()` 的 **AMOLED alpha 位运算错误**与 `roleMemo` 的**失效缺口**；
4. 一整套**无日志的静默 catch**。

这正是"症状只能逐个控件打补丁、同一个 bug 换个入口又复现"的机制来源。
（`MainModule.kt` 未发现实质问题。）
