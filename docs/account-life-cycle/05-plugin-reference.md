# 插件开发者参考

> 可以依赖的承诺、读取契约的示例、退出前后的时间线，以及明确不要做的事。

## 可以依赖的承诺

有账号时，契约在**进程环境**与 **`home/.env`** 里都有。没有账号、或账号是离线时，账号字段整组不存在，所以先读 `HDSL_ACCOUNT_CONTRACT`。

契约**永远不含密钥**。密钥只在进程环境里，名字可以由路由名推出，见[密钥变量名](01-concepts.md#密钥变量名)。

运行期 patch 里有 `config.providers.<ROUTE>` 这条路由，但它**只在那一次运行里**存在，退出即回收。不要缓存它的地址，也不要假设它一直在。

另外三条：

* 路由名与账号显示名今天相同（`ROUTE == NAME`），但不要写进逻辑：账号改名就是换了路由名。
* 玩家改自己的供应商不会失败，因为路由写在配置编辑器编辑的那份文档里。
* 启动失败会说明原因（谁的密钥被拒、哪个地址答不上来）。会话出错之前，先看一眼启动输出。

## 读取契约

服务端半边这样读：

```ts
import { launchEnvironmentOf } from '@deepseek-ai/dsh-launch-environment'

const env = launchEnvironmentOf(ctx)

// 先确认契约版本，不认识的版本整体忽略
if (env.get('HDSL_ACCOUNT_CONTRACT')?.value !== '1') return

const from = (name: string) => env.getFrom(name, ['process', 'user-env'])?.value

const route  = from('HDSL_ACCOUNT_ROUTE')   // 例：'MCXCC-sf1'，没有就是离线/无账号
const vendor = from('HDSL_ACCOUNT_VENDOR')  // 例：'siliconflow'
const kind   = from('HDSL_ACCOUNT_KIND')    // 'official' | 'third-party' | 'offline'
```

层与优先级、客户端半边怎么接，见[在插件中读取](../plugin-guide/03-read-in-plugin.md)；全部变量名见[账号契约参考](../plugin-guide/02-account-contract.md)。

## 常用写法

**按供应商换行为，而不是按账号名**，这样同一个供应商的两个账号表现一致：

```ts
const themeOf = (vendor?: string) =>
  vendor === 'deepseek' ? 'deepseek-dark'
  : vendor === 'siliconflow' ? 'cn-light'
  : 'default'
```

**离线账号要能画出来**：`HDSL_ACCOUNT_KIND === 'offline'` 时没有 `HDSL_ACCOUNT_ENDPOINT`，也没有 `HDSL_ACCOUNT_MODEL`，但 `NAME` 和皮肤还在，按「未连接供应商的玩家」渲染即可。

**运行期确认本账号的路由在不在**（例如要在自己的页面上列出模型）：读 harness 解释之后的配置，而不是去解析文件。patch 是 harness 与玩家共有的，注释、`!!js` 表达式、缩进都可能有：

```ts
const providers = ctx.get('loader')?.entries()
  ?.find((e: any) => e.options.id === 'llm-pi-ai')
  ?.options.config?.providers ?? {}
const mine = route ? providers[route] : undefined
if (!mine) {
  // 正常情况：本次没有账号、账号是离线，或者玩家刚把它删了
}
```

> **说明**：上面读 `loader` 的写法是示意，服务名以你那个 harness 版本为准；在同一个 profile 里跑时，用 harness 的配置或设置服务通常更合适。路由在不在这个判断用哪种方式读都不影响结论：没有就是没有，按没有处理。

## 时间线速查

| 观测点 | 启动前 | 运行期 | 退出后 |
|---|---|---|---|
| `HDSL_ACCOUNT_*`（进程环境） | 不存在 | 有 | 随进程消失 |
| `HDSL_ACCOUNT_*`（`home/.env`） | 上次启动写的 | 同左 | 保留 |
| `HDSL_LAUNCH_API_KEY_…` | 不存在 | 有（只在环境里） | 随进程消失 |
| `config.providers.<ROUTE>`（patch） | 不存在 | 有 | **被回收** |
| 玩家自己的路由（patch） | 一直在 | 一直在 | 一直在 |
| 便签 / 名单 | 可能残留 | 便签有、名单有 | 便签删除，名单保留 |

## 禁止事项

* 不要**把密钥写进任何文件、日志或遥测**。密钥只应活在进程环境里，写出去就等于泄露，而且退出后没有任何东西会替你清理。
* 不要**把读到的契约值写回 `.env`**。harness 的引导会抛错拒绝某些前缀和名字，写错一个实例就起不来。
* 不要**假设路由长期存在**，也不要**缓存「账号 → 地址」之类的映射**。退出即回收，账号可改名，供应商可改地址，模型表每次启动都变。
* 不要**自己重写整个 `llm-pi-ai` 条目**。那会把玩家的供应商一起覆盖；要改配置就用 harness 的编辑能力。
* 不要**依赖 `NAME == ROUTE`**。契约把它们分成两个变量，就是为了将来能分开。

## 版本与兼容

契约版本在 `HDSL_ACCOUNT_CONTRACT`，当前为 `1`，语义变更才递增。遇到不认识的版本整体忽略，不要猜字段；新增可选变量不递增版本，你本来就该忽略不认识的键。

路由名规则（`[A-Za-z0-9][A-Za-z0-9._-]*`）与密钥变量名规则（[密钥变量名](01-concepts.md#密钥变量名)）都是稳定的，但它们是 **HDSL 与 harness 之间**的约定，插件能不用就不用。

> **提示**：想知道「这个实例现在到底在用什么」，最简单的答案永远在契约里，其次才是 harness 的配置。两者不一致时（例如玩家刚把路由删了），以 harness 的配置为准：那才是真正生效的东西。

## 相关资源

* [账号、供应商与路由](01-concepts.md)
* [启动：挑选、校验、注入](02-launch.md)
* [退出：回收与兜底](04-exit.md)
* [HDSL 插件接入指南](../plugin-guide/README.md)
