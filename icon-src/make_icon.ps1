Add-Type -AssemblyName System.Drawing
$root = "D:\Vim\vrc-watch-friends\app\src\main\res"
$logo = [System.Drawing.Bitmap]::FromFile("D:\Vim\vrc-watch-friends\icon-src\logo2.png")

function New-RoundedPath([float]$x, [float]$y, [float]$w, [float]$h, [float]$r) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $r * 2
    $p.AddArc($x, $y, $d, $d, 180, 90)
    $p.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
    $p.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
    $p.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
    $p.CloseFigure()
    return $p
}

function Draw-Icon([int]$S, [bool]$background, [bool]$round) {
    $bmp = New-Object System.Drawing.Bitmap($S, $S, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.InterpolationMode = 'HighQualityBicubic'
    $g.PixelOffsetMode = 'HighQuality'
    $g.Clear([System.Drawing.Color]::Transparent)

    if ($background) {
        $bg = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 14, 17, 22))
        if ($round) { $g.FillEllipse($bg, 0, 0, $S - 1, $S - 1) }
        else {
            $rp = New-RoundedPath 0 0 $S $S ([float]($S * 0.22))
            $g.FillPath($bg, $rp)
            $rp.Dispose()
        }
        # 细蓝边，深色表盘上更容易辨认
        $pen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255, 59, 156, 255), [float]($S * 0.012))
        if ($round) { $g.DrawEllipse($pen, $S * 0.006, $S * 0.006, $S * 0.988, $S * 0.988) }
        else {
            $rp2 = New-RoundedPath ($S * 0.006) ($S * 0.006) ($S * 0.988) ($S * 0.988) ([float]($S * 0.215))
            $g.DrawPath($pen, $rp2)
            $rp2.Dispose()
        }
        $pen.Dispose(); $bg.Dispose()
    }

    # 白色圆角卡片（VRChat 的黑色 logo 放白底最清楚）
    $cardW = $S * $(if ($background) { 0.66 } else { 0.56 })
    $cardH = $cardW / 2.29
    $cardX = ($S - $cardW) / 2
    $cardY = ($S - $cardH) / 2
    $cardBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
    $cardPath = New-RoundedPath $cardX $cardY $cardW $cardH ([float]($cardH * 0.26))
    $g.FillPath($cardBrush, $cardPath)
    $cardPath.Dispose(); $cardBrush.Dispose()

    # 画 logo（等比放进卡片内，留 7% 边距）
    $pad = $cardW * 0.07
    $lw = $cardW - 2 * $pad
    $lh = $lw * $logo.Height / $logo.Width
    if ($lh -gt $cardH - 2 * $pad) {
        $lh = $cardH - 2 * $pad
        $lw = $lh * $logo.Width / $logo.Height
    }
    $lx = ($S - $lw) / 2
    $ly = ($S - $lh) / 2
    $g.DrawImage($logo, [float]$lx, [float]$ly, [float]$lw, [float]$lh)

    # 在线状态小圆点（绿点 + 白环），压在卡片右下角
    $dotR = $S * $(if ($background) { 0.075 } else { 0.055 })
    $dotX = $cardX + $cardW - $dotR * 0.9
    $dotY = $cardY + $cardH - $dotR * 0.9
    $ring = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 255, 255, 255))
    $g.FillEllipse($ring, $dotX - $dotR, $dotY - $dotR, $dotR * 2, $dotR * 2)
    $green = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 59, 209, 111))
    $inner = $dotR * 0.78
    $g.FillEllipse($green, $dotX - $inner, $dotY - $inner, $inner * 2, $inner * 2)
    $ring.Dispose(); $green.Dispose()

    $g.Dispose()
    return $bmp
}

$legacy = @{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
$adaptive = @{ 'mdpi' = 108; 'hdpi' = 162; 'xhdpi' = 216; 'xxhdpi' = 324; 'xxxhdpi' = 432 }

foreach ($k in $legacy.Keys) {
    $dir = Join-Path $root "mipmap-$k"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $s = $legacy[$k]
    $b1 = Draw-Icon $s $true $false
    $b1.Save((Join-Path $dir 'ic_launcher.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $b1.Dispose()
    $b2 = Draw-Icon $s $true $true
    $b2.Save((Join-Path $dir 'ic_launcher_round.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $b2.Dispose()
    $s2 = $adaptive[$k]
    $b3 = Draw-Icon $s2 $false $false
    $b3.Save((Join-Path $dir 'ic_launcher_foreground.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $b3.Dispose()
    Write-Output "$k ok"
}

$anydpi = Join-Path $root 'mipmap-anydpi-v26'
New-Item -ItemType Directory -Force -Path $anydpi | Out-Null
$values = Join-Path $root 'values'
New-Item -ItemType Directory -Force -Path $values | Out-Null

$xml = @'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
'@
Set-Content -Path (Join-Path $anydpi 'ic_launcher.xml') -Value $xml -Encoding utf8
Set-Content -Path (Join-Path $anydpi 'ic_launcher_round.xml') -Value $xml -Encoding utf8

$colors = @'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#0E1116</color>
    <color name="background_dark">#000000</color>
</resources>
'@
Set-Content -Path (Join-Path $values 'colors.xml') -Value $colors -Encoding utf8

$logo.Dispose()
Write-Output "done"
