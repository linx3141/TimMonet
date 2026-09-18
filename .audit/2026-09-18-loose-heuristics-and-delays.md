# 审计：过宽的判据 与 定时兜底（2026-09-18）

起因：`isPlusPanelHost` 的一条兜底（"向上 8 层里有 `QQViewPager` 就算"+"面板宿主"）
把**设置页的行容器背景换成了自绘的 12dp 全圆角底板**，表现为"设置页每行变成独立
圆角卡片"，而且只有打开过"+"面板（文件发送页）之后才复现（见 `AGENTS.md` 坑 26）。

修掉之后做了一次全量审计：**找出所有同形态的问题** ——
① 判据过宽/兜底过松（尤其是"宽松判据 + 破坏性动作"的组合）；
② "过了 xx 毫秒再补一次"的猜时间逻辑。

审计基线：`app/src/main/java/com/timmonet/hooks/TimMonetHooks.kt`（md5
`86913bd0dfa4a232e5f51549060699a5`，11005 行，HEAD `370db81fa`）。

> 约定（本文件即判据）：**能按身份（类名/资源名/对象实例）判定时，不要用
> "途经某个通用控件""尺寸像""颜色像"兜底**；宽松判据后接破坏性动作
> （`setBackground` 替换 / SRC_IN 单色化 / `alpha=0`）的组合优先修。

---

## 一、判据过宽

### 1. `fixNoticeBar` —— 几何 + 通用九宫格当"微云提示条"身份 ⚠️ 待处理（高危）
- 位置：`TimMonetHooks.kt:6860`（判据 6862-6869；调用点 1935、6913）
- 现状：`背景类名含 SkinnableNinePatch` + `全宽` + `高度 32–48dp` + `y 在屏高 4%–20%`
- 危险：这只是"某种皮肤底"，不是"微云提示条"。搜索栏/顶栏/各种全宽提示条都在这
  个尺寸区间。命中后动作很重：背景刷成页面底色，且 `walkViewTree(v, 30)` 把子树里
  **所有** ImageView / TextView compoundDrawable 用 SRC_IN 染成单一 `onSurface`
  （彩色图标变单色块）。还被"每个 View attach"与 dispatchDraw 每帧各调一次。
  注释里写的还是旧判据（"高 100..140 + y 250..380"），与代码已脱节。
- 建议：只保留身份判据（该提示条的类名/背景资源名，反编译可查）；图标那段加
  "无彩色 + 亮度低"门槛（`BgResolver.isGray`/`luma`），彩色图标原样保留。

### 2. `dIsPrimary` 的 `d.colorFilter != null` —— 与"是不是 primary 底"无关 ✅ 已修
- 位置：`TimMonetHooks.kt:2199`（调用点 3952、5273）
- 危险：`recolorInPlace` 会给大量普通图标挂 colorFilter，于是"白字数字 + 3 层内
  有个被我们染过的 ImageView 兄弟"被判成"数字压在 primary 底上"，文字被强制改成
  `onPrimary`（深色方案里 onPrimary 是深色 #383E61）→ 压在非 primary 底上几乎看不见。
  触发面很大：`probeWhiteNumberText` 挂在 `TextView.setText`，任何 1–4 位纯数字都进来。
- 修法：删掉该兜底，只保留"颜色≈primary"；判不出来返回 false（宁可漏染）。

### 3. `fixThinBrightLineDrawable` —— 全宽 + 高度 ≤40dp + 亮色 即压成页面底色 ⚠️ 待处理
- 位置：`TimMonetHooks.kt:6722`（入口：attach 与 `onSetBackgroundArg`）
- 危险：原始实证只是"设置页底部一条 3px 亮线"，代码把高度放宽到 40dp 且没有位置
  条件 → 搜索栏/输入栏/全宽浅色条/浅色分隔条都会被刷成页面底色、边界消失。
  且每次 `setBackground(Drawable)` 都会重跑。
- 建议：收回实证范围（高度 ≤3px 级 + 贴屏幕底/顶），或改为按资源名在 setBackground
  入口处理。

### 4. `dispatchDraw` 每帧细线扫描 —— 几何 + 亮度当分隔线身份 🟡 部分修
- 位置：`TimMonetHooks.kt:1906`（判据 1911-1916）
- 危险：同一签名覆盖有语义的亮线（选中下划线、进度/滑块填充、亮色描边），
  改成页面底色＝高亮消失；每帧跑，判错即持续生效。
- 已做：排除 `ch.isClickable || ch.isSelected`（有交互语义的线不碰）。
- 待做：改按资源名在 `setBackground`/`setBackgroundColor` 入口判定。

### 5. `isJumpArrowDrawable` 的尺寸兜底 + `alpha = 0` 隐藏 ⚠️ 待处理（高危）
- 位置：`TimMonetHooks.kt:3540`（几何兜底 3555-3564；隐藏 3428；调用 5180）
- 危险：名字查不到时用"≤24dp 的方形图"当引用跳转箭头 → 引用块里**所有**小图被判成
  箭头并栅格化重染（彩色图形变单色）；推不出颜色时 `view.alpha = 0f` 直接把图标藏起来。
- 建议：删掉尺寸兜底，按资源名或"引用条写入入口的上下文"限定（坑 11 做法）；
  `alpha=0` 兜底一并去掉，判不出来就原样放过。
- 注意：改这里前先用探针确认命中面（该功能是坑 11 硬啃出来的，别盲改）。

### 6. `isPopupBeakView` —— 宽扁 + 高 ≤24 **px** + 根类名含 "Popup" ⚠️ 待处理
- 位置：`TimMonetHooks.kt:6322`（调用 6531）
- 危险：`rootView` 含 "Popup" 只说明"这是弹窗"；叠加"宽≥1.6×高、高≤24px"
  （**px 非 dp**，阈值随密度漂移）会命中弹窗里所有横向小图；命中后改成 `bgPage`
  并写 `imageTintList` → 图形变成与页面同色的色块。
- 建议：按身份判（尖角属于 PopupWindow 的背景 drawable：比对实例或资源名）；
  阈值改 dp，并加"宽度接近面板宽度"的约束。

### 7. `fixLowContrastText` 把"读不到背景（-1）"当成深底 ✅ 已修
- 位置：`TimMonetHooks.kt:5931`（判据 5964）
- 危险：父链 8 层内读不到背景色时（背景是图片/自绘/在更上层），也被当成深底 →
  压在**浅色图片**上的灰字被提亮成白字，反而看不见。
- 修法：只在实际读到深色背景时才提亮（`bgLuma in 0..DARK_TEXT`）。

### 8. `isIconLikeName` 兜底：`startsWith("qui_")` / `contains("_ic")` ⚠️ 待处理
- 位置：`TimMonetHooks.kt:9900`（返回 9925-9927）
- 危险：`qui_` 是 TIM 通用前缀（背景/装饰/插画都会用），`_ic` 会命中 `topic`/`magic`
  之类词 —— 彩色图形被整体 SRC_IN 成一块纯色。
- 建议：显式白名单（仓库已有 `AV_ICON_TINT_COLORS` 的做法），单色化前加
  "无彩色/亮度低"的像素门槛。

### 9. `fixTinySolidBg` 只凭采样色偏亮就压暗（`solidColorOf` 会采样位图） ✅ 已修
- 位置：`TimMonetHooks.kt:6805`（位图采样 4946）
- 危险：区分不出"遗留浅色面"与"主色偏亮的图片当背景"——后者被就地染成页面底色，
  整张图变成纯色块。
- 修法：新增 `isUniformBitmapBg()`：位图背景先做 4×4 采样，亮度跨度 >12 视为
  图片/渐变直接跳过；非位图不受影响（保留原来"不按类型/尺寸筛"的目标）。

### 10. `insideProfileRootTree` 从窗口根 BFS 找 `profilecard` ⚠️ 待处理（低危）
- 位置：`TimMonetHooks.kt:4382`（返回 4395）
- 危险：判据从"这个 View 在资料卡页"变成"本窗口任何位置有 profilecard 容器"，
  弹层/半屏卡都会让整窗被判成资料卡页；当前调用点影响有限，但易被后续改动放大。
- 建议：删掉全窗 BFS，只保留 Activity 类名 + 直接父链。

---

## 二、定时兜底（"过了 xx 毫秒再补一次"）

### 11. 支付密码：用 120ms 时间窗当"这个 #333333 是密码圆点"的身份 ⚠️ 待处理（高危）
- 位置：`TimMonetHooks.kt:7578`（时间戳 7570，常量 7606）
- 危险：`#333333` 是 TIM 通用深灰；而格子底**每帧重画都会刷新时间戳**，只要弹窗在屏上
  这个窗就一直开着 → 该进程里任何 `Paint.setColor(#FF333333)`（金额/标题/自绘图形）
  都会被换成 primary；绘制顺序一变又会漏染。
- 建议：改成对象身份 —— 命中格子底色时记住宿主 `PasswordEditText`（反编译有
  `mPaintBackground`/`mPaintForeground` 字段），只在同一宿主的另一个 Paint 上替换；
  时间邻接整条删掉。

### 12. `registerSystemNightCallback` 无上限自递归重试 ✅ 已修
- 位置：`TimMonetHooks.kt:6017`（重试 6024-6025）
- 危险：拿不到 Application 就每 500ms 重投一次，**无上限、无退出条件**；在
  `currentApplication()` 长期为 null 的进程里就是永不退出的主线程定时器
  （每次还要 `Class.forName` + 反射）。
- 修法：最多 10 次（≈5 秒）后放弃。

### 13. `hookPolarLightLate` 的 `postDelayed(150)` ⚠️ 待处理
- 位置：`TimMonetHooks.kt:6752`（延时 6757-6764）
- 危险：靠 150ms 后再设一次 colorFilter 去压过通用路径 —— 正是坑 19 禁止的猜时间：
  TIM 若在 150ms 之后重设/换新 drawable 仍被覆盖（"颜色闪一下又回去"）。
- 建议：改成事件入口（`setImageDrawable`/`onSetBackgroundArg` 后立即纠正；
  或 dispatchDraw 里带缓存判断的每帧纠正），删掉 150ms。

### 14. `handleReplyJumpIcon` 推不出颜色就 `alpha=0` + 200ms 恢复 ⚠️ 待处理
- 位置：`TimMonetHooks.kt:3420`（隐藏 3428、恢复 3429-3431）
- 危险：用"先隐藏 200ms"掩盖"暂时不知道颜色"＝猜时间；若该 View 因误判（第 5 条）
  被反复 setImage，会不断重新计时 → "图标时有时无"；用 alpha 当开关还会盖掉 TIM
  自己对 alpha 的使用（动画/禁用态）。
- 建议：判不出颜色就原样放过；把待处理 View 记进集合，由"算出引用块文字色"那一遍
  统一补染（事件驱动）。

### 15. `SettingsBridge.killTimProcess` 固定 300ms 后杀进程 🟢 可接受（已记录）
- 位置：`core/SettingsBridge.kt:103`
- 判定：属固定延时写法，但最坏只是杀早/杀晚（配置已写远端、下次冷启动生效），
  实际风险低。隐患是后来者可能误以为"300ms 内一定写完"。
- 建议（可选）：在确认写入完成处再杀，删掉固定延时。

---

## 已核实**不算问题**（避免重复报）

- `MonetPalette` 的 `RETRY_THROTTLE_MS=5000`：失败节流只看 `lastAttemptAt`（坑 9 已修）。
- `SettingsBridge.ensureRemote` 的 `ATTACH_RETRY_MS=5000`：失败重连节流。
- mannounce 注入 JS 的 `setTimeout(...,150)`：`MutationObserver` 去抖 + 重入保护（坑 24 的正解）。
- 多处 `v.post { ... }`：下一帧事件驱动，不是固定延时。
- 当前版本已无 `Thread.sleep` / `Timer` / `schedule*`。

---

## 高危 Top 5（按"误伤面 × 动作破坏性"排序）

1. **#1 `fixNoticeBar`**：几何 + 通用九宫格当身份，命中即刷背景 + 单色化整子树图标，
   且每帧/每次 attach 都跑 —— 与 `isPlusPanelHost` 事故同形态。
2. **#5 `isJumpArrowDrawable` 尺寸兜底 + `alpha=0`**：≤24dp 图片即当箭头，推不出色就隐藏。
3. **#11 支付密码 120ms 时间窗**：格子底每帧刷新使窗常开，同进程任意 `#333333` 被改色。
4. **#3 `fixThinBrightLineDrawable`**：全宽 + ≤40dp + 亮色即压成页面底色，每次 setBackground 都跑。
5. **#2 `dIsPrimary`**（**本轮已修**，列此以说明其危害级别）。
