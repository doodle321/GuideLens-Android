# Downloads GuideLens ML models with verification (TFL3 magic + size).
# Run from the repo root:  powershell tools/download-models.ps1
$assets = Join-Path $PSScriptRoot "..\app\src\main\assets"
New-Item -ItemType Directory -Force $assets | Out-Null

function Test-Tfl([string]$f) {
    if (-not (Test-Path $f)) { return $false }
    $fs = [System.IO.File]::OpenRead($f)
    try {
        $skip = New-Object byte[] 4; $m = New-Object byte[] 4
        $fs.Read($skip, 0, 4) | Out-Null
        if ($fs.Read($m, 0, 4) -ne 4) { return $false }
        return ($m[0] -eq 0x54 -and $m[1] -eq 0x46 -and $m[2] -eq 0x4C -and $m[3] -eq 0x33)
    } finally { $fs.Close() }
}

function Get-Fetch([string]$name, [long]$min, [string[]]$urls, [bool]$required) {
    $f = Join-Path $assets $name
    if ((Test-Tfl $f) -and (Get-Item $f).Length -gt $min) {
        Write-Host "OK: $name already present"; (Get-FileHash $f -Algorithm SHA256).Hash; return
    }
    foreach ($u in $urls) {
        Write-Host "Trying: $u"
        $tmp = "$f.part"
        try {
            Invoke-WebRequest -Uri $u -OutFile $tmp -TimeoutSec 60
            if ((Test-Tfl $tmp) -and (Get-Item $tmp).Length -gt $min) {
                Move-Item -Force $tmp $f
                Write-Host "Downloaded $name"
                Write-Host ("SHA-256: " + (Get-FileHash $f -Algorithm SHA256).Hash + "  <- pin in app/models.sha256")
                return
            }
        } catch { Write-Host "failed: $($_.Exception.Message)" }
        Remove-Item -Force -ErrorAction SilentlyContinue $tmp
    }
    if ($required) { Write-Host "::error::could not obtain $name"; exit 1 }
    Write-Host "WARNING: $name unavailable"
}

Get-Fetch "detect.tflite" 1000000 @(
    "https://cdn.jsdelivr.net/gh/am15h/object_detection_flutter@master/assets/detect.tflite",
    "https://raw.githubusercontent.com/am15h/object_detection_flutter/master/assets/detect.tflite"
) $true

Get-Fetch "deeplab_cityscapes.tflite" 1000000 @(
    "https://raw.githubusercontent.com/google-coral/edgetpu/master/test_data/deeplabv3_mnv2_cityscapes_quant.tflite"
) $false
