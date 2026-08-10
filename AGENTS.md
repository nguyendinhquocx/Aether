所有更改默认同时需要在 iOS 和 Android 生效，且行为需一致。

复盘记录：

- 共享 UI 与平台原生 UI 可能同时存在并分别作为实际入口。修改 `shared/src/commonMain` 后，必须确认 Android 的 `app` 和 iOS 的 `iosApp` 是否各自还有同功能实现，并分别检查、编译和安装验证，不能仅凭共享层代码推断两个平台都会生效。
- Compose 中使用 `Box` 叠加阴影时，`matchParentSize()` 子项不参与父容器尺寸测量。父容器必须至少有一个正常参与测量的内容子项（例如固定高度的 `Row`），否则胶囊、按钮等控件可能宽度变为 0 而完全消失。
- iOS 真机构建使用命令行时，工程若未配置 `DEVELOPMENT_TEAM` 会在签名阶段失败。应从本机 provisioning profile 或 Xcode 已配置的团队读取 Team ID，并通过本次 `xcodebuild` 的 `DEVELOPMENT_TEAM=<Team ID>` 参数构建；不要把个人本机签名配置写入仓库。
- 安装完成后，必须用实际连接设备核对包名/Bundle ID 和安装结果；Android 与 iOS 的 application identifier 不一定相同，不能只看构建成功。
- 搜索文件或代码时，默认始终排除缓存目录、第三方源码目录、构建目录及其他生成文件目录，避免被无用信息淹没；除非明确知道自己需要搜索这些目录。
