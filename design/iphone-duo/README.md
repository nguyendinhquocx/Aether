# Aether iPhone Duo UI · 修订 02

2026-10-14。依据 Apple HIG、当前实现与本次审阅反馈修订。Duo 改变布局；模型胶囊、消息、工具调用与输入区继续沿用原版。

## 交付

- [可编辑 Pencil 文档](/Users/baimoqilin/.pencil/documents/5bb13357-d8e6-48b2-87fe-de6b8046e100/untitled.pen)：直接更新原文档，包含全部可编辑图层。
- [设计 PDF](./Aether-iPhone-Duo-UI.pdf)：14 页，包含设计说明、原版组件、11 张界面状态与适配规范。
- [预览](./preview.png)、[PNG](./exports/)与[切换动效示意](./agent-computer-transition.gif)。动效由 Pencil 静态界面组成，不是真实终端或浏览器直播。
- [Agent 电脑实现设计](./agent-computer.md)：流输出、历史追加、PTY、只读浏览器与切换规则。
- [交付索引](./design-manifest.json)：画板与导出映射。

本次只修改设计和说明，未实现应用功能，未创建分支或提交。尺寸、折线、相机和侧栏宽度均为示意，不是硬件规格或实现常量。内容为样例；实施时沿用原图标、系统字体、本地化与动态字号。

## 审阅反馈落实

| 编号 | 修订结果 |
| --- | --- |
| 01 | 恢复原版白色模型胶囊、思考时长、折叠工具行、Markdown 消息和输入胶囊。左上返回会话列表；删除输入区下方永久 Skills · MCP 与上下文提示。 |
| 02 | 恢复 iPad 会话列表：Aether 标题、搜索图标、普通标题行、中性选中背景，保留原 Chat 按钮。左下 Settings 替换为上下文进度条与 used/full。右侧栏同 01。 |
| 03A | 会话与 Agent 的电脑 1:1 并列。没有 Artifact、没有工具调用时，右侧仍显示空终端与提示符。 |
| 03B | 命令和实时输出连续追加，前面的记录保留。Agent 终端只读，没有输入框或辅助键。 |
| 03C | Agent 使用 Alpine Chrome 时水平滑动切换到实时浏览器画面；终端历史保持。画板为静态示意。 |
| 03D | 保留认可的 Artifact 布局，明确标注尚未实现的未来可选状态。默认使用 Agent 的电脑。 |
| 04 | 删除画板与导出。 |
| 05 | 保留目录与编辑器并列的设计。 |
| 06 | 恢复单一全屏 Alpine 终端和原生导航，删除分类和上下文列。用户可以操作；与 03 观察终端分开。 |
| 07 | 同步 01，侧栏移至左侧外边缘；文字与图标正常阅读，返回会话列表。 |
| 08 | 保留设置设计。 |
| 09 | 删除画板与导出。原有引导和队列功能不因删页改变。 |
| 10 | 保留菜单，背景同步原版聊天。沿用既有低频菜单入口，统一侧栏不增加省略号。 |

01、02、03、07 侧栏上部依次为 **New Chat / Scheduled Tasks / Skills / Files / Terminal**，底部 **Settings**。返回键留在会话顶部。原 Composer 的选中技能、MCP 等 action tray 继续按既有条件显示，不增加永久 footer。

02 上下文示例为 `64k / 200k`，进度 32%。实际读当前会话计量；未知总量时显示未知，不伪造上限。进度条提供读屏数值。

## 03 的关系与行为

02 是“会话列表 → 当前会话”的主次导航，左窄右宽。03 是“会话 ↔ 执行环境”的并列工作空间，左右等宽，不依赖 Artifact。

默认显示终端提示符，不为这个空态启动等待用户输入的 shell。开始 bash 后追加命令与输出；Agent 使用浏览器时终端向左滑出、Chrome 从右滑入。下一条前台 bash 开始时反向返回。动画约 240ms；Reduce Motion 使用淡入。

Agent’s computer 仅在已确认 Duo 的半折叠模式下自动显示，使用 SwiftUI 实现。外屏和完全展开不提供打开入口；移除打开、隐藏、终端/Chrome 标签及自动跟随开关。面板随当前 Agent 活动自动切换，用户只可查看历史，不能向 shell 或网页输入。后台 stdout 只保存日志。Agent 完成或等待时保留最近画面，不退回空白。

视图切换不新建 Chrome、不停止进程、不清空历史。离开半折叠模式只解绑观察渲染，任务继续。收起和展开保留会话、模型、草稿、消息锚点、工具详情、日志与浏览器状态。未来 Artifact 仅由显式打开进入，关闭恢复此前电脑视图。

## 原版依据与实现差距

重新核对了 `public/chat.jpg`、`public/research.jpg`、`public/input_bar.jpg`、`public/tool_execution.jpg` 及以下入口：

| 实现 | 核对结论 |
| --- | --- |
| `IosComposeApp.kt` 的 `SharedConversationModelSelector` / `SharedComposer` | 38dp 白色阴影模型胶囊、主次文字分色；工具 action tray 在输入区内部，无永久 footer。 |
| `SharedConversationMessages.kt` 的 `SharedToolInvocationGroup` | 思考/工作时长与 Executed/Executing tools 折叠行，不使用打勾步骤列表。 |
| `SharedConversationDrawer.kt` | 列表 320dp，默认搜索图标，没有日期分组和时间副标题；选中行中性背景。 |
| `NativeSettingsView.swift` 的 `NativeAlpineTerminalScreen` | 单一全屏终端，标题 Alpine，Done 关闭。 |
| `AlpineFileManagerView.swift` 与原生设置入口 | 05、08 内部设计保留，后续实现复用原生功能。 |
| `pi-bridge/src/bridge.ts` | Pi 拥有实际 bash，已有工具流更新与 onData；旧 RuntimeHostToolExecutor 不是当前 Agent 主要 bash 路径。 |
| `SharedChromeManager.kt` | 存在 Alpine Chromium / Xvnc / noVNC 路径；工具动作截图刷新不等于持续直播。 |
| `PlatformBrowserBackend.ios.kt` | 当前 iOS 默认为 WKWebView；03C Alpine Chrome 需要明确接入对应后端和持续只读画面流。 |

iOS 当前 `scheduledTasks=false`，Android 为 true。设计按要求保留图标，实现需补齐 iOS 能力。Artifact 尚未实现；画板不表示功能已经存在。

## Apple HIG 对照

依据 [Designing for iPhone Duo](https://developer.apple.com/design/human-interface-guidelines/designing-for-iphone-duo) 和 [Preparing your app for iPhone Duo](https://developer.apple.com/documentation/technologyoverviews/preparing-your-app-for-iphone-duo)。

| 主题 | 修订中的处理 |
| --- | --- |
| 动态布局 | 使用场景容器、size class、安全区与可用空间，不按硬件型号或单一宽度阈值判断。 |
| 一致性 | 延续原版视觉与功能，空间展开已有层级或 Agent 执行环境，状态保持。 |
| Reserved regions | 相机与活动折线由系统报告，重要文字、操作和输入分别落在安全区域。 |
| Split / arrangement | 02 主次导航；03 同等内容独立排列，导航在外。避免 arrangement 嵌入 navigation split、列表或滚动容器。 |
| 侧边控件 / 多任务 | 统一入口沿窗口外侧；07 移至左侧。栏和系统状态区的几何由原生容器管理。 |
| 语义 / 溢出 | 图标有本地化标题。返回在会话顶部，停止/发送留在 Composer；低频操作沿用现有菜单。 |
| 可访问性 | 至少 44pt 热区，动态字号、VoiceOver、高对比度、Reduce Motion；38dp 可见控件扩展命中区域。 |

## 后续验收

本次核对 Pencil 截图和导出图；没有运行应用测试或在 Duo 真机验证。后续实施重点：

1. 外屏、展开、半折叠和 Split View 中状态保持，不重复发送或执行。
2. 连续三条命令的实时输出、退出码和历史追加；ANSI、Unicode 跨 chunk、取消、失败与断线恢复。
3. Chrome 获得持续帧，后台输出不抢前台，切回终端历史完整；观察端拒绝键盘、pointer 和终端控制信号。
4. 容器重排不停止任务；区分用户关闭 06 与当前 `onDisappear { terminal.stop() }` 被重排触发。
5. 没有 Artifact 时仍有提示符。Alpine 未就绪时说明状态，不隐式安装；重试只重连观察，不重复命令。
6. 新布局及观察能力仅适用于 iPhone Duo；普通 iPhone、iPad、Android 必须保持原有界面和行为，并进行回归检查。

## Xcode

沿用确认的安排：先交设计，系统升级由用户处理。本轮未安装或切换 Xcode 27.1 RC；待满足 macOS 前置条件后继续。

2026-10-16 图标定稿：Scheduled Tasks 使用用户提供的 `assets/clock-8.svg`；Skills 使用 `assets/blocks.svg`。Pencil 使用对应的 Lucide 矢量图标，原始 SVG 随交付保存。


2026-10-16 实施范围修正：本设计仅用于 iPhone Duo。宽度与 size class 只决定已确认 Duo 的布局，不能启用功能。原生端仅在 iOS 27.1+ 的 phone 设备报告 division reserved region（含 inactive）时确认能力；没有能力证据时保留原版，SDK 不足时关闭。普通设备不启用侧栏、上下文页脚、Agent Computer、定时任务入口、并列文件编辑或 Alpine 浏览器替换，也不订阅新增观察事件。外屏冷启动的能力报告仍需 27.1 SDK / Duo 模拟器验证。

2026-10-16 原生实现：SwiftUI 负责只读终端和 Chrome 观察面板；原生容器依据 active division 分配两侧区域。形态切换复用同一个聊天控制器，通过系统转场时长同步调整区域并淡化旧布局，保留草稿、滚动锚点与执行状态；Reduce Motion 关闭空间位移动画。
