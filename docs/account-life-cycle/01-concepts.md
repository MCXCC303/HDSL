# 账号、供应商与路由

> 账号是启动器里的一条记录，供应商提供端点与协议，路由才是 harness 真正读到的那条配置。

## 账号

一个账号就是启动器里的一条记录，存在 `launcher-settings.json` 的 `accounts` 数组里。

| 字段 | 类型 | 说明 |
|---|---|---|
| `kind` | 枚举 | `official` / `third-party` / `offline`，见[三种账号](#三种账号) |
| `vendorId` | 字符串 | 供应商 id，如 `deepseek`、`opencode`、`siliconflow` |
| `apiKey` | 字符串 | 密钥，离线账号为空 |
| `baseUrl` | URL 或空 | 账号自己的端点，空则用供应商的 |
| `label` | 字符串或空 | 玩家给这个账号起的名字，**路由名就是它** |
| `model` | 字符串或空 | 玩家指定的起始模型，空表示由供应商的模型表决定 |
| `skin*` | — | 头像相关，与供应商无关，见[账号契约参考](../plugin-guide/02-account-contract.md) |

账号与供应商是两件事。同一个供应商可以有两个账号（两份密钥），于是有**两条**路由；玩家给账号起名字，就是为了让这两条路由分得开。

## 三种账号

| `kind` | 有密钥吗 | 会被注入吗 | 说明 |
|---|---|---|---|
| `official` | 有 | 会 | 启动器内置目录里的第一家供应商 |
| `third-party` | 有 | 会 | 玩家自己填或选的供应商 |
| `offline` | 没有 | 不会 | 只有名字和头像；harness 起在玩家自己配好的状态上 |

判断规则只有一条：`kind != offline` 且密钥非空（代码里是 `carriesAKey()`）。

离线账号不写路由、不设默认模型、不往子进程环境里放密钥。契约里的 `HDSL_ACCOUNT_KIND` 就是给这种情况用的，界面应当能画出「一个没连接供应商的玩家」。

## 路由名

路由名按下面的顺序取第一个可用的：

```text
label（玩家起的名字）
  → 供应商显示名
  → vendorId
```

路由名必须是 harness 能寻址的标识符：`[A-Za-z0-9][A-Za-z0-9._-]*`。账号对话框在保存时就校验，因为一个带空格的名字到了 harness 那里会变成它找不到的东西。

| 账号 | 路由名 |
|---|---|
| `label = MCXCC-sf1`，供应商 `siliconflow` | `MCXCC-sf1` |
| `label` 为空，供应商 `deepseek` | `DeepSeek` |
| `label` 为空，`vendorId = opencode`，目录里查不到 | `opencode` |

> **提示**：契约里的 `HDSL_ACCOUNT_NAME` 与 `HDSL_ACCOUNT_ROUTE` 今天总是相等。要**显示**名字用 `NAME`，要在配置里**查**这条路由用 `ROUTE`。将来两者可能分开，所以别把相等写进逻辑。

## 供应商决定什么

**端点**按「账号自己的 `baseUrl` → 内置供应商目录 → 玩家添加的供应商」的顺序解析。内置目录随启动器发布，玩家添加的供应商由玩家自己在账号页填地址加进来，两者都能查到。三处都没有地址就没有路由，启动会中止。插件不要自己去猜端点。

**协议**取供应商声明的 `api`；供应商查不到时按 `openai-completions` 处理。

**密钥变量名**与供应商无关，由 HDSL 生成，见下一节。

## 密钥变量名

密钥永远不写进任何配置文件。路由里只写「去哪儿读」：

```yaml
apiKeyEnv: HDSL_LAUNCH_API_KEY_MCXCC_SF1_6DC38953
```

命名规则是 `HDSL_LAUNCH_API_KEY_` 加上路由名的大写化形式（非字母数字换成 `_`，最长 24 字符），再加上路由名哈希的 8 位十六进制。

| 路由名 | 变量名 |
|---|---|
| `MCXCC-sf1` | `HDSL_LAUNCH_API_KEY_MCXCC_SF1_6DC38953` |
| `DeepSeek` | `HDSL_LAUNCH_API_KEY_DEEPSEEK_<8位>` |
| `a-b` 与 `a_b` | 两个**不同**的变量名，所以两条路由不会共用一份密钥 |

一个路由一个变量，是为了让上一次启动留下的路由拿不到这一次账号的密钥：它指向的变量名没人设置，于是明确失败，而不是把别人的密钥发到别的端点去。

> **注意**：插件不要把读到的密钥写到磁盘、日志或别的进程里。需要调用供应商时，优先复用 harness 已经配好的那条路由，而不是自己再发一份请求。

## DeepSeek 路由的特殊处理

供应商 id 是 `deepseek`，或端点主机名落在 `deepseek.com` 下时，HDSL 会额外做两件事。

一是在子进程环境里再放一份 `DEEPSEEK_API_KEY`，因为 harness 的联网搜索插件读的就是这个名字。启动器因此不必去改那个插件的配置，改了反而会让它的设置页存不下。

二是在模型表里补上容量与推理档（`defaultContextWindow`、`defaultMaxTokens`、`reasoningEfforts`）。这几项本应由 harness 自带的 DeepSeek 适配器提供，但启动器写的路由不在那份目录里；不补，同一个模型走两条路时能力就会不一致。

非 DeepSeek 的账号不会拿到 `DEEPSEEK_API_KEY`。它不是那个服务的密钥，放进去只会让搜索失败得更晚。

## 一次启动涉及的文件

| 文件 | 谁写 | 什么时候 | 退出后 |
|---|---|---|---|
| `<实例 home>/profiles/<profile>/cordis.patch.yml` | HDSL 写路由；玩家在界面里改供应商也是写这里 | 启动时 | **路由被取出**，玩家的其它内容不动 |
| 子进程环境 | HDSL | 启动时 | 随进程消失 |
| `<实例 home>/.env` | HDSL 写契约块 | 每次启动重写 | 持久 |
| `<实例 home>/.hdsl-injected.json`（便签） | HDSL | 启动时 | 回收完成即删除 |
| `<实例 home>/.hdsl-injected-routes.json`（名单） | HDSL | 每次注入一条 | 持久，启动器用它认自己的旧路由 |
| `<实例 home>/settings.yaml` | HDSL | 启动时 | 见[启动](02-launch.md) |

名单里只有**名字**，没有密钥。它是启动器回答「这条路由是我造的吗」的依据，因此下次启动才能把上次被强杀留下的路由收走。

## 相关资源

* [启动：挑选、校验、注入](02-launch.md)
* [插件开发者参考](05-plugin-reference.md)：契约中每个变量的含义
* [账号契约参考](../plugin-guide/02-account-contract.md)：全部变量名与取值
