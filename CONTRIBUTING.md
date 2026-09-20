# HMCL-DSH 开发规约

本文件记录本项目的复刻方法、验证流程与提交约定。它总结自 HMCL-DSH 的实际开发过程，
不是愿景文档：每条都是从踩过的坑里得出的。

---

## 一、复刻顺序：先看原版，再找复用，最后才自己写

这是本项目最重要的一条，因为**违反它的每一次都造成了返工**。

### 1. 先看原版实际长什么样

不要从源码反推界面。源码是 3.17，用户机器上跑的是 3.16.3，两者界面有实际差异
（图标选择对话框就是例子：3.17 是点即生效，3.16.3 带确定/取消）。

- 原版 HMCL 通常在同一 `DISPLAY` 上运行，窗口名 `Hello Minecraft! Launcher v3.16.SNAPSHOT`
- 直接截图对比：`import -window $WINDOW_ID out.png`
- 参照图存放在 `docs/reference/`，命名对应原版页面

### 2. 再确认原版组件是否已移植

**这是最容易漏的一步，我已因此重复造过三次轮子。**

移植过来的控件分散在**两个**目录，只翻 `ui/construct/` 一定会漏：

| 目录 | 典型内容 |
|---|---|
| `ui/construct/` | `LineButton` `LinePane` `TwoLineListItem` `MDListCell` `ImagePickerItem` `FontComboBox` `PopupMenu` `IconedMenuItem` `AdvancedListBox` `ComponentList` `ComponentSublist` `RadioChoiceList` `RipplerContainer` `ImageContainer` |
| `ui/` | `ListPageBase` `ToolbarListPageSkin` `LogWindow` `Controllers` `FXUtils` `SVG` |

查法：

```bash
# 列表页骨架
find src/main/java -name "ListPageBase.java" -o -name "ToolbarListPageSkin.java"

# 某个原版类是否已移植
find src/main/java -name "FontComboBox.java"

# 原版在哪些页面用了它
grep -rn "FontComboBox" /path/to/HMCL/HMCL/src/main/java/org/jackhuang/hmcl/ui/
```

已确认可复用的对应关系（截至当前）：

| 原版 | 本项目用途 |
|---|---|
| `ListPageBase` + `ToolbarListPageSkin` | Node 管理、插件列表、会话列表 |
| `JavaItemCell` 的行形态 | Node 运行时行（版本徽章 + 双行标签 + 尾部动作） |
| `ModInfoListCell`（`MDListCell`） | 插件行（复选框 + 图标 + 版本 tag） |
| `WorldListCell` | 会话行（左图标 + 双行标签 + 尾部动作） |
| `GameListCell` + `JFXListView` | 实例列表 |
| `GameListPopupMenu` | 启动下拉（限高 + 固定行高 + 可滚动） |
| `GameInstanceIconDialog` | 实例图标选择 |
| `ImagePickerItem` | 图标设置行 |
| `FontComboBox` | 字体选择（**通用下拉在 2273 个字体家族下会崩**） |
| `ComponentList.createComponentListTitle` | 章节标题（**放在卡片之间，不是卡片里面**） |
| `LogWindow` | 测试启动的日志窗口 |
| `CreateDeb` | Debian 打包 |

### 3. 最后才自行实现

自造控件时，**必须**沿用原版的 CSS 类名和结构，否则字体、间距、颜色都会偏离。

---

## 二、验证流程

每一次改动都必须经过以下循环，缺一不可：

```
编译 → 启动 → 截图/测试 → 目视确认 → 提交
```

### 编译

```bash
./gradlew compileJava          # 快速检查
./gradlew clean build          # 提交前必做
```

**`clean build` 必须包含测试源集。** 我已两次在只编译主代码后提交，导致
`./gradlew build` 是红的，只能靠下一个提交补救。改 `DshInstance` 这类记录时
尤其容易漏——每个测试夹具都要补参数。

### 启动与截图

```bash
export DISPLAY=:1 XAUTHORITY=/run/user/1000/xauth_dpRvSh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk nohup ./gradlew run --no-daemon --console=plain \
  --args="--page <路由>" > /tmp/app.log 2>&1 &
sleep 15
WID=$(xdotool search --name "HMCL-DSH" | head -1)
xdotool windowmove $WID 180 120
import -window $WID /tmp/shot.png
```

### 深链优先，不要靠点

**这是本流程里最省时间的一条。** 用 xdotool 点击不可靠，我为点到正确的标签页、
侧栏项、按钮反复浪费过整轮时间。能用深链就用深链：

```bash
--page home                          # 主页
--page instances                     # 实例列表
--page settings/general|node|appearance|about
--page instance:<id>                 # 某个实例，默认第一个标签
--page instance:<id>/<tab>           # tab: settings|plugins|sessions|browse|details
--page create                        # 新建实例向导
--page create-version:<version>      # 指定版本的向导
```

**新页面一律同时加深链**，否则下一轮验证只能靠点。

### 无头验证优先

能不开界面验证的，就不要开界面：

```bash
./gradlew run --args="--doctor"              # 环境与目录自检
./gradlew run --args="--list-instances"
./gradlew run --args="--list-sessions <id>"
./gradlew run --args="--test-launch <id>"    # 启动并打印进程输出
./gradlew run --args="--migrate-session <src> <sid> <dst>"
./gradlew run --args="--install-plugin <id> <spec>"
./gradlew run --args="--help"
```

新增功能时**顺手加一条对应的 CLI 子命令**，收益远大于成本。

### 必须点击时

KWin 下可用的配方（点两次是为了穿过 ripple/动画）：

```bash
eval $(xdotool getwindowgeometry --shell $WID)
xdotool windowactivate $WID; xdotool windowraise $WID; xdotool windowfocus $WID
sleep 1.5
xdotool mousemove --sync $((X+相对x)) $((Y+相对y)); sleep 1; xdotool click 1; sleep 1.5; xdotool click 1
```

**先截图量坐标**，不要凭猜。截图后裁剪放大可以精确读出目标位置：

```python
from PIL import Image
im = Image.open('/tmp/shot.png')
im.crop((x0, y0, x1, y1)).resize(((x1-x0)*2, (y1-y0)*2), Image.NEAREST).save('/tmp/zoom.png')
```

### 截图要看全，别只看一眼

同一页面的**工具条会有多种状态**，截到哪一张取决于它当时停在哪里。
我按一张截图认为原版的「搜索」是常驻输入框，实际它是一个**按钮**，
点下去才把整条工具条换成输入框 —— 因为截图时它正停在按钮状态。

**读源码时要把整个控件块读完**，不要看到 `setPromptText` 就下结论。

### 弹窗抓不到

`JFXPopup` / `Stage` 是独立窗口，`import -window <主窗口>` 抓不到；
`import -window root` 在本机失败，`spectacle -f` 抓到的是桌面壁纸。

**这类界面目前只能请使用者实测。** 不要在上面反复消耗时间。

---

## 三、排查约定

### 日志

`LOG.info` 不输出到 stdout，只有 warning 及以上会。诊断时直接用
`System.out.println` 并加显眼前缀（如 `[menu-debug]`），**提交前删干净**。

### 已知会反复出现的坑

| 现象 | 原因 |
|---|---|
| 打开页面整个应用崩溃 | `FXUtils.smoothScrolling(this)` 在 `setContent` **之前**调用 |
| 列表空白 | `ListCell.updateItem` 在空分支 `setGraphic(null)` 后**没有为非空分支恢复** |
| 列表空白 | 用 `setItems(newList)` 换掉了整个 ListProperty；应 `getItems().setAll(...)` |
| 整片白底浅字 | 用了普通 `ListView`；透明背景规则在 `.jfx-list-view`，须用 `JFXListView` |
| 字体不对 | 用了 `LineButton`；原版列表行是 `TwoLineListItem`（15px/12px 来自 CSS） |
| 中文显示英文/字面量 | 语言资源缺 `sublanguages.csv` 等文件；或 i18n 键不存在 |
| 设置存了但下次启动丢失 | `SettingsManager.Snapshot` 需要同步加字段 |
| 界面改了但显示没变 | 页面把实例对象捕获在构造时，写回后**没有重读** |
| 侧栏比内容区暗 | 页面和 `getLeft()` **各加了一次** `gray-background`；50% 的板层叠两层就是更暗 |
| 某列永远空白 | 解析了 npm 的返回值，但它是**数组包一层对象**；静默返回空而不是报错 |
| i18n 值没生效 | 键**原版 bundle 里已存在**，「不存在才追加」的逻辑跳过了它 —— 先 `grep` 再新增 |

### i18n 键必须按枚举核对

拼接出来的键（`prefix + enum.name().toLowerCase()`）**不能凭猜**。用脚本对齐：

```python
# 对每个 prefix，读出对应 enum 的全部常量，逐个检查两个 bundle 里是否存在
```

已因此修掉：`neo_forge`（不是 `neoforge`）、`wait_for_background`（不是 `eager`）、
整个缺失的 `dsh.settings.window`。

---

## 四、提交约定

- **一个主题一个提交**，信息写清「原版是什么样、为什么这样做」
- **提交前跑 `./gradlew clean build`**（含测试）
- 提交信息用 `git commit -F <文件>`，**不要写在 `-m` 里**——反引号会被 shell 当命令执行
  （已因此误跑过一次 `dsh plugin`）
- 验证截图放 `docs/screenshots/`，原版参照图放 `docs/reference/`
- 提交信息用中文或英文均可，但**不要吹嘘**：写明验证到什么程度、哪里没验证

---

## 五、诚实报告

**没验证的就说没验证。** 本项目的报告里出现过这些如实记录：

- 「流式路径需要凭据，因此未验证」
- 「弹窗抓不到，只验证了底部三项的标签和图标」
- 「测试启动链路我只验证了后半段，没能端到端实测」

这些比一句「已完成」有用得多——使用者据此知道该测哪里。
