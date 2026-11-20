# make-contact-sheet.ps1 - 生成真实尺寸联系表 + 模拟启动器二次裁切
# 目的：在没有视觉模型的情况下，把「表上到底长什么样」变成可量的数字 + 可看的图
param(
  [string]$ResDir = "$PSScriptRoot\..\app\src\main\res",
  [string]$OutPng = "$PSScriptRoot\preview-contact-sheet.png"
)
Add-Type -AssemblyName System.Drawing

function Load-Icon([string]$dpi, [string]$name) {
  return [System.Drawing.Bitmap]::FromFile((Resolve-Path (Join-Path $ResDir "$dpi\$name")))
}

# 圆形遮罩裁切（模拟启动器把图标裁成圆）
function Get-RoundClipped([System.Drawing.Bitmap]$src, [int]$cropPct) {
  $dst = New-Object System.Drawing.Bitmap($src.Width, $src.Height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $g = [System.Drawing.Graphics]::FromImage($dst)
  $g.Clear([System.Drawing.Color]::Transparent)
  $g.InterpolationMode = 'HighQualityBicubic'
  $d = $src.Width * $cropPct / 100.0      # 裁切后保留的直径占比
  $path = New-Object System.Drawing.Drawing2D.GraphicsPath
  $path.AddEllipse(($src.Width - $d) / 2, ($src.Height - $d) / 2, $d, $d)
  $g.SetClip($path)
  $g.DrawImage($src, 0, 0, $src.Width, $src.Height)
  $g.Dispose()
  return $dst
}

function Count-Green([System.Drawing.Bitmap]$bmp) {
  $n = 0
  for ($y = 0; $y -lt $bmp.Height; $y++) {
    for ($x = 0; $x -lt $bmp.Width; $x++) {
      $p = $bmp.GetPixel($x, $y)
      if ($p.A -ge 160 -and $p.G -ge 120 -and ($p.G - $p.R) -ge 45 -and ($p.G - $p.B) -ge 25) { $n++ }
    }
  }
  return $n
}

# ---------- 1) 激进裁切存活测试 ----------
$round192 = Load-Icon 'mipmap-xxxhdpi' 'ic_launcher_round.png'
$survive = @()
foreach ($pct in 100, 95, 90, 85, 80, 75) {
  $c = Get-RoundClipped $round192 $pct
  $survive += [pscustomobject]@{ CropPct = "$pct%"; GreenPx = (Count-Green $c) }
  $c.Dispose()
}
$survive | Format-Table -AutoSize
$survive | ConvertTo-Json | Set-Content "$PSScriptRoot\qa-crop-survival.json" -Encoding UTF8

# ---------- 2) 联系表 ----------
$S = 1024
$sheet = New-Object System.Drawing.Bitmap($S, 640, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($sheet)
$g.Clear([System.Drawing.Color]::FromArgb(255, 32, 32, 36))
$g.InterpolationMode = 'HighQualityBicubic'
$g.PixelOffsetMode = 'HighQuality'
$g.TextRenderingHint = 'ClearTypeGridFit'

$font = New-Object System.Drawing.Font('Segoe UI', 13)
$fontS = New-Object System.Drawing.Font('Segoe UI', 11)
$brush = [System.Drawing.Brushes]::WhiteSmoke

# 左：大图 192 round（未裁） + 圆裁 90%
$big = Load-Icon 'mipmap-xxxhdpi' 'ic_launcher_round.png'
$bigClip = Get-RoundClipped $big 90
$g.DrawImage($big, 20, 30, 192, 192)
$g.DrawString("192 原图", $fontS, $brush, 20, 232)
$g.DrawImage($bigClip, 240, 30, 192, 192)
$g.DrawString("192 圆裁 90%", $fontS, $brush, 240, 232)
$big.Dispose(); $bigClip.Dispose()

# 中：真实尺寸阶梯（圆裁 90%）
$x = 470; $y = 40
foreach ($dpi in @(@('mipmap-xxxhdpi',192), @('mipmap-xxhdpi',144), @('mipmap-xhdpi',96), @('mipmap-hdpi',72), @('mipmap-mdpi',48))) {
  $b = Load-Icon $dpi[0] 'ic_launcher_round.png'
  $c = Get-RoundClipped $b 90
  $sz = $dpi[1]
  $g.DrawImage($c, $x, $y, $sz, $sz)
  $g.DrawString([string]$sz, $fontS, $brush, $x, $y + $sz + 4)
  $x += $sz + 30
  if ($x -gt 940) { $x = 470; $y = 290 }
  $b.Dispose(); $c.Dispose()
}

# 右：48px 放大 6 倍（最近邻，看绿点实况）
$b48 = Load-Icon 'mipmap-mdpi' 'ic_launcher_round.png'
$c48 = Get-RoundClipped $b48 90
$g.InterpolationMode = 'NearestNeighbor'
$g.DrawImage($c48, 700, 300, 288, 288)
$g.InterpolationMode = 'HighQualityBicubic'
$g.DrawString("48px x6 放大（最近邻）", $fontS, $brush, 700, 596)
$b48.Dispose(); $c48.Dispose()

$g.DrawString("VRC Watch Friends 图标验收联系表 / 圆裁 90% 模拟启动器", $font, $brush, 20, 600)
$g.Dispose()
$sheet.Save($OutPng, [System.Drawing.Imaging.ImageFormat]::Png)
$sheet.Dispose()
Write-Output "written: $OutPng"
