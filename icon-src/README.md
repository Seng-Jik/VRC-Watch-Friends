# 图标源文件（icon-src）

Q-011 的**非生图模型替代方案**：没有可用的生图 API，改为手写矢量（SVG）绘制，
再用 Chrome headless + System.Drawing 栅格化成各密度 PNG。全部文件都可重建、无第三方依赖。

## 设计

- 深色底：`#0E1116`（自适应图标背景色，见 `res/values/colors.xml`）
- 头显本体：青绿渐变 `#5CF2D8 → #2BD3E4 → #16A9D6`，圆角矩形 552×280（1024 网格）
- 面罩内窗：`#0B1220`，双镜片 `#BFFFF3 → #57F0DC`
- 在线绿点：`#43B581`，位于**中心正下方 `cy=680`**，整图内容最大半径 **325 px < 338 px 安全区**
  （上一版绿点贴右下角，被 ColorOS 二次裁切吃掉 → 这里挪进安全区）
- 内容包围盒 `200,296 – 820,728`，水平中心 510、垂直中心 512（基本居中）

## 文件

| 文件 | 用途 |
|---|---|
| `icon-fg.svg` | 自适应图标前景（透明底，只含图形 + 绿点） |
| `icon-full.svg` | 完整图（含深色底，用于 legacy `ic_launcher.png` / `_round`） |
| `render-fg.html` / `render-full.html` | Chrome headless 渲染壳（1024×1024） |
| `fg-1024.png` / `full-1024.png` | 渲染结果（源） |
| `preview-1024-full.png` / `preview-1024-foreground.png` | 给人看的大图 |
| `preview-192-round.png` / `preview-432-foreground.png` | 实际打包尺寸预览 |

## 重新生成

```powershell
$chrome='C:\Program Files\Google\Chrome\Application\chrome.exe'
& $chrome --headless=new --disable-gpu --hide-scrollbars --default-background-color=00000000 `
  --window-size=1024,1024 --screenshot="fg-1024.png" "file:///D:/Vim/vrc-watch-friends/icon-src/render-fg.html"
& $chrome --headless=new --disable-gpu --hide-scrollbars `
  --window-size=1024,1024 --screenshot="full-1024.png" "file:///D:/Vim/vrc-watch-friends/icon-src/render-full.html"
```

然后按 `mdpi 48 / hdpi 72 / xhdpi 96 / xxhdpi 144 / xxxhdpi 192`（legacy）
与 `108 / 162 / 216 / 324 / 432`（foreground）用 HighQualityBicubic 缩放写入
`app/src/main/res/mipmap-*/`；`_round` 额外做一次椭圆裁剪（角 alpha=0）。

## 合规校验（每次改图标都要跑）

1. `fg` 不透明像素离中心最大距离 **< 338 px**（108dp 网格的 66% 安全区）
2. 绿点存在（底部中间），镜片为亮青
3. 改完 `./gradlew :app:testDebugUnitTest :app:assembleDebug --init-script build-tools/init-aliyun.gradle` 全绿

## 数值验收（自动化，代替「用眼睛看」）

模型没有视觉，所以把「表上长什么样」全部变成可判定的数字。三个脚本，改图标后按序跑：

```powershell
pwsh -File .\qa-icon.ps1          # 15 个资源：尺寸/绿点像素/绿点距边/圆角 alpha/安全区/对比度
pwsh -File .\make-contact-sheet.ps1  # 联系表 preview-contact-sheet.png + 激进裁切存活测试
pwsh -File .\qa-apk.ps1           # 直接验收 APK 里最终打进包的图标（aapt2 会重压缩，只能比像素）
```

### 2026-11-20 08:30 验收结果（全部 PASS）

| 检查 | 结果 |
|---|---|
| 尺寸 | 15/15 与期望完全一致（48/72/96/144/192、fg 108/162/216/324/432） |
| 绿点存在 | mdpi 48px 仍有 **12 px**（≈3.75px 直径），最大 192px 有 166 px —— 不会「小尺寸消失」 |
| 绿点位置 | 距图边 **16.0–16.3 dp**（全密度一致，`cy=680` 居中） |
| 圆角裁剪 | `_round` 四角 alpha **= 0**，5/5 |
| 前景安全区 | 最大半径归一 0.63–0.64 < 0.660（338px/512px，即 1024 网格下 ≈324 px），5/5 |
| 本体/底对比度 | 4.44–13.20（WCAG 3:1 阈值之上，48px 时 6.30） |
| **激进裁切存活** | 圆形遮罩保留 **100%→75%** 六档，绿点像素恒为 **166**（一像素不掉）⇒ 这次不管启动器裁多狠都吃不到绿点 |
| **APK 内实测** | 15/15 条目从 APK 解出后重新计数，与源一致 |

联系表：`preview-contact-sheet.png` —— 192 原图 / 192 圆裁 90% / 真实尺寸阶梯 192→48 / 48px 六倍最近邻放大。

> 注：`qa-report.json`、`qa-apk-report.json`、`qa-crop-survival.json` 是机器可读结果，进版本库便于对比回归。
