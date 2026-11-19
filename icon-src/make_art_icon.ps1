Add-Type -AssemblyName System.Drawing

$srcPath = "D:\Vim\vrc-watch-friends\icon-src\src-art.jpg"
$root = "D:\Vim\vrc-watch-friends\app\src\main\res"
$src = [System.Drawing.Bitmap]::FromFile($srcPath)

$side = [Math]::Min($src.Width, $src.Height)
$x0 = [int](($src.Width - $side) / 2)
$y0 = [int]($src.Height * 0.06)
if ($y0 + $side -gt $src.Height) { $y0 = $src.Height - $side }

function Draw-Icon([int]$S, [bool]$withDot) {
    $bmp = New-Object System.Drawing.Bitmap($S, $S, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.InterpolationMode = 'HighQualityBicubic'
    $g.PixelOffsetMode = 'HighQuality'
    $g.Clear([System.Drawing.Color]::Transparent)

    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddEllipse(0, 0, $S - 1, $S - 1)
    $g.SetClip($path)
    $dest = New-Object System.Drawing.Rectangle(0, 0, $S, $S)
    $srcRect = New-Object System.Drawing.Rectangle($x0, $y0, $side, $side)
    $g.DrawImage($src, $dest, $srcRect, [System.Drawing.GraphicsUnit]::Pixel)
    $g.ResetClip()

    $pen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255, 14, 17, 22), [float]([Math]::Max(2, $S * 0.02)))
    $g.DrawEllipse($pen, $S * 0.01, $S * 0.01, $S - 1 - $S * 0.02, $S - 1 - $S * 0.02)
    $pen.Dispose()

    if ($withDot) {
        $r = $S * 0.11
        $cx = $S * 0.79
        $cy = $S * 0.79
        $ring = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
        $g.FillEllipse($ring, $cx - $r, $cy - $r, $r * 2, $r * 2)
        $green = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 59, 209, 111))
        $ir = $r * 0.74
        $g.FillEllipse($green, $cx - $ir, $cy - $ir, $ir * 2, $ir * 2)
        $ring.Dispose(); $green.Dispose()
    }

    $g.Dispose()
    return $bmp
}

$legacy = @{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
$adaptive = @{ 'mdpi' = 108; 'hdpi' = 162; 'xhdpi' = 216; 'xxhdpi' = 324; 'xxxhdpi' = 432 }

foreach ($k in $legacy.Keys) {
    $dir = Join-Path $root "mipmap-$k"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $s = $legacy[$k]
    $b1 = Draw-Icon $s $true
    $b1.Save((Join-Path $dir 'ic_launcher.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $b1.Dispose()
    $b2 = Draw-Icon $s $true
    $b2.Save((Join-Path $dir 'ic_launcher_round.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $b2.Dispose()
    $s2 = $adaptive[$k]
    $b3 = Draw-Icon $s2 $true
    $b3.Save((Join-Path $dir 'ic_launcher_foreground.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $b3.Dispose()
    Write-Output "$k ok"
}

$src.Dispose()
Write-Output "done"
