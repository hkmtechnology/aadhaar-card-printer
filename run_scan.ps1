param(
    [string]$OutputFile = "temp_scan.bmp",
    [int]$Dpi = 200,
    [int]$ColorMode = 1
)

try {
    $dm = New-Object -ComObject WIA.DeviceManager
    if ($dm.DeviceInfos.Count -eq 0) {
        Write-Output "NO_SCANNER"
        exit 1
    }

    $targetDevice = $null
    for ($i = 1; $i -le $dm.DeviceInfos.Count; $i++) {
        $info = $dm.DeviceInfos.Item($i)
        if ($info.Type -eq 1) { # Scanner
            $targetDevice = $info
            if ($info.Properties.Item("Name").Value -like "*L3250*") {
                break
            }
        }
    }

    if (-not $targetDevice) {
        Write-Output "NO_SCANNER"
        exit 1
    }

    $devName = $targetDevice.Properties.Item("Name").Value
    Write-Output "DEVICE:$devName"

    $device = $targetDevice.Connect()
    $item = $device.Items.Item(1)

    # 6146: Intent (1 = Color, 2 = Grayscale, 4 = Text/B&W)
    try { $item.Properties.Item("6146").Value = $ColorMode } catch {}
    try { $item.Properties.Item("6147").Value = $Dpi } catch {}
    try { $item.Properties.Item("6148").Value = $Dpi } catch {}

    # Format ID for BMP
    $wiaFormatBMP = "{B96B3CAB-0728-11D3-9D7B-0000F81EF32E}"
    $image = $item.Transfer($wiaFormatBMP)

    if (Test-Path $OutputFile) {
        Remove-Item $OutputFile -Force
    }

    $image.SaveFile($OutputFile)
    Write-Output "SUCCESS"
    exit 0
} catch {
    Write-Output "ERROR:$($_.Exception.Message)"
    exit 2
}