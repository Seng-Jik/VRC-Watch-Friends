# qa-icon.ps1 - 图标几何/可辨识度数值验收（无视觉模型也能判定）
# 输出：控制台 + qa-report.json
param(
  [string]$ResDir = "$PSScriptRoot\..\app\src\main\res",
  [string]$OutJson = "$PSScriptRoot\qa-report.json"
)

Add-Type -AssemblyName System.Drawing

$expect = @{
  'mipmap-mdpi\ic_launcher.png'            = 48
  'mipmap-hdpi\ic_launcher.png'            = 72
  'mipmap-xhdpi\ic_launcher.png'           = 96
  'mipmap-xxhdpi\ic_launcher.png'          = 144
  'mipmap-xxxhdpi\ic_launcher.png'         = 192
  'mipmap-mdpi\ic_launcher_round.png'      = 48
  'mipmap-hdpi\ic_launcher_round.png'      = 72
  'mipmap-xhdpi\ic_launcher_round.png'     = 96
  'mipmap-xxhdpi\ic_launcher_round.png'    = 144
  'mipmap-xxxhdpi\ic_launcher_round.png'   = 192
  'mipmap-mdpi\ic_launcher_foreground.png' = 108
  'mipmap-hdpi\ic_launcher_foreground.png' = 162
  'mipmap-xhdpi\ic_launcher_foreground.png'= 216
  'mipmap-xxhdpi\ic_launcher_foreground.png'=324
  'mipmap-xxxhdpi\ic_launcher_foreground.png'=432
}

function Get-Px([System.Drawing.Bitmap]$bmp, [int]$x, [int]$y) {
  if ($x -lt 0 -or $y -lt 0 -or $x -ge $bmp.Width -or $y -ge $bmp.Height) { return $null }
  return $bmp.GetPixel($x, $y)
}

function Get-Lum($c) { return (0.2126 * $c.R + 0.7152 * $c.G + 0.0722 * $c.B) }
function Get-RelLum([double]$lum255) {
  $s = $lum255 / 255.0
  if ($s -le 0.03928) { return $s / 12.92 } else { return [math]::Pow(($s + 0.055) / 1.055, 2.4) }
}
function Get-Contrast($c1, $c2) {
  $l1 = Get-RelLum (Get-Lum $c1); $l2 = Get-RelLum (Get-Lum $c2)
  if ($l1 -lt $l2) { $t = $l1; $l1 = $l2; $l2 = $t }
  return ($l1 + 0.05) / ($l2 + 0.05)
}

$rows = @()
$fail = 0

foreach ($rel in ($expect.Keys | Sort-Object)) {
  $path = Join-Path $ResDir $rel
  $exp  = $expect[$rel]
  $kind = if ($rel -match '_foreground') { 'fg' } elseif ($rel -match '_round') { 'round' } else { 'legacy' }

  if (-not (Test-Path $path)) {
    $rows += [pscustomobject]@{ File=$rel; Kind=$kind; Size="MISSING"; Expect=$exp; Green=-1; DotDp=-1; CornerA=-1; MaxRnorm=-1; Contrast=-1; Verdict='FAIL' }
    $fail++
    continue
  }

  $bmp = [System.Drawing.Bitmap]::FromFile((Resolve-Path $path))
  try {
    $w = $bmp.Width; $h = $bmp.Height
    $sizeOk = ($w -eq $exp -and $h -eq $exp)

    # --- 绿色在线点：G 明显高于 R/B 的像素 ---
    $green = 0; $minX = $w; $maxX = -1; $minY = $h; $maxY = -1
    $opaque = 0; $minA = 255
    $maxR2 = 0.0
    $sumR = 0; $sumG = 0; $sumB = 0; $nVis = 0
    for ($y = 0; $y -lt $h; $y++) {
      for ($x = 0; $x -lt $w; $x++) {
        $p = $bmp.GetPixel($x, $y)
        if ($p.A -lt $minA) { $minA = $p.A }
        if ($p.A -lt 16) { continue }
        $opaque++
        $sumR += $p.R; $sumG += $p.G; $sumB += $p.B; $nVis++
        $dx = ($x + 0.5) - $w / 2.0; $dy = ($y + 0.5) - $h / 2.0
        $r2 = [math]::Sqrt($dx * $dx + $dy * $dy) / ($w / 2.0)
        if ($r2 -gt $maxR2) { $maxR2 = $r2 }
        if ($p.A -ge 160 -and $p.G -ge 120 -and ($p.G - $p.R) -ge 45 -and ($p.G - $p.B) -ge 25) {
          $green++
          if ($x -lt $minX) { $minX = $x }; if ($x -gt $maxX) { $maxX = $x }
          if ($y -lt $minY) { $minY = $y }; if ($y -gt $maxY) { $maxY = $y }
        }
      }
    }

    # --- 圆角图标四角必须全透明 ---
    $cornerA = -1
    if ($kind -eq 'round') {
      $cornerA = 0
      $xm = $w - 1; $ym = $h - 1
      $corners = @( ,@(0,0) ) + @( ,@($xm,0) ) + @( ,@(0,$ym) ) + @( ,@($xm,$ym) )
      foreach ($pt in $corners) {
        $p = $bmp.GetPixel([int]$pt[0], [int]$pt[1])
        if ($p.A -gt $cornerA) { $cornerA = $p.A }
      }
    }

    # --- 绿点中心到边缘的余量（换算成 dp，1dp = size/48 for legacy/round）---
    $dotDp = -1
    if ($green -gt 0) {
      $dpm = if ($kind -eq 'fg') { $exp / 108.0 } else { $exp / 48.0 }
      $cx = ($minX + $maxX + 1) / 2.0; $cy = ($minY + $maxY + 1) / 2.0
      $distEdge = [math]::Min([math]::Min($cx, $w - $cx), [math]::Min($cy, $h - $cy))
      $dotDp = [math]::Round($distEdge / $dpm, 2)
    }

    # --- 对比度：头显本体（左上 33%,37%）vs 底（右上 90%,8%）---
    $contrast = -1
    if ($kind -ne 'fg') {
      $bodyPx = Get-Px $bmp ([int]($w * 0.30)) ([int]($h * 0.33))
      $bgPx   = Get-Px $bmp ([int]($w * 0.90)) ([int]($h * 0.08))
      if ($bodyPx -and $bgPx) { $contrast = [math]::Round((Get-Contrast $bodyPx $bgPx), 2) }
    }

    $verdict = 'PASS'
    $notes = @()
    if (-not $sizeOk) { $verdict = 'FAIL'; $notes += "尺寸 $w x $h != $exp" }
    if ($kind -eq 'round' -and $cornerA -ne 0) { $verdict = 'FAIL'; $notes += "圆角 alpha=$cornerA (应 0)" }
    if ($kind -eq 'fg' -and $maxR2 -gt (338.0 / 512.0)) { $verdict = 'FAIL'; $notes += "超出安全区 r=$([math]::Round($maxR2*512,1))px" }
    # 绿点在小尺寸下必须仍有可数像素，且不贴边
    if ($green -lt 4) { $verdict = 'FAIL'; $notes += "绿点像素仅 $green" }
    if ($green -gt 0 -and $dotDp -lt 2.0) { $verdict = 'FAIL'; $notes += "绿点距边仅 $dotDp dp" }
    if ($kind -ne 'fg' -and $contrast -gt 0 -and $contrast -lt 3.0) { $verdict = 'WARN'; $notes += "本体/底对比 $contrast < 3.0" }

    if ($verdict -eq 'FAIL') { $fail++ }

    $rows += [pscustomobject]@{
      File = $rel; Kind = $kind; Size = "$w x $h"; Expect = $exp
      Green = $green; DotDp = $dotDp; CornerA = $cornerA
      MaxRnorm = [math]::Round($maxR2, 4); MaxRpx = [math]::Round($maxR2 * ($exp / 2.0), 1)
      Contrast = $contrast; Verdict = $verdict; Notes = ($notes -join '; ')
    }
  } finally { $bmp.Dispose() }
}

$rows | Format-Table File, Kind, Size, Green, DotDp, CornerA, Contrast, Verdict -AutoSize
$rows | Where-Object { $_.Notes } | Format-Table File, Notes -AutoSize

$rows | ConvertTo-Json -Depth 4 | Set-Content -Path $OutJson -Encoding UTF8
Write-Output ""
Write-Output ("RESULT: {0} rows, {1} failed -> {2}" -f $rows.Count, $fail, $(if ($fail -eq 0) { 'ALL PASS' } else { 'HAS FAILURES' }))
if ($fail -gt 0) { exit 1 } else { exit 0 }
