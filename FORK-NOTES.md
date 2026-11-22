# 这个 fork 改了什么

基于 **[AzukiPann/AzukiReader](https://github.com/AzukiPann/AzukiReader) `1.00.12`**，只动书架的三处小地方，
没有碰阅读器、书源、同步、界面其它部分。

- 版本：`1.00.12-mod.2`（versionCode `32664`；`mod` 是 `32663`，两者只差第 4 节的两个修复）
  包名仍是 `io.azukireader.app`，可与官方版覆盖安装/共存二选一
- 分支：`bookshelf-tweaks`
- 安装包见 [Releases](../../releases)

## 1. 书架横滑震感

原来书架横滑靠的是 Compose 的 `HapticFeedbackType.SegmentFrequentTick`，它最终走
`View.performHapticFeedback`，强度由系统「触摸反馈」决定，实机上基本只有很轻的一下、经常感觉不到。

现在改成直接调 `Vibrator`：

- 每跨过一本书振一次：`VibrationEffect.createOneShot(15ms, 150)`
- 振动放在单线程 executor 里，快速横滑时不会因为 binder 调用掉帧
- 参数与「阅读S」（同一个作者在 legado 上做的档案式书架）一致，两边手感相同

改动位置：

| 文件 | 说明 |
| --- | --- |
| `feature/bookshelf/archive/ShelfHaptics.kt` | 新增：`Vibrator` 封装，15ms / 振幅 150 |
| `feature/bookshelf/archive/Wenku8ArchiveShelf.kt` | 用 `LaunchedEffect(activeLane)` 监听「跨过一本书」，替换原来的两处 `performHapticFeedback`（拖动时、停稳时） |
| `AndroidManifest.xml` | 新增 `android.permission.VIBRATE` |

## 2. 封面上下编号往里收

封面上的 `NO.001`（上）和 `FILE / 001`（下）原来离书口只有 10dp / 9dp / 8dp，
现在统一收到 `18dp / 12dp / 11dp`（左 / 上 / 下），编号不再贴着裁切线。

文件：`Wenku8ArchiveShelf.kt` 里 `COVER_PAD_START_DP` / `COVER_PAD_TOP_DP` /
`COVER_PAD_END_DP` / `COVER_PAD_BOTTOM_DP` 四个常量。

## 3. 书名字体与字号

- 书脊竖排书名、书架下方的书名/作者改用 **思源宋体 CN Medium**（`res/font/archive_serif.ttf`）。
  中文默认是黑体，换宋体后更接近纸质档案的观感。
- 字号没有照搬另一个改版（那套 10.6 / 8.4 / 8.2sp 是按 legado 的书架尺寸调出来的），
  这里按 AzukiReader 自己 100–128dp 的封面重新实测：
  - 书脊竖排书名：9sp 黑体 → **8.2sp 宋体**（`SPINE_TITLE_SP`，行高 9.4sp）
  - 书架下方书名：17sp 黑体加粗 → **16sp 宋体**（`CAPTION_TITLE_SP`）
  - 书架下方作者：12sp → **11sp 宋体**（`CAPTION_AUTHOR_SP`）
  - 封面上的 `NO.xxx` / `FILE / xxx` 仍是等宽字体（档案编号的观感，和原版一致）
- 字体是 GB2312 子集（6763 汉字 + ASCII + 常用标点，2.5MB），生僻字会回落到系统字体。
  授权见 `app/src/main/assets/licenses/archive-serif.txt`（SIL OFL 1.1）。

## 4. 顺手修的两个问题（mod.2）

### 4.1 省电模式下立体书架会「消失」

**现象**：手机电量低、系统自动进省电模式后，书架从立体的书脊变成一排平铺的封面卡片
（只剩 `NO.xxx` / `FILE / xxx` 和封面），看起来像书架没了。

**原因**：上游把「省电模式」也算进了立体书架的开关：

```kotlin
// Wenku8Effects.resolveWenku8Effects
shelf3d = !saver && !lowRam && theme.allows(Wenku8PerformanceEffect.Shelf3d)
```

`saver` 来自 `PowerManager.isPowerSaveMode`，一进省电模式就被置为 false，书架直接降级成平铺。

**改法**：书架是一次性绘制的静态画面（没有持续动画），省电模式下没必要降级，去掉 `saver` 条件：

```kotlin
shelf3d = !lowRam && theme.allows(Wenku8PerformanceEffect.Shelf3d)
```

低内存设备（`isLowRamDevice`）仍会降级；显式打开「性能与动效 → 低性能模式」时，
仍按那一页的「保留效果」处理（勾上「立体书架」即可保持 3D）。

文件：`app/src/main/java/io/legado/app/core/designsystem/wenku8/effects/Wenku8Effects.kt`

### 4.2 朗读设置页偶发崩溃（NPE）

**现象**：进入「设置 → 朗读设置」时偶发崩溃，日志首行是：

```
java.lang.NullPointerException: Attempt to invoke virtual method
'java.lang.Class java.lang.Object.getClass()' on a null object reference
```

**定位**：用本仓库自己 build 出来的 `app/build/outputs/mapping/appCoreRelease/mapping.txt`
反查混淆栈，关键帧对应关系是

| 混淆帧 | 真实位置 |
| --- | --- |
| `ft.p0.<init>` | `Wenku8ListeningState`（听书状态）的构造函数 |
| `ws.hi.invokeSuspend` | `Wenku8ListeningRepository` 里的 flow lambda |
| `b70.o0` / `c70.q` | `mapLatest` / `combine` |

也就是说崩在「朗读设置页订阅听书状态」这条流上。`Object.getClass()` 的空指针通常来自
Gson 对 null 取值/序列化，说明某个字段在这台机器上是 null。

**改法**（防御性）：模拟器（Android 16）上反复进出朗读设置复现不出来，没有确认到具体空字段，
所以做两层兜底，保证这一页不会再整页崩：

- `observe()` 里整段状态构建包一层 `runCatching`，异常时退回 `Wenku8ListeningState()`
  （等价「未开始听书」）；
- 声音列表项 `ListeningVoice(...)` 与自定义音色 `ListeningCustomConfig(...)` 里可能为空的字符串
  统一 `orEmpty()` 后再用。

文件：`app/src/main/java/io/legado/app/data/repository/Wenku8ListeningRepository.kt`

> 如果升级后仍会崩，欢迎把新日志贴到 issue：这台机器是 Android 17，可能有额外的系统 TTS 差异。

## 5. 切换标签页的重影 / 书架两侧空白（mod.3）

### 5.1 旧页面不消失，新页面叠上来

**现象**：在底部标签之间切换时，上一个页面的内容没有立刻消失，新页面半透明地盖在上面，
两页的文字交错，看起来「很卡」。不是每次都能看到。

**原因**：`Wenku8MainPager` 的切换实现是「保留旧页 + 新页斜向擦除」：

```kotlin
val visible = active || page == outgoing       // 旧页仍在绘制
... Modifier.archivePlane(clockState, direction, !active)   // 旧页只平移 10dp
LaunchedEffect(frame) { clock.animateTo(1f, tween(Wenku8Motion.Enter, ...)) }  // 420ms
```

旧页在整个 420ms 里保持不透明，新页由一个斜向 `clipPath` 从左（或右）扫出来——
扫到一半时，屏幕左边是旧页、右边是新页，斜边正好切过文字。设备掉帧时这个中间态会停留更久，
就变成了「两个页面叠在一起」。

**改法**：只绘制当前页，旧页在切换时立即移除；擦除时长从 420ms 收到 200ms（`Wenku8Motion.Page`），
新页从背景里推出来，不再有重影。

```kotlin
val visible = active
if (frame.outgoing >= 0) clock.animateTo(1f, tween(Wenku8Motion.Page, easing = LinearEasing))
```

文件：`app/src/main/java/io/legado/app/core/designsystem/wenku8/effects/Wenku8PageMotion.kt`

> 代价：切换时不再有「旧页垫在下面」的过渡，但在慢设备上观感明显更干净。
> 如果之后还想保留旧页做衬底，需要把擦除改成不透明蒙版（`drawRect` 覆盖）而不是让旧页一直画着。

### 5.2 立体书架两侧留空白

**现象**：书架左右各有一条和「档案书目」一样的 24dp 空白，书排不到屏幕边缘。

**原因**：书架是档案书目 LazyGrid 的头部 item，网格的 `contentPadding` 横向 24dp 对所有 item 生效。

**改法**：新增 `Modifier.shelfFullBleed(bleed)`——把测量宽度加宽 `2 × bleed` 再左移 `bleed`，
只有书架这一块顶到屏幕两边，下面的书名行等仍在原边距：

```kotlin
internal fun Modifier.shelfFullBleed(bleed: Dp = 24.dp): Modifier = layout { measurable, constraints ->
    val inset = bleed.roundToPx()
    val wide = constraints.copy(
        minWidth = constraints.maxWidth + inset * 2,
        maxWidth = constraints.maxWidth + inset * 2,
    )
    val placeable = measurable.measure(wide)
    layout(constraints.maxWidth, placeable.height) { placeable.place(-inset, 0) }
}
```

调用处（`BookshelfScreen.kt`）传 `shelfModifier = Modifier.shelfFullBleed(24.dp)`；
`Wenku8ArchiveShelf` 新增 `shelfModifier` 参数，只作用在书架那块 `BoxWithConstraints` 上。

## 构建

```bash
export JAVA_HOME=<JDK 21>
export GRADLE_USER_HOME=<与工程同盘的 gradle 缓存>
gradle :app:assembleAppCoreRelease \
  -PRELEASE_STORE_FILE=<keystore> -PRELEASE_STORE_PASSWORD=... \
  -PRELEASE_KEY_ALIAS=... -PRELEASE_KEY_PASSWORD=...
# 产物 app/build/outputs/apk/app/core/release/
```

本项目要求 Gradle 9.8 / AGP 9.5.1 / JDK 21 / compileSdk 37.1，比常见的 Android 工程新不少。

## 致谢

| 项目 | 作者 | 这里用到了什么 |
| --- | --- | --- |
| [AzukiPann/AzukiReader](https://github.com/AzukiPann/AzukiReader) | [AzukiPann](https://github.com/AzukiPann) | 整个 App，以及这次的立体书架、档案式书目、动效 |
| [P5UEDGK/Wenku8_Flutter](https://github.com/P5UEDGK/Wenku8_Flutter) | [P5UEDGK](https://github.com/P5UEDGK) | 立体书架与档案式界面的原始设计（AzukiReader 据此移植） |
| [HapeLee/legado-with-MD3](https://github.com/HapeLee/legado-with-MD3) | HapeLee | 阅读主体、书源与阅读业务（AzukiReader 的基线） |
| [gedoor/legado](https://github.com/gedoor/legado) | gedoor | 上述两个项目的源头 |
| 阅读S（本人 legado 改版） | sssdex | 震感参数（15ms / 150）与宋体字号的参考 |

上游是纯 vibecoding 的个人项目、作者已说明后续不更新；这个 fork 也只是按自己的使用习惯补三处细节，
同样不保证维护。上游未声明 License，本 fork 仅作个人使用与学习，版权归原作者。
