# HMCL-DSH 开发计划

> 用 HMCL 的 JavaFX 外观层，做成一个 **DeepSeek Harness (dsh) 的版本/实例管理器与启动器**。
> 仅 Linux。产品名 **HMCL-DSH**；沿用 `org.jackhuang.hmcl.*` 包名以保留 GPLv3 要求的版权头。

---

## 0. 已确定的关键决策

### 当前进度

| 里程碑 | 内容 | 状态 |
|---|---|---|
| — | 依赖闭包分析、375 文件移植清单、宿主层、切点修复 | ✅ |
| **M0** | `./gradlew run` 弹出 HMCL 风格窗口（主题/i18n/动画生效） | ✅ |
| **M1** | 设置页面（通用 / 外观 / 关于） | ✅ |
| **M2** | DSH 版本管理（Node/npm/pnpm 探测、npm 版本列表、私有 prefix 安装/卸载、CLI、版本页） | ✅ |
| — | i18n 修复（补齐 `LocaleUtils` 依赖的 CSV 与生成的 `languages.json`） | ✅ |
| **M3** | 实例模型 + `DSH_HOME` 隔离策略 + 实例列表页 | ✅ |
| **M4** | 启动与进程管理（web/headless/acp、URL 行就绪信号、优雅停止、日志窗口） | ✅ |
| **M5** | 主页右下角启动按钮 + 「正在运行的实例」面板 | ✅ |
| **M6** | 完整外观设置：本地图片 / 网络图片作背景、背景不透明度滑块、窗口透明 | ✅ |
| **M7** | Node.js 运行时管理：虚拟环境安装多版本 node/npm（对标 HMCL 的 Java 管理） | ✅ |
| **M8** | 安装向导（选版本 → 快速安装页填名称+勾选内容 → 下载等待）+ 快速预设目录 | ✅ |
| **M9a** | 插件管理页（列出已装插件、生效状态、卸载） | ✅ |
| **M9b** | 会话与 ACP 原生面板（Java 实现 ACP 客户端） | ✅ 已用 stub peer 单测覆盖流式路径 |
| **M10** | Linux 打包（deb + 自解压 sh） | ✅ |

验证截图见 `docs/screenshots/`。

### 用户明确要求保留/新增的 HMCL 视觉与功能

- **M5**：保留 HMCL 右下角的**大启动按钮**。点击后启动实例（实际是拉起 `dsh web` 并打开浏览器），
  DSH 在后台常驻。**主页只保留侧栏 + 启动按钮**；实例列表折进启动按钮右侧的展开菜单，
  菜单里显示每个实例的运行状态；**选中的实例若正在运行，按钮变成「停止」**，
  从交互上杜绝同一实例起多个进程（上游没有单实例锁）。
- **M6**：保留并补完**主题相关设置**。当前主题引擎已移植但设置项不全 ——
  最缺的是「选择图片作为背景」（`BackgroundType.CUSTOM` / `NETWORK` / `PAINT`，
  需要 `LineFileChooserButton` + 背景加载管线接线）。
- **M7**：把 HMCL 的 **Java 管理**换成 **Node.js 管理**。
  DSH 用 npm/pnpm 运行，所以逻辑对应：探测已装运行时 → 列出可下载版本 → 装到 `runtimes/<version>/` →
  实例选择运行时。真实价值是让用户不依赖系统 Node，尤其是系统 Node 版本不满足 `engines` 时。

### 已知上游注意点

- **新建 profile 的 `allowBuilds` 是占位符**。profile 模板写入的是
  `allowBuilds: { node-pty: set this to true or false }`，在用户解析它之前，
  pnpm 会拒绝运行 `node-pty` 的构建脚本并**以非零码退出**（但包其实已经装进去了）。
  这是上游行为（直接跑 `dsh plugin` 同样失败），HMCL-DSH **不会代替用户批准** ——
  `allowBuilds` 决定是否允许执行任意 postinstall 脚本，属于用户的安全决策。
  启动器做的是把 pnpm 那一堆进度输出翻译成一条可执行的提示
  （给出 `pnpm approve-builds` 与该 profile 的路径）。

### 关键决策

| 决策点 | 结论 | 理由 |
|---|---|---|
| 开发路线 | **路线 B**：抽取 HMCL 的 UI 工具箱，新建独立工程 | 不背 10 万行 Minecraft 死代码；移植层零改写风险 |
| 工程位置 | `/home/thf/Programme/Git/harness-launcher` | 与 `HMCL`、`deepseek-harness` 平级，独立 git 仓库 |
| 包名 | 保留 `org.jackhuang.hmcl.*` | 375 个文件零改写；GPLv3 §7(b) 要求保留版权声明 |
| 产品名 | HMCL-DSH | 社区认知延续 |
| 目标平台 | Linux only（x86_64 / aarch64） | 用户指定；砍掉 `.exe`、macOS、Terracotta |
| 目标 JDK | **21 + JavaFX 21.0.8**（CLASSIC 线） | 系统 `archlinux-java` 默认已是 21；**无需切换** |
| DSH_HOME 策略 | **默认每实例独立**；提供"共享 DSH_HOME"高级选项 | 见 §3.2，多版本共享是危险行为 |
| 启动方式 | 默认 `dsh --profile web --no-open --port 0` | URL 行是官方就绪信号 |
| 程序化接口 | 远期用 **ACP**，不用 `/api` | ACP 是稳定契约；`/api` 是内部 cookie 认证 |

---

## 1. 外观层移植：已完成的分析与切割

### 1.1 方法

对 `HMCL` + `HMCLCore` 两个源码根做了**类级依赖闭包**分析（`.dsh-migration/closure.py`）：

1. 把 `game/download/auth/modpack/addon/schematic/nbt/java/launch/setting/upgrade/countly/terracotta`
   以及 `ui/{game,instances,account,download,terracotta,skin,nbt,export,main,directory}` 标记为**领域层**；
2. 从 UI 工具箱种子包出发做 BFS，**禁止穿入领域层**；
3. 每一条被阻断的边记录为一个**切点**（必须改写/删除的具体代码位置）。

> 关键：wildcard import（`import org.jackhuang.hmcl.util.*;`）必须按**实际使用到的类名**解析，
> 否则会误报大量切点。第一版脚本没有做这件事，结果虚高到 869 文件 / 161k 行。

### 1.2 结果

| | 文件 | 行数 |
|---|---|---|
| ✅ **抽出的工具箱总量** | **375** | **68,174** |
| ├─ 真正零改动 | 346 | 56,955 |
| └─ ⚠️ 切点（需手术，是子集） | **29** | **11,219** |
| ❌ 领域层（丢弃） | — | ~100,000 |

> 注意：切点文件**已经包含在 375 个文件里**（它们是工具箱的成员，只是带着一条伸向领域层的边），
> 不是额外的文件。移植脚本抓取的是闭包全集，切点清单是其中的"待处理子集"。

> **闭包之外还有一类依赖：jar**。HMCL 用 `lib/JFoenix.jar` 作为编译依赖，同时用
> `HMCL/src/main/java/com/jfoenix/{controls,skins,transitions,effects,utils}` 下的**源码覆盖**其中一部分类
> （源码优先于 classpath），而 `com.jfoenix.converters` 等仍来自 jar。
> 源码 import 分析**看不见**这类依赖 —— 这是首次编译才暴露出来的缺口。
> 处理方式：把 `lib/JFoenix.jar` 一并复制，并在 `build.gradle.kts` 里加 `flatDir` + `implementation("libs:JFoenix")`。
>
> **静态 import 也是坑**：`import static org.jackhuang.hmcl.setting.SettingsManager.settings;`
> 只给出成员名，必须先解析到拥有它的**类**才能找到真实依赖。闭包脚本的初版漏掉了这一点，
> 因此漏判了 `HTMLRenderer.java` 和 `animation/AnimationUtils.java` 两个切点。

移植集按包分布（前 20）：

```
 9113  59  org.jackhuang.hmcl.ui.construct     组件库/对话框/列表
 6072  17  com.jfoenix.skins
 5969  24  org.jackhuang.hmcl.theme             主题引擎
 5704  24  com.jfoenix.controls
 5364  29  org.jackhuang.hmcl.util              （顶层工具，见下注）
 3868  12  org.jackhuang.hmcl.ui                FXUtils/SVG/DialogUtils/...
 3405  13  org.jackhuang.hmcl.task             异步任务 DAG
 3047  17  org.jackhuang.hmcl.util.io
 2562  18  org.jackhuang.hmcl.util.gson
 1999  11  org.jackhuang.hmcl.util.platform
 1937   6  org.jackhuang.hmcl.ui.decorator      无边框窗口装饰
 1853  14  org.jackhuang.hmcl.util.platform.windows   ← 可裁剪
 1581   3  org.jackhuang.hmcl.util.versioning
 1302   6  org.jackhuang.hmcl.util.i18n
 1277  11  org.jackhuang.hmcl.util.javafx
 1214   4  org.jackhuang.hmcl.ui.animation
 1175  10  org.jackhuang.hmcl.ui.image.apng.argb8888
 1083  10  org.jackhuang.hmcl.ui.image.apng.reader
  983   3  org.jackhuang.hmcl
  787   3  org.jackhuang.hmcl.ui.image
  (其余为 apng/wizard/hardware/linux/transitions/tree/logging/event/function)
```

外部依赖（从移植集的实际 import 统计，已写入 `gradle/libs.versions.toml`）：
`jetbrains-annotations`(209) · `gson`(110) · `jna`(40) · `MonetFX`(20) · `kala-compress`(13) ·
`weburl`(4) · `jsoup`(4) · `commonmark`(4) · `fxsvgimage`(4) · `webp`(3) · `pci-ids`(3) ·
`uuid-tools`(2) · `kala-encoding-detector`(2) · `xz`(2) · `nanohttpd`(1) · `simple-png-javafx`。

> 注：`org.jackhuang.hmcl` 顶层的 983 行是 `Launcher.java` —— 会被删除并重写。

### 1.3 切点工作清单（29 个标记 / **8 个真正需要改**）

> **验证方法**：把 375 个文件一起 `./gradlew compileJava`，收集所有报错文件与闭包预测集合求差。
>
> **结论：标记出的 29 个"切点"里只有 8 个真的有领域 import**，其余 21 个是脚本按标识符扫描时
> 命中注释/javadoc 的误报（例如 `MurmurHash2` 的注释 "Constants for 32-bit variant"、
> `ResourceNotFoundError` 的 `@see CrashReporter`）。真正阻塞编译的只有下面 8 个：

| # | 文件 | 处理 |
|---|---|---|
| 1 | `ui/decorator/Decorator.java` | 删除 authlib-injector 拖拽分支 |
| 2 | `util/io/NetworkUtils.java` | 删除 CurseForge API key 注入（`API_KEYS` 置空） |
| 3 | `ui/construct/TaskListPane.java` | 删除 `GameInstallTask` 特例与任务名翻译调用 |
| 4 | `ui/LogWindow.java` | 删除 JVM jstack dump 导出；`game.Log` → 新建的 `ui.LogLine` |
| 5 | `util/i18n/I18n.java` | 删除 37 个 MC 类的任务名/组件名翻译整块 |
| 6 | `util/platform/ManagedProcess.java` | `launch.StreamPump` → 复制到 `util.platform` |
| 7 | `ui/DialogController.java` | 整个删除（只服务账号登录） |
| 8 | `util/io/ChecksumMismatchException.java` | 改为 `extends IOException` |

**另外发现的两个闭包盲区（只有编译能暴露）**：

1. **jar 依赖**：HMCL 用 `lib/JFoenix.jar` 作编译依赖，同时用源码**覆盖**其中一部分类
   （`com/jfoenix/{controls,skins,transitions,effects,utils}`），`com.jfoenix.converters` 仍来自 jar。
   源码 import 分析看不见 —— 必须一并复制 jar 并加 `flatDir` + `implementation("libs:JFoenix")`。
2. **静态 import**：`import static …SettingsManager.settings;` 只给成员名，必须先解析到拥有它的**类**。
   初版脚本漏掉这点，因此漏判了 `HTMLRenderer` 和 `animation/AnimationUtils`。

**另有 21 个文件曾被误标**；它们的"切点"其实由新写的宿主类一次性满足，无需改动：

| 新写的宿主类 | 替代了 HMCL 的 | 满足的文件 |
|---|---|---|
| `ui/Controllers`（约 300 行） | HMCL 653 行的 `Controllers`（含游戏页面注册表、账号对话框、更新检查） | 11 个 |
| `setting/StyleSheets` | 依赖 `FontManager` 的版本 | 3 个 |
| `setting/{LauncherSettings, SettingsManager, LauncherState}` | HMCL 的 686 + 1726 行版本（含 schema、迁移器、游戏目录） | 4 个 |
| `setting/{BackgroundType, ThemeColorType}` | 原样复制 | — |
| `Metadata`（新写，约 90 行） | 含 Minecraft 目录/更新源的版本 | 6 个 |
| `Launcher` + `Main`（新写） | 含账号/下载/游戏自举的入口 | — |
| `ui/LogLine`（从 `game.Log` 改写） | Minecraft 日志行 | 1 个 |

**主题设置切片**（`theme/` 只需要这些，全部是 HMCL `LauncherSettings` 里本来就通用的部分）：
`selectedTheme` / `getSelectedThemeOrDefault` / `getThemeAppearanceOverrides` / `themeBrightnessMode` /
`customThemeColor` / `themeColorType` / `themeColorStyle` / `titleBarTransparent` / `windowTransparent` /
`backgroundType` / `builtinBackgroundId` / `customBackgroundImagePath` / `networkBackgroundImageUrl` /
`customBackgroundPaint` / `backgroundOpacity` / `networkBackgroundImageCachePolicy` /
`backgroundFallbackType` / `backgroundFallbackPaint` / `backgroundLoadPolicy`，
外加常量 `THEME_APPEARANCE_{BRIGHTNESS_MODE,COLOR,COLOR_STYLE}` 与枚举
`BackgroundType{DEFAULT,BUILTIN,CUSTOM,NETWORK,PAINT,THEME}`、`ThemeColorType{DEFAULT,SYSTEM,BACKGROUND}`。

> **里程碑 M0 的验收标准**：以上处理完，`./gradlew run` 弹出一个 HMCL 风格的空窗口
> （无边框标题栏、主题、动画、i18n 均生效）。

---

## 2. HMCL-DSH 架构

```
harness-launcher/
├── src/main/java/org/jackhuang/hmcl/
│   ├── ui/{construct,animation,decorator,image,wizard}/   ← 移植层（原样）
│   ├── theme/                                             ← 移植层（少量手术）
│   ├── task/, event/, util/{io,gson,logging,platform,javafx,i18n,function,tree}/
│   │                                                      ← 移植层（原样）
│   │
│   ├── dsh/                       ← 【新增】DSH 领域层
│   │   ├── DshVersion.java               一个已安装的 dsh 版本（semver + 私有 prefix）
│   │   ├── DshVersionManager.java        查询 npm 版本列表 / 安装 / 卸载 / 校验
│   │   ├── DshRelease.java               npm registry 上的一条版本+dist-tag
│   │   ├── DshInstance.java              实例：version + profile + workspace + DSH_HOME + 参数
│   │   ├── DshInstanceManager.java       实例 CRUD、持久化、快照
│   │   ├── DshHome.java                  DSH_HOME 路径解析与隔离/共享策略
│   │   ├── DshProfile.java               profiles/<name>/package.json 的读模型
│   │   ├── DshProfileTemplate.java       5 个内置模板：web/headless/acp/sdk/sdk-minimal
│   │   ├── DshPlugin.java                插件/bundle 元数据
│   │   ├── DshPreset.java                快速安装预设（见 §4）
│   │   ├── DshPresetCatalog.java         内置目录 + 远程目录刷新
│   │   ├── DshLauncher.java              拼命令行 + env，启动进程
│   │   ├── DshProcess.java               进程句柄：日志行、URL 行、退出码、优雅停止
│   │   ├── DshWebSession.java            web URL 行解析 + 就绪状态
│   │   └── DshNodeRuntime.java           node / pnpm 探测与最低版本校验
│   │
│   ├── setting/                   ← 【新增】HMCL-DSH 自己的设置模型
│   │   ├── DshSettings.java              全局设置（主题、语言、代理、并发…）
│   │   ├── DshSettingsManager.java       基于移植的 JsonSettingFile
│   │   └── ThemeSettings.java            移植层 theme/ 需要的设置接口
│   │
│   └── ui/dsh/                    ← 【新增】页面
│       ├── RootPage.java                 侧栏：实例 / 安装 / 设置 / 关于
│       ├── MainPage.java                 启动按钮 + 当前实例
│       ├── InstanceListPage.java         实例列表
│       ├── InstancePage.java             实例详情（配置/插件/会话/日志 四个 Tab）
│       ├── InstanceSettingsPage.java     profile、工作目录、DSH_HOME 策略、参数、env
│       ├── PluginPage.java               插件列表 + 启停 + 卸载
│       ├── SessionPage.java              会话列表（Phase 5 接 ACP）
│       ├── InstallWizardProvider.java    安装向导（版本 + 预设 + 快速安装插件）
│       ├── VersionListPage.java          npm 版本列表
│       └── NodeSetupPage.java            Node/pnpm 探测与安装指引
└── src/main/resources/assets/{css,themes,img,lang,about}   ← 移植资源
```

---

## 3. DSH 领域模型设计

### 3.1 磁盘布局

```
~/.local/share/hmcl-dsh/            (XDG_DATA_HOME)
├── settings.json                    全局设置
├── versions/
│   └── 0.1.6-alpha.2/               一个私有 npm prefix（version = manifest 的版本）
│       ├── package.json             { "dependencies": { "@deepseek-ai/dsh": "0.1.6-alpha.2" } }
│       ├── node_modules/@deepseek-ai/dsh/...
│       └── .hmcl-dsh.json           安装元数据（安装时间、来源 registry、校验）
├── instances/
│   └── <instance-id>/
│       ├── instance.json            实例定义（version、profile、workspace、homeMode、args、env）
│       ├── home/                    DSH_HOME —— 隔离模式下的实例私有 home
│       └── icon.png
└── catalog/
    └── presets.json                 快速安装预设目录（内置 + 远程刷新缓存）
```

**关键点：`versions/` 是 DSH 安装（code），`instances/<id>/home` 是 DSH 数据（data）。**
两者正交：两个实例可以共用一个版本，但各自拥有独立的 home。

### 3.2 DSH_HOME 策略（核心安全设计）

`DshHome` 支持三种模式，实例创建时选择，之后可改（附带数据迁移警告）：

| 模式 | DSH_HOME | 适用 |
|---|---|---|
| **`ISOLATED`（默认，强烈推荐）** | `instances/<id>/home` | 每个实例完全自洽：profiles / sessions / settings / credentials 全隔离 |
| `VERSION_SHARED` | `versions/<ver>/.dsh-home` | 同一 dsh 版本的多个实例共用一份数据 |
| `CUSTOM` / `SHARED` | 用户指定的目录（例如 `~/.dsh`） | 高级用户，接管已有安装 |

UI 必须在选择 `VERSION_SHARED`/`SHARED` 时显示警告，因为上游证据表明跨版本共享 `DSH_HOME` 会出问题：

1. **模块回退抖动**：`profiles/node_modules` 的回退符号链接会被不同版本互相判定为过期并重写；
   `healProfilesModuleFallbackLocked` **只增不删**，最终累积两个版本的并集。
2. **profile 是共享可变状态**：`dsh.profile.bundles` 只固定**包名**不固定版本，解析时**安装优先**；
   `normalizeShippedProfile` / `reconcileProfilePlugins` 会重写共享的 `package.json`，两个版本会互相覆盖。
3. **存储域版本锁**：`storages/workspace.json` 无兼容版本列表，schema 升级后旧版本启动即失败；
   `storage-json` 是最后写入者胜，且无跨进程锁。
4. **会话格式单向**：`SESSION_FORMAT_VERSION = 3`，旧构建**拒绝**新格式；不可跨版本读写同一 sessions 根。
5. **凭据/设置为文档版本化**：`.credentials.yaml` 版本不匹配直接抛错。
6. **DSH_HOME 根部没有版本标记/锁** —— 启动器必须自己记录"哪个 home 被哪个版本用过"。

因此 `instances/<id>/instance.json` 里要冗余记录 `dshHome` 与 `lastUsedVersion`，
实例列表页在版本与 home 不匹配时给出显式警告。

### 3.3 启动一个实例

```java
// 1. 解析
Path bin     = DshVersionManager.binOf(version);        // versions/<ver>/node_modules/.bin/dsh
Path profile = instance.profile();                       // profiles/<name>
Path cwd     = instance.workspace();                     // 会话按 workspace 分组

// 2. 命令（web 一律 --no-open，端口一律 0 由内核分配，避免 EADDRINUSE 直接退出）
List<String> cmd = List.of(bin.toString(), "--profile", profile,
                           "--no-open", "--port", "0");
// headless: List.of(bin, "--profile", "headless", "--json", task)
// acp:      List.of(bin, "--profile", "acp")

// 3. 环境（DSH_* 只能走进程环境；.env 文件会拒绝 DSH_ 前缀）
Map<String,String> env = new HashMap<>();
env.put("DSH_HOME", instance.home().toString());
env.put("DSH_TELEMETRY_DISABLED", "1");                  // 默认关闭遥测
instance.env().forEach(env::put);                        // 用户覆盖

// 4. 启动 → 复用移植层的 ManagedProcess / StreamPump / ExitWaiter
DshProcess p = DshProcess.start(cmd, cwd, env);
```

**就绪信号**：解析 stdout 上的
`^dsh web: (http://127\.0\.0\.1:\d+/\?token=[A-Za-z0-9_-]+)$`
（该行在 Loader 完全就绪 + required entry 审计通过之后才打印，是官方锚定的测试断言）。

**停止**：`destroy()`（发 SIGTERM）→ 等待 5 s（上游 `PROCESS_SHUTDOWN_TIMEOUT_MS`）→ `destroyForcibly()`。
第二个信号会强制退出，退出码 `SIGTERM=0` / `SIGINT=130`。

**必须解析**：web URL 行、退出码、headless `--json` 的 NDJSON、stderr 的 `Full diagnostics: <path>`。
**不得依赖**：其他 web stdout、人类可读的错误/帮助文本、`--dump-config` 的字节、`/api` 形状、
`.jsonl.zstd` 作为公开格式、任何 `tui` / `--resume` / `--config` / `plugin list` 界面。

### 3.4 版本管理

- 版本列表：`npm view @deepseek-ai/dsh versions --json` + `dist-tags --json`（`latest` / `alpha` 等）。
- 安装：`npm install --prefix versions/<ver> @deepseek-ai/dsh@<ver>`（或 pnpm），完成后写 `.hmcl-dsh.json`。
- 运行前置：**Node `^22.19.0 || >=24.0.0`**、**pnpm 11.7.0**（`dsh plugin` 需要 pnpm 在 PATH 上，否则退出码 127）。
  `DshNodeRuntime` 负责探测并在 `NodeSetupPage` 给出指引。
- 上游**没有版本管理器、没有自更新**（只有 Electron Desktop 有 updater）。
  所以版本管理完全由 HMCL-DSH 自己实现，这也是本项目的核心价值之一。
- 原生依赖均为预编译产物（`node-addon-system`、`node-pty`、`sharp`、`ripgrep`、`node-addon-require-builtin`），
  **不需要编译工具链**。

---

## 4. 快速安装预设目录（对标 Forge/Fabric/NeoForge/OptiFine）

上游仓库里**没有内置插件市场**，发现机制就是 pnpm。所以预设目录由 HMCL-DSH 自己维护：
`resources/assets/dsh/presets.json`（内置）+ 可选的远程刷新（缓存到 `catalog/`）。

预设分三类，UI 上视觉区分（对应 HMCL 的"安装器列表"）：

**Kind 1 — Profile 模板（等价于"装一个干净的游戏版本"，零网络下载）**

| 预设 | bundles | 说明 |
|---|---|---|
| **Web 界面（默认）** | `dsh-base`, `dsh-web-app` | 浏览器 UI，最常用 |
| **Headless 一次性任务** | `dsh-base`, `dsh-headless` | 跑一条任务就退出，`--json` NDJSON 输出 |
| **ACP 协议服务** | `dsh-base`, `dsh-acp-app` | 给 HMCL-DSH 自己做原生会话面板用 |
| **SDK** | `dsh-base`, `dsh-sdk-app` | 编程接口 |
| **SDK 精简** | `dsh-sdk-minimal` | 独立，不依赖 base |

创建方式统一走 `dsh --profile <new> --from-default-profile <template>`（目标目录必须不存在）。

**Kind 2 — 需要 pnpm 安装的 bundle（等价于 Forge/Fabric，**需要重启**）**

| 预设 | 包名 | 说明 |
|---|---|---|
| **插件市场 dsh-market** ⭐ | `dshmarket` | 社区最成熟的插件商店，**建议默认勾选** |
| 侧边栏增强 | `dsh-better-sidebar` | 用户当前 web profile 已在用 |
| 上下文管理 | `dsh-context` | 长会话上下文压缩/查看 |
| 成本计量 | `dsh-cost-meter` | token/费用统计 |
| 技能管理 | `@michengai/dsh-skills-manager` | 技能包管理 |
| 远程 Web UI | `@linxin666/dsh-remote-web-ui` | 手机/局域网访问 |
| Git 图谱 | `@linxin666/dsh-client-ui-git-graph` | 会话内 git 可视化 |
| 皮肤中心 | `@linxin666/dsh-client-ui-skin-center` | 主题/皮肤（HMCL-DSH 也可对接） |
| 鲸鱼挂件 | `dsh-whale-widget` | 装饰 |
| Codex 子代理 | `@deepseek-ai/dsh-subagent-codex` | 上游内置 bundle，装完即用 |
| Claude Code 子代理 | `@deepseek-ai/dsh-subagent-claude-code` | 同上 |
| 实验：代理团队 | `@deepseek-ai/dsh-experimental-agent-team-profile` + `-web-profile` | 上游已内置但默认关闭 |
| 实验：自动审查 | `@deepseek-ai/dsh-experimental-auto-review` | 同上 |

安装命令：`dsh plugin --profile <name> add <spec>`（转发给 pnpm；会自动把带 `dsh.bundle.patch` 的包追加进 `dsh.profile.bundles`）。

**Kind 3 — 零安装开关（等价于"改游戏设置"，即时生效，不下载、不重启进程）**

直接改 `<profile>/cordis.patch.yml`。这些行已经在 dsh 的依赖闭包里，只是 `disabled: true`：

| 开关 | 目标 |
|---|---|
| Ralph 循环工具 | `@deepseek-ai/dsh-tool-ralph` |
| 技能徽章 | `@deepseek-ai/dsh-skill-badge` |
| Web 定时任务 | web `ui-schedule` |
| MCP 客户端 | `@deepseek-ai/dsh-mcp-client` |
| 定时任务 | `@deepseek-ai/dsh-schedule` |
| GitHub webhook | `@deepseek-ai/dsh-webhook-github` |
| 备选 LLM 接入 | `@deepseek-ai/dsh-llm-pi-ai` |
| 浏览器自动化（实验） | `@deepseek-ai/dsh-experimental-browser-use-playwright-mcp` / `-chrome-devtools-mcp` |
| 计算机操作（实验） | `@deepseek-ai/dsh-experimental-computer-use-cua-driver-native` |
| LSP 支持 | `@deepseek-ai/dsh-lsp-stdio` + `@deepseek-ai/dsh-tool-lsp` |
| Office 技能 | `@deepseek-ai/dsh-skill-office` |
| Python PTC 运行时 | `@deepseek-ai/dsh-experimental-ptc-runtime-python` |
| 检查器 | `@deepseek-ai/dsh-experimental-inspector` |

> 上游共 **11 个包**声明了 `dsh.bundle.patch`：`dsh-base`、`dsh-web-app`、`dsh-headless`、
> `dsh-acp-app`、`dsh-sdk-app`、`dsh-sdk-minimal`、`dsh-subagent-codex`、`dsh-subagent-claude-code`、
> `dsh-experimental-agent-team-profile`、`-web-profile`、`dsh-experimental-auto-review`。
>
> ⚠️ **注意**：你本机装的 `tui` profile 和 `--resume` 参数**不在 0.1.6-alpha.2 上游仓库里** ——
> `dsh tui` 只是 `--help` 的示例，真正的 TUI 是第三方插件 `github:deepseek-harness/turtle-ui`。
> 预设目录里如果要提供 TUI，必须标注为第三方。

预设目录的每条记录建议的字段（上游**没有**图标/分类/权限元数据，所以要自己维护）：

```json
{
  "id": "dshmarket",
  "kind": "bundle",
  "name": "插件市场",
  "spec": "dshmarket",
  "description": "浏览、安装、分享 DSH 插件",
  "category": "core",
  "recommended": true,
  "icon": "assets/img/dsh/market.png",
  "homepage": "https://www.npmjs.com/package/dshmarket",
  "requiresRestart": true
}
```

---

## 5. 分阶段开发计划

### Phase 0 — 骨架与移植（进行中，约 3–5 人日）

| # | 任务 | 状态 |
|---|---|---|
| 0.1 | 依赖闭包分析，确定移植集与切点 | ✅ 完成 |
| 0.2 | 新建工程骨架（wrapper / build 文件 / 资源 / LICENSE） | ✅ 完成 |
| 0.3 | 复制 375 个移植文件 + 资源 | ✅ 完成 |
| 0.4 | 新增 `ThemeSettings` / `StyleSheets` / `Controllers`(精简) / Linux 窗口工具 / `StreamPump` 迁移 | ⬜ |
| 0.5 | 处理 12 个定点手术切点 | ⬜ |
| 0.6 | 删除 4 个待重写文件，写新 `Launcher`/`EntryPoint` | ⬜ |
| 0.7 | 裁剪 Windows/macOS 平台包（可选，-2100 行） | ⬜ |
| 0.8 | 裁剪 `assets/lang`（1500 key → 保留约 500，新增约 300） | ⬜ |

**M0 验收**：`./gradlew run` 出现 HMCL 风格空窗口；主题切换、动画、中英文 i18n 均正常。

### Phase 1 — 领域模型与版本管理（M1，约 5–8 人日）

- `DshVersion` / `DshVersionManager`：npm 版本列表、安装到私有 prefix、卸载、`.hmcl-dsh.json` 元数据。
- `DshNodeRuntime`：探测 node/pnpm 版本，不满足时给出明确指引。
- `DshHome` + 三种策略；`DshInstance` / `DshInstanceManager` + 持久化。
- `setting/DshSettings` + `DshSettingsManager`（复用移植的 `JsonSettingFile`）。

**M1 验收**：列出版本、安装两个版本、建两个隔离实例、重启后状态保持。

### Phase 2 — 启动与进程管理（M2，约 3–5 人日）

- `DshLauncher`：命令行拼装（web / headless / acp）、env 注入、cwd = workspace。
- `DshProcess`：复用 `ManagedProcess`/`StreamPump`/`ExitWaiter`；`--port 0` + URL 行解析。
- 优雅停止（SIGTERM + 5 s + 强杀）；退出码映射（0/1/127/130）。
- 日志窗口（复用移植的 `LogWindow`）；`Full diagnostics:` 的捕获与"打开日志目录"按钮。

**M2 验收**：点启动 → 浏览器打开 DSH Web UI；点停止 → 5 s 内退出；日志实时可见。

### Phase 3 — 界面替换（M3，约 8–12 人日）

- `RootPage` 侧栏：实例 / 安装 / 设置 / 关于（去掉账号、联机、下载源）。
- `MainPage`：启动按钮 + 实例下拉（几乎照搬 HMCL 的布局，只换数据源）。
- `InstanceListPage` / `InstancePage`（配置 / 插件 / 会话 / 日志 四个 Tab）。
- `InstanceSettingsPage`：profile、workspace、DSH_HOME 策略（带警告）、启动模式、附加参数、环境变量。
- `InstallWizardProvider` + `VersionListPage` + 预设勾选（复用移植的 `ui/wizard` 引擎）。
- 设置页清理：删掉 Java/内存/图形后端/下载源，保留主题、个性化、语言、代理、日志。

**M3 验收**：全流程不再出现任何 Minecraft 概念；新用户能从零装好并启动。

### Phase 4 — 预设与插件管理（M4，约 4–6 人日）

- `presets.json` 目录 + `DshPresetCatalog`（内置 + 远程刷新）。
- `dsh plugin --profile X add/remove` 的调用、日志流、失败回滚提示（pnpm 退出码）。
- Kind 3 零安装开关：直接编辑 `cordis.patch.yml`（YAML 读写用移植的 `util/gson`? → 需引入 SnakeYAML 或手写）。
- 插件列表读取：`profiles/<name>/package.json` 的 `dependencies` + `dsh.profile.bundles`。

**M4 验收**：一键装 `dsh-market`，重启 profile 后 Web UI 出现市场；可卸载、可启停。

### Phase 5 — 会话与 ACP（可选，M5，约 8–15 人日）

- Java 实现 ACP v1 客户端（stdio，`@agentclientprotocol/sdk@1.4.0` 的协议线格式）。
- `session/new` / `list` / `resume` / `prompt` / `cancel` / `update` 渲染成 JavaFX 面板。
- 这一步做完，HMCL-DSH 就不只是"启动器"，而是 DSH 的原生 GUI。

**M5 验收**：不开浏览器即可新建/继续会话并与 agent 对话。

### Phase 6 — 打包与发布（M6，约 3–5 人日）

- 移植 `CreateDeb` 思路，产出 `hmcl-dsh_<ver>_amd64.deb` + 自解压 `.sh`（纯 Java 实现，无需 `dpkg`）。
- 品牌替换：产品名、图标、URL、User-Agent、数据目录（**避免与真实 HMCL 冲突**）。
- **删除/重定向自更新链路**：HMCL 的公钥会验证并安装官方 HMCL，必须整体移除。
- 首次运行引导（Node 检查 → 安装 dsh → 创建实例）。

---

## 6. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| 上游 dsh 迭代极快（0.1.5→0.1.6 已换模板） | 预设目录/CLI 参数失效 | 只依赖 §3.3 列出的稳定面；预设目录远程可刷新 |
| 跨版本共享 DSH_HOME | 数据损坏 | 默认 `ISOLATED`；共享模式显式警告 + 记录 lastUsedVersion |
| 上游无插件市场元数据（无图标/分类/权限） | 无法自动渲染商店 | 自建 `presets.json` 目录并版本化维护 |
| 端口冲突（无自动回退，EADDRINUSE 直接退出） | 启动失败 | 一律 `--port 0`，解析 URL 行取实际端口 |
| pnpm 不在 PATH | `dsh plugin` 退出 127 | `DshNodeRuntime` 前置校验 + 安装指引页 |
| JavaFX 泄漏进核心（`Task.updateProgressImmediately` 里裸调 `Platform.runLater`） | headless 场景崩溃 | 若做 CLI 需加 toolkit 保护；GUI 场景无影响 |
| GPLv3 §7 附加条款 | 合规风险 | 改名 HMCL-DSH、保留版权头、开源完整对应源码、保留第三方声明 |
| 上游 HMCL 无法再 merge | 永久分叉 | 已接受（路线 B 本来就是独立仓库） |

---

## 7. 构建环境

- **JDK 21**（`archlinux-java` 默认已是 `java-21-openjdk`）—— **无需切换**。
  工程用 `sourceCompatibility/targetCompatibility = 21`，不固定 toolchain，兼容任意 ≥21 的 JDK。
- **JavaFX**：Linux x86_64 用 classifier `linux`，aarch64 用 `linux-aarch64`；
  版本随 Gradle 所在 JDK 自动选择（JDK ≤22 → `21.0.8`，JDK ≥23 → `25`）。
  可用 `-PjavafxPlatform=` / `-PjavafxVersion=` 覆盖。
- **网络**：首次构建需要下载 Gradle 9.7.1 发行版、插件与 Maven 依赖。
- **命令**：
  ```bash
  cd /home/thf/Programme/Git/harness-launcher
  ./gradlew run          # 运行
  ./gradlew build        # 构建
  ./gradlew build -PjavafxVersion=25   # 若用 JDK 25 运行
  ```

---

## 8. 迁移工具

分析脚本与移植清单保存在 HMCL 仓库的 `.dsh-migration/`（**不进入 HMCL 的 git**）：

| 文件 | 用途 |
|---|---|
| `closure.py` | 依赖闭包分析，重新生成移植集与切点清单 |
| `transplant.txt` | 375 个可原样移植的文件 |
| `cutpoints.txt` | 27 个切点文件及其被阻断的引用 |
| `bootstrap.sh` | 复制 wrapper / 源码 / 资源到新工程 |
| `write-build.sh` | 写入 Gradle 构建文件 |

---

## 测试

`./gradlew test`

`DshAcpClientTest` 用一个 stub ACP peer 覆盖会话流式路径。之所以需要 stub：
真实服务器把 stdout 完全留给协议帧，而且在配置好模型凭据之前不会流式输出助手文本 ——
也就是说这条路径用真机是测不出来的。

- `streamsAssistantTextAndReportsTheStopReason` —— 逐块送达 `session/update` 通知（顺序正确），
  并从 `session/prompt` 的响应里取到 stop reason。
- `closingTheConnectionStopsTheChild` —— 关闭 stdin 即协议自身的关停请求，peer 应当退出。

---

# 第二阶段：向 HMCL 的形态收敛

第一阶段的结论是「能跑」；这一阶段的目标是**让 HMCL-DSH 的组织方式与 HMCL 一致**，
而不只是长得像。六组任务按依赖顺序排列，每组都能独立验证和提交。

## 任务 1：导入系统会话（`~/.dsh` → 实例）

### 现状与约束

- 使用者已有 `~/.dsh`，其中 `sessions/<slug>/<uuid>/session.v3.jsonl.zstd` 是既存历史
- **明确要求：不对 `~/.dsh` 写入。** 这个应用不是系统 dsh 的管理器
- `storages/session_projcache/sessions/<uuid>.json` 是**明文 JSON**，含
  `identity.{formatVersion,createdAt,cwd}` 与 `rows.{title,sessionStats,costUsage,modelSelection,...}`

### 关键判断

**读取路径已经存在。** `DshSessions.list(home)` 只读；`DshSessions.migrate(src, session, dst, move=false)`
就是「复制到目标、不碰源」。所以本任务不是新机制，而是**把 `~/.dsh` 当作一个只读来源接进现有流程**：

1. `DshSessions.list(Paths.get(System.getProperty("user.home"), ".dsh"))` — 纯读
2. 兼容性仍按格式版本判定（源是 v3；目标实例的 dsh 版本决定能否读）
3. 复制时 `move=false`，并在 UI 上**不提供「移动」选项**，因为源不是我们管的
4. 目标已存在同 id 会话时跳过并计数

### 不做的事

- 不写 `~/.dsh`，包括不删除、不改 projcache
- 不做「打包为特定格式文件」——直接复制即可，中间格式没有收益
- 不解析 zstd 日志（Java 无内置解码器）；标题和元数据取自 projcache

### 验证

- `--import-sessions <dst> [--from <home>]` 新 CLI 子命令，打印导入/跳过数量
- 导入后用 `--list-sessions <dst>` 核对
- 断言 `~/.dsh` 的 mtime 与文件数不变

### 参考

dsh 自身没有 session 导入/导出接口；ACP 只有 `session/list|resume|close`。
`packages/` 下未见相关插件实现。**因此自行实现，但只用文件级复制。**

---

## 任务 2：重构实例创建与"环境"归属

### 问题

现在创建实例要问三件事，其中两件问错了地方：

| 现在问的 | 应该在哪 | 理由 |
|---|---|---|
| 工作目录 | **不问** | 对启动器无意义；dsh 的会话自带 cwd |
| DSH_HOME 是否独立 | **全局设置 + 实例设置，默认独立** | 这是环境策略，不是每次创建的问题 |
| Node.js | **全局设置** | HMCL 不会给每个游戏实例配一份 JRE |

### 目标形态

**创建实例页**按 HMCL 的「安装新游戏」复刻：**只有实例名称可编辑**，其余靠点卡片选择。

```
实例名称  [instance-4]
┌────────────┬────────────┬────────────┐
│  dsh       │ Better     │ Context    │      ← 卡片，点击进入选择页
│  0.1.5-rc.2│ Sidebar    │            │
│     →      │  不安装     │  不安装     │
└────────────┴────────────┴────────────┘
                                        立即安装
```

卡片点击 → 选择页 → 返回，**沿用现有 `VersionSelectPage` / `PresetChoicePage` 模式**。

**全局设置**新增「环境」分组：

```
环境
┌──────────────────────────────────┐
│ Node.js 运行时      系统 Node.js ▾ │   ← 对标 HMCL 的 Java 管理
│ DSH_HOME 策略        独立 ▾        │
└──────────────────────────────────┘
```

**实例设置**保留 DSH_HOME 策略（可覆盖全局），移除创建时的询问。

### 迁移

既有实例已有这些字段，`DshInstance` 不需改结构；只是把**默认值来源**从「创建时询问」
改为「全局设置」。已有实例的自有值优先于全局。

---

## 任务 3：实例列表 UI 复刻

对照原版补齐：

- **背景**（列表区域目前是纯色卡片，原版有内容区背景）
- **工具条**：刷新、搜索框
- **侧栏**：安装新游戏→**安装新实例**、安装整合包**去掉**、全局游戏设置→**全局设置**，
  以及**更改游戏目录**入口

---

## 任务 4：游戏目录（`hmcl-dsh/` ≙ `.minecraft/`）

- 引入「游戏目录」概念：当前只有一个隐含目录，需要显式化并支持多个
- 实例列表提供「添加实例文件夹」，自动扫描该目录下已有实例
- 对标原版 `GameDirectoryManager` 的行为

**注意**：这会动到 `DshPaths` 与实例的存储位置，属于**结构性改动**，
必须与任务 2 一起设计，否则会做两遍。

---

## 任务 5：下载页（仅「游戏 → 实例」）

复刻原版下载页的**游戏**部分，其余分类（模组/资源包/光影/世界）推后：

- 搜索、刷新、筛选：**正式版 / 全部 / rc / alpha**，默认**全部**（dsh 无正式版）
- 版本标记、更新时间
- 图标使用 `assets/img/dsh-version-list-*.svg`（使用者提供）
- 点击进入该版本的实例创建页（已有 `--page create-version:<v>` 的向导支持）
- **链接指向 GitHub 对应版本的 release**，而非 MCWiki

---

## 任务 6：启动进度弹窗

对标原版：启动时弹出进度窗口，**浏览器就绪后自动消失**，不再弹「已启动」提示。

- 复用原版的任务/对话框机制（`TaskExecutorDialogPane`、`Task`）
- 就绪信号已有：`DshProcess.webUrl()`（web surface 的就绪行）
- 兜底：若长时间未就绪，按原版的处理方式收尾

---

## 执行顺序与依赖

```
1 导入会话          独立，可先做
2 创建页重构        独立
   ↓ 与 4 联合设计
4 游戏目录          结构性，需与 2 同批设计
3 实例列表 UI       依赖 4 的目录概念
5 下载页            依赖 2 的创建向导
6 启动进度弹窗      独立，最小
```

**建议顺序：6 → 1 → 2+4 → 3 → 5**

先做 6（最小、独立、立刻改善测试体验），再做 1（独立），
然后 2 与 4 合并设计（都动实例的存储与创建），最后 3 和 5。

---

## ②+④ 合并设计（已定稿）

### 决定

- **不迁移、不复制任何现有实例。** 现有 `~/.local/share/hmcl-dsh/instances/*` 原地视为
  **默认游戏目录下的实例**，零改动。这与 HMCL 引入游戏目录时的动作一致：只扫描，不动数据。
- **全局设置只影响新实例与「跟随全局」的实例**，不回溯修改已有实例的自有值。

### ④ 游戏目录

```
GameDirectory(id, path, @Nullable customName)
```

- 持久化在 `launcher-state.json`，与实例列表同处
- 默认目录 = 现有的 `DshPaths.INSTANCES`，**开箱即用，既有实例自动归属**
- 「添加实例文件夹」：选定目录后**扫描**其子目录，凡含 `instance.json` 的即视为实例
- 侧栏「更改游戏目录」切换当前目录；实例列表按当前目录过滤
- 不因移除目录而删除任何实例文件

**落地方式**：`DshInstanceManager` 从「单一目录」改为「按目录读取」。
`DshPaths.instanceDirectory(id)` 保持不变（默认目录下的路径），新增
`DshInstanceManager.listIn(GameDirectory)`。

### ② 创建页与「环境」归属

**创建实例页**复刻原版「安装新游戏」：只有实例名称可编辑。

```
实例名称  [instance-4]
┌──────────────┬──────────────┬──────────────┐
│     dsh      │ Better       │ Context      │
│  0.1.5-rc.2  │ Sidebar      │              │
│      →       │    不安装     │    不安装     │
└──────────────┴──────────────┴──────────────┘
                                    立即安装
```

卡片点击 → 选择页 → 返回。**复用现有 `VersionSelectPage` / `PresetChoicePage`**，
它们已经是「点进去、选完返回」的形态。

移除三问：工作目录、DSH_HOME 策略、Node.js。

**全局设置新增「环境」分组**（对标 HMCL 的 Java 管理位置）：

```
环境
┌────────────────────────────────────┐
│ 默认 Node.js 运行时   系统 Node.js ▾ │
│ 默认 DSH_HOME 策略    独立 ▾        │
└────────────────────────────────────┘
```

**实例设置**对应两项改为可选「跟随全局设置」：

| 项 | 取值 |
|---|---|
| Node.js 运行时 | 跟随全局 / 系统 / 某个托管运行时 |
| DSH_HOME 策略 | 跟随全局 / 独立 / 版本共享 / 自定义 |

### 继承语义的实现

HMCL 用 `InheritableProperty`，但那是个未移植的属性框架（属切点），
**不为一个开关引入整套框架**。改用哨兵值：

| 字段 | 现状 | 改为 |
|---|---|---|
| `nodeRuntime` | `@Nullable String`，null = 系统 | null / `"global"` = **跟随全局**；`"system"` = 系统；其余 = 托管版本 |
| `homeMode` | 非空 `DshHomeMode` | 新增 `DshHomeMode.GLOBAL` = **跟随全局** |

`nodeRuntime` 已是可空，既有实例的 null 从「系统」变为「跟随全局」，
而全局默认值是 `system`，**行为不变**。
`homeMode` 加一个枚举常量比改成可空更省事，也不会让既有 JSON 失效。

新增全局设置：
- `defaultNodeRuntime`（默认 `system`）
- `defaultHomeMode`（默认 `ISOLATED`）

两者都要同步加进 `SettingsManager.Snapshot`——**漏了会表现为「设置存了但下次启动丢失」**，
这张坑表在 `CONTRIBUTING.md` 里。

### 验证

- `--list-instances` 增加目录维度输出
- 新增 `--list-directories` / `--add-directory <path>`
- 用 `--page create` 截图核对创建页形态
- 用 `--page settings/general` 截图核对「环境」分组
- 断言：改动前后既有实例的 `instance.json` 内容与位置完全不变
