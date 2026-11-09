import os
import sys
import subprocess
import time
import threading
import tempfile
import uuid
import socket
import urllib.request
import urllib.error
from PIL import Image
import autocrop
import json
import re

CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
SCAN_SCRIPT = os.path.join(CURRENT_DIR, "run_scan.ps1")
SETTINGS_FILE = os.path.join(CURRENT_DIR, "settings.json")

# Track active scanning process and abort state
CURRENT_SCAN_PROC = None
PROC_LOCK = threading.Lock()
ABORT_REQUESTED = False

# Create the dedicated scanning powershell script
POWERSHELL_SCANNER_CODE = """
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
"""

try:
    with open(SCAN_SCRIPT, "w", encoding="utf-8") as f:
        f.write(POWERSHELL_SCANNER_CODE.strip())
except Exception:
    pass


def get_current_connection_mode():
    """Reads saved connection mode ('usb' or 'wifi') and Wi-Fi IP from settings.json."""
    try:
        if os.path.exists(SETTINGS_FILE):
            with open(SETTINGS_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
                return data.get("connectionMode", "usb"), data.get("wifiPrinterIp", "192.168.31.223")
    except Exception:
        pass
    return "usb", "192.168.31.223"


def set_windows_default_printer(printer_name):
    """Sets Windows default printer using PrintUIEntry."""
    if sys.platform != "win32" or not printer_name:
        return False
    try:
        subprocess.run(["rundll32", "printui.dll,PrintUIEntry", "/y", "/n", printer_name], capture_output=True, timeout=5)
        return True
    except Exception as e:
        print(f"Error setting default printer to {printer_name}: {e}")
        return False


def test_ip_reachable(ip, timeout=1.0):
    """Tests if a given IP address has open printer/scanner ports (80, 8080, 9100, 631)."""
    if not ip or not ip.strip():
        return False
    clean_ip = ip.strip()
    for port in [80, 8080, 9100, 631]:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(timeout)
            r = s.connect_ex((clean_ip, port))
            s.close()
            if r == 0:
                return True
        except Exception:
            pass
    return False


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
                subprocess.run(["taskkill", "/F", "/T", "/PID", str(pid)], capture_output=True)
            except Exception:
                try:
                    CURRENT_SCAN_PROC.kill()
                except Exception:
                    pass
            CURRENT_SCAN_PROC = None

    # Terminate orphan powershell instances
    try:
        subprocess.run(
            ["powershell", "-NoProfile", "-Command", "Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*run_scan.ps1*' } | Stop-Process -Force"],
            capture_output=True,
            timeout=5
        )
    except Exception:
        pass

    # Clean up temporary BMP files
    try:
        for f in os.listdir(CURRENT_DIR):
            if f.startswith("raw_scan_") and f.endswith(".bmp"):
                try:
                    os.remove(os.path.join(CURRENT_DIR, f))
                except Exception:
                    pass
    except Exception:
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


def check_scanner_online(force=False, connection_mode=None, wifi_ip=None):
    """
    Checks if the physical Epson scanner is connected, powered on, and responsive.
    Supports both USB and Wi-Fi connection modes explicitly.
    """
    saved_mode, saved_ip = get_current_connection_mode()
    mode = (connection_mode or saved_mode or "usb").lower()
    target_wifi_ip = wifi_ip or saved_ip or "192.168.31.223"

    if is_scan_active():
        return {
            "online": True,
            "busy": True,
            "scanning": True,
            "connection_mode": mode,
            "device_name": "EPSON L3250 Series",
            "message": "Epson L3250 Scanning in progress..."
        }

    global _LAST_STATUS_TIME, _CACHED_STATUS
    now = time.time()
    with _STATUS_LOCK:
        if not force and (now - _LAST_STATUS_TIME) < 2.5 and _CACHED_STATUS.get("connection_mode") == mode:
            return dict(_CACHED_STATUS)

    if mode == "wifi":
        # 1. Check Wi-Fi Connection
        is_ip_online = test_ip_reachable(target_wifi_ip, timeout=0.8)

        # 2. Check if WIA has a Network scanner device
        ps_code_wifi = """
try {
    $dm = New-Object -ComObject WIA.DeviceManager
    $target = $null
    for ($i = 1; $i -le $dm.DeviceInfos.Count; $i++) {
        $info = $dm.DeviceInfos.Item($i)
        if ($info.Type -eq 1) {
            $name = $info.Properties.Item("Name").Value
            if ($name -like "*Network*" -or $name -like "*Wi-Fi*" -or $name -like "*WiFi*") {
                $target = $info
                break
            }
        }
    }
    if ($target) {
        $dev = $target.Connect()
        $dev.ExecuteCommand("{9B26B7B2-ACAD-11D2-A093-00C04F72DC3C}")
        Write-Output "WIA_OK|$($target.Properties.Item('Name').Value)"
    } else {
        Write-Output "NO_WIA_NET"
    }
} catch {
    Write-Output "WIA_FAIL|$($_.Exception.Message)"
}
"""
        wia_net_ok = False
        try:
            res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_code_wifi], capture_output=True, text=True, timeout=3)
            out = res.stdout.strip()
            if "WIA_OK|" in out:
                wia_net_ok = True
        except Exception:
            pass

        is_online = is_ip_online or wia_net_ok
        dev_name = "EPSON L3250 Series (Network)"
        msg = f"EPSON L3250 Ready on Wi-Fi ({target_wifi_ip})" if is_online else f"Wi-Fi Printer Unreachable at {target_wifi_ip}"
        status_dict = {
            "online": is_online,
            "connection_mode": "wifi",
            "ip": target_wifi_ip,
            "port": target_wifi_ip,
            "device_name": dev_name,
            "message": msg
        }
    else:
        # USB Mode
        ps_code_usb = """
try {
    $dm = New-Object -ComObject WIA.DeviceManager
    $target = $null
    for ($i = 1; $i -le $dm.DeviceInfos.Count; $i++) {
        $info = $dm.DeviceInfos.Item($i)
        if ($info.Type -eq 1) {
            $name = $info.Properties.Item("Name").Value
            $portVal = ""
            try { $portVal = $info.Properties.Item("Port").Value } catch {}
            if ($name -notlike "*Network*" -and ($name -like "*L3250*" -or $name -like "*Epson*" -or $portVal -like "*USB*")) {
                $target = $info
                break
            }
        }
    }
    if (-not $target) {
        # Fallback to any scanner
        if ($dm.DeviceInfos.Count -gt 0) { $target = $dm.DeviceInfos.Item(1) }
    }
    if (-not $target) {
        Write-Output "RESULT|0|No USB scanner detected|"
        exit
    }
    $devName = $target.Properties.Item("Name").Value
    try {
        $dev = $target.Connect()
        Write-Output "RESULT|1|Ready|$devName"
    } catch {
        Write-Output "RESULT|0|USB Scanner is powered off or busy|$devName"
    }
} catch {
    Write-Output "RESULT|0|Scanner service unavailable|"
}
"""
        status_dict = {
            "online": False,
            "connection_mode": "usb",
            "device_name": "EPSON L3250 Series (USB)",
            "port": "USB001",
            "message": "USB Scanner Offline (Powered Off)"
        }
        try:
            res = subprocess.run(
                ["powershell", "-NoProfile", "-Command", ps_code_usb],
                capture_output=True,
                text=True,
                timeout=4
            )
            out = res.stdout.strip()
            for line in out.splitlines():
                if line.startswith("RESULT|"):
                    parts = line.split("|")
                    is_online = (parts[1] == "1")
                    dev = parts[3] if len(parts) > 3 and parts[3] else "EPSON L3250 Series"
                    status_dict = {
                        "online": is_online,
                        "connection_mode": "usb",
                        "port": "USB001",
                        "device_name": dev,
                        "message": f"{dev} Ready on USB" if is_online else f"{dev} Offline (Check USB cable & power)"
                    }
                    break
        except subprocess.TimeoutExpired:
            status_dict = {"online": False, "connection_mode": "usb", "port": "USB001", "device_name": "EPSON L3250 Series", "message": "USB Scanner Offline (No Response)"}
        except Exception as e:
            status_dict = {"online": False, "connection_mode": "usb", "port": "USB001", "device_name": "EPSON L3250 Series", "message": f"USB Scanner Error: {e}"}

        # Resilient fallback: Check Win32_Printer if WIA reported offline
        if not status_dict.get("online"):
            try:
                ps_printer_check = "Get-CimInstance Win32_Printer | Where-Object { ($_.Name -like '*3250*' -or $_.DriverName -like '*3250*' -or $_.Name -like '*Epson*') -and ($_.PortName -like '*USB*' -or -not ($_.PortName -like '*.*' -or $_.PortName -like '*WSD*')) -and -not $_.WorkOffline } | Select-Object -ExpandProperty Name"
                p_check = subprocess.run(["powershell", "-NoProfile", "-Command", ps_printer_check], capture_output=True, text=True, timeout=2.5)
                p_out = p_check.stdout.strip()
                if "3250" in p_out or "EPSON" in p_out or "Epson" in p_out:
                    found_name = p_out.splitlines()[0].strip()
                    status_dict["online"] = True
                    status_dict["device_name"] = found_name
                    status_dict["message"] = f"{found_name} Ready on USB"
            except Exception:
                pass

    with _STATUS_LOCK:
        _LAST_STATUS_TIME = time.time()
        _CACHED_STATUS = status_dict

    return status_dict


_DEVICE_INFO_LOCK = threading.Lock()
_LAST_DEVICE_INFO_TIME = 0
_CACHED_DEVICE_INFO = None


def invalidate_device_caches():
    """Immediately invalidates any cached scanner status or device info."""
    global _LAST_STATUS_TIME, _CACHED_STATUS, _LAST_DEVICE_INFO_TIME, _CACHED_DEVICE_INFO
    with _STATUS_LOCK:
        _LAST_STATUS_TIME = 0
        _CACHED_STATUS = {}
    with _DEVICE_INFO_LOCK:
        _LAST_DEVICE_INFO_TIME = 0
        _CACHED_DEVICE_INFO = None


def get_system_device_info(force=False):
    """
    Detects whether the system is connected via Wi-Fi or USB,
    retrieves printer names for both USB and Wi-Fi interfaces,
    model number, IP address, and status.
    """
    global _LAST_DEVICE_INFO_TIME, _CACHED_DEVICE_INFO
    now = time.time()
    with _DEVICE_INFO_LOCK:
        if not force and _CACHED_DEVICE_INFO and (now - _LAST_DEVICE_INFO_TIME < 3.5):
            return _CACHED_DEVICE_INFO

    active_mode, saved_wifi_ip = get_current_connection_mode()

    info = {
        "success": True,
        "active_mode": active_mode,
        "connection_type": "USB" if active_mode == "usb" else "Wi-Fi",
        "wifi_ssid": None,
        "wifi_signal": None,
        "wifi_ip": saved_wifi_ip,
        "usb_device": {
            "name": "EPSON L3250 Series",
            "port": "USB001",
            "online": False
        },
        "wifi_device": {
            "name": "EPSON L3250 Series (Network)",
            "port": saved_wifi_ip,
            "ip": saved_wifi_ip,
            "online": False
        },
        "printer_name": "EPSON L3250 Series" if active_mode == "usb" else "EPSON L3250 Series (Network)",
        "model_number": "L3250",
        "port": "USB001" if active_mode == "usb" else saved_wifi_ip,
        "printer_connection": f"{'USB' if active_mode == 'usb' else 'Wi-Fi / Network'} ({'USB001' if active_mode == 'usb' else saved_wifi_ip})",
        "printer_status": "Offline",
        "scanner_name": "EPSON L3250 Series",
        "scanner_online": False,
        "scanner_message": "Scanner Offline"
    }

    # 1. Check Wi-Fi connection interface via netsh
    try:
        wlan_res = subprocess.run(["netsh", "wlan", "show", "interfaces"], capture_output=True, text=True, timeout=2)
        for line in wlan_res.stdout.splitlines():
            line = line.strip()
            if line.startswith("SSID") and not line.startswith("BSSID"):
                parts = line.split(":", 1)
                if len(parts) == 2:
                    info["wifi_ssid"] = parts[1].strip()
            elif line.startswith("Signal"):
                parts = line.split(":", 1)
                if len(parts) == 2:
                    info["wifi_signal"] = parts[1].strip()
    except Exception:
        pass

    # 2. Check Windows Win32_Printers via PowerShell
    try:
        ps_cmd = 'Get-CimInstance Win32_Printer | Select-Object Name, DriverName, PortName, WorkOffline | ConvertTo-Json'
        ps_res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_cmd], capture_output=True, text=True, timeout=3)
        if ps_res.stdout.strip():
            p_data = json.loads(ps_res.stdout)
            printers = [p_data] if isinstance(p_data, dict) else p_data
            for p in printers:
                pname = p.get("Name", "")
                dname = p.get("DriverName", "")
                pport = p.get("PortName", "")
                offline = p.get("WorkOffline", False)

                if "3250" in pname or "epson" in pname.lower() or "3250" in dname or "epson" in dname.lower():
                    is_net = ("network" in pname.lower() or "192." in pport or "10." in pport or "wsd" in pport.lower() or "." in pport)
                    if is_net:
                        info["wifi_device"]["name"] = pname
                        info["wifi_device"]["port"] = saved_wifi_ip or pport
                        info["wifi_device"]["online"] = not offline
                        if saved_wifi_ip and test_ip_reachable(saved_wifi_ip, timeout=0.5):
                            info["wifi_device"]["ip"] = saved_wifi_ip
                            info["wifi_ip"] = saved_wifi_ip
                        elif "." in pport and not pport.startswith("WSD"):
                            info["wifi_device"]["ip"] = pport
                            info["wifi_ip"] = pport
                    else:
                        info["usb_device"]["name"] = pname
                        info["usb_device"]["port"] = pport or "USB001"
                        info["usb_device"]["online"] = not offline
    except Exception:
        pass

    # 3. Check Active Mode & Online Status
    sc_status = check_scanner_online(force=force, connection_mode=active_mode, wifi_ip=info["wifi_ip"])
    info["scanner_online"] = sc_status.get("online", False)
    info["scanner_message"] = sc_status.get("message", "Scanner Offline")

    if active_mode == "usb":
        info["printer_name"] = info["usb_device"]["name"]
        info["port"] = info["usb_device"]["port"]
        info["printer_connection"] = f"USB ({info['port']})"
        is_ready = bool(info["scanner_online"] or info["usb_device"]["online"])
        info["printer_status"] = "Ready" if is_ready else "Offline"
        info["scanner_name"] = info["usb_device"]["name"]
        if is_ready:
            info["online"] = True
            info["scanner_online"] = True
            info["scanner_message"] = f"{info['printer_name']} Ready on USB"
    else:
        info["printer_name"] = info["wifi_device"]["name"]
        info["port"] = info["wifi_device"]["port"]
        info["printer_connection"] = f"Wi-Fi / Network ({info['port']})"
        is_ready = bool(info["scanner_online"] or info["wifi_device"]["online"])
        info["printer_status"] = "Ready" if is_ready else "Offline"
        info["scanner_name"] = info["wifi_device"]["name"]
        if is_ready:
            info["online"] = True

    info["online"] = bool(info["scanner_online"] or info["printer_status"] == "Ready")
    info["message"] = (
        info["scanner_message"] if info["scanner_online"]
        else (f"{info['printer_name']} Ready on {info['connection_type']}" if info["printer_status"] == "Ready" else "Printer Offline")
    )

    with _DEVICE_INFO_LOCK:
        _LAST_DEVICE_INFO_TIME = time.time()
        _CACHED_DEVICE_INFO = info

    return info


def scan_via_escl(target_ip, output_path, dpi=300, color_mode=1):
    """
    Direct eSCL (Apple AirScan / Mopria standard) scanning over Wi-Fi network.
    Supported natively by Epson L3250 over Wi-Fi network without PC driver dependencies.
    """
    if not target_ip:
        raise Exception("No Wi-Fi printer IP address provided for eSCL scan.")

    color_val = "RGB24" if color_mode == 1 else ("Grayscale8" if color_mode == 2 else "BlackAndWhite1")

    base_url = None
    for p in [80, 8080]:
        test_url = f"http://{target_ip}:{p}/eSCL/ScannerCapabilities"
        try:
            req = urllib.request.Request(test_url, headers={"User-Agent": "HKMTECH-CardPrinter"})
            with urllib.request.urlopen(req, timeout=3) as resp:
                if resp.status == 200:
                    base_url = f"http://{target_ip}:{p}/eSCL"
                    break
        except Exception:
            continue

    if not base_url:
        base_url = f"http://{target_ip}/eSCL"

    scan_job_xml = f"""<?xml version="1.0" encoding="UTF-8"?>
<scan:ScanSettings xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05/03" xmlns:pwg="http://www.pwg.org/schemas/2010/12/sm">
  <pwg:Version>2.6</pwg:Version>
  <scan:Intent>Document</scan:Intent>
  <pwg:ScanRegions>
    <pwg:ScanRegion>
      <pwg:Height>3507</pwg:Height>
      <pwg:Width>2480</pwg:Width>
      <pwg:XOffset>0</pwg:XOffset>
      <pwg:YOffset>0</pwg:YOffset>
    </pwg:ScanRegion>
  </pwg:ScanRegions>
  <scan:InputSource>Platen</scan:InputSource>
  <scan:ColorMode>{color_val}</scan:ColorMode>
  <scan:XResolution>{dpi}</scan:XResolution>
  <scan:YResolution>{dpi}</scan:YResolution>
</scan:ScanSettings>"""

    post_url = f"{base_url}/ScanJobs"
    post_req = urllib.request.Request(
        post_url,
        data=scan_job_xml.encode("utf-8"),
        headers={
            "Content-Type": "text/xml; charset=utf-8",
            "User-Agent": "HKMTECH-CardPrinter"
        },
        method="POST"
    )

    with urllib.request.urlopen(post_req, timeout=12) as resp:
        if resp.status not in (201, 200):
            raise Exception(f"eSCL ScanJobs returned HTTP {resp.status}")
        job_loc = resp.headers.get("Location")
        if not job_loc:
            raise Exception("No Location header returned from eSCL ScanJobs")

    if not job_loc.startswith("http"):
        root_url = base_url.rsplit("/eSCL", 1)[0]
        job_url = f"{root_url}{job_loc}"
    else:
        job_url = job_loc

    doc_url = f"{job_url}/NextDocument"
    time.sleep(2)
    doc_req = urllib.request.Request(doc_url, headers={"User-Agent": "HKMTECH-CardPrinter"})

    for _ in range(35):
        if ABORT_REQUESTED:
            raise Exception("Scan was aborted by user.")
        try:
            with urllib.request.urlopen(doc_req, timeout=30) as doc_resp:
                if doc_resp.status == 200:
                    raw_bytes = doc_resp.read()
                    import io
                    img = Image.open(io.BytesIO(raw_bytes))
                    img.save(output_path)
                    return True
        except urllib.error.HTTPError as he:
            if he.code in (503, 404):
                time.sleep(1)
                continue
            raise he
        except Exception:
            time.sleep(1)

    raise Exception("eSCL Scan timed out waiting for document transfer.")


def scan_from_epson(output_path, dpi=300, color_mode=1, do_autocrop=True, doc_type="id_card", auto_enhance=False, enhance_preset="clean", corner_curve_mm=0, connection_mode=None, wifi_ip=None):
    """
    Triggers the Epson L3250 scanner via WIA or Wi-Fi eSCL based on connection mode,
    saves the scan, and optionally auto-crops (ID card, full A4 page, or small document).
    Supports cancellation via abort_current_scan().
    """
    global CURRENT_SCAN_PROC, ABORT_REQUESTED
    ABORT_REQUESTED = False
    set_scan_active(True)

    saved_mode, saved_ip = get_current_connection_mode()
    mode = (connection_mode or saved_mode or "usb").lower()
    target_ip = wifi_ip or saved_ip or "192.168.31.223"

    raw_scan_path = os.path.join(tempfile.gettempdir(), f"raw_scan_{int(time.time())}_{uuid.uuid4().hex[:6]}.bmp")
    scan_performed = False
    device_name = "EPSON L3250 Series" if mode == "usb" else "EPSON L3250 Series (Network)"

    cmd = [
        "powershell",
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", SCAN_SCRIPT,
        "-OutputFile", raw_scan_path,
        "-Dpi", str(dpi),
        "-ColorMode", str(color_mode),
        "-ConnectionMode", mode
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
        for line in output.splitlines():
            if line.startswith("DEVICE:"):
                device_name = line.replace("DEVICE:", "").strip()

        if os.path.exists(raw_scan_path) and os.path.getsize(raw_scan_path) > 1000:
            scan_performed = True
        elif mode == "wifi":
            # Fallback to direct eSCL scanning over Wi-Fi
            try:
                raw_png = raw_scan_path.replace(".bmp", ".png")
                scan_via_escl(target_ip, raw_png, dpi=dpi, color_mode=color_mode)
                if os.path.exists(raw_png):
                    raw_scan_path = raw_png
                    scan_performed = True
                    device_name = f"EPSON L3250 (eSCL Network {target_ip})"
            except Exception as escl_err:
                print(f"eSCL fallback failed: {escl_err}")

        if not scan_performed or not os.path.exists(raw_scan_path):
            if ABORT_REQUESTED:
                return {
                    "success": False,
                    "aborted": True,
                    "error": "Scan was aborted by user.",
                    "path": None,
                    "cropped": False
                }
            error_hint = "Make sure your Epson L3250 is turned on and connected via USB." if mode == "usb" else f"Make sure your Epson L3250 is turned on and connected to Wi-Fi ({target_ip})."
            return {
                "success": False,
                "aborted": False,
                "error": f"Scanner failed to scan. {error_hint} {output}",
                "path": None,
                "cropped": False
            }

        # Perform Auto-crop if requested
        was_cropped = False
        final_img = None
        ratio_info = {"ratio_type": "custom", "aspect": 1.585}

        if do_autocrop:
            try:
                if doc_type in ("a4", "small", "auto"):
                    cropped_pil, detected, crop_box, r_info = autocrop.detect_and_crop_document(raw_scan_path, doc_type=doc_type, corner_curve_mm=corner_curve_mm)
                else:
                    card_curve = corner_curve_mm if corner_curve_mm > 0 else 3.18
                    cropped_pil, detected, crop_box, r_info = autocrop.detect_and_crop_id_card(raw_scan_path, corner_curve_mm=card_curve)
                if crop_box:
                    r_info["bbox"] = list(crop_box)
                ratio_info = r_info
                if cropped_pil is not None:
                    final_img = cropped_pil
                    was_cropped = detected
                else:
                    final_img = Image.open(raw_scan_path)
            except Exception as crop_err:
                print(f"[Scanner] Auto-crop fallback: {crop_err}")
                import traceback
                traceback.print_exc()
                final_img = Image.open(raw_scan_path)
                was_cropped = False
                ratio_info = {
                    "doc_type": doc_type,
                    "ratio_type": "custom",
                    "aspect": final_img.width / float(final_img.height),
                    "width": final_img.width,
                    "height": final_img.height,
                    "is_portrait": (final_img.height > final_img.width),
                    "bbox": [0, 0, final_img.width, final_img.height]
                }
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
            except Exception:
                pass

        detected_bbox = list(ratio_info.get("bbox", [])) if "bbox" in ratio_info else None
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
            "connection_mode": mode,
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
        import traceback
        traceback.print_exc()
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
        if 'raw_scan_path' in locals() and raw_scan_path and os.path.exists(raw_scan_path):
            try:
                os.remove(raw_scan_path)
            except Exception:
                pass

if __name__ == "__main__":
    print("Testing scanner module...")
    info = get_system_device_info(force=True)
    print("Device Info:", json.dumps(info, indent=2))
