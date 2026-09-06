<div align="center">
<h1>TimMonet</h1>

<a href="https://github.com/linx3141/TimMonet/stargazers"><img alt="GitHub stars" src="https://img.shields.io/github/stars/linx3141/TimMonet?label=Stars"></a>
<a href="https://github.com/linx3141/TimMonet/releases"><img alt="GitHub all releases" src="https://img.shields.io/github/downloads/linx3141/TimMonet/total?label=Downloads"></a>
<a href="https://github.com/linx3141/TimMonet/releases/latest"><img alt="GitHub latest release" src="https://img.shields.io/github/v/release/linx3141/TimMonet"></a>

<a href="https://qm.qq.com/q/Tink3oFUau"><img alt="QQ群 1051328541" src="https://img.shields.io/badge/QQ%E7%BE%A4-1051328541-12B7F5?logo=tencentqq&logoColor=white"></a>

<p>为 TIM（QQ NT 架构的轻量版 QQ）适配 Material You「莫奈取色」与深色主题的 Xposed 模块</p>

<p>
  <b>支持框架</b>：
  <b><a href="https://github.com/LSPosed/LSPosed">LSPosed</a></b>
</p>
</div>

---

## 项目简介(必看)

- 这是一款基于 **Xposed API** 开发的 LSPosed 模块，作用于 **TIM 4.1.0[.4050]**
- 推荐使用最新的LSPosed, 于lsposed.zip下载
- 在模块内部完成 Material You 调色板计算，**不依赖系统莫奈引擎**（低版本 Android 同样可用）
- 将 TIM 的品牌蓝与中性色阶整体映射为壁纸/种子色派生的 Material 3 配色：
  - 深色主题化：登录页、聊天（AIO）、转发对话框、浮层菜单、我的页等原生页面
  - 品牌色统一：按钮、链接、选中态、聊天气泡、徽标红点、底栏图标等
- 来自KernelSU的设置页，可实时预览配色
- 全程事件驱动、无延时轮询，热路径全部记忆化，性能开销低
- **在TimMonet模块内**设置深色浅色与Monet配色方案

---

## 功能分类

本模块功能主要分为以下 3 类：

| 功能类型 | 作用说明 | 启用方式 |
|---|---|---|
| 主题覆盖 | 对 TIM 各原生页面/组件做深色化与调色映射 | 勾选 TIM 后直接生效 |
| 配色定制 | 自定义颜色模式、种子色、调色板风格与色彩规范 | 在模块设置页调整 |
| AMOLED黑 | 你将看到一个气泡都是AMOLED黑的最省电Tim | 在模块设置页设置 |

## 使用说明

### 主题覆盖

1. 在 LSPosed 管理器中启用本模块，作用域勾选 `com.tencent.tim`（`scope.list` 已声明）
2. 强制停止并重启 TIM
3. 生效后进入各页面检查配色
4. 如果某些页面未生效，可能是该组件走的渲染路径未覆盖，请前往 [Issues](https://github.com/linx3141/TimMonet/issues) 或QQ群组反馈（附截图与日志最佳）

### 配色定制

1. 打开模块的 Compose 设置页（直接打开模块应用）
2. 选择颜色模式 / 取色源 / 调色板风格 / 色彩规范
3. 保存后模块会自动强制重启 TIM 使新配色完整生效
4. 更换壁纸后如需跟随系统重新取色，同样建议重启一次 TIM

---

## 兼容性

- 推荐 **LSPosed**（或兼容 Xposed API 93+ 的框架）配合 **TIM 4.1.0.4050** 使用
- 模块最低支持 Android 8.0（API 26）
- TIM 混淆会在版本升级时重命名方法，hook 使用「方法名 + 签名」匹配并附带签名兜底，但跨大版本仍可能需要同步更新类名
- 部分色彩如 错误红 / 成功绿 / 警告黄、纯黑纯白蒙层等固定语义色不参与取色

---


## 构建

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

要求 JDK 17+。

---

## 问题反馈

请前往 **[GitHub Issues](https://github.com/linx3141/TimMonet/issues)** 或QQ群组提交反馈, 请携带好bug复现方法和设备信息。

---

## 免责声明

- 本模块仅供学习交流使用，请勿用于商业或违法用途
- 本模块完全免费，不存在收费盈利；如果你是付费购买的，请联系售卖者退款
- 禁止售卖、倒卖或二次打包分发本模块
- 因使用本模块产生的任何后果，由使用者自行承担

---

## 交流与赞助

### QQ 交流群

- 群号：**1051328541**
- 适配反馈、使用交流、新版本通知均可在群内进行

### 赞助支持

- 模块目前为个人维护项目，适配与更新需要投入时间与精力
- 如果这个模块确实帮到了你，欢迎自愿赞助支持（可选，模块保持免费）

微信赞赏：

<img width="1290" height="1290" alt="mm_reward_qrcode_1788704088884" src="https://github.com/user-attachments/assets/935bbb34-786c-4f19-8a7d-0ed668b98c3b" />

支付宝：

<img width="1080" height="1620" alt="1788704013661" src="https://github.com/user-attachments/assets/fe1200b2-c137-4450-a3e1-baa6a4033aa1" />
