# 4. 显示玩家信息

本章做一个完整的例子：在插件界面右下角显示 **头像 + 用户名 + 供应商徽标**。

目标：

```
┌──────────────────────────────┐
│  (头像)  MCXCC303            │
│          deepseek            │
└──────────────────────────────┘
```

## 4.1 服务端半边：把契约整理成一份 JSON

```ts
import { launchEnvironmentOf } from '@deepseek-ai/dsh-launch-environment'

// 只信这两层：启动器传进来的，和玩家自己 home 里的
const TRUSTED = ['process', 'user-env'] as const

export function apply(ctx: any) {
  const env = launchEnvironmentOf(ctx)
  const webServer = ctx.get('webServer')

  const read = (name: string) => env.getFrom(name, TRUSTED)?.value

  const hasContract = () => read('HDSL_ACCOUNT_CONTRACT') === '1'

  const profile = () => {
    if (!hasContract()) return { contract: false }
    const skinFile = read('HDSL_ACCOUNT_SKIN_FILE')
    return {
      contract: true,
      name: read('HDSL_ACCOUNT_NAME') ?? null,
      vendor: read('HDSL_ACCOUNT_VENDOR') ?? null,
      kind: read('HDSL_ACCOUNT_KIND') ?? null,
      skin: read('HDSL_ACCOUNT_SKIN') ?? 'default',
      skinModel: read('HDSL_ACCOUNT_SKIN_MODEL') ?? 'default',
      // 只告诉浏览器「有没有」，路径本身不出进程
      hasSkinImage: Boolean(skinFile),
    }
  }

  ctx.effect(() => webServer.register({
    kind: 'exact',
    path: '/my-plugin/whoami',
    handler: (req, res) => {
      const body = Buffer.from(JSON.stringify(profile()))
      res.writeHead(200, {
        'content-type': 'application/json; charset=utf-8',
        'content-length': String(body.byteLength),
        'cache-control': 'no-cache',
      })
      res.end(body)
    },
  }))
}
```

> **注意**
> 返回给浏览器的 JSON **不要包含 `HDSL_ACCOUNT_SKIN_FILE` 之类的绝对路径**。
> 绝对路径会把玩家的家目录结构暴露给前端代码，而前端并不需要它——它只需要一张图。

## 4.2 服务端半边：把玩家自己的头像送出去

浏览器读不到本地文件，所以由服务端半边读文件、以图片形式返回：

```ts
import { readFile } from 'node:fs/promises'

ctx.effect(() => webServer.register({
  kind: 'exact',
  path: '/my-plugin/skin.png',
  handler: async (req, res) => {
    // 路径只来自环境，绝不来自请求：请求里的路径就是「任意文件读取」
    const file = read('HDSL_ACCOUNT_SKIN_FILE')
    if (file === undefined) {
      res.writeHead(404, { 'cache-control': 'no-store' }).end()
      return
    }
    try {
      const png = await readFile(file)
      res.writeHead(200, {
        'content-type': 'image/png',
        'content-length': String(png.byteLength),
        'cache-control': 'no-cache',
      })
      res.end(png)
    } catch {
      // 文件被删了：回 404，让前端用回退头像
      res.writeHead(404, { 'cache-control': 'no-store' }).end()
    }
  },
}))
```

## 4.3 客户端半边：渲染与回退

```ts
type Who = {
  contract: boolean
  name?: string | null
  vendor?: string | null
  kind?: string | null
  skin?: string
  skinModel?: string
  hasSkinImage?: boolean
}

export async function renderUserBadge(el: HTMLElement) {
  const who: Who = await fetch('/my-plugin/whoami', { credentials: 'same-origin' })
    .then(r => r.json())
    .catch(() => ({ contract: false }))

  // 1) 用户名
  el.querySelector('.name')!.textContent =
    who.name ?? (who.contract ? '未选择账号' : '本地会话')

  // 2) 供应商：离线账号没有供应商，别显示空白
  const vendorEl = el.querySelector('.vendor')!
  if (who.kind === 'offline') {
    vendorEl.textContent = '离线'
  } else if (who.vendor) {
    vendorEl.textContent = who.vendor
  } else {
    vendorEl.remove()
  }

  // 3) 头像：玩家自己的图 > 内置形象 > 默认
  const img = el.querySelector('img')!
  if (who.hasSkinImage) {
    img.src = '/my-plugin/skin.png'
    img.onerror = () => { img.src = builtinAvatar(who.skin ?? 'default') }
  } else {
    img.src = builtinAvatar(who.skin ?? 'default')
  }
}

function builtinAvatar(skin: string): string {
  switch (skin) {
    case 'steve': return '/assets/steve.png'
    case 'alex':  return '/assets/alex.png'
    default:      return '/assets/you.png'
  }
}
```

## 4.4 三种状态下的表现

| 情况 | 名字 | 供应商 | 头像 |
|---|---|---|---|
| HDSL + 官方/第三方账号 | `HDSL_ACCOUNT_NAME` | `HDSL_ACCOUNT_VENDOR` | `local` 且有图 → 图片；否则内置 |
| HDSL + 离线账号 | 账号名 | 显示「离线」（`kind=offline`） | 同上（离线账号也能有自己的头像） |
| 没有 HDSL | 回退文案 | 不显示 | 内置默认 |

## 4.5 皮肤为什么这么麻烦

皮肤是**玩家自己的一张 PNG**，存在启动器的数据目录里，harness 与你的插件都不把它打包进来。
所以能拿到的只有一个**服务端绝对路径**，浏览器半边必须经由你自己的服务端转发。
这也是为什么契约里同时给了 `SKIN`（选择）、`SKIN_MODEL`（体型）和 `SKIN_FILE`（文件）三样：
前两个让你在**没有图**的时候也能画出正确的形象，第三个才有真正的像素。

> **提示**
> 如果你的插件只想要一个「像玩家的头像」，可以不读文件：把 `SKIN` 映射到你自己的素材即可，
> 这样也不需要注册图片路由。
