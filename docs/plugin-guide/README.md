# HDSL 插件接入指南

## 简介

本指南面向 **DSH 插件开发者**，讲解如何在自己的插件里读取并展示「这个实例正以谁的身份运行」——
玩家设置的用户名、正在使用的模型供应商、账号头像（皮肤）等。

这些信息由 **HDSL（Hello DeepSeek! Launcher）** 在启动实例时发布。你的插件不需要依赖 HDSL，
也不需要 HDSL 认识你的插件：HDSL 把信息放进 harness 自己的「启动环境」，你按约定的名字去读；
读不到就当作没有（例如玩家是手工敲 `dsh web` 启动的），回退到你自己的默认显示。

> **说明**
> 本文档描述的契约随 HDSL 一起发布。harness 侧使用的机制不是 HDSL 发明的，而是 harness 自带的
> `@deepseek-ai/dsh-launch-environment`，因此这条路径是官方支持的接入方式。

## 适用对象

| 你是 | 可以做什么 |
|---|---|
| 客户端插件（浏览器里跑的界面） | 显示用户名、按供应商换配色、显示账号头像 |
| 服务端插件（Node 侧） | 直接读取契约，注册路由把自己的半边暴露给浏览器 |
| 主题 / 皮肤类插件 | 按供应商或账号切换主题；把玩家头像画进界面 |

## 目录

1. [快速开始](01-quickstart.md) —— 五分钟内在你的插件里显示用户名
2. [账号契约参考](02-account-contract.md) —— 全部变量名、含义与取值
3. [在插件中读取](03-read-in-plugin.md) —— 服务端半边、客户端半边、以及不写代码的接法
4. [显示玩家信息](04-display-user-info.md) —— 用户名 / 供应商 / 皮肤的完整示例
5. [常见问题与限制](05-faq.md) —— 读不到怎么办、哪些绝对不能做

## 一页速览

```ts
import { launchEnvironmentOf } from '@deepseek-ai/dsh-launch-environment'

const env = launchEnvironmentOf(ctx)
if (env.get('HDSL_ACCOUNT_CONTRACT')?.value === '1') {
  const name = env.getFrom('HDSL_ACCOUNT_NAME', ['process', 'user-env'])?.value
  const vendor = env.getFrom('HDSL_ACCOUNT_VENDOR', ['process', 'user-env'])?.value
  const skin = env.getFrom('HDSL_ACCOUNT_SKIN', ['process', 'user-env'])?.value
}
```

没有 HDSL 时，这三个值都是 `undefined`，你的插件照常工作。
