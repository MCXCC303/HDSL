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

## 一之补：比对顺序 —— 截图先行，代码随后

规约一（先看原版、再找复用、最后自己写）解决的是「用什么写」，
但**「像不像」只能靠截图判断**。两者的关系是：

```
截图比对  →  调整  →  再看一遍截图
                          ↓ 还是不对
                     比对样式代码  →  调整  →  再看截图
```

**代码一致不等于观感一致。** 反例就在本项目里：
下载页的工具条我按代码结构「正确」地放进了列表所在的卡片里，
逻辑上完全说得通，但原版是**悬空在列表之上、80% 不透明、带阴影**的独立层 ——
这个差异只有把两个窗口的表头采样成数值才看出来（(57,50,45) 与 (24,18,13) 的分别）。

所以：
- **先截图**，用肉眼和采样找差异
- **调整后再截一次**，确认改动真的产生了预期的观感
- 还不对，**才去比对样式代码**找机制（如 `SearchPane` 其实是个 `GridPane` + `.card` 类）

顺序反了会浪费：光读代码会得出「结构一致」的结论，而观感差异依然存在。

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

# 轮询等窗口出现，不要用固定 sleep
for i in $(seq 1 40); do
  sleep 0.5
  WID=$(xdotool search --name "HMCL-DSH" 2>/dev/null | head -1)
  [ -n "$WID" ] && break
done
sleep 2                      # 让首帧画完
xdotool windowmove $WID 180 120
xdotool windowsize $WID 818 593     # 与实机原版同尺寸，便于逐像素比对
import -window $WID /tmp/shot.png
```

**实测启动到窗口出现约 7 秒**，固定 `sleep 15`/`sleep 20` 纯属浪费。
轮询还比固定值可靠：机器快慢都能适应。

**截图前把窗口调成 818x593**（原版实机尺寸）。尺寸不同会导致壁纸裁切不同，
进而让「内容区是不是实心面」这类判断得出错误结论 —— 我在实例列表那页踩过。

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

### 点击其实可用 —— 关键是事件的拆法

**`xdotool click` 在这台机器上几乎从不生效，但下面的写法稳定可用**（本会话失败十余次后才找到）：

```bash
click() {
  xdotool mousemove --sync $1 $2
  sleep 1.2                        # 让 hover/ripple 先完成
  xdotool mousedown 1
  sleep 0.15                       # 按下与抬起之间要有间隔
  xdotool mouseup 1
  sleep 3                          # 等页面切换动画
}
# 先激活并置顶，否则点击落在别的窗口上
xdotool windowactivate $WID; xdotool windowraise $WID; xdotool windowfocus $WID
```

`windowmove` 与 `getwindowgeometry` 的坐标是**一致的**（实测设 300,200 就读回 300,200），
窗口是自绘标题栏、没有 WM 装饰，所以**窗口内坐标可直接与截图对齐**，不需要补偿偏移。

导航原版 HMCL 时用它；自己的页面仍然优先用深链。

### 旧配方（仅在上面失效时尝试）

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

### 定期 diff 样式表，不要只看截图

`root.css` 是从原版整份移植的，所以**任何差异都是我引入的**：

```bash
diff /home/thf/Programme/Git/HMCL/HMCL/src/main/resources/assets/css/root.css \
     src/main/resources/assets/css/root.css
```

原版有而我缺失的规则（`diff` 输出里 `^<` 的行）**一律是 bug**。
我就是这样才发现自己早先把原版的一条规则**改名**了：

```css
/* 原版 */
.installer-item-wrapper .installer-item:list-item > .installer-item-status { -fx-max-width: infinity; }
/* 被我改成了自造的 arrow 规则，原规则丢失，只剩一条范围过宽的全局规则 */
```

截图看不出来 —— 受影响的只是特定作用域下的一个属性。
**每次改 CSS 后跑一次这个 diff，比对着截图找差异快得多。**

### 文本替换要确认改的是哪一处

用脚本批量替换时，**同一个字符串在文件里往往出现多次**。
我改设置页的 `tab.select(generalTab, false)` 时，本意是改构造函数里的初始选中，
结果把深链分支 `case "general"` 也一起改了 —— 于是访问「通用」标签显示的是另一个标签的内容。
改完要 `grep` 确认**每一处**变成了什么，而不是只看编译过没过。

### 列表页的背景来自 ComponentList，不是页面

原版列表页的内容区是**一块不透明面**，而 `gray-background` 只有 50% 不透明度。
那层实心面来自 `ComponentList` 的一个间接效果：

```
ComponentList.add(child)
  → 把 child 包进一个带 .options-list-item 的容器
      → .options-list-item { -fx-background-color: -monet-surface; }   ← 不透明
```

所以原版列表页是 `StackPane(notice-pane, padding 10)` > `ComponentList(no-padding)` > 内容。
**用裸 `VBox` 就没有这层背景**，壁纸会透进列表和每一行，
界面里所有颜色都会随窗口背后是什么而变化 —— 这种问题在截图上不明显，
要把两个窗口的同一区域**采样成数值**才看得出来。

### ComponentList 的子项要用它自己的 setVgrow

`ComponentList` 会把每个子项**包进一个 `ItemWrapper`** 再放进 VBox。
所以在子项上写 `VBox.setVgrow(child, ALWAYS)` 是**无效的** ——
VGrow 设在了盒子并不直接布局的那个节点上。

原版为此提供了静态方法：

```java
ComponentList.setVgrow(node, Priority.ALWAYS);
// 内部：node.getProperties().put("ComponentList.vgrow", priority);
// 建 wrapper 时再读出这个属性并 VBox.setVgrow(wrapper, priority)
```

**症状**：列表不随窗口高度伸展，看起来像「高度被固定了」。
判定方法：把窗口拉高，看卡片下边界是否跟着移动。

### 工具的固定高度：别给容器再加内边距

`.jfx-tool-bar-button` 自带**固定高度**：

```css
.jfx-tool-bar-button {
    -fx-toggle-icon4-size: 37px;
    -fx-pref-height: 37px; -fx-max-height: 37px; -fx-min-height: 37px;
}
```

所以**工具条容器的高度就等于 37px 加上你给容器加的内边距**。
我给实例列表的工具条加了 `setPadding(new Insets(4))`，表头就比原版高 8px。

**量法**：沿一条内容为空的竖直线做亮度剖面，找分隔线：

- 原版分隔线 y=95，表头从 y=58 起 → **37px**
- 我（改前）y=103 → **45px**
- 我（改后）y=95 → **37px** ✓

### 卡片类控件要照抄节点结构

原版的 `installer-item` 系列 CSS 是三层结构：

```
StackPane.installer-item-wrapper   :card   ← 背景、圆角、宽度 180、阴影
└── RipplerContainer
    └── VBox.installer-item        :card   ← 居中
        ├── SVGContainer(32) .installer-item-image   margin (8,0,8,0)
        ├── Label .installer-item-name
        ├── Label .installer-item-status
        └── JFXButton .toggle-icon4  带 SVG.ARROW_FORWARD
```

三个要点：

1. **`:card` 是伪类**（`pseudoClassStateChanged`），不是类名 `installer-item-card`
2. **高度绑定为宽度的 0.7 倍**（皮肤里用 `onWeakChangeAndOperate(widthProperty, ...)`），
   不绑的话内容行数不同的卡片会参差
3. **箭头是图标按钮**，不是字符 `→`。字符依赖界面字体有该字形及其度量，会和图标集脱节
4. **同一行的卡片子元素数量必须一致**，否则内容居中后图标位置对不齐。
   原版连「陈述版本、不可点」的卡片也带箭头（禁用态），就是为了对齐

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
| 样式「几乎对但就是不对」 | **CSS 随包带来了，控件却没移植**。原版的选择器是 `.installer-item-wrapper .installer-item:card`，把两个类加在同一个节点上匹配不到任何规则 —— 移植前先 `grep` CSS 确认它期望的节点结构 |

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
