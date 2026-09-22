# HDSL 插件生态 / 打包 / 会话 调研报告

> 本文件是调研记录，**不进入 git**（不 `git add`，也不写进 `.gitignore`）。
> 结论来自：`../deepseek-harness` 源码与文档、本机已安装的 harness 产物与 profile、
> `dshmarket` 1.52.0 的未混淆 TypeScript 源码，以及四路并行只读调研与两处实测。
> 标注：**[实测]** 亲自跑过 · **[源码]** 以代码/文档为证 · **[推断]** 由已验证事实推导 · **[?]** 未验证。

---

## 结论速览

| # | 问题 | 结论 | 关键限制 |
|---|---|---|---|
| 1 | 插件市场（下载插件） | **可行，且不必依赖 market 包** | 公开目录 JSON（4053 条）；约 49% 条目只有 GitHub 源，安装慢 |
| 2 | 一次安装多个插件 + 顺序 | **CLI 可批量，但批量会丢失输入顺序** | 顺序 = `dsh.profile.bundles` 数组本身；后层整段覆盖前层 |
| 3 | 打包式会话导出/导入 | **官方只有单向导出，导入完全没有，必须自建** | slug 必须等于 `projectKey(cwd)`；压缩模式/版本/cwd 三处硬约束 |
| 4 | 应用+插件打包（整合包） | **配置级整合包可行**（仓库内 Desktop 发布链是先例）；已装 `node_modules` 不可行 | 平台原生包、pnpm store 绝对路径、凭据、构建脚本审批 |
| 5 | 批量删除插件 | **CLI 可行**；服务端 API 单名且**无回滚** | 一次 pnpm 事务，失败可能部分删除 |
| 6 | 从文件安装插件 | **可以，但有路径依赖陷阱（实测会弄坏 profile）** | profile 记录 `file:`/`link:` 路径，源文件消失后所有插件操作失败 |

---

## 1. 插件市场：数据源与安装命令

### 1.1 市场如何取得插件列表 [源码]

`dshmarket`（包名 **`dshmarket`**，非 `@deepseek-ai/dsh-market`）以静态 JSON 目录为唯一数据源：

- 主源：`https://awesome-dsh-plugin.com/plugins.json`
  （`dshmarket/src/regions.ts:80` `CATALOG_OFFICIAL`）。
  公开、无鉴权、CORS `*`、带 ETag/Last-Modified；实测 4.2 MB、`count: 4053`、
  `server: GitHub.com`（Pages + Fastly），由 `github.com/awesome-dsh-plugin/awesome-dsh-plugin` 每日 CI 生成。
- 第二源（更可版本化）：npm 包 **`dsh-plugin-catalog`**（`regions.ts:128`），
  取 `<registry>/dsh-plugin-catalog/latest` → `dist.tarball` → `package/plugins.json`
  （`src/catalog-npm.ts:91-119`）。带版本号、provenance，可走 npm 镜像。
- 区域路由（`regions.ts:130-158`）：`global` 用官方 URL；`china` 用腾讯 npm 镜像上的
  `dsh-plugin-catalog`，回退官方 URL，并给 GitHub 走 `gh-proxy.com` / `ghfast.top` 代理。
- 环境覆盖：`DSHM_REGISTRY_URL`（**整体替换**来源列表）、`DSHM_NPM_MIRROR`、`DSHM_GITHUB_PROXY`；
  区域选择持久化在 `profiles/<p>/.dsh-market/state.json`。
- 取回逻辑 `src/registry.ts:175` `loadRegistry()`：每个来源 2 次尝试，`if-none-match`/`if-modified-since`，
  304 复用内存体，15 s 超时；**故意不落盘、不带内置快照**（注释：*"stale is not a degraded answer, it is a wrong one"*）。
  全部失败 → `/dsh-market/registry` 返回 **502**。

条目形状（真实样例）：

```json
{"name":"dsh-j-space","owner":"AnonyJcy","url":"https://github.com/AnonyJcy/dsh-j-space",
 "page":"https://awesome-dsh-plugin.com/p/AnonyJcy/dsh-j-space/","category":"agi",
 "description":{"en":"...","zh":"..."},"npm":"@anonyjcy/dsh-j-space","version":"1.1.1",
 "stars":0,"downloads":259,"downloadsStart":"2026-08-22","downloadsEnd":"2026-09-20",
 "downloadsCheckedAt":"2026-09-21",
 "install":"dsh plugin --profile web add @anonyjcy/dsh-j-space","added":"2026-09-20"}
```

字段统计（对全部 4053 条计算）：`npm` 为 **null 的有 1969 条**（只能 `github:` 安装，慢路径），
2084 条有合法 npm 名，278 条带预编译 Release `tarball`，761 条带截图。
**除"在精选列表里"之外没有任何信任/签名/审核信号。**

搜索/筛选/分页**全在客户端**（`client/market-data.ts:426-488` 字段加权打分；`MarketSection.tsx:1077,1979` 分页切片），
没有服务端 search/pagination 接口。另有兼容性预检 `POST /dsh-market/discovery-compatibility`
（读 `engines.dsh` 与 `@deepseek-ai/dsh-*` peer，24 h 磁盘缓存）。

### 1.2 市场如何安装 [源码]

`POST /dsh-market/install`（`src/routes.ts:4414`）：
1. 同源校验（`:4421`）、agent 忙碌拒绝；
2. 服务端按 body 的 `{url}` 在目录里查条目，查不到 → 400 `plugin is not in the curated registry`
   （**客户端不能指定安装源**）；
3. `installTargetFor(entry)`（`src/sources.ts:330-339`）决定目标，优先级：
   **npm 名 → 预编译 Release tarball（需与条目 repo 一致）→ `github:owner/repo[#path:/sub]`**；
4. `spawn("<dsh> plugin --profile <p> add <target>")`（`src/dsh-cli.ts:938`，重新调用启动本 host 的那个 CLI）；
   参数整形：存在 `pnpm-workspace.yaml` 时注入 `-w`，`add|remove|install` 追加 `--reporter=ndjson`，
   目标串过 `TARGET_RE` 白名单；
5. 失败重试策略：`--config.minimumReleaseAge=0` / `--config.auto-install-peers=false` /
   `--config.fetchTimeout=600000`，pnpm 大版本漂移时 `pnpm install --no-frozen-lockfile`；
6. 装后：manifest 快照（失败回滚）、`validateAddedPlugins`（无 dsh 清单/无可用 entry/loader id 冲突则移除）、
   `hotMount` 或安排重启、`verifyActivation` 返回 `hot|restart|refresh`；
7. **构建脚本许可**是独立请求 `POST /dsh-market/approve-builds` → 写 profile 的
   `pnpm-workspace.yaml` 的 `allowBuilds`（`src/profile.ts:900-967`）。

### 1.3 harness 自身没有任何目录/搜索机制 [源码]

- `apps/cli/src/args.ts:171-183`：`dsh plugin` 是 **pnpm 纯直通**
  （`.allowUnknownOption()`、`.argument('[args...]', 'pnpm arguments, forwarded verbatim')`）。
  因此 `dsh plugin --profile web search|list|outdated|why` 都是 pnpm 自己的命令，
  `pnpm search` 打的是整个 npm registry，与 dsh 无关。
- 唯一的包元数据查询是 `inspect(spec)` → `pnpm view <spec> name version description dsh --json`
  （`packages/boot/plugin-manager/src/operations.ts:223`），只能查调用者点名的那个 spec。
- `packages/host/plugin-inventory` 是**只读**的 Loader 投影（不能增删启停）。
- 内置"清单"只有 `OPTIONAL_BUNDLES`（两个实验性 bundle，`app-boot/src/profile.ts:167-170`）。
- 全仓库检索 `awesome-dsh-plugin` **零命中**。

### 1.4 对 HDSL 的落点

- 数据源用 `awesome-dsh-plugin.com/plugins.json`（ETag 缓存）或 `dsh-plugin-catalog`，
  **不要依赖 `dshmarket` 包**。安装走现有 `DshPluginInstaller`（即 `dsh plugin add`）。
- market 独有的"安全增值"不会白拿：目录校验、重名/别名冲突、loader id 冲突检测、
  manifest+lockfile 回滚、pnpm 失败分类与重试、collection repo 重定向、hot-mount、
  兼容性评估，以及 **beta** 的同源 API `/dsh-market/api/v1/{capabilities,updates,operations,rollback,restart}`。
- 界面照 HMCL 的远程列表页（`ui/instances/DownloadListPage`：搜索 + 列表 + 版本/来源选择），
  复用 `PluginListPage` 的行样式、`DshPluginInstaller`、进度对话框；
  来源选择可复用刚做的 `NodeSource` 模式。

---

## 2. 一次安装多个插件 & 顺序敏感性

### 2.1 能否一次装多个：能

`dsh plugin --profile <p> add a b c` 合法：变参 → `pnpm add a b c`，**一次 pnpm 运行**
（B 用实例自带 commander 15 复现了参数解析；官方文档 `apps/cli/reference/README.md:69-73` 就是这么示范的）。
相对路径 spec 会被 dsh 锚定到**调用时的 cwd**（`operations.ts:46-50` `anchorPathSpec`）。
服务端 API `PluginManager.installBundle` 是**单 spec**（装出 ≠1 个新依赖即 `ambiguous-install`，`index.ts:368-373`）。
代价：一次 CLI 调用 = 一次 `package.json` 锁（120 s 等待）+ 一次 pnpm 运行。

### 2.2 顺序规则 [源码]

`reconcileProfilePlugins`（`app-boot/src/profile-plugins.ts`，被 `operations.ts:73-97` 调用）：

- **顺序 = `dsh.profile.bundles` 数组本身**。没有拓扑排序、没有字母排序、
  **harness 级没有 `before`/`after`**（`dsh.bundle` 清单只有 `patch` 一个字段，
  `packages/util/package-manifest/src/types.ts:51-55`）。
- 已有条目**保持原位**；只有依赖消失或失去 `dsh.bundle` 才被剔除；
  in-box 包（`dsh-base`、`dsh-web-app`、`dsh-headless`）固定、不可增删；
- **新装的追加到末尾**（`bundles.push`）；**重新启用也追加到末尾**
  （`index.ts:529`；测试 `manager.spec.ts:120-129` 断言 disable 保留依赖、re-enable 加到尾部）。
- **⚠ 批量安装的坑**：一次装多个时追加顺序取 `package.json` 的依赖键序，
  而 **pnpm 会把依赖键归一化为字母序**（pnpm 源码 `normalize2()`/`keys4.sort()`）→
  **输入顺序丢失**。要保序必须**逐个安装**（HDSL 现在的做法正确）。
  实证：profile 的 `dependencies` 是字母序，而 `bundles` 是安装序。

### 2.3 顺序为什么敏感 [源码]

层栈构成（`profile-context.ts:63-75`）：`bundles` 顺序 → profile `cordis.patch.yml` →
home `$DSH_HOME/cordis.patch.yml` → `--patch`（按 argv 顺序）→ 应用 entry patch（`profile.ts:944-950`）。

- **后层按 row id 覆盖前层，且 patch 是整段替换 `config`，不深合并**
  （`docs/user/develop/basic/publish.md:123-126`；`vendor/include/src/index.ts:120-123`）。
- **前层无法 patch 后层插入的 row**（目标尚不存在 → 警告并跳过，`include/src/index.ts:110-113`）。
- 插入顺序 = 可见顺序（根 row 与 group 子 row 都按顺序追加）。
- 重复 row id 折叠为一条，**后者胜**（`group.ts:51-55`）。
- `inject` 负责服务就绪，**不是**顺序机制。

第三方约定：`dsh.bundle.order.{before,after}` 是 **market 自己引入**的
（`dshmarket/src/order.ts`，"issue #98"），harness 不认；本机已装的 7 个社区插件都没声明它。
market 的做法值得抄：**改序前试装校验（replay 组合）+ 快照 + 失败拒绝写回**（`trial.ts`、`snapshot.ts`、`presets.ts`）。

### 2.4 启用/禁用

- bundle 级：`package.json` 里是依赖但不在 `bundles` 数组 = **已安装但休眠**；
  `setBundleEnabled` 只改数组、不跑 pnpm。
- row 级：`cordis.patch.yml` 里 `{id, disabled: true}`（`patch.ts:14-43`）。
- **CLI 没有"装了但不启用"的开关**：成功 `add` 必然自动激活；
  要 install-but-disabled 只能用服务端 API（`installBundle(spec,{enabled:false})`）或 Web UI。

---

## 3. 打包式会话导出/导入

### 3.1 一个会话包含什么 [源码]

```
<DSH_HOME>/sessions/<slug>/<session-id>/
    session.v<N>.jsonl.zstd     # 唯一不可再生的产物：第 1 行 header，其后每行一个事件
    session.lock                # 0 字节，flock 目标；可复制可删除，harness 会重建
    session.v3.jsonl.zstd.cost-meter-backup-*   # 第三方 dsh-cost-meter 的备份，非规范名
```

- 路径是**推导**出来的，不是记录的：`projectKey(cwd)` → 目录 slug；文件名 `session.v<N>.jsonl[.zstd]`
  （`session-persistence-jsonl/src/format.ts:224-304`）。
- 标题在日志里（`session/title` 事件），**投影缓存严格可再生**：
  *"a stale or unreadable cache costs a longer tail replay, never a wrong value"*
  （`session-projection-cache/src/spec.ts:86-88`）。
- 除 projcache 文件名外，整个 home 里只有 `storages/workspace.json` 与第三方
  `storages/cost-meter/ledger.json` 引用会话 id。
- **最小可搬运集合 = 会话目录（规范日志）本身**；分组另需 workspace 注册表。

### 3.2 三条硬规则 [源码]

| 规则 | 位置 | 违反后果 |
|---|---|---|
| ① 目录 slug 必须等于 `projectKey(header.cwd)` | `session-persistence-jsonl/src/index.ts:1436-1463`，测试 `jsonl.spec.ts:2535` | **在 list 阶段抛错** → 整个 home 列不出来 → workspace 初始化失败 → 实例可能起不来 |
| ② `cwd` 必须解析到真实目录 | `workspace/src/index.ts:597-611` | 仍能列出/打开，但**不属于任何项目**（"未分组"），不会为它建 workspace |
| ③ 续聊要求 cwd 字符串**完全相等** | Web `api/session-controller/src/agent.ts:463-464`（`ApiSessionCwdConflict`）、headless `bundle/headless/src/index.ts:224-229` | 换机器/换路径后续聊被拒（不 realpath 归一化） |

### 3.3 版本行为 [源码]

- `SESSION_FORMAT_VERSION = 3`（`core/session/src/types.ts:88`），header 里也是 `version: 3`；
  已发布格式记录：`docs/session-format-status.md` → `latestReleasedVersion: 3`，evidence `dsh-v0.1.5-alpha.1`。
- 更新的格式：list **跳过**，打开报"升级 harness"；更旧的格式：读取时按 v0→v1→v2→v3 链迁移
  （`session-format-catalog/src/generated.ts:14-31`）。
- **写入式打开会在旁边发布新一代文件**（旧文件保持不动），因此目录里可能同时存在
  `session.v2.jsonl.zstd` 与 `session.v3.jsonl.zstd`，**harness 取版本号最高者**。
- 事件 schema 是 TypeScript 类型 + 手写审计校验（无发布的 JSON-Schema）：
  未知的**必需**事件类型必须拒绝读取（`core/session/src/types.ts:470-489`，60 个已知类型见 `known-event-types.ts`）。
  → 第三方**读**可行；第三方**写**必须复现全部审计规则，风险高。

### 3.4 现有功能：只有单向导出 [源码]

- 插件 `@deepseek-ai/dsh-session-log-export`（仅 web bundle 挂载）：
  `/export` 斜杠命令 + `GET /api/session.export?sessionId=<id>&includeDescendants=true`。
- ZIP 内容：根日志 `session.v3.jsonl`（**未压缩**）、子会话 `subagents/<id>/session.v3.jsonl`、
  图片 `media/<attachmentId>.<ext>`、文件 `files/<sha[:2]>/<sha>/<name>`；**没有 manifest**。
- 它是**浏览器下载**（文档明说 "produces a browser download, not a Host path write"）。
- **没有任何导入路径**：`session.import|importSession|uploadSession` 全仓库 0 命中；
  CLI 只有 `web|headless|tui|rescue|plugin`、`--profile`、`--patch`、`--dump-config`。
- **官方导出件不能直接回灌**：未压缩 `.jsonl` 放进 zstd 配置的 home 会让**整根**报
  `encodingMismatch`（`index.ts:1534-1542`）。
- 目前唯一可用的导入器是 HDSL 自己的目录复制（本报告 §3.5 的风险点）。

### 3.5 打包/导入必须处理的事项

1. **附件**：引用图片/文件的会话需要 `attachments/v1/**`（按 sha256 内容寻址，复制即可）。
   HDSL 现在的 `DshSessions.migrate` **不搬附件**（源码注释自认），
   而 `.dsh/attachments` 有 **119 MB** → 导入后引用会 404。**这是最大的静默丢失点。**
2. **slug 照抄**（规则①），**压缩模式与目标一致**（否则整根拒绝）。
3. **各代日志都带上**（或至少最高代）。
4. **子会话整棵子树**：父单独导出只让其子行变 `unavailable` 诊断（优雅降级）；
   **子单独导出基本不可达**（UI 用 `(parentSessionId, childSessionId, mode)` 寻址，拒绝裸地址，
   `api/session-controller/src/history.ts:333-372`）。
5. **workspace 分组**：`initialized:false` 让 harness 从 header 重新派生（无需自己写注册表——
   写错 schema 会让实例起不来，见 HDSL 历史 bug）。
6. **期望 cwd 与 preset 写进包**：自定义 `agentPreset` 在缺该 preset 的 home 上无法续聊
   （`preset/agent-presets/src/index.ts:372-385`）。
7. **按"可能含密"对待**：抽查 97 个真实日志，命中 1 条——工具输出里打印了
   `.credentials.yaml`，含真实 `DEEPSEEK_API_KEY: sk-…`。导出的包必须视为敏感数据。

### 3.6 HDSL 现存 bug（本次调研发现，已复核）

`DshSessions.LOG_FILE = ^session\.v(\d+)\..+$`（`DshSessions.java:78`）会匹配
`session.v3.jsonl.zstd.cost-meter-backup-<stamp>-<uuid>`（本机真实存在 6 个），
且 `findLog` 用 `Files.list(...).findFirst()`（枚举顺序不定）；
同一目录存在两代日志时还可能取到**低版本**，把 `refusalReason` 的版本判断带偏。
修法：只认规范名 `session[.vN].jsonl[.zstd]`、排除插件备份、取版本号最大者。

---

## 4. 应用 + 插件打包（DSH 整合包）

### 4.1 可复现清单（机器无关）[源码]

- `<instance>/dsh/package.json` 的 `@deepseek-ai/dsh` 版本；
- `<instance>/dsh/pnpm-workspace.yaml` 的 `@deepseek-ai/dsh-app-boot` override（**HDSL 已在写**）；
- profile `package.json`：`dependencies` + **有序** `dsh.profile.bundles`；
- profile `pnpm-lock.yaml`：550 条 `resolution.integrity`、**0 条 tarball URL**（按 registry+integrity 解析）；
- profile `cordis.patch.yml`；
- 可选：home 的 `sessions/`、`storages/`（`workspace.json` 让 harness 自己重建更好）。

### 4.2 不可移植 / 障碍 [源码+实测]

- 已装 `node_modules`：平台原生包（`node-addon-*-linux-x64`、`@img/sharp-linux-x64`、
  `@koromix/koffi-linux-x64`、node-pty 各平台预编译）、
  `packages/subprocess/subprocess-local/package.json:41` 的 `postinstall` 只做 `chmod +x`
  （归档/Windows 拷贝易丢可执行位）；
- pnpm 记账的绝对路径：`.modules.yaml` 的 `storeDir`、`.pnpm-workspace-state-v1.json`；
- `instance.json` 的 `workspace`/`nodeRuntime`/`port`；
- 会话 header 的**绝对 cwd**；
- `.credentials.yaml`（**绝不能进包**）；
- **构建脚本审批**：pnpm ≥10/11 需要 `allowBuilds`；离线复现要在包的 profile
  `pnpm-workspace.yaml` 里预先声明，或让用户批准；
- registry 可达性（可复用刚做的下载源选择）。

### 4.3 仓库内的先例 [源码]

Desktop 发布链就是"app + 私有 host + 插件"打成一包：
`apps/desktop/src/core-package-set.ts`（`desktop-packages/*.tgz` + `integrity` +
用 `overrides` 把所有核心包挡在 registry 外）、`runtime-tree.ts`
（*"Relocatable, integrity-recorded production packages"*）、
`scripts/prepare-dsh.ts`（`install --lockfile-only` → `--prod --frozen-lockfile --trust-lockfile`，
自带 store，解引用 + 按平台过滤）。
**没有通用 export/import 命令**（全仓库检索 `exportProfile|importProfile|profile export|import` 零命中）。

### 4.4 加载流程（用现有 API 即可）

写 dsh 清单 → `pnpm install`（或 vendored tarball / 离线 store）→ 建 home →
写 profile `package.json`（依赖 + **按记录顺序逐个 `dsh plugin add` 以保序**）→ 写 patch →
可选导入会话。装完通常**需要重启** harness（包替换无法热加载）。

---

## 5. 批量删除插件

- **CLI 可以**：`remove a b c` → 一次 `pnpm remove`，成功后 reconcile 把它们从 `bundles` 移除。
- **服务端单名**（`removeBundle(name)`），顺序：先从 `bundles` 取消选择 → 卸载运行时贡献 → `pnpm remove`；
  前置拒绝：安装自带包不可删、`bundle-in-use`、无 HMR 时的 `stop-profile`。
- **回滚差异**：`installBundle` 失败/取消会恢复 `package.json` + `pnpm-lock.yaml`；
  **`removeBundle` 没有回滚**。README 的失败语义：*"Stop at the failed step. Preserve completed changes…"*
  → 批量删除失败可能留下部分删除。
- 顺序对删除影响小（都移除），批量删除比批量安装安全。
- 界面：HDSL 插件页目前只有逐行删除；多选要新做（HMCL 的 `ModListPage` 有多选/批量删除先例）。

---

## 6. 从文件安装插件

### 6.1 接受的 spec 形式 [源码]

- **CLI（宽）**：原样转发 pnpm → npm 名、`name@range`、alias、目录、
  `file:`/`link:`、`.tgz`/`.tar.gz`、tarball URL、git（`github:`/`git+ssh://`/托管 URL）、任意 pnpm 参数。
  相对路径被 dsh 锚定到**调用时的 cwd**（`operations.ts:46-50`）。
- **Web/agent 路径（严）**：`install-spec.ts:51-73` 要求本地路径**必须是绝对路径**，
  tarball 也接受，`inspect(spec)` 会先读路径的 `package.json` 校验是否声明 `dsh.bundle`。

### 6.2 路径依赖陷阱 [实测]

pnpm 11.26.0 实测：

- `pnpm add ../x.tgz` → `"dependencies": {"lib-a": "file:../x.tgz"}`；
- **删掉该 tgz 后再装第三个包 → `ENOENT … x.tgz`，整次操作失败**（第三个包也没装上）
  → **一个本地文件 spec 能让整个 profile 后续所有 add/remove 都失败**；
- 目录形式（dsh 锚成 `link:/abs/dir`）是**活符号链接**：源目录没了，启动时
  `resolveBundleDir` 直接报 "cannot resolve profile bundle…"；
- 只有 `file:` 目录/tarball 的内容会被物化进 store，运行时不受影响，但**重解析仍要源文件在位**。

### 6.3 为什么必须走 `dsh plugin`

只改 `package.json` 不会激活插件——不在 `dsh.profile.bundles` 里就是"已安装但休眠"
（`PluginListPage` 的勾选框正是显示这个状态）；bundle 协调、lock、
pnpm 的 `allowBuilds` 审批都在那条路径上。

### 6.4 正确做法

要支持"从文件安装"，就把包**复制进实例自己的目录**（如 `<instance>/plugins/foo-1.0.0.tgz`），
用该文件在实例内的路径安装，并明确告知"该文件已成为实例的一部分、不能移动/删除"；
优先 `.tgz`（物化快照）而非目录 symlink；能在 registry 找到就别用本地文件。

---

## 7. 验证结果（本轮实测）

会话规则已用**真实 home 的副本**（`~/.cache/hdsl-verify/home`，95 个会话）实测，方法是直接启动 harness
本体（`node <dsh>/lib/bin.js --profile web --port <port> --no-open`）：

| 实验 | 结果 |
|---|---|
| 基线（不改动） | 启动成功：`dsh web: http://127.0.0.1:…`，HTTP 303，无错误 |
| **① 把一个 slug 目录改名** | **启动整体失败**（exit 1）：`failed to apply loader entry workspace (@deepseek-ai/dsh-workspace): corrupt session log "…": header id "…" and cwd identify "…"` → 一个目录名不对 = **整个 home 起不来** |
| **④ 在会话目录里放未压缩 `session.v3.jsonl`** | **启动整体失败**（exit 1）：`encodingMismatch …` → 整根被拒 |
| ② 会话的 `cwd` 已不存在（构造：header 改到不存在的路径，slug 相应改名） | **启动正常**；该会话**不进任何 workspace**（`workspace.json` 里没有它的路径），界面里落"未分组" |
| ⑤ 日志的 zstd 分帧 | 真实日志是 **每行一个 zstd 帧**（实测 60 帧 / 60 行）；把整份日志压成**单帧**会被拒：`corrupt Zstandard session log: first frame is not exactly one header line`；改成"**第一帧只放 header 行**、其余另起一帧"后通过 → **任何工具都不该重新编码日志，只能复制** |

两条方法论：`--dump-config` **不初始化会话/工作区存储**（改名 slug 后它照样 exit 0），
所以"能不能启动"的判断必须用**真正服务**的启动方式；`zstd -l` 可以看帧数，用来确认日志的写法。

规则 ③（续聊要求 cwd 字符串完全相等）本轮未测：需要真的续聊一次。

其余待验证项（仍然开放）：整实例 `cp -a` 到别处能否启动；`add --offline` 的冷/热 store 行为；
`dsh plugin list --json` 作为已装清单；附件缺失时的具体失败形态；未知 `agentPreset` 的失败形态；
跨版本（v3 → 未来 v4）会话导入。详见 §3.5–§3.6 与本节。

另外，启动器侧那个日志文件名 bug 已修（提交 `48fec1d`）：只认 harness 读的规范名、
取版本号最高的一代、两代同名时取压缩的那份，并加了两个单测（其中一个就用
`dsh-cost-meter` 留下的备份文件名）。

### 7.1 插件目录与网络的实测（本轮）

- 目录主机 `awesome-dsh-plugin.com` **可达性不稳定**：本轮先取到 4.2 MB 全量目录（4053 条、169 页），
  随后连续多次超时（curl 亦然），页面因此进入失败态。→ 目录客户端需要**超时 + 重试 + 可换源**，
  这正是 market 的 region 路由与 `DSHM_REGISTRY_URL` 存在的原因；本启动器加了
  `-Dhdsl.pluginCatalog=<url>` 作为镜像/离线测试入口（离线用本地 HTTP 服务验证过整页）。
- `github.com` 在本网络被劫持：`pnpm view github:owner/repo` 报 `ERR_PNPM_INVALID_PACKAGE_NAME`
  （`pnpm view` 只查 registry、不接 git spec，属正常），但更要紧的是 **1969 条只有 GitHub 源的插件
  在本网络装不上**（克隆/下载会失败）。npm 源的 2084 条正常：抽样 3 条 `pnpm view <spec> version` 均成功。
  → 与 Node 下载源同理，值得给 git 源加代理选项（market 用 `gh-proxy.com` / `ghfast.top`）。
- 因此"插件市场页"的可用范围要说清楚：**npm 源可用，git 源取决于网络**。

### 7.2 会话包的实测（本轮，端到端）

用新加的命令行 `--export-sessions` / `--import-pack` 对**真实实例**做了一遍（导出只读，导入进独立 scratch 实例）：

| 步骤 | 结果 |
|---|---|
| 从 `0.1.6-alpha.2` 导出 | 95 个会话、**2 个被引用的附件**（用 `zstd -d` 扫描日志里的 64 位内容寻址 id 得到闭包，而不是整库 119 MB）、95 MiB |
| 导入全新 home（有 profile、无会话） | `Imported 95, skipped 0, attachments 2`；95 个日志**逐字节与源一致**；95 条 projcache 行、2 个附件到位；`workspace.json` 被置为 `initialized:false` |
| 再导入一次 | `Imported 0, skipped 95` —— 幂等，已存在的会话一律不碰 |
| **启动 harness** | `dsh web` **READY**；启动后注册表 `initialized:true`、**16 个项目、95 条会话归属**（HMCL / Medical-AI-2026 / SpeedyNote / shisui-danmu…），即由 header 重新派生分组成功 |
| 界面 | 会话页工具栏出现「导出全部会话 / 导入会话包」（截图 `feat-session-pack-toolbar.png`），导入后的列表**带标题**（标题随包携带的 projcache 行） |

由此确认的实现要点：**日志只能逐字节复制**（本启动器没有 zstd 解压器，且重新编码会破坏"首帧=header"约束）；
包必须带**同目录名**（实测：错位 slug 会让 harness 拒绝启动）；**压缩模式必须与目标 home 一致**
（实测：未压缩日志写进 zstd home 同样拒绝启动）；附件按内容寻址复制即可，无需索引；
分组交给 harness 用 `initialized:false` 重新派生。

### 7.3 整合包（配置级）的实测（本轮，端到端）

用新命令 `--export-modpack` / `--restore-profile` 对真实实例做了一遍：

| 步骤 | 结果 |
|---|---|
| 导出 `0.1.6-alpha.2` 的配置 | 784 字节的包：`manifest.json`（dsh 版本 0.1.6-alpha.2、profile=web、**7 个插件含版本**、**9 个 bundle 的原始顺序**）+ `cordis.patch.yml`（217 B）；**不含** `node_modules`/lockfile/凭据 |
| 还原进 scratch 实例（空 home + 符号链接的 dsh） | 先让 harness 自己初始化 profile，然后只改写 `dependencies` 与 `dsh.profile.bundles` 两个字段，再跑**一次** `dsh plugin install` |
| 结果比对 | 还原后的 `dependencies` 与 `bundles` **与源实例逐项、逐序一致**（`list(...) == list(...)` 为 True）；patch 层写入 ✔；harness 目录里其余字段（含它自己加的未知字段）保持不动 |
| **启动 harness** | `dsh web` **READY** —— 说明按记录顺序还原出的插件栈能被 harness 正常加载 |
| 单测 | 记录版本/插件顺序、还原只动两个字段且保序、非包文件拒绝、缺 dsh 版本拒绝 |

由此确认：**配置级整合包完全可行**，且"保序"的正确做法不是逐个 `dsh plugin add`
（那会把每个新 bundle 追加到末尾），而是**写好 manifest 后一次 resolve**——协调逻辑会保留 manifest 里已有的条目顺序、只追加缺的。

### 7.4 从文件安装插件的实测（本轮，端到端）

用一个真实打包的插件（`pnpm pack` 出来的 295 B `dsh-hello-local2-1.0.0.tgz`，声明 `dsh.bundle.patch`）
在 scratch 实例（符号链接 dsh + 真实 profile 副本）上验证：

| 检查 | 结果 |
|---|---|
| 安装时做了什么 | 先把文件复制进 `<instance>/plugins/dsh-hello-local2-1.0.0.tgz`，再从**这个副本**安装（日志：`Copying … into the instance` / `Installing … from the instance's own copy`） |
| profile 记录 | `"dsh-hello-local2": "file:/…/instances/scratch/plugins/dsh-hello-local2-1.0.0.tgz"` —— 指向**实例自己的副本**，不是用户挑的文件 |
| 是否激活 | 进了 `dsh.profile.bundles` ✔（声明了 patch 才会进；没声明的会在安装日志里明确提示"装了但不会生效"） |
| **删掉用户挑的文件后再解析 profile** | `pnpm install` → `Already up to date`（**成功**）。对照第 0 轮的实测：直接把 `file:` 指到外部文件时，源文件一没，该 profile 之后**任何** add/remove 都会 `ENOENT` 失败 |
| harness 启动 | `dsh web` **READY**（本地安装的插件作为 bundle 正常加载） |

另外补了一条防止"包说谎"的规则：如果 profile 里某条依赖的"版本"其实是一个本地路径
（`file:`/`link:`/绝对路径），整合包导出会把它标记为 **local**，`installSpecs()` 不把它当版本去请求，
加载时明确告知"这些插件来自原实例上的文件，无法在这里获取"——否则包会去要一个叫
`x@file:/…` 的包，什么也解析不出来（已加单测）。

### 7.5 用两个真实本地插件的复验（本轮）

用你 `~/Programme/Deepseek/` 下的两个真实插件（`dsh-chembl-search`、`dsh-pubchem-search`，
都带 `dsh.bundle.patch` 与 `dsh.client.platform=web`，各自已有 0.1.0 的 tgz）复验了"从文件安装"：

| 检查 | 结果 |
|---|---|
| 安装 | 两个包都先被复制进 `<instance>/plugins/`，再从副本安装；日志给出包名/版本与"来自实例自己的副本" |
| profile 记录 | 两条依赖都指向**实例内**的副本路径（`file:/…/instances/scratch/plugins/…tgz`） |
| bundle 列表 | 两者都进入 `dsh.profile.bundles` 末尾（按安装顺序） |
| **启动 harness** | **READY**，两个真实插件（真实模块、真实 patch）都被正常加载 |
| 删掉被挑选的文件后 | `pnpm install` → `Already up to date`；再次启动仍然 **READY** |
| 你的原目录 | 未改动（只读复制，tgz 大小/时间戳不变） |

即：真实插件的完整链路（文件 → 实例内副本 → profile 依赖 → bundle 列表 → harness 加载）已经跑通。

## 8. 落地优先级（风险最小 → 收益最大）

1. **插件市场页**（公开目录 + `dsh plugin add`；只读+安装，风险最低、收益最大）；
2. **批量删除**（比批量安装安全；`remove a b c`）；
3. **修两个已发现的 bug**（会话文件名匹配、导入不带附件）——它们是会话打包的前置条件；
4. **会话打包导出/导入**（带附件、按源 slug、压缩模式一致、包内记录期望 cwd/preset）；
5. **整合包**（先做"配置级"manifest 导出/加载，不搬 `node_modules`）；
6. **从文件安装**（最后做，必须带"文件随实例保存"的约束，否则会弄坏 profile）。
