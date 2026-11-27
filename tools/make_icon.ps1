# Generates every brand image the project ships: exe icon, launcher preview art and the Inno
# Setup wizard images. One geometry (green tile + white screen glyph) everywhere, so the app, the
# installer and the launcher all match. Run from the repo root:
#   powershell -File tools\make_icon.ps1
param()
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing
$root = Split-Path -Parent $PSScriptRoot
$green = [System.Drawing.Color]::FromArgb(0x0F, 0x7B, 0x4F)
$white = [System.Drawing.Color]::White
$surface = [System.Drawing.Color]::FromArgb(0xF1, 0xF2, 0xF3)

function RoundRect([System.Drawing.Drawing2D.GraphicsPath]$p, [float]$x, [float]$y, [float]$w, [float]$h, [float]$r) {
    $p.AddArc($x, $y, $r, $r, 180, 90)
    $p.AddArc($x + $w - $r, $y, $r, $r, 270, 90)
    $p.AddArc($x + $w - $r, $y + $h - $r, $r, $r, 0, 90)
    $p.AddArc($x, $y + $h - $r, $r, $r, 90, 90)
    $p.CloseFigure()
}

function PaintTile([System.Drawing.Graphics]$g, [float]$s, [bool]$withFace) {
    # Green rounded tile with the white screen glyph; with a face when used as the mascot.
    $pad = $s * 0.06
    $w = $s - 2 * $pad
    $r = [Math]::Max(4, $w * 0.20)
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    RoundRect $p $pad $pad $w $w $r
    $g.FillPath((New-Object System.Drawing.SolidBrush $green), $p)
    # screen glyph
    $sw = $w * 0.58; $sh = $w * 0.40
    $sx = $pad + ($w - $sw) / 2; $sy = $pad + $w * 0.26
    $sp = New-Object System.Drawing.Drawing2D.GraphicsPath
    RoundRect $sp $sx $sy $sw $sh ([Math]::Max(2, $sh * 0.16))
    $g.FillPath((New-Object System.Drawing.SolidBrush $white), $sp)
    # stand
    $stw = $w * 0.34; $sth = [Math]::Max(2, $s * 0.03)
    $g.FillRectangle((New-Object System.Drawing.SolidBrush $white), ($pad + ($w - $stw) / 2), ($sy + $sh + $s * 0.03), $stw, $sth)
    if ($withFace) {
        # two green eyes + a smile inside the white screen
        $eyw = $sw * 0.11; $eyh = $sh * 0.34
        $g.FillRectangle((New-Object System.Drawing.SolidBrush $green), ($sx + $sw * 0.28), ($sy + $sh * 0.20), $eyw, $eyh)
        $g.FillRectangle((New-Object System.Drawing.SolidBrush $green), ($sx + $sw * 0.61), ($sy + $sh * 0.20), $eyw, $eyh)
        $pen = New-Object System.Drawing.Pen $green, ([Math]::Max(2, $s * 0.02))
        $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
        $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
        # smile = the lower slice of the arc, so it reads as a curve, not a cup
        $g.DrawArc($pen, ($sx + $sw * 0.28), ($sy + $sh * 0.40), ($sw * 0.44), ($sh * 0.50), 25, 130)
    }
}

function New-TileBitmap([int]$size, [bool]$face, [bool]$transparent = $false) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    if ($transparent) {
        # Explorer/taskbar icons must not carry a white plate: clear to nothing.
        $g.Clear([System.Drawing.Color]::FromArgb(0, 255, 255, 255))
    } else {
        $g.Clear($white)
    }
    PaintTile $g ([float]$size) $face
    $g.Dispose()
    return $bmp
}

function Save-Png($bmp, $path) { $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose() }

function Write-Ico([string]$path, $sizes) {
    $blobs = @()
    foreach ($s in $sizes) {
        $png = New-Object System.IO.MemoryStream
        (New-TileBitmap $s $false $true).Save($png, [System.Drawing.Imaging.ImageFormat]::Png)
        $blobs += ,@($s, $png.ToArray())
    }
    $fs = [System.IO.File]::Create($path)
    $bw = New-Object System.IO.BinaryWriter $fs
    $bw.Write([uint16]0); $bw.Write([uint16]1); $bw.Write([uint16]$blobs.Count)
    $offset = 6 + 16 * $blobs.Count
    foreach ($b in $blobs) {
        $dim = if ($b[0] -ge 256) { 0 } else { $b[0] }
        $bw.Write([byte]$dim); $bw.Write([byte]$dim); $bw.Write([byte]0); $bw.Write([byte]0)
        $bw.Write([uint16]1); $bw.Write([uint16]32)
        $bw.Write([uint32]$b[1].Length); $bw.Write([uint32]$offset)
        $offset += $b[1].Length
    }
    foreach ($b in $blobs) { $bw.Write($b[1]) }
    $bw.Close(); $fs.Close()
}

# --- exe icon -------------------------------------------------------------
New-Item -ItemType Directory -Force -Path "$root\receiver" | Out-Null
Write-Ico "$root\receiver\icon.ico" @(32, 48, 256)
Write-Host "icon.ico -> $((Get-Item "$root\receiver\icon.ico").Length) bytes"

# --- Inno Setup wizard images ---------------------------------------------
$img = "$root\installer\img"
New-Item -ItemType Directory -Force -Path $img | Out-Null
Save-Png (New-TileBitmap 76 $false) "$img\wizard-small.png"

# wizard large: 164x314, mascot = screen-face tile + a small phone + wifi dots
$bmp = New-Object System.Drawing.Bitmap 164, 314
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.Clear($white)
$g.TranslateTransform(14, 44)                 # head sits top-left of the canvas
PaintTile $g 92 $true
$g.ResetTransform()
# small phone beside it
$phone = New-Object System.Drawing.Drawing2D.GraphicsPath
RoundRect $phone 114 60 40 72 8
$g.FillPath((New-Object System.Drawing.SolidBrush $green), $phone)
$g.FillRectangle((New-Object System.Drawing.SolidBrush $white), 121, 70, 26, 40)
$g.FillRectangle((New-Object System.Drawing.SolidBrush $white), 126, 116, 16, 4)
# wifi dots connecting phone and mascot, centred below
$g.FillEllipse((New-Object System.Drawing.SolidBrush $green), 66, 186, 7, 7)
$g.FillEllipse((New-Object System.Drawing.SolidBrush $green), 78, 182, 9, 9)
$g.FillEllipse((New-Object System.Drawing.SolidBrush $green), 92, 178, 11, 11)
# soft foot so the composition does not float
$g.FillRectangle((New-Object System.Drawing.SolidBrush $surface), 0, 300, 164, 14)
$g.Dispose()
Save-Png $bmp "$img\wizard-large.png"
Write-Host "wizard-small/large -> " (Get-ChildItem $img | ForEach-Object { $_.Name })