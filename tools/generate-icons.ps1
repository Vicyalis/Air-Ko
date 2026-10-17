# Generate Air-Ko launcher icons (white background, black "Air").
#
# Usage (from repo root):
#   powershell -ExecutionPolicy Bypass -File tools/generate-icons.ps1
#
# Note: PNGs are rendered into a staging directory first and then copied into
# app/src/main/res. Rendering directly into a path that contains non-ASCII
# characters (e.g. a Chinese project folder) makes GDI+ silently no-op on
# Bitmap.Save, so the staging step is deliberate - do not remove it.
#
# Outputs, per density:
#   mipmap-<density>/ic_launcher.png            legacy icon (white bg + black text)
#   mipmap-<density>/ic_launcher_background.png adaptive background (solid white)
#   mipmap-<density>/ic_launcher_foreground.png adaptive foreground (transparent + black text)
#   mipmap-<density>/ic_launcher_monochrome.png themed icon (transparent + black text)

Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = 'Stop'

# density -> @(legacy icon edge, adaptive layer edge)
$densities = [ordered]@{
    'mdpi'    = @(48, 108)
    'hdpi'    = @(72, 162)
    'xhdpi'   = @(96, 216)
    'xxhdpi'  = @(144, 324)
    'xxxhdpi' = @(192, 432)
}

$resRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\app\src\main\res')).Path
$staging = Join-Path $env:TEMP 'airko-icon-staging'
if (Test-Path $staging) { Remove-Item $staging -Recurse -Force }
New-Item -ItemType Directory -Path $staging | Out-Null

$fontFamily = 'Arial'
$text = 'Air'

function Get-FittedFont {
    param(
        [System.Drawing.Graphics]$Graphics,
        [double]$TargetWidth,
        [double]$TargetHeight
    )
    $lo = 4.0
    $hi = 600.0
    for ($i = 0; $i -lt 40; $i++) {
        $mid = ($lo + $hi) / 2
        $probe = New-Object System.Drawing.Font($fontFamily, $mid, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
        $size = $Graphics.MeasureString($text, $probe)
        if ($size.Width -le $TargetWidth -and $size.Height -le $TargetHeight) { $lo = $mid } else { $hi = $mid }
        $probe.Dispose()
    }
    return New-Object System.Drawing.Font($fontFamily, $lo, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
}

function New-Icon {
    param(
        [string]$OutputPath,
        [int]$Edge,
        [System.Drawing.Color]$BackgroundColor,
        [System.Drawing.Color]$TextColor,
        [double]$FillRatio,
        [switch]$WithoutText
    )
    $bitmap = New-Object System.Drawing.Bitmap($Edge, $Edge, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $graphics.Clear($BackgroundColor)

    if (-not $WithoutText) {
        $font = Get-FittedFont -Graphics $graphics -TargetWidth ($Edge * $FillRatio) -TargetHeight ($Edge * ($FillRatio + 0.12))
        $brush = New-Object System.Drawing.SolidBrush($TextColor)
        $format = New-Object System.Drawing.StringFormat
        $format.Alignment = [System.Drawing.StringAlignment]::Center
        $format.LineAlignment = [System.Drawing.StringAlignment]::Center
        $rect = New-Object System.Drawing.RectangleF(0, 0, $Edge, $Edge)
        $graphics.DrawString($text, $font, $brush, $rect, $format)
        $format.Dispose()
        $brush.Dispose()
        $font.Dispose()
    }

    $graphics.Dispose()
    $bitmap.Save($OutputPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
}

$white = [System.Drawing.Color]::White
$black = [System.Drawing.Color]::Black
$transparent = [System.Drawing.Color]::Transparent

foreach ($density in $densities.Keys) {
    $legacyEdge = $densities[$density][0]
    $layerEdge = $densities[$density][1]

    $stageDir = Join-Path $staging $density
    New-Item -ItemType Directory -Path $stageDir | Out-Null

    New-Icon -OutputPath (Join-Path $stageDir 'ic_launcher.png') -Edge $legacyEdge -BackgroundColor $white -TextColor $black -FillRatio 0.58
    New-Icon -OutputPath (Join-Path $stageDir 'ic_launcher_background.png') -Edge $layerEdge -BackgroundColor $white -TextColor $black -FillRatio 0.0 -WithoutText
    New-Icon -OutputPath (Join-Path $stageDir 'ic_launcher_foreground.png') -Edge $layerEdge -BackgroundColor $transparent -TextColor $black -FillRatio 0.48
    New-Icon -OutputPath (Join-Path $stageDir 'ic_launcher_monochrome.png') -Edge $layerEdge -BackgroundColor $transparent -TextColor $black -FillRatio 0.48
}

# Copy into the resource tree (Copy-Item handles the non-ASCII project path).
foreach ($density in $densities.Keys) {
    $targetDir = Join-Path $resRoot "mipmap-$density"
    if (-not (Test-Path $targetDir)) { New-Item -ItemType Directory -Path $targetDir | Out-Null }
    Get-ChildItem (Join-Path $staging $density) -File | ForEach-Object {
        Copy-Item $_.FullName (Join-Path $targetDir $_.Name) -Force
        Write-Output ("{0} -> mipmap-{1}/{2} ({3} bytes)" -f 'ok', $density, $_.Name, $_.Length)
    }
}

Remove-Item $staging -Recurse -Force
Write-Output 'done.'
