# 3. 在插件中读取

本章讲三种读法，以及为什么推荐哪一种。

## 3.1 服务端半边：读启动环境

`launchEnvironmentOf` 是 **服务端库**（harness 原话：*Import it as a library; it cannot be mounted from cordis.yml*），
浏览器半边读不到 `process.env`，所以读取动作必须发生在服务端半边。

```ts
import { launchEnvironmentOf } from '@deepseek-ai/dsh-launch-environment'

const env = launchEnvironmentOf(ctx)
const name = env.get('HDSL_ACCOUNT_NAME')?.value
```

`get()` 返回的不只是值，还有**它来自哪一层**：

```ts
const entry = env.get('HDSL_ACCOUNT_NAME')
// entry = { value: 'MCXCC303', source: 'process' | 'project-env' | 'user-env', path?: string }
```

## 3.2 怎么判断有没有 HDSL

不要靠「某个变量有没有值」判断——那可能只是玩家没选账号。**判断契约版本**：

```ts
const hasContract = env.get('HDSL_ACCOUNT_CONTRACT')?.value === '1'
```

- 有契约、有账号：显示账号信息。
- 有契约、无账号：显示「未选择账号」，皮肤/实例信息仍可用。
- 无契约：HDSL 没参与这次启动（玩家手工敲的 `dsh web`，或别的启动器），全部回退。

## 3.3 客户端半边：一条只读路由

浏览器半边由你的服务端半边转发。harness 官方的写法是：

```ts
// 服务端半边：注册路由；ctx.effect 保证插件卸载时路由一并撤掉
const webServer = ctx.get('webServer')
ctx.effect(() => webServer.register({
  kind: 'exact',
  path: '/my-plugin/whoami',
  handler: (req, res) => {
    const body = Buffer.from(JSON.stringify({
      name: env.getFrom('HDSL_ACCOUNT_NAME', ['process', 'user-env'])?.value ?? null,
      vendor: env.getFrom('HDSL_ACCOUNT_VENDOR', ['process', 'user-env'])?.value ?? null,
    }))
    res.writeHead(200, {
      'content-type': 'application/json; charset=utf-8',
      'content-length': String(body.byteLength),
      'cache-control': 'no-cache',
    })
    res.end(body)
  },
}))
```

```ts
// 客户端半边
const who = await fetch('/my-plugin/whoami', { credentials: 'same-origin' }).then(r => r.json())
```

> **说明**
> `{ kind: 'exact', path, handler }` 是 harness 自己注册路由的形状（官方 OAuth 回调就是这样注册的，
> 见 `@deepseek-ai/dsh-deepseek-account-platform`）。不同 harness 版本的服务名可能不同，
> 装好的 `dsh-claude-style` 里也有一份可直接对照的实例。

**只读、只回自己的信息。** 不要在这条路由上暴露密钥、文件系统路径或任何可写操作。

## 3.4 不写代码也能接

契约值最终也在 `process.env` 里，所以写 patch 的人可以直接把值喂进你的配置——
**你一个字段都不用改**：

```yaml
- id: ui-skin-claude-style
  config:
    username: !!js process.env.HDSL_ACCOUNT_NAME
```

这也是把自己的插件接进 HDSL 的最省事办法：让插件暴露一个「显示名」配置项即可。

## 3.5 只信某一层

`getFrom(name, sources)` 只在你列出的层里查，**不在列表里的层不可达**。
这条能力是给敏感判断用的：项目目录带来的 `.env` 会随仓库一起被克隆，
所以一个「不该被项目覆盖」的值应当写成：

```ts
// 只接受启动器传进来的，以及用户自己 home 里的
env.getFrom('HDSL_ACCOUNT_NAME', ['process', 'user-env'])
```

本契约里的账号信息都属于这一类：**项目目录没有资格声明「玩家是谁」**。

## 3.6 什么时候读

**启动时读一次就够。** 契约在进程生命周期内不会变；`process.loadEnvFile` 只在引导时执行。
不要轮询、不要放进渲染循环。

如果玩家在会话中途换了账号，那是**下一次启动**的事——你不需要处理。
