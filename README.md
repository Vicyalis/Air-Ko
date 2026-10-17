<div align="center">
  <img src="docs/icon.png" alt="App Icon" width="100" />
  <h1>Air-Ko</h1>

Android 端 LLM 聊天客户端，基于 <a href="https://github.com/rikkahub/rikkahub">RikkaHub</a>（AGPL-3.0）的衍生分支。
</div>

## 这是什么

Air-Ko 是基于 [RikkaHub](https://github.com/rikkahub/rikkahub) , [Tinycontainer](https://github.com/Cateners/tiny_container),  [Reticle](https://github.com/Bumfuzzle-app/reticle)的 Android 端 LLM 聊天客户端衍生版。保留上游的全部能力（多服务商接入、助手系统、MCP、技能、工作区、桌面容器、设备控制、语音等），
并在其上做了这些改动：

- **全新优化后的Linux工作区**：工作区支持更全面的Linux， 可以自由选择自己偏好的Distribution，同样提供了niri， gnome等作为桌面下载使用，也可以装Code-server作为便携式的安卓编辑器。
- **手机控制**：通过无障碍权限和Shizuku来操控手机，灵感来源于Reticle，更为流畅地用ai控制手机。
- **统计与花费**：定点金额、缓存节省、覆盖率提示、模型价格自定义（最长通配匹配），灵感来源于Reasonix。

## 下载

到 [Releases](../../releases) 下载 APK：

| 文件 | 适用场景 |
| --- | --- |
| `Air-Ko-<版本>-arm64.apk` | 绝大多数现代手机（推荐，体积小） |
| `Air-Ko-<版本>-universal.apk` | 不确定设备架构时使用（体积大） |

> 当前发布为 **debug 签名构建**（包名 `com.airko.app.debug`），可直接安装体验，与上游 RikkaHub 可共存。
> 自行构建 release 版需要自备签名密钥（见下文）。

## 构建

环境要求：JDK 17+（CI 使用 JetBrains Runtime 21）、Android SDK（compileSdk 37）、
Node 22 + pnpm 11（web-ui 前端子构建需要）。

```shell
# Debug 构建（产出 app/build/outputs/apk/debug/）
./gradlew assembleDebug

# Release 构建（需要签名配置）
./gradlew assembleRelease
```

签名配置写在仓库根目录的 `local.properties`（该文件不随仓库分发）：

```properties
storeFile=app.key
storePassword=你的密码
keyAlias=你的别名
keyPassword=你的密码
```

其中 `storeFile` 相对 `app/` 模块目录解析（即 keystore 放在 `app/app.key`）。

## 许可与致谢

- 本项目基于 [RikkaHub](https://github.com/rikkahub/rikkahub)，遵循 **AGPL-3.0**，保留原项目的版权与许可（见 `LICENSE` 与 `LICENSES/`）；
- 衍生版与上游项目无关，问题请在本仓库反馈；
- 本仓库不包含任何 API Key；模型调用的费用与数据边界由你与服务商之间的协议决定。

## 安全提示

设备控制依赖无障碍服务与 Shizuku，可对手机执行真实操作；请只在信任的模型与提示词下开启，
并善用「设备控制 → 审计」页复核每一次操作记录。
