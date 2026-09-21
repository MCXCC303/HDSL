# HDSL

用 [HMCL](https://github.com/HMCL-dev/HMCL) 的 JavaFX 外观层构建的
[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（`dsh`）启动器与版本管理器。

- **仅 Linux**（x86_64 / aarch64）
- **多版本并存**：每个 dsh 版本安装到独立 npm prefix
- **多实例隔离**：默认每个实例拥有自己的 `DSH_HOME`（也可显式共享）
- **保留 HMCL 外观**：无边框窗口装饰、组件库、动画、主题引擎、个性化设置、i18n

> 本项目**不启动 Minecraft**，也不包含任何 Minecraft 相关代码。

---

## 状态

**Phase 0 已完成，设置页面已完成。**

| 里程碑 | 状态 |
|---|---|
| 依赖闭包分析 → 375 个可移植文件，其中 8 个真正需要手术 | ✅ |
| 工程骨架 + 移植层 + 资源（68k 行 Java / 2.3 MB 资源） | ✅ |
| **M0** 宿主层补齐、切点修复、`./gradlew run` 弹出 HMCL 风格窗口 | ✅ |
| **M1** 设置页面（通用 / 外观 / 关于），由移植的主题引擎驱动 | ✅ |
| M2 起：DSH 版本管理 → 实例隔离 → 启动与进程管理 → 预设目录 → 打包 | ⬜ |

验证截图：[`docs/screenshots/`](docs/screenshots)。

完整路线图见 [`PLAN.md`](PLAN.md)。

---

## 构建

需要 **JDK 21+**（Arch Linux：`sudo archlinux-java set java-21-openjdk`，默认已是 21）。
工程不固定 Gradle toolchain，任意 ≥21 的 JDK 均可。

JavaFX 版本随 Gradle 所在 JDK 自动选择：JDK ≤22 用 `21.0.8`，JDK ≥23 用 `25`；
可用 `-PjavafxVersion=` 与 `-PjavafxPlatform=` 覆盖。

```bash
./gradlew run      # 运行
./gradlew build    # 构建
```

首次构建需要联网（下载 Gradle 9.7.1 发行版、插件与 Maven 依赖）。

---

## 目录结构

```
src/main/java/org/jackhuang/hmcl/
├── ui/{construct,animation,decorator,image,wizard}/   移植层：组件库/动画/窗口装饰/图片管线/向导
├── theme/                                             移植层：主题引擎
├── task/ event/ util/{io,gson,logging,platform,javafx,i18n,function,tree}/
│                                                      移植层：异步任务 DAG、通用工具
├── dsh/                                               【待建】DSH 领域层（版本/实例/进程/预设）
├── setting/                                           【待建】HDSL 设置模型
└── ui/dsh/                                            【待建】页面
```

包名保留 `org.jackhuang.hmcl.*`：移植层是 HMCL 的衍生作品，
GPLv3 §7(b) 要求保留原始版权声明，保留包名可让移植保持零改写。

---

## 许可

本项目是 **Hello Minecraft! Launcher** 的衍生作品，整体遵循 **GNU GPL v3**（见 `LICENSE`）。

依据 HMCL 在 `docs/README.md` 中附加的 GPLv3 §7 条款：

- 本作品已**更名**为 **Hello DeepSeek Launcher（HDSL）** 以区别于原作品（§7(c)）；
- 原始版权声明与版权头**完整保留**（§7(b)），见 `NOTICE`；
- 发行二进制时须一并提供完整对应源码。

界面层的说法与实现来自 HMCL 及其贡献者；DSH 领域层为 HDSL 原创。
