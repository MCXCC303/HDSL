<div align="center">
    <img src="src/main/resources/assets/img/icon@8x.png" alt="HDSL Logo" width="64"/>
</div>

<h1 align="center">Hello DeepSeek Launcher</h1>

<div align="center">

[![License](https://img.shields.io/badge/License-GPLv3-blue?style=flat-square)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Linux-lightgrey?style=flat-square&logo=linux&logoColor=ffffff)](https://www.kernel.org)
[![Java](https://img.shields.io/badge/Java-21-orange?style=flat-square&logo=openjdk&logoColor=ffffff)](https://openjdk.org/projects/jdk/21)

</div>

---

## 简介

HDSL 是一款 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（`dsh`）的启动器与版本管理器，使用 [HMCL](https://github.com/HMCL-dev/HMCL) 的 JavaFX 外观层构建。

DeepSeek Harness 通过 npm 发布、以 `dsh web` 启动。HDSL 把这些步骤收进一个图形界面：下载并安装任意已发布的 `dsh` 版本、为每个实例保留独立的运行环境与 `DSH_HOME`、管理插件、查看会话与运行日志，并在同一个窗口里启动和停止它们。

HDSL 的界面、控件与交互取自 HMCL，因此两者观感一致；领域层（`org.jackhuang.hmcl.dsh.*`）为 HDSL 原创。与 Minecraft 相关的功能已全部移除。

## 功能

- **实例管理**：每个实例带一份自己的 DeepSeek Harness 与独立的 `DSH_HOME`，可整体复制、移动或删除
- **版本管理**：从 npm 仓库读取已发布版本，按发布渠道筛选；实例可在不同版本之间迁移
- **插件管理**：经 `dsh plugin` 安装与管理插件，内置 dsh-market 等预设
- **会话与日志**：查看实例的会话列表与启动输出
- **多实例运行**：同时运行多个实例，各自占用独立端口
- **多实例文件夹**：可以把任意文件夹加入实例列表，原有数据无需搬迁

## 环境要求

| 项目 | 要求 |
|---|---|
| 操作系统 | **仅 Linux**（x86_64 / aarch64） |
| 运行时 | **Java 21**（构建与运行） |
| DeepSeek Harness | **Node.js `^22.19.0 \|\| >=24.0.0`**，以及用于插件管理的 **pnpm** |

HDSL 不自行下载 Node.js 之外的运行时，也不修改系统环境；它只调用 PATH 上的 `node`、`npm` 与 `pnpm`。

## 构建

```bash
./gradlew build      # 编译并运行测试
./gradlew run        # 直接启动
```

打包为 `.deb` 与自解压脚本：

```bash
./gradlew makeDeb
```

产物在 `build/libs/` 下。

## 数据目录

HDSL 的数据集中在 `~/.local/share/hdsl`（遵循 `XDG_DATA_HOME`）：

```
~/.local/share/hdsl/
├── instances/           每个实例一份运行环境与配置
│   └── <实例>/
│       ├── dsh/         该实例自己的 DeepSeek Harness
│       ├── home/        隔离的 DSH_HOME
│       └── instance.json
├── homes/               选择「同一版本共用」时使用的共享 DSH_HOME
└── launcher-settings.json
```

删掉这个目录即可完全卸载；其中没有任何东西写在别处。

## 参与贡献

HDSL 是 HMCL 的衍生作品，欢迎提交问题与改进。

- 提交缺陷或功能请求：请在本仓库创建 issue
- 提交代码：fork 后发起 pull request

## 开源协议

本作品是 **Hello Minecraft! Launcher** 的衍生作品，整体遵循 [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html) 开源协议，同时附有以下附加条款。

### 附加条款（依据 GPLv3 开源协议第七条）

1. 当你分发该程序的修改版本时，你必须以一种合理的方式修改该程序的名称或版本号，以示其与原始版本不同。（依据 [GPLv3, 7(c)](https://github.com/HMCL-dev/HMCL/blob/11820e31a85d8989e41d97476712b07e7094b190/LICENSE#L372-L374)）

   本作品的名称已改为 **Hello DeepSeek Launcher（HDSL）**，版本号见 `build.gradle.kts`。

2. 你不得移除该程序所显示的版权声明。（依据 [GPLv3, 7(b)](https://github.com/HMCL-dev/HMCL/blob/11820e31a85d8989e41d97476712b07e7094b190/LICENSE#L368-L370)）

   原始版权声明与源码文件头的版权信息完整保留，见 [`NOTICE`](NOTICE)。

界面层的说法与实现来自 HMCL 及其贡献者；DeepSeek Harness 领域层为 HDSL 原创。
