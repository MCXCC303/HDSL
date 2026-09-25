# 2. 账号契约参考

本章列出 HDSL 发布的全部变量。**以 `HDSL_ACCOUNT_CONTRACT` 为准**：读不到它就当整组不存在。

## 2.1 为什么是这组名字

harness 的启动引导会拒绝任何 `.env` 里的 `DSH_`、`XDG_`、`DYLD_`、`BASH_FUNC_`
前缀，以及 `PATH` / `HOME` / `LD_*` / `NODE_OPTIONS` 等名字，**而且是抛错拒绝**——
写进去会让 harness 直接起不来。所以 HDSL 一律使用 `HDSL_` 前缀。

> **注意**
> 你的插件**不要**把读到的值再写回任何 `.env`。那不但没有意义，还可能触发上面那条拒绝。

## 2.2 变量总表

### 始终存在

| 变量 | 类型 | 说明 | 示例 |
|---|---|---|---|
| `HDSL_ACCOUNT_CONTRACT` | 字符串 | 契约版本，当前为 `1`。**先读它** | `1` |
| `HDSL_LAUNCHER_VERSION` | 字符串 | 启动器版本，报问题时说明来源用 | `0.2.0+g399d750` |
| `HDSL_INSTANCE_ID` | 字符串 | 实例 id | `codex=1` |
| `HDSL_INSTANCE_VERSION` | 字符串 | 实例钉住的 dsh 版本 | `0.1.5-alpha.2` |
| `HDSL_INSTANCE_PROFILE` | 字符串 | 实例启动的 profile | `codex` |
| `HDSL_INSTANCE_WORKSPACE` | 绝对路径 | 实例的工作目录 | `/home/thf` |

### 有账号时才存在

| 变量 | 类型 | 说明 | 示例 |
|---|---|---|---|
| `HDSL_ACCOUNT_NAME` | 字符串 | **账号显示名**，想显示用户名就读它 | `MCXCC303` |
| `HDSL_ACCOUNT_ROUTE` | 字符串 | 该账号在 harness 里的路由名（今天等于 NAME） | `MCXCC303` |
| `HDSL_ACCOUNT_VENDOR` | 字符串 | 供应商标识 | `deepseek` |
| `HDSL_ACCOUNT_KIND` | 枚举 | 账号种类，见 2.3 | `official` |
| `HDSL_ACCOUNT_ENDPOINT` | URL | 账号自有端点（没有就不存在） | `https://api.deepseek.com` |
| `HDSL_ACCOUNT_MODEL` | 字符串 | 账号指定的起始模型（没有就不存在） | `deepseek-flash` |
| `HDSL_ACCOUNT_SKIN` | 枚举 | 头像来源，见 2.4 | `local` |
| `HDSL_ACCOUNT_SKIN_MODEL` | 枚举 | 体型：`default`（宽）或 `slim`（细） | `default` |
| `HDSL_ACCOUNT_SKIN_FILE` | 绝对路径 | **玩家自己的头像 PNG**（有才存在） | `/home/thf/.local/share/hdsl/skins/480c….png` |
| `HDSL_ACCOUNT_CAPE_FILE` | 绝对路径 | 披风 PNG（有才存在） | 同上 |

> **提示**
> `NAME` 与 `ROUTE` 今天总是相等。想「显示名字」用 `NAME`；想在 harness 配置里查这条路由用 `ROUTE`。

## 2.3 账号种类（`HDSL_ACCOUNT_KIND`）

| 取值 | 含义 | 有密钥吗 |
|---|---|---|
| `official` | 官方供应商账号 | 有 |
| `third-party` | 玩家自己填的供应商 | 有 |
| `offline` | **离线账号**：只有名字和头像，不连任何供应商 | 没有 |

离线账号是真实且常见的状态。此时 **没有** `ENDPOINT`，也 **没有** `MODEL`，
但 `NAME` 和皮肤仍然在——UI 上要能画出一个「未连接供应商的玩家」。

## 2.4 头像来源（`HDSL_ACCOUNT_SKIN`）

| 取值 | 含义 | 怎么画 |
|---|---|---|
| `default` | 没有特别选择 | 用你自己的默认头像 |
| `steve` | 玩家选了「经典」内置形象 | 用你自己的经典形象素材 |
| `alex` | 玩家选了「细手臂」内置形象 | 用你自己的细手臂素材 |
| `local` | 玩家导入了一张 PNG | 读 `HDSL_ACCOUNT_SKIN_FILE` 显示 |

> **注意**
> `local` 只说明「选的是本地图片」，**不代表 `SKIN_FILE` 一定存在**（文件可能被删）。
> 两个都要判断，缺一就回退。

## 2.5 写在哪里、什么时候写

| 层 | 何时在 | 由谁写 |
|---|---|---|
| 子进程环境（`process`） | 每次由 HDSL 启动时 | HDSL，每次启动 |
| `<实例 home>/.env`（`user-env`） | 持久 | HDSL，**每次启动重写** |

`.env` 里属于契约的行夹在两个注释标记之间：

```ini
# >>> HDSL account contract: written by the launcher, rewritten on every launch
HDSL_ACCOUNT_CONTRACT=1
…
# <<< HDSL account contract
```

**不属于契约的行不会被改动**——玩家自己写的变量、注释都原样保留。

优先级：`process` 覆盖 `user-env`。想只信某一层，用
`getFrom(name, ['process', 'user-env'])` 明确列出来，见 [3.5](03-read-in-plugin.md)。

## 2.6 版本策略

- `HDSL_ACCOUNT_CONTRACT` 是唯一必需键，语义变更时递增。
- 遇到不认识的版本：**整体忽略**，不要猜字段。
- 新增可选变量不递增版本——你本来就该忽略不认识的键。
