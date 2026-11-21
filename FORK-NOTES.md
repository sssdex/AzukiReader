# 这个 fork 改了什么

基于 **[AzukiPann/AzukiReader](https://github.com/AzukiPann/AzukiReader) `1.00.12`**，只动书架的三处小地方，
没有碰阅读器、书源、同步、界面其它部分。

- 版本：`1.00.12-mod`（versionCode `32663`，包名仍是 `io.azukireader.app`，可与官方版覆盖安装/共存二选一）
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
