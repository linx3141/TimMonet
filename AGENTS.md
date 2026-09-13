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

## 代码地图

| 文件 | 职责 |
|---|---|
| `hooks/TimMonetHooks.kt`（~10k 行） | **全部 hook**：资源加载、View 背景/绘制、控件特判 |
| `core/MonetPalette.kt` | 调色板生成（种子 → DynamicScheme）、`isDarkNow()`、AMOLED 压黑 |
| `core/TokenMapper.kt` | 资源名/颜色 → 配色**角色**的映射；`BgResolver`（亮度判据统一入口） |
| `core/ThemeState.kt` | **已废弃**的兼容壳，仅转发 `MonetPalette.isDarkNow()` |
| `core/SettingsBridge.kt` | TIM 进程侧读远端设置；配色变更时重启 TIM |
| `settings/*` | 设置读写与跨进程同步（模块 UI ↔ TIM 进程） |
| `ui/*` | 模块设置界面（Compose，Material Expressive） |
| `MainModule.kt` | LSPosed 入口：`onPackageReady` → `TimMonetHooks.install()` |

## 染色链路（理解这个才能改对）

1. **资源层** —— `Resources.getDrawable / getColor / getColorStateList /
   loadDrawable`、`TypedArray.getDrawable / getColor`、`SkinEngine`（腾讯皮肤引擎）
   → 按**资源名**或**颜色值**映射到配色角色。
2. **View 层** —— `View.setBackground(Drawable)`（注册表 + dispatcher）、
   `View.onAttachedToWindow`（12 个处理器合并成的 dispatcher）、
   `ViewGroup.dispatchDraw`（每帧纠正，用于会被 TIM 覆盖的东西）。
3. **图片层** —— `ImageView.setImageDrawable` → 采样主色后重染
   （`rasterizeIconUniform` 等）。

## 关键约定（新代码必须遵守）

- **深浅判定只能用 `MonetPalette.isDarkNow()`**。不要问 TIM 的 `QQTheme`，
  也不要用 `ThemeState.isNight()`（原因见「坑 1」）。
- **亮度判据走 `BgResolver`**：`luma()` / `chromaSpan()` / `isLightLeftover()`
  （亮底 + 彩色排除）/ `isNearWhite()`；阈值用它的 `NEAR_WHITE`…`DARK` 常量，
  **不要再写裸数字**。
- **全透明色不参与任何映射**（`alpha == 0` 原样返回）。
- **给同一个 View 加"每帧纠正"要带缓存判断**（例如 `d.colorFilter == null` 才重设），
  否则每帧白染。
- **hook 热点方法前先加廉价预筛**（类名 / id 高位 / 缓存判据）。热路径：
  `dispatchDraw`、`setBackground`、`onAttachedToWindow`、`getColor`。
- **新增 attach 处理器**用 `onViewAttached { v -> ... }` 注册，**不要**再单独 hook
  `View.onAttachedToWindow`（注册顺序 = 执行顺序）。
- **新增"替换 setBackground 入参"**用 `onSetBackgroundArg { v, incoming -> ... }`。
- `findMethod` 带**按参数类型兜底**：语义敏感的方法（`show`/`dismiss`、
  `loadDrawable`…）必须用 `findMethodStrict` / `findMethodStrictDeep`。
- **目标角色不要强行统一**：同一个白色，内联色（布局写死的 `@color`）给**卡片色**、
  drawable（控件自带的卡片面）给**面色** —— 这是语义差异，不是重复。

## 已知的坑（都真实踩过）

1. **`ThemeState.isNight()` 曾恒为 false**：`hookForceLight` 把
   `QQTheme.isNowThemeIsNight()` hook 成永远 false（为了让 TIM 走浅色资源），
   而 `ThemeState` 正是反射调它并**永久缓存** —— 于是所有 `if (isNight)` 分支都是
   死代码，几个通用兜底从来没运行过，表现为"同一个症状反复修不好、只能逐个打控件
   补丁"。现已改为转发 `isDarkNow()`。
2. **透明色 `#0` 会被当成"纯黑文字"提亮**：TIM 的 `?attr/xxx` 取值未定义时拿到 `#0`
   （RGB 也是 0），曾被 `dark && opaque == 0xFF000000 → onSurface` 兜底命中，
   于是"什么都不画"的地方显形成一条亮线。现已在 `TypedArray.getColor` 入口加
   透明短路。
3. **Android 17 的 `PorterDuffColorFilter` 字段是 `mAdd`/`mMul`** ——
   历史沿革 `mSrcColor` → `mColor` → `mAdd`/`mMul`，只试老名字会静默失效
   （皮肤红气泡重染就是因此失效很久）。
4. **`findMethod` 兜底会挂错方法**：`QQCustomArkDialogForAio.show` 继承自 `Dialog`，
   不在 `declaredMethods` 里，兜底按"无参"抓到了它自己的 `dismiss` ——
   "弹窗显示时染背景"实际在**关闭时**才执行。
5. **`MonetPalette.palette(dark)` 的 `dark` 参数早就不用了**（深浅由设置决定），
   但 TokenMapper 的 memo 键里还留着它 —— 传错时"取色对、分支错"，
   表现为时好时坏、不像同一个 bug。
6. **TIM 会在我们染色之后重新设置背景**（极光卡片、底部提示条都遇到过）——
   一次性染色会被覆盖，需要"attach 后再补一次"或"绘制时持续纠正"。

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
- `.audit/` —— 一次完整代码审查的清单（重复代码 / 可通用化 / 正确性 / 性能），
  大部分已落地。
- `shots/` —— 验证用截图（含修复前后对照）。
