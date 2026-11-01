# Renders the launcher tile (ic_launcher_background + ic_launcher_foreground) to a PNG from the
# same coordinates as the vectors, for the store listing. Windows PowerShell 5.1, nothing installed:
#   powershell -File tools/desktop/render_icon.ps1 -out fastlane/metadata/android/en-US/images/icon.png
# Change the vectors and this together.
param([string]$out, [int]$size = 512)
Add-Type -AssemblyName System.Drawing
$bmp = New-Object System.Drawing.Bitmap $size, $size
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = 'AntiAlias'
# The visible 72dp centre of the 108dp layers fills the image: v -> (v - 18) * size / 72.
$k = $size / 72.0
function M([double]$v) { return ($v - 18) * $k }
$rect = New-Object System.Drawing.RectangleF 0, 0, $size, $size
$lg = New-Object System.Drawing.Drawing2D.LinearGradientBrush ([System.Drawing.PointF]::new((M 0),(M 0))), ([System.Drawing.PointF]::new((M 108),(M 108))), ([System.Drawing.Color]::FromArgb(255,0xE2,0xFF,0x7A)), ([System.Drawing.Color]::FromArgb(255,0x8C,0xCB,0x1E))
$blend = New-Object System.Drawing.Drawing2D.ColorBlend 3
$blend.Colors = @([System.Drawing.Color]::FromArgb(255,0xE2,0xFF,0x7A), [System.Drawing.Color]::FromArgb(255,0xC8,0xFF,0x4D), [System.Drawing.Color]::FromArgb(255,0x8C,0xCB,0x1E))
$blend.Positions = @(0.0, 0.55, 1.0)
$lg.InterpolationColors = $blend
$g.FillRectangle($lg, $rect)
$ink = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255,0x0B,0x0F,0x1A))
$x = 24.8
foreach ($h in 14,26,40,56,40,26,14) {
  $y0 = 54 - $h/2
  $w = 5.6 * $k; $hh = $h * $k; $r = 2.8 * $k
  $p = New-Object System.Drawing.Drawing2D.GraphicsPath
  $px = M $x; $py = M $y0
  $p.AddArc($px, $py, 2*$r, 2*$r, 180, 180)
  $p.AddArc($px, $py + $hh - 2*$r, 2*$r, 2*$r, 0, 180)
  $p.CloseFigure()
  $g.FillPath($ink, $p)
  $x += 8.8
}
$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
