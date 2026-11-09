import socket
import threading
import tempfile
import uuid
import io
from PIL import Image
import os
import sys
import json
import base64
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import urllib.parse
import cv2
import numpy as np
import scanner
import autocrop

PORT = 8767
CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
SETTINGS_FILE = os.path.join(CURRENT_DIR, "settings.json")

DEFAULT_SETTINGS = {
    "connectionMode": "usb",
    "wifiPrinterIp": "192.168.31.223",
    "wifiPrinterName": "EPSON L3250 Series (Network)",
    "usbPrinterName": "EPSON L3250 Series",
    "scanDpi": 300,
    "paperSize": "a4",
    "autoCrop": True,
    "aspectMode": "auto",
    "sizePreset": "standard",
    "cardWidthMm": 85.6,
    "topPosMm": 47.25,
    "bottomPosMm": 195.75,
    "borderStyle": "none",
    "borderClass": "border-none",
    "cornerCurveMm": 3.18,
    "colorMode": "clean",
    "brightness": 4.0,
    "contrast": 18.0,
    "sharpness": 40.0,
    "saturation": 110.0,
    "bgWhiten": 45.0,
    "edgeTrimPx": 0,
    "midLinePosMm": 148.5,
    "showMidLine": True,
    "printMidLine": False,
    "syncBottomWithMid": True,
    "copies": 1,
    "autoSave": True
}

class ScanRequestHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=CURRENT_DIR, **kwargs)

    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        if url.path == "/api/connection" or url.path == "/api/device_info":
            force = ("force" in (url.query or ""))
            info = scanner.get_system_device_info(force=force)
            payload = json.dumps(info).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(payload)
            return

        if url.path == "/api/status":
            status_data = scanner.check_scanner_online()
            payload = json.dumps(status_data).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(payload)
            return

        if url.path == "/api/settings":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            data = dict(DEFAULT_SETTINGS)
            if os.path.exists(SETTINGS_FILE):
                try:
                    with open(SETTINGS_FILE, "r", encoding="utf-8") as f:
                        saved = json.load(f)
                    data.update(saved)
                except Exception as e:
                    pass
            self.wfile.write(json.dumps(data).encode("utf-8"))
            return

        return super().do_GET()

    def do_POST(self):
        from PIL import Image
        import io
        url = urllib.parse.urlparse(self.path)

        if url.path == "/api/connection":
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
            try:
                params = json.loads(body)
                mode = params.get("mode", "usb").lower()
                wifi_ip = params.get("wifi_ip") or params.get("wifiPrinterIp")
                set_default = params.get("setDefault", True)

                current = dict(DEFAULT_SETTINGS)
                if os.path.exists(SETTINGS_FILE):
                    try:
                        with open(SETTINGS_FILE, "r", encoding="utf-8") as f:
                            current.update(json.load(f))
                    except:
                        pass
                current["connectionMode"] = mode
                if wifi_ip:
                    current["wifiPrinterIp"] = str(wifi_ip).strip()
                with open(SETTINGS_FILE, "w", encoding="utf-8") as f:
                    json.dump(current, f, indent=2)

                scanner.invalidate_device_caches()

                if set_default and sys.platform == "win32":
                    target_name = current.get("usbPrinterName", "EPSON L3250 Series") if mode == "usb" else current.get("wifiPrinterName", "EPSON L3250 Series (Network)")
                    scanner.set_windows_default_printer(target_name)

                info = scanner.get_system_device_info(force=True)
                resp = {"success": True, "mode": mode, "info": info}
            except Exception as e:
                resp = {"success": False, "error": str(e)}

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(resp).encode("utf-8"))
            return

        if url.path == "/api/connection/test_wifi":
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
            try:
                params = json.loads(body)
                ip = params.get("ip") or params.get("wifiPrinterIp", "192.168.31.223")
                reachable = scanner.test_ip_reachable(ip, timeout=1.5)
                resp = {
                    "success": True,
                    "ip": ip,
                    "online": reachable,
                    "message": f"Epson printer online at {ip}" if reachable else f"Cannot reach printer at {ip}"
                }
            except Exception as e:
                resp = {"success": False, "error": str(e)}

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(resp).encode("utf-8"))
            return

        if url.path == "/api/settings":
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
            try:
                new_data = json.loads(body)
                current = dict(DEFAULT_SETTINGS)
                if os.path.exists(SETTINGS_FILE):
                    try:
                        with open(SETTINGS_FILE, "r", encoding="utf-8") as f:
                            current.update(json.load(f))
                    except:
                        pass
                current.update(new_data)
                with open(SETTINGS_FILE, "w", encoding="utf-8") as f:
                    json.dump(current, f, indent=2)
                resp = {"success": True, "settings": current}
            except Exception as e:
                resp = {"success": False, "error": str(e)}

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(resp).encode("utf-8"))
            return

        if url.path == "/api/settings/reset":
            try:
                with open(SETTINGS_FILE, "w", encoding="utf-8") as f:
                    json.dump(DEFAULT_SETTINGS, f, indent=2)
                resp = {"success": True, "settings": DEFAULT_SETTINGS}
            except Exception as e:
                resp = {"success": False, "error": str(e)}

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(resp).encode("utf-8"))
            return

        if url.path == "/api/abort":
            scanner.abort_current_scan()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps({"success": True, "message": "Scan aborted"}).encode("utf-8"))
            return

        if url.path == "/api/crop":
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
            try:
                params = json.loads(body)
            except:
                params = {}

            img_b64 = params.get("image", "")
            if "," in img_b64:
                img_b64 = img_b64.split(",", 1)[1]

            corner_curve_mm = float(params.get("corner_curve_mm", 3.18))
            doc_type = params.get("doc_type", "id_card")

            try:
                raw_bytes = base64.b64decode(img_b64)
                pil_raw = Image.open(io.BytesIO(raw_bytes))

                points = params.get("points")
                if points and len(points) == 4:
                    cropped_pil, detected, bbox, r_info = autocrop.warp_quad_card(
                        pil_raw, points, corner_curve_mm=corner_curve_mm
                    )
                elif doc_type in ("a4", "small", "auto"):
                    cropped_pil, detected, bbox, r_info = autocrop.detect_and_crop_document(
                        pil_raw, doc_type=doc_type, corner_curve_mm=corner_curve_mm
                    )
                else:
                    cropped_pil, detected, bbox, r_info = autocrop.detect_and_crop_id_card(
                        pil_raw, corner_curve_mm=corner_curve_mm
                    )

                if cropped_pil is None:
                    cropped_pil = pil_raw
                    detected = False
                    r_info = {
                        "ratio_type": "custom",
                        "aspect": pil_raw.width / float(pil_raw.height),
                        "is_portrait": (pil_raw.height > pil_raw.width)
                    }

                buf = io.BytesIO()
                cropped_pil.save(buf, format="PNG")
                out_b64 = base64.b64encode(buf.getvalue()).decode("utf-8")

                response_data = {
                    "success": True,
                    "image": f"data:image/png;base64,{out_b64}",
                    "cropped": detected,
                    "bbox": list(bbox) if bbox else None,
                    "corners": r_info.get("corners"),
                    "ratio_type": r_info.get("ratio_type", "custom"),
                    "aspect": r_info.get("aspect", cropped_pil.width / float(cropped_pil.height)),
                    "is_portrait": r_info.get("is_portrait", (cropped_pil.height > cropped_pil.width)),
                    "width": cropped_pil.width,
                    "height": cropped_pil.height
                }
            except Exception as e:
                response_data = {
                    "success": False,
                    "error": str(e)
                }

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(response_data).encode("utf-8"))
            return

        if url.path == "/api/orient":
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
            try:
                params = json.loads(body)
            except:
                params = {}

            img_b64 = params.get("image", "")
            if "," in img_b64:
                img_b64 = img_b64.split(",", 1)[1]

            try:
                raw_bytes = base64.b64decode(img_b64)
                pil_raw = Image.open(io.BytesIO(raw_bytes))
                img_rgb = np.array(pil_raw.convert("RGB"))
                img_bgr = cv2.cvtColor(img_rgb, cv2.COLOR_RGB2BGR)

                res_bgr, rot_angle, is_portrait, aspect, ratio_type = autocrop.detect_and_correct_text_orientation(img_bgr)

                res_rgb = cv2.cvtColor(res_bgr, cv2.COLOR_BGR2RGB)
                res_pil = Image.fromarray(res_rgb)

                buf = io.BytesIO()
                res_pil.save(buf, format="PNG")
                out_b64 = base64.b64encode(buf.getvalue()).decode("utf-8")

                response_data = {
                    "success": True,
                    "image": f"data:image/png;base64,{out_b64}",
                    "rotation": rot_angle,
                    "ratio_type": ratio_type,
                    "aspect": aspect,
                    "is_portrait": is_portrait,
                    "width": res_pil.width,
                    "height": res_pil.height
                }
            except Exception as e:
                response_data = {
                    "success": False,
                    "error": str(e)
                }

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(response_data).encode("utf-8"))
            return

        if url.path == "/api/scan":
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
            try:
                params = json.loads(body)
            except:
                params = {}

            side = params.get("side", "front")
            autocrop_flag = params.get("autocrop", True)
            dpi = int(params.get("dpi", 300))
            doc_type = params.get("doc_type", "id_card")
            auto_enhance = bool(params.get("auto_enhance", False))
            enhance_preset = params.get("enhance_preset", "clean")
            corner_curve_mm = float(params.get("corner_curve_mm", 0))

            conn_mode = params.get("connection_mode")
            wifi_ip = params.get("wifi_ip")
            temp_out = os.path.join(tempfile.gettempdir(), f"web_scan_{side}_{int(time.time())}_{uuid.uuid4().hex[:6]}.png")
            result = scanner.scan_from_epson(
                output_path=temp_out,
                dpi=dpi,
                color_mode=1,
                do_autocrop=autocrop_flag,
                doc_type=doc_type,
                auto_enhance=auto_enhance,
                enhance_preset=enhance_preset,
                corner_curve_mm=corner_curve_mm,
                connection_mode=conn_mode,
                wifi_ip=wifi_ip
            )

            response_data = {}
            if result.get("aborted"):
                response_data = {
                    "success": False,
                    "aborted": True,
                    "error": "Scan was aborted by user."
                }
            elif result["success"] and os.path.exists(result["path"]):
                with open(result["path"], "rb") as f:
                    b64 = base64.b64encode(f.read()).decode("utf-8")
                raw_b64 = None
                raw_path = result.get("raw_path")
                if raw_path and os.path.exists(raw_path):
                    try:
                        with open(raw_path, "rb") as rf:
                            raw_b64 = f"data:image/jpeg;base64,{base64.b64encode(rf.read()).decode('utf-8')}"
                        os.remove(raw_path)
                    except:
                        pass
                response_data = {
                    "success": True,
                    "aborted": False,
                    "image": f"data:image/png;base64,{b64}",
                    "raw_image": raw_b64,
                    "bbox": result.get("bbox"),
                    "corners": result.get("corners"),
                    "cropped": result["cropped"],
                    "doc_type": result.get("doc_type", doc_type),
                    "ratio_type": result.get("ratio_type", "custom"),
                    "aspect": result.get("aspect", 1.585),
                    "is_portrait": result.get("is_portrait", False),
                    "device": result["device_name"]
                }
                try:
                    os.remove(result["path"])
                except:
                    pass
            else:
                response_data = {
                    "success": False,
                    "aborted": False,
                    "error": result.get("error", "Unknown scanning error")
                }

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(response_data).encode("utf-8"))
            return

        if url.path == "/api/enhance":
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
            try:
                params = json.loads(body)
                img_data = params.get("image", "")
                if "," in img_data:
                    img_data = img_data.split(",", 1)[1]
                raw_bytes = base64.b64decode(img_data)
                preset = params.get("preset", "clean")
                brightness = float(params.get("brightness", 4))
                contrast = float(params.get("contrast", 18))
                sharpness = float(params.get("sharpness", 40))
                bg_whiten = float(params.get("bg_whiten", 45))
                saturation = float(params.get("saturation", 110))

                from io import BytesIO
                from PIL import Image
                input_pil = Image.open(BytesIO(raw_bytes))
                enh_pil = autocrop.enhance_document(
                    input_pil,
                    preset=preset,
                    brightness=brightness,
                    contrast=contrast,
                    sharpness=sharpness,
                    bg_whiten=bg_whiten,
                    saturation=saturation
                )
                out_io = BytesIO()
                enh_pil.save(out_io, format="PNG")
                out_b64 = base64.b64encode(out_io.getvalue()).decode("utf-8")
                resp = {"success": True, "image": f"data:image/png;base64,{out_b64}"}
            except Exception as e:
                resp = {"success": False, "error": str(e)}

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(resp).encode("utf-8"))
            return

        if url.path == "/api/restart":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"success": True, "message": "Server restarting..."}).encode("utf-8"))
            def _delayed_exit():
                time.sleep(0.5)
                os._exit(0)
            threading.Thread(target=_delayed_exit, daemon=True).start()
            return

        if url.path == "/api/print_pages":
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
            try:
                params = json.loads(body)
                pages_data = params.get("pages", [])
                copies = max(1, min(99, int(params.get("copies", 1))))
                action = params.get("action", "print")

                if not pages_data:
                    raise ValueError("No pages provided to print.")

                from io import BytesIO
                from PIL import Image

                dpi = int(params.get("dpi", 300))
                paper_w_mm = float(params.get("paper_width_mm", 210.0))
                paper_h_mm = float(params.get("paper_height_mm", 297.0))
                A4_W = int(round(paper_w_mm * dpi / 25.4))
                A4_H = int(round(paper_h_mm * dpi / 25.4))
                pil_pages = []

                for item in pages_data:
                    rot = 0
                    if isinstance(item, dict):
                        b64_str = item.get("image", "")
                        rot = int(item.get("rotation", 0))
                    else:
                        b64_str = item

                    if "," in b64_str:
                        b64_str = b64_str.split(",", 1)[1]
                    raw = base64.b64decode(b64_str)
                    src_img = Image.open(BytesIO(raw)).convert("RGB")
                    if rot != 0:
                        src_img = src_img.rotate(-rot, expand=True, resample=Image.Resampling.BICUBIC)

                    # Fit or center onto 300 DPI A4 page
                    page_canvas = Image.new("RGB", (A4_W, A4_H), (255, 255, 255))
                    margin = int(A4_W * 0.035) # ~9 mm margin
                    avail_w = A4_W - (margin * 2)
                    avail_h = A4_H - (margin * 2)

                    # Scale while preserving aspect ratio
                    src_aspect = src_img.width / float(src_img.height)
                    avail_aspect = avail_w / float(avail_h)

                    if src_aspect > avail_aspect:
                        tw = avail_w
                        th = int(tw / src_aspect)
                    else:
                        th = avail_h
                        tw = int(th * src_aspect)

                    resized = src_img.resize((tw, th), Image.Resampling.LANCZOS)
                    pos_x = margin + (avail_w - tw) // 2
                    pos_y = margin + (avail_h - th) // 2
                    page_canvas.paste(resized, (pos_x, pos_y))
                    pil_pages.append(page_canvas)

                # Expand copies if requested
                final_pages = []
                for _ in range(copies):
                    final_pages.extend(pil_pages)

                temp_pdf = os.path.join(tempfile.gettempdir(), f"multi_page_doc_{int(time.time())}_{uuid.uuid4().hex[:6]}.pdf")
                if len(final_pages) == 1:
                    final_pages[0].save(temp_pdf, "PDF", resolution=float(dpi))
                else:
                    final_pages[0].save(temp_pdf, "PDF", resolution=float(dpi), save_all=True, append_images=final_pages[1:])

                if action == "print" and sys.platform == "win32":
                    os.startfile(temp_pdf)

                resp = {
                    "success": True,
                    "pages_count": len(pil_pages),
                    "copies": copies,
                    "pdf_path": temp_pdf
                }
            except Exception as e:
                resp = {"success": False, "error": str(e)}

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(resp).encode("utf-8"))
            return

        if url.path == "/api/photo/enhance" or url.path == "/api/photo/camera_raw":
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
            try:
                params = json.loads(body)
                img_data = params.get("image", "")
                if "," in img_data:
                    img_data = img_data.split(",", 1)[1]
                raw_bytes = base64.b64decode(img_data)

                from io import BytesIO
                from PIL import Image
                input_pil = Image.open(BytesIO(raw_bytes))

                raw_params = params.get("raw_params")
                if raw_params:
                    enh_pil = autocrop.apply_camera_raw_filter(input_pil, raw_params)
                else:
                    preset = params.get("preset", "studio")
                    brightness = float(params.get("brightness", 0))
                    contrast = float(params.get("contrast", 10))
                    sharpness = float(params.get("sharpness", 35))
                    saturation = float(params.get("saturation", 115))
                    bg_mode = params.get("bg_mode", "white")
                    enh_pil = autocrop.enhance_passport_photo(
                        input_pil,
                        preset=preset,
                        brightness=brightness,
                        contrast=contrast,
                        sharpness=sharpness,
                        saturation=saturation,
                        bg_mode=bg_mode
                    )

                out_io = BytesIO()
                enh_pil.save(out_io, format="JPEG", quality=96)
                out_b64 = base64.b64encode(out_io.getvalue()).decode("utf-8")
                resp = {"success": True, "image": f"data:image/jpeg;base64,{out_b64}"}
            except Exception as e:
                resp = {"success": False, "error": str(e)}

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(resp).encode("utf-8"))
            return

        if url.path == "/api/photo/remove_bg":
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
            try:
                params = json.loads(body)
                img_data = params.get("image", "")
                if "," in img_data:
                    img_data = img_data.split(",", 1)[1]
                if not img_data:
                    raise ValueError("No photo image provided.")
                raw_bytes = base64.b64decode(img_data)

                from io import BytesIO
                from PIL import Image
                input_pil = Image.open(BytesIO(raw_bytes))

                cutout_pil, ok = autocrop.remove_photo_background(input_pil)
                out_io = BytesIO()
                cutout_pil.save(out_io, format="PNG")
                out_b64 = base64.b64encode(out_io.getvalue()).decode("utf-8")
                resp = {"success": True, "image": f"data:image/png;base64,{out_b64}"}
            except Exception as e:
                resp = {"success": False, "error": str(e)}

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(resp).encode("utf-8"))
            return

        if url.path == "/api/photo/change_bg":
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
            try:
                params = json.loads(body)
                img_data = params.get("image", "")
                hex_color = params.get("color", "#ffffff").strip()
                if "," in img_data:
                    img_data = img_data.split(",", 1)[1]
                if not img_data:
                    raise ValueError("No photo image provided.")
                raw_bytes = base64.b64decode(img_data)

                from io import BytesIO
                from PIL import Image
                input_pil = Image.open(BytesIO(raw_bytes))

                final_pil, cutout_pil, ok = autocrop.change_photo_background(input_pil, hex_color=hex_color)

                out_io = BytesIO()
                final_pil.save(out_io, format="JPEG", quality=96)
                out_b64 = base64.b64encode(out_io.getvalue()).decode("utf-8")

                cutout_io = BytesIO()
                cutout_pil.save(cutout_io, format="PNG")
                cutout_b64 = base64.b64encode(cutout_io.getvalue()).decode("utf-8")

                resp = {
                    "success": True,
                    "image": f"data:image/jpeg;base64,{out_b64}",
                    "cutout": f"data:image/png;base64,{cutout_b64}"
                }
            except Exception as e:
                resp = {"success": False, "error": str(e)}

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(resp).encode("utf-8"))
            return

        if url.path == "/api/photo/print_grid":
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
            try:
                params = json.loads(body)
                img_data = params.get("image", "")
                if "," in img_data:
                    img_data = img_data.split(",", 1)[1]
                if not img_data:
                    raise ValueError("No photo image provided.")
                raw_bytes = base64.b64decode(img_data)

                from io import BytesIO
                from PIL import Image
                photo_pil = Image.open(BytesIO(raw_bytes)).convert("RGB")

                rows = max(1, min(10, int(params.get("rows", 2))))
                cols = max(1, min(8, int(params.get("cols", 4))))
                photo_w_mm = float(params.get("photo_w_mm", 35.0))
                photo_h_mm = float(params.get("photo_h_mm", 45.0))
                gap_mm = float(params.get("gap_mm", 3.0))
                top_margin = params.get("top_margin_mm")
                top_margin_mm = float(top_margin) if top_margin is not None else None
                left_margin = params.get("left_margin_mm")
                left_margin_mm = float(left_margin) if left_margin is not None else None
                fill_count = max(1, min(rows * cols, int(params.get("fill_count", rows * cols))))
                border_style = params.get("border_style", "solid")
                cut_guides = bool(params.get("cut_guides", True))
                active_indices = params.get("active_indices")
                copies = max(1, min(99, int(params.get("copies", 1))))
                action = params.get("action", "print")

                paper_w_mm = float(params.get("paper_w_mm", 210.0))
                paper_h_mm = float(params.get("paper_h_mm", 297.0))

                sheet = autocrop.render_passport_grid_a4(
                    photo_pil,
                    rows=rows,
                    cols=cols,
                    photo_w_mm=photo_w_mm,
                    photo_h_mm=photo_h_mm,
                    gap_mm=gap_mm,
                    top_margin_mm=top_margin_mm,
                    left_margin_mm=left_margin_mm,
                    fill_count=fill_count,
                    border_style=border_style,
                    cut_guides=cut_guides,
                    active_indices=active_indices,
                    paper_w_mm=paper_w_mm,
                    paper_h_mm=paper_h_mm
                )

                # Export to PDF or image
                temp_pdf = os.path.join(tempfile.gettempdir(), f"passport_photos_{int(time.time())}_{uuid.uuid4().hex[:6]}.pdf")
                sheets_list = [sheet] * copies
                sheets_list[0].save(temp_pdf, "PDF", resolution=300.0, save_all=(copies > 1), append_images=sheets_list[1:] if copies > 1 else [])

                if action == "print" and sys.platform == "win32":
                    os.startfile(temp_pdf)

                out_io = BytesIO()
                sheet.save(out_io, format="JPEG", quality=95)
                sheet_b64 = base64.b64encode(out_io.getvalue()).decode("utf-8")

                resp = {
                    "success": True,
                    "pdf_path": temp_pdf,
                    "preview_image": f"data:image/jpeg;base64,{sheet_b64}",
                    "total_photos": fill_count * copies,
                    "copies": copies
                }
            except Exception as e:
                resp = {"success": False, "error": str(e)}

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(resp).encode("utf-8"))
            return

        self.send_response(404)
        self.end_headers()

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()


def cleanup_project_temp_artifacts():
    """Automatically removes any legacy scan or PDF dumps from project directory."""
    try:
        patterns = ("raw_scan_", "web_scan_", "scanned_", "multi_page_doc_", "passport_photos_")
        for f in os.listdir(CURRENT_DIR):
            if any(f.startswith(p) for p in patterns) and (f.endswith(".bmp") or f.endswith(".jpg") or f.endswith(".png") or f.endswith(".pdf")):
                try:
                    os.remove(os.path.join(CURRENT_DIR, f))
                except Exception:
                    pass
    except Exception:
        pass


def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"

def start_udp_discovery_beacon():
    def _beacon_loop():
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(("0.0.0.0", 8766))
            while True:
                data, addr = s.recvfrom(1024)
                if b"HKM_SCANNER_DISCOVERY" in data:
                    local_ip = get_local_ip()
                    resp = f"HKM_SCANNER_BRIDGE|{local_ip}|{PORT}".encode("utf-8")
                    s.sendto(resp, addr)
        except Exception:
            pass
    threading.Thread(target=_beacon_loop, daemon=True).start()


def start_adb_reverse_daemon():
    """
    Automatically maintains ADB reverse port forwarding (tcp:8767 -> tcp:8767)
    for any USB-connected Android device running the HKM Mobile App.
    Runs silently in a background daemon thread every 4 seconds.
    """
    def _adb_loop():
        import subprocess
        while True:
            try:
                res = subprocess.run(
                    ["adb", "devices"],
                    capture_output=True,
                    text=True,
                    timeout=3,
                    creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)
                )
                if res.returncode == 0:
                    lines = [line.strip() for line in res.stdout.splitlines() if line.strip() and not line.startswith("List of")]
                    for line in lines:
                        if "\tdevice" in line:
                            dev_id = line.split("\t")[0].strip()
                            rev_check = subprocess.run(
                                ["adb", "-s", dev_id, "reverse", "--list"],
                                capture_output=True,
                                text=True,
                                timeout=2,
                                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)
                            )
                            if f"tcp:{PORT}" not in rev_check.stdout:
                                subprocess.run(
                                    ["adb", "-s", dev_id, "reverse", f"tcp:{PORT}", f"tcp:{PORT}"],
                                    capture_output=True,
                                    timeout=2,
                                    creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)
                                )
                                print(f"[ADB Bridge] Auto-configured reverse tcp:{PORT} for device {dev_id}", flush=True)
            except Exception:
                pass
            time.sleep(4)
    threading.Thread(target=_adb_loop, daemon=True).start()

def run_server():
    cleanup_project_temp_artifacts()
    server_address = ("0.0.0.0", PORT)
    start_udp_discovery_beacon()
    start_adb_reverse_daemon()
    local_ip = get_local_ip()
    ThreadingHTTPServer.allow_reuse_address = True

    try:
        httpd = ThreadingHTTPServer(server_address, ScanRequestHandler)
    except OSError as e:
        import urllib.request
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/api/settings", timeout=1.5) as res:
                if res.status == 200:
                    print(f"Scanner bridge server is already active and healthy on http://127.0.0.1:{PORT}", flush=True)
                    sys.exit(0)
        except Exception:
            pass
        print(f"[ERROR] Port {PORT} is already in use by another process: {e}", flush=True)
        sys.exit(1)

    print(f"Scanner bridge server running on http://127.0.0.1:{PORT} and Wi-Fi http://{local_ip}:{PORT}", flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down server.", flush=True)

if __name__ == "__main__":
    run_server()
