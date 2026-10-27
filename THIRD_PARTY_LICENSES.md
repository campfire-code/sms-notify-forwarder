# 第三方许可证

本应用由篝火自研，没有复制同类开源项目的源码、资源或界面文案。打包进安装包的第三方库如下，均为宽松许可证，未修改源码。

| 库 | 版本 | 许可证 | 用途 |
|---|---|---|---|
| Kotlin 标准库 | 2.0.21 | Apache-2.0 | 语言运行时 |
| kotlinx.coroutines | 1.9.0 | Apache-2.0 | 协程 |
| kotlinx.serialization (json) | 1.7.3 | Apache-2.0 | 构造和解析 JSON |
| AndroidX Core / Activity / Lifecycle | 1.13.1 / 1.9.3 / 2.8.7 | Apache-2.0 | 安卓基础组件 |
| Jetpack Compose（BOM 2024.10.01）：UI、Material 3、Material Icons Core | 见 BOM | Apache-2.0 | 界面 |
| AndroidX DataStore Preferences | 1.1.1 | Apache-2.0 | 保存配置 |
| AndroidX Room | 2.6.1 | Apache-2.0 | 保存转发日志 |
| AndroidX WorkManager | 2.9.1 | Apache-2.0 | 失败重试 |
| OkHttp（含 Okio） | 4.12.0 | Apache-2.0 | 向用户配置的 Webhook 发请求 |

仅用于构建和测试、不进入安装包：Android Gradle Plugin、KSP、Gradle（Apache-2.0）；JUnit 4.13.2（EPL-1.0）；OkHttp MockWebServer 4.12.0（Apache-2.0）。

Apache-2.0 全文见 https://www.apache.org/licenses/LICENSE-2.0 。

## 硬件方案（hardware/，不进入安装包）

- 电脑端接收器只用 Python 标准库。Windows 上读串口需要用户自行安装 pyserial（PyPI 标注 BSD 许可证），本仓库不附带它。
- 固件脚本运行在合宙 LuatOS 上（LuatOS 为 MIT 许可证，由用户通过官方工具刷入，本仓库不附带）。
- 固件逻辑测试可选使用 lupa（PyPI 标注 MIT 风格许可证），仅用于测试，不随任何产物发布。
