# qa-apk.ps1 - 直接验收 APK 里最终打进包的图标（aapt2 会重压缩，不能比字节只能比像素）
param(
  [string]$Apk = "$PSScriptRoot\..\VRC-Watch-Friends-1.0.3-icon-preview.apk",
  [string]$OutJson = "$PSScriptRoot\qa-apk-report.json"
)
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.IO.Compression.FileSystem

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

$zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $Apk))
$rows = @(); $fail = 0
try {
  foreach ($e in ($zip.Entries | Where-Object { $_.FullName -match '^res/mipmap-(m|h|xh|xxh|xxxh)dpi-v4/ic_launcher(_round|_foreground)?\.png$' } | Sort-Object FullName)) {
    $s = $e.Open()
    $ms = New-Object System.IO.MemoryStream
    $s.CopyTo($ms); $s.Close()
    $ms.Position = 0
    $bmp = [System.Drawing.Bitmap]::FromStream($ms)
    $kind = if ($e.FullName -match '_foreground') { 'fg' } elseif ($e.FullName -match '_round') { 'round' } else { 'legacy' }
    $green = Count-Green $bmp
    $corner = -1
    if ($kind -eq 'round') {
      $corner = 0
      foreach ($pt in @(@(0,0), @(($bmp.Width - 1),0), @(0,($bmp.Height - 1)), @(($bmp.Width - 1),($bmp.Height - 1)))) {
        $p = $bmp.GetPixel([int]$pt[0], [int]$pt[1]); if ($p.A -gt $corner) { $corner = $p.A }
      }
    }
    $ok = ($green -ge 4) -and ($kind -ne 'round' -or $corner -eq 0)
    if (-not $ok) { $fail++ }
    $rows += [pscustomobject]@{ Entry = $e.FullName; Kind = $kind; Size = "$($bmp.Width)x$($bmp.Height)"; Green = $green; CornerA = $corner; Verdict = $(if ($ok) { 'PASS' } else { 'FAIL' }) }
    $bmp.Dispose(); $ms.Dispose()
  }
} finally { $zip.Dispose() }

$rows | Format-Table -AutoSize
$rows | ConvertTo-Json -Depth 3 | Set-Content $OutJson -Encoding UTF8
Write-Output ("APK QA: {0} entries, {1} failed -> {2}" -f $rows.Count, $fail, $(if ($fail -eq 0) { 'ALL PASS' } else { 'HAS FAILURES' }))
if ($fail -gt 0) { exit 1 }
