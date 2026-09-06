# TimMonet

为 TIM 4.1.0（基于 QQ NT 架构的轻量版 QQ）适配 Material You「莫奈取色」的
LSPosed 模块。

## 主页面（v2.0）

模块的主页面移植自 KernelSU Manager 的 Material 主题设置页（样式未做改动）：

- 颜色模式：系统 / 浅色 / 深色 / 纯黑
- 自动取色（跟随系统莫奈引擎）或自定义种子色
- 色彩风格（TonalSpot / Neutral / Vibrant / Expressive / Rainbow / FruitSalad / Monochrome / Fidelity / Content）
- 色彩标准（Material Color Spec 2021 / 2025）

设置通过 ContentProvider 传给 TIM 进程，修改后约 3 秒内自动生效，无需重启。
KernelSU 中的预测性返回手势、导航栏角标、页面缩放与取色无关，已移除。

## 效果

- 取色源与 KernelSU Manager 一致：直接读取系统莫奈引擎生成的
  `system_accent1_*` / `system_primary_light/dark`（Android 12 以下才退回壁纸量化），
  因此壁纸是蓝色而系统莫奈选了绿色时，本模块同样跟随系统取绿色；
- 把 TIM 的品牌蓝（按钮、链接、选中态、聊天气泡、导航栏品牌底等）替换成
  壁纸派生的主色，中性文字/背景/边框同步映射到对应明度的中性色阶；
- 日间 / 夜间两套调色板自动切换，跟随 TIM 自身的主题开关；
- 壁纸更换后自动重新取色，无需重启。

## Hook 点

| 取色链路 | 类 / 方法 | 覆盖范围 |
| --- | --- | --- |
| QUI token 组件 | `com.tencent.biz.qui.quitoken.b.a` 的 `d(Context,int,int)`、`e(Context,int,int)` | NT 主界面、聊天页、设置页等原生组件 |
| Hippy / JSI / WebView | `com.tencent.mobileqq.vas.theme.api.QUIUtil.getCurrentTokenMap()` | 跨端页面 |
| 经典皮肤引擎 | `com.tencent.theme.SkinEngine.getColor(int)`、`loadColorStateList(int)` | 遗留页面 |
| Resources 直读 | `Resources.getColor*`、`getColorStateList*`、`loadColorStateList(TypedValue,int,Theme)`、`TypedArray.getColor` | 主界面顶栏/聊天列表/底栏、AIO 消息气泡等绝大多数原生页面 |
| Drawable 染色 | `Resources.getDrawable*`、`Resources.loadDrawable*` | 底栏图标、输入栏背景、联系人/设置页面背景、新朋友卡片等 XML 膨胀的烘焙色资源 |
| 气泡文字 | `com.tencent.mobileqq.aio.utils.ai.h/f(Context)` | 修复深色模式下气泡文字与气泡背景同浅色导致的不可见 |
| 主页面背景 | `com.tencent.mobileqq.resconfig.a.a/b/c(Context)` | 深色模式写死十六进制的页面背景 |

全部使用 after-hook：每次调用基于原始值重新映射，不修改 TIM 的任何缓存，因此
幂等且不影响其他主题功能。Resources 层只重映射 `qui_` 前缀的语义色，其余资源原样返回。

浅色模式的页面/列表/卡片背景会按主色相做轻微染色（不是纯中性白），深色模式保持中性。

## 构建

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

要求 JDK 17+（项目已配置 `org.gradle.java.home` 指向本机缓存的 JDK 21）。

## 安装与使用

1. 安装 APK 到手机；
2. 在 LSPosed 管理器中启用「TIM 莫奈取色」模块，作用域勾选 `com.tencent.tim`
   （`scope.list` 已声明）；
3. 强制停止并重启 TIM；
4. 更换壁纸后颜色会自动跟随。

## 已知限制

- 错误红 / 成功绿 / 警告黄、纯黑纯白蒙层等固定语义色不参与取色；
- TIM 混淆会在版本升级时重命名方法，hook 使用「方法名 + 签名」匹配并附带
  签名兜底，但跨大版本仍可能需要同步更新类名。
