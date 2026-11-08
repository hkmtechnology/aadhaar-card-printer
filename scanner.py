import os
import sys
import subprocess
import time
import threading
from PIL import Image
import autocrop

CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
SCAN_SCRIPT = os.path.join(CURRENT_DIR, "run_scan.ps1")

# Track active scanning process and abort state
CURRENT_SCAN_PROC = None
PROC_LOCK = threading.Lock()
ABORT_REQUESTED = False

# Create the dedicated scanning powershell script
POWERSHELL_SCANNER_CODE = """
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
"""

with open(SCAN_SCRIPT, "w", encoding="utf-8") as f:
    f.write(POWERSHELL_SCANNER_CODE.strip())

def abort_current_scan():
    """
    Immediately terminates any running scanner subprocess and child processes (WIA).
    Returns True if an active process was terminated, False otherwise.
    """
    global CURRENT_SCAN_PROC, ABORT_REQUESTED
    ABORT_REQUESTED = True

    with PROC_LOCK:
        if CURRENT_SCAN_PROC and CURRENT_SCAN_PROC.poll() is None:
            pid = CURRENT_SCAN_PROC.pid
            try:
                # Forcefully kill process tree in Windows
                subprocess.run(["taskkill", "/F", "/T", "/PID", str(pid)], capture_output=True)
            except Exception as e:
                try:
                    CURRENT_SCAN_PROC.kill()
                except:
                    pass
            CURRENT_SCAN_PROC = None

    # Terminate any orphan powershell instances executing run_scan.ps1
    try:
        subprocess.run(
            ["powershell", "-NoProfile", "-Command", "Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*run_scan.ps1*' } | Stop-Process -Force"],
            capture_output=True,
            timeout=5
        )
    except:
        pass

    # Clean up any leftover temporary BMP files
    try:
        for f in os.listdir(CURRENT_DIR):
            if f.startswith("raw_scan_") and f.endswith(".bmp"):
                try:
                    os.remove(os.path.join(CURRENT_DIR, f))
                except:
                    pass
    except:
        pass

    return True

_LAST_STATUS_TIME = 0
_CACHED_STATUS = {"online": False, "device_name": None, "message": "Checking..."}
_STATUS_LOCK = threading.Lock()

IS_SCANNING_ACTIVE = False
SCAN_ACTIVE_LOCK = threading.Lock()

def is_scan_active():
    with SCAN_ACTIVE_LOCK:
        return IS_SCANNING_ACTIVE

def set_scan_active(val):
    global IS_SCANNING_ACTIVE
    with SCAN_ACTIVE_LOCK:
        IS_SCANNING_ACTIVE = bool(val)

def check_scanner_online(force=False):
    """
    Checks if the physical Epson scanner is connected, powered on, and responsive.
    Uses cached result for 2.5 seconds to prevent excessive process spawning during polling.
    If a scan is actively running, avoids querying WIA to prevent COM device bus contention.
    """
    if is_scan_active():
        return {
            "online": True,
            "busy": True,
            "scanning": True,
            "device_name": "EPSON L3250 Series",
            "message": "Epson L3250 Scanning in progress..."
        }

    global _LAST_STATUS_TIME, _CACHED_STATUS
    now = time.time()
    with _STATUS_LOCK:
        if not force and (now - _LAST_STATUS_TIME) < 2.5:
            return dict(_CACHED_STATUS)

    ps_code = """
try {
    $dm = New-Object -ComObject WIA.DeviceManager
    $target = $null
    for ($i = 1; $i -le $dm.DeviceInfos.Count; $i++) {
        $info = $dm.DeviceInfos.Item($i)
        if ($info.Type -eq 1) {
            $target = $info
            if ($info.Properties.Item("Name").Value -like "*L3250*") {
                break
            }
        }
    }
    if (-not $target) {
        Write-Output "RESULT|0|No scanner detected|"
        exit
    }
    $devName = $target.Properties.Item("Name").Value
    try {
        $dev = $target.Connect()
        # Verify physical hardware by issuing synchronize command
        $dev.ExecuteCommand("{9B26B7B2-ACAD-11D2-A093-00C04F72DC3C}")
        Write-Output "RESULT|1|Ready|$devName"
    } catch {
        Write-Output "RESULT|0|Scanner is powered off or busy|$devName"
    }
} catch {
    Write-Output "RESULT|0|Scanner service unavailable|"
}
"""
    status_dict = {"online": False, "device_name": None, "message": "Scanner Offline"}
    try:
        res = subprocess.run(
            ["powershell", "-NoProfile", "-Command", ps_code],
            capture_output=True,
            text=True,
            timeout=4
        )
        out = res.stdout.strip()
        for line in out.splitlines():
            if line.startswith("RESULT|"):
                parts = line.split("|")
                is_online = (parts[1] == "1")
                msg = parts[2]
                dev = parts[3] if len(parts) > 3 and parts[3] else "EPSON L3250 Series"
                status_dict = {
                    "online": is_online,
                    "device_name": dev if is_online else None,
                    "message": f"{dev} Ready" if is_online else f"{dev} Offline (Powered Off)"
                }
                break
    except subprocess.TimeoutExpired:
        status_dict = {"online": False, "device_name": None, "message": "Scanner Offline (No Response)"}
    except Exception as e:
        status_dict = {"online": False, "device_name": None, "message": f"Scanner Error: {e}"}

    with _STATUS_LOCK:
        _LAST_STATUS_TIME = time.time()
        _CACHED_STATUS = status_dict

    return status_dict

def scan_from_epson(output_path, dpi=300, color_mode=1, do_autocrop=True, doc_type="id_card", auto_enhance=False, enhance_preset="clean"):
    """
    Triggers the Epson L3250 scanner via WIA, saves the scan,
    and optionally auto-crops (ID card, full A4 page, or small document).
    Supports cancellation via abort_current_scan().
    """
    global CURRENT_SCAN_PROC, ABORT_REQUESTED
    ABORT_REQUESTED = False
    set_scan_active(True)

    raw_scan_path = os.path.join(CURRENT_DIR, f"raw_scan_{int(time.time())}.bmp")
    
    cmd = [
        "powershell",
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", SCAN_SCRIPT,
        "-OutputFile", raw_scan_path,
        "-Dpi", str(dpi),
        "-ColorMode", str(color_mode)
    ]

    try:
        with PROC_LOCK:
            CURRENT_SCAN_PROC = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True
            )

        stdout, stderr = CURRENT_SCAN_PROC.communicate(timeout=120)

        with PROC_LOCK:
            CURRENT_SCAN_PROC = None

        if ABORT_REQUESTED:
            if os.path.exists(raw_scan_path):
                try: os.remove(raw_scan_path)
                except: pass
            return {
                "success": False,
                "aborted": True,
                "error": "Scan was aborted by user.",
                "path": None,
                "cropped": False
            }

        output = stdout.strip() if stdout else ""
        
        device_name = "EPSON L3250 Series"
        for line in output.splitlines():
            if line.startswith("DEVICE:"):
                device_name = line.replace("DEVICE:", "").strip()
            elif line.startswith("NO_SCANNER"):
                return {
                    "success": False,
                    "aborted": False,
                    "error": "No scanner detected. Make sure your Epson L3250 is turned on and connected via USB or Wi-Fi.",
                    "path": None,
                    "cropped": False
                }
            elif line.startswith("ERROR:"):
                return {
                    "success": False,
                    "aborted": False,
                    "error": f"Scanner error: {line.replace('ERROR:', '').strip()}",
                    "path": None,
                    "cropped": False
                }

        if not os.path.exists(raw_scan_path):
            if ABORT_REQUESTED:
                return {
                    "success": False,
                    "aborted": True,
                    "error": "Scan was aborted by user.",
                    "path": None,
                    "cropped": False
                }
            return {
                "success": False,
                "aborted": False,
                "error": f"Scan failed to create output file. {output}",
                "path": None,
                "cropped": False
            }

        # Perform Auto-crop if requested
        was_cropped = False
        final_img = None
        ratio_info = {"ratio_type": "custom", "aspect": 1.585}

        if do_autocrop:
            if doc_type in ("a4", "small", "auto"):
                cropped_pil, detected, crop_box, r_info = autocrop.detect_and_crop_document(raw_scan_path, doc_type=doc_type)
            else:
                cropped_pil, detected, crop_box, r_info = autocrop.detect_and_crop_id_card(raw_scan_path)
            if crop_box:
                r_info["bbox"] = list(crop_box)
            ratio_info = r_info
            if cropped_pil is not None:
                final_img = cropped_pil
                was_cropped = detected
            else:
                final_img = Image.open(raw_scan_path)
        else:
            final_img = Image.open(raw_scan_path)
            aspect = final_img.width / float(final_img.height)
            ratio_info = {
                "doc_type": doc_type,
                "ratio_type": "4:3" if (1.22 <= aspect <= 1.45) else ("3:4" if (0.68 <= aspect <= 0.82) else "custom"),
                "aspect": aspect,
                "width": final_img.width,
                "height": final_img.height
            }

        # Auto-enhance if requested
        if auto_enhance and final_img is not None:
            final_img = autocrop.enhance_document(final_img, preset=enhance_preset)

        final_img.save(output_path, "PNG")

        # Save raw scan image before removing BMP for manual adjustment
        raw_output_path = output_path.replace(".png", "_raw.jpg")
        try:
            raw_pil = Image.open(raw_scan_path)
            raw_pil.convert("RGB").save(raw_output_path, "JPEG", quality=85)
        except Exception:
            raw_output_path = None

        if os.path.exists(raw_scan_path):
            try:
                os.remove(raw_scan_path)
            except:
                pass

        detected_bbox = list(r_info.get("bbox", [])) if "bbox" in r_info else None
        if not detected_bbox and "crop_box" in ratio_info:
            detected_bbox = list(ratio_info["crop_box"])

        return {
            "success": True,
            "aborted": False,
            "path": output_path,
            "raw_path": raw_output_path,
            "bbox": detected_bbox,
            "corners": ratio_info.get("corners"),
            "cropped": was_cropped,
            "ratio_type": ratio_info.get("ratio_type", "custom"),
            "aspect": ratio_info.get("aspect", final_img.width / float(final_img.height)),
            "is_portrait": ratio_info.get("is_portrait", (final_img.height > final_img.width)),
            "device_name": device_name,
            "error": None
        }

    except subprocess.TimeoutExpired:
        abort_current_scan()
        return {
            "success": False,
            "aborted": False,
            "error": "Scanner timed out. Please check if Epson L3250 is ready.",
            "path": None,
            "cropped": False
        }
    except Exception as e:
        if ABORT_REQUESTED:
            return {
                "success": False,
                "aborted": True,
                "error": "Scan was aborted by user.",
                "path": None,
                "cropped": False
            }
        return {
            "success": False,
            "aborted": False,
            "error": str(e),
            "path": None,
            "cropped": False
        }
    finally:
        set_scan_active(False)
        with PROC_LOCK:
            CURRENT_SCAN_PROC = None

if __name__ == "__main__":
    print("Testing scanner module...")
