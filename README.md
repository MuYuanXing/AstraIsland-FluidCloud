# 流体云事件接入

星流官方插件「流体云事件接入」的源码。

本插件是适用于 OPPO、一加、真我手机的 LSPosed 模块：读取系统流体云中进行中的内容（来电与通话、计时与闹钟、实时活动、系统状态），按星河岛系统事件标准清单翻译后交由星流的星河岛显示，并隐藏系统流体云中相同内容的胶囊；用户点按星河岛上的按钮或卡片时，交由系统执行对应的操作。

> **English summary.** Source code of the official AstraFlow plugin for OPPO, OnePlus and realme phones. It is an LSPosed module that reads ColorOS Fluid Cloud events and hands them to AstraIsland. The source is published under the [PolyForm Strict License 1.0.0](LICENSE.md) with an [additional permission](ADDITIONAL-PERMISSION.md) for preparing contributions. Redistribution of the source code or of any build is not permitted. Contributions are welcome through pull requests; see [CONTRIBUTING.md](CONTRIBUTING.md). Self-built packages are accepted by AstraIsland only after the device owner allows a local test in AstraFlow, so changes can be verified on your own device.

## 安装

请在星流的插件商店中安装本插件，无需自行编译。使用前需满足：

- OPPO、一加或真我手机，Android 15 或以上；
- 已安装并启用与本插件交换版本一致的星流；连接状态在插件设置页的「运行状态」中显示；
- 在 LSPosed 中同时启用星流与本插件，作用域为系统界面。

星河岛默认只接受与星流使用相同签名且交换版本一致的插件。自行编译的安装包可在星流中允许本机测试；该操作只允许本机安装的这份插件，不放宽交换版本检查。做法见 [CONTRIBUTING.md](CONTRIBUTING.md)「在自己的手机上测试」。

系统流体云的胶囊、副岛与展开卡片内容由系统页面提供，星河岛依据该页面显示文字、图片、动画与按钮。音乐与通话使用星河岛自己的卡片，充电按星河岛的充电规则显示；卡片外壳、位置与光效由星河岛控制。

「接入系统流体云」关闭时，星河岛撤下本机音乐、通知、验证码与系统实时内容，系统恢复显示；已选设置保留，外部应用接入独立管理。

## 仓库内容

| 目录 | 内容 |
| --- | --- |
| `fluidcloud/` | 插件应用：系统界面中的入口、设置页与运行状态 |
| `fluidcloud-core/` | 读取与翻译的核心：读取系统流体云、隐藏系统胶囊、执行按钮操作 |
| `island-events/` | 标准事件的模型与编码，以及插件与星河岛之间的对接约定 |
| `contracts/` | 星河岛系统事件标准清单与 OPPO 对照表 |
| `tools/contracts/` | 校验标准清单、对照表与代码是否一致的程序 |

本仓库只包含插件本身与插件和星河岛之间的对接约定，星流与星河岛的其余部分不在本仓库中。

## 编译与检查

需要 JDK 17 或以上与 Android SDK（compileSdk 37）。单元测试使用 JDK 25，本机没有时由 Gradle 自动下载。

界面部件使用 [compose-hig 项目修改版](https://github.com/MuYuanXing/compose-hig-astraflow) 的 `27.0.1-alpha05-astraflow6` 版本。先检出该项目的 `astraflow-perf` 分支，确认版本表中的版本与本项目一致，在其目录执行：

```bash
./gradlew :hig:publishToMavenLocal :hig-core:publishToMavenLocal
```

随后回到本项目编译。主部件与核心部件统一锁定到版本表中的修改版，扩展图标使用对应的官方版本。仓库自动检查执行相同的准备步骤。

```bash
./gradlew :fluidcloud:assembleDebug          # 编译测试用安装包
./gradlew :fluidcloud:testDebugUnitTest      # 运行单元测试
node tools/contracts/validate-island-system-events.mjs   # 校验标准清单与对照表
```

正式安装包只由星流维护者使用正式签名编译，并在星流的插件商店发布。

## 参与贡献

欢迎通过 Pull Request 提交修改。提交前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。所有修改经维护者审核后合并，由维护者编译并在插件商店发布。

## 许可

本仓库的代码依据 [PolyForm Strict License 1.0.0](LICENSE.md) 公开，并附有[补充许可](ADDITIONAL-PERMISSION.md)。概括如下：

- 可以查看、下载本仓库的代码，可以用于个人学习、研究等非商业用途；
- 为向本仓库提交修改，可以修改代码，并在自己的设备上编译、运行修改后的版本进行测试；
- 不得分发本仓库的代码或安装包，包括修改后的版本；
- 向本仓库提交修改，即表示同意依照补充许可中的条款，授权许可人使用、修改、发布该修改。

以上概括仅供参考，具体以 [LICENSE.md](LICENSE.md) 与 [ADDITIONAL-PERMISSION.md](ADDITIONAL-PERMISSION.md) 的原文为准。版权声明见 [NOTICE.md](NOTICE.md)。
