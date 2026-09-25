# 1. 快速开始

本章用一个最小例子，把「玩家用户名」显示出来。全程约五分钟。

## 前置条件

- 一个已经能跑起来的 DSH 插件（服务端半边 + 客户端半边，或只有服务端半边）。
- 运行它的实例是由 **HDSL** 启动的。判断方法见「[3.2 怎么判断有没有 HDSL](03-read-in-plugin.md)」。

> **提示**
> 如果你只想在自己的界面里显示这个名字，而不想引入任何依赖，可以直接跳到
> [3.4 不写代码也能接](03-read-in-plugin.md)。

## 步骤 1：引入 harness 的启动环境库

在你的服务端半边：

```ts
import { launchEnvironmentOf } from '@deepseek-ai/dsh-launch-environment'
```

这个包由 harness 自身提供，与你的插件在同一个进程里，不需要额外声明依赖版本。

## 步骤 2：读取用户名

```ts
// ctx 是你的插件上下文
const env = launchEnvironmentOf(ctx)

// 先确认契约存在：读不到版本号就说明不是 HDSL 启动的
const hasContract = env.get('HDSL_ACCOUNT_CONTRACT')?.value === '1'

// 只接受「启动器传进来的」和「用户自己 home 里的」，不接受项目目录带来的 .env
const name = hasContract
  ? env.getFrom('HDSL_ACCOUNT_NAME', ['process', 'user-env'])?.value
  : undefined

const displayName = name ?? '未登录'
```

## 步骤 3：把值送到界面

浏览器半边读不到进程环境，所以要么由你的服务端半边注册一条路由，要么用你已有的
`remote` 服务转发。最小写法是一条只读路由：

```ts
// 服务端半边
ctx.webServer.register({
  kind: 'exact',
  path: '/my-plugin/whoami',
  handler: (req, res) => {
    const body = Buffer.from(JSON.stringify({ name: displayName }))
    res.writeHead(200, {
      'content-type': 'application/json; charset=utf-8',
      'content-length': String(body.byteLength),
      'cache-control': 'no-cache',
    })
    res.end(body)
  },
})
```

```ts
// 客户端半边
const who = await fetch('/my-plugin/whoami', { credentials: 'same-origin' }).then(r => r.json())
renderUser(who.name)
```

## 步骤 4：验证

1. 由 HDSL 启动实例，打开你的插件界面，应显示玩家的账号名。
2. 在终端里手工执行 `dsh web`（同一个 `DSH_HOME`）再打开，**仍然应显示同一个名字**——
   因为契约同时写进了该 home 的 `.env`。
3. 换一个非 HDSL 的方式启动，应回退到你自己的默认文案。

> **注意**
> 第 3 步是必须测的。契约随时可能不存在，界面不能因此空白或报错。

## 下一步

- 想显示供应商、皮肤：[4. 显示玩家信息](04-display-user-info.md)
- 想了解每个变量的确切含义：[2. 账号契约参考](02-account-contract.md)
