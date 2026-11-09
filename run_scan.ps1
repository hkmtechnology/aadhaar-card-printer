param(
    [string]$OutputFile = "temp_scan.bmp",
    [int]$Dpi = 200,
    [int]$ColorMode = 1,
    [string]$ConnectionMode = "usb"
)

try {
    $dm = New-Object -ComObject WIA.DeviceManager
    if ($dm.DeviceInfos.Count -eq 0) {
        Write-Output "NO_SCANNER"
        exit 1
    }

    $targetDevice = $null

    # 1. Match based on requested ConnectionMode (USB vs Wi-Fi)
    for ($i = 1; $i -le $dm.DeviceInfos.Count; $i++) {
        $info = $dm.DeviceInfos.Item($i)
        if ($info.Type -eq 1) { # Scanner
            $devName = ""
            $portVal = ""
            try { $devName = $info.Properties.Item("Name").Value } catch {}
            try { $portVal = $info.Properties.Item("Port").Value } catch {}

            $isNetwork = ($devName -like "*Network*" -or $devName -like "*Wi-Fi*" -or $devName -like "*WiFi*" -or $portVal -like "*192.*" -or $portVal -like "*WSD*")

            if ($ConnectionMode -eq "wifi") {
                if ($isNetwork) {
                    $targetDevice = $info
                    break
                }
            } else { # USB mode
                if (-not $isNetwork -and ($devName -like "*L3250*" -or $devName -like "*Epson*" -or $portVal -like "*USB*")) {
                    $targetDevice = $info
                    break
                }
            }
        }
    }

    # 2. Fallback to any Epson scanner if exact mode driver isn't distinct
    if (-not $targetDevice) {
        for ($i = 1; $i -le $dm.DeviceInfos.Count; $i++) {
            $info = $dm.DeviceInfos.Item($i)
            if ($info.Type -eq 1) {
                $devName = ""
                try { $devName = $info.Properties.Item("Name").Value } catch {}
                if ($devName -like "*L3250*" -or $devName -like "*Epson*") {
                    $targetDevice = $info
                    break
                }
            }
        }
    }

    # 3. Fallback to first available scanner
    if (-not $targetDevice) {
        for ($i = 1; $i -le $dm.DeviceInfos.Count; $i++) {
            $info = $dm.DeviceInfos.Item($i)
            if ($info.Type -eq 1) {
                $targetDevice = $info
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