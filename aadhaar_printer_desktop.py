import os
import sys
import json
import threading
import tkinter as tk
from tkinter import ttk, filedialog, messagebox
from PIL import Image, ImageTk, ImageDraw, ImageOps, ImageEnhance, ImageFilter
import scanner
import autocrop

CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))

# Constants for A4 at 300 DPI
DPI = 300
MM_TO_INCH = 25.4
A4_WIDTH_PX = int(round(210.0 / MM_TO_INCH * DPI))   # 2480 px
A4_HEIGHT_PX = int(round(297.0 / MM_TO_INCH * DPI))  # 3508 px

class AadhaarPrinterApp:
    def __init__(self, root):
        self.root = root
        self.root.title("HKMTECH - Card & Document Printer (Epson L3250)")
        self.root.geometry("1280x820")
        self.root.minsize(1050, 680)

        # Set Application Icon
        ico_path = os.path.join(CURRENT_DIR, "app_icon.ico")
        if os.path.exists(ico_path):
            try:
                self.root.iconbitmap(ico_path)
            except Exception:
                pass
        png_icon = os.path.join(CURRENT_DIR, "icon-512.png")
        if os.path.exists(png_icon):
            try:
                self._app_icon_img = tk.PhotoImage(file=png_icon)
                self.root.iconphoto(True, self._app_icon_img)
            except Exception:
                pass

        # State
        self.front_img = None
        self.back_img = None

        self.size_preset = tk.StringVar(value="standard")
        self.card_width_mm = tk.DoubleVar(value=85.6)
        self.aspect_ratio = 85.6 / 54.0

        self.aspect_mode = tk.StringVar(value="auto")
        self.front_aspect = 85.6 / 54.0
        self.back_aspect = 85.6 / 54.0
        self.front_ratio_name = "Standard ID (85.6×54)"
        self.back_ratio_name = "Standard ID (85.6×54)"

        self.top_pos_mm = tk.DoubleVar(value=48.0)
        self.bottom_pos_mm = tk.DoubleVar(value=196.0)

        self.border_style = tk.StringVar(value="none")
        self.corner_curve_var = tk.StringVar(value="3.18mm (Standard PVC)")
        self.color_mode = tk.StringVar(value="clean")
        self.brightness_var = tk.DoubleVar(value=4.0)
        self.contrast_var = tk.DoubleVar(value=18.0)
        self.sharpness_var = tk.DoubleVar(value=40.0)
        self.saturation_var = tk.DoubleVar(value=110.0)
        self.bg_whiten_var = tk.DoubleVar(value=45.0)
        self.edge_trim_var = tk.IntVar(value=0)

        self.auto_crop_var = tk.BooleanVar(value=True)
        self.scan_dpi_var = tk.StringVar(value="300")

        self.show_mid_line = tk.BooleanVar(value=True)
        self.print_mid_line = tk.BooleanVar(value=False)
        self.mid_line_pos_mm = tk.DoubleVar(value=148.5)
        self.sync_bottom_with_mid = tk.BooleanVar(value=True)
        self.bottom_offset_from_center = 0.0

        # Direct canvas manipulation (Drag & Corner Rotate)
        self.front_x_mm = tk.DoubleVar(value=105.0)
        self.back_x_mm = tk.DoubleVar(value=105.0)
        self.front_canvas_angle = tk.DoubleVar(value=0.0)
        self.back_canvas_angle = tk.DoubleVar(value=0.0)
        self.drag_mode = None
        self.drag_start_mouse = (0, 0)
        self.drag_start_card_pos = (105.0, 48.0)
        self.drag_center = (0, 0)
        self.drag_start_mouse_angle = 0.0
        self.drag_start_card_angle = 0.0

        self.copies_var = tk.IntVar(value=1)
        self.auto_save_var = tk.BooleanVar(value=True)

        self.is_scanning = False
        self.scan_thread = None

        # Multi-Page Document Scanner State
        self.doc_pages = []
        self.doc_current_idx = -1
        self.doc_scan_type = tk.StringVar(value="a4")
        self.doc_scan_dpi = tk.StringVar(value="300")
        self.doc_auto_enhance = tk.BooleanVar(value=True)
        self.doc_enhance_preset = tk.StringVar(value="clean")
        self.doc_bg_whiten = tk.DoubleVar(value=45.0)
        self.doc_sharpness = tk.DoubleVar(value=40.0)
        self.doc_contrast = tk.DoubleVar(value=18.0)
        self.doc_brightness = tk.DoubleVar(value=4.0)
        self.doc_copies = tk.IntVar(value=1)
        self.doc_is_scanning = False
        self.doc_scan_thread = None
        self.doc_preview_tk = None
        self.scanner_is_online = False

        # Passport Photo Studio State
        self.photo_raw_img = None
        self.photo_enhanced_img = None
        self.photo_preview_tk = None
        self.photo_size_preset = tk.StringVar(value="indian_passport")
        self.photo_w_mm = tk.DoubleVar(value=35.0)
        self.photo_h_mm = tk.DoubleVar(value=45.0)
        self.photo_grid_preset = tk.StringVar(value="8")
        self.photo_rows = tk.IntVar(value=2)
        self.photo_cols = tk.IntVar(value=4)
        self.photo_fill_count = tk.IntVar(value=8)
        self.photo_gap_mm = tk.DoubleVar(value=3.0)
        self.photo_top_margin_mm = tk.DoubleVar(value=20.0)
        self.photo_left_margin_mm = tk.DoubleVar(value=30.5)
        self.photo_border_style = tk.StringVar(value="solid")
        self.photo_cut_guides = tk.BooleanVar(value=True)
        self.photo_disabled_slots = set()
        self.photo_preset = tk.StringVar(value="studio")
        self.photo_brightness = tk.DoubleVar(value=2.0)
        self.photo_contrast = tk.DoubleVar(value=12.0)
        self.photo_sharpness = tk.DoubleVar(value=35.0)
        self.photo_saturation = tk.DoubleVar(value=115.0)
        self.photo_bg_whiten = tk.DoubleVar(value=40.0)
        self.photo_copies = tk.IntVar(value=1)
        self.photo_is_scanning = False
        self.photo_slot_rects = [] # stores (x1, y1, x2, y2, idx) on canvas for click toggling

        self.setup_ui()
        self.load_settings()
        self.root.protocol("WM_DELETE_WINDOW", self.on_closing)
        self.update_preview()
        self.root.after(500, self.check_scanner_hardware_status)

    def setup_ui(self):
        style = ttk.Style()
        style.theme_use("clam")
        style.configure("TNotebook", background="#0f172a", borderwidth=0, tabmargins=[6, 4, 6, 0])
        style.configure("TNotebook.Tab", background="#1e293b", foreground="#94a3b8",
                        padding=[18, 9], font=("Segoe UI", 10, "bold"), borderwidth=1, bordercolor="#334155")
        style.map("TNotebook.Tab",
                  background=[("selected", "#0284c7"), ("active", "#334155")],
                  foreground=[("selected", "#ffffff"), ("active", "#f8fafc")],
                  bordercolor=[("selected", "#38bdf8"), ("active", "#475569")])

        # Top Mode Switcher: 🪪 2-in-1 Card Mode | 📑 Multi-Page Document Scanner | 📷 Passport Photo Studio
        self.notebook = ttk.Notebook(self.root)
        self.notebook.pack(fill=tk.BOTH, expand=True)

        card_tab_frame = ttk.Frame(self.notebook)
        self.notebook.add(card_tab_frame, text="  🪪 2-in-1 Card Mode  ")

        doc_tab_frame = ttk.Frame(self.notebook)
        self.notebook.add(doc_tab_frame, text="  📑 Multi-Page Document Scanner  ")

        photo_tab_frame = ttk.Frame(self.notebook)
        self.notebook.add(photo_tab_frame, text="  📷 Passport Photo Studio (Grid Print)  ")

        # 3-PANE HORIZONTAL SPLIT: Left Controls | Center A4 Simulation | Right Adjustments & Print
        main_paned = ttk.PanedWindow(card_tab_frame, orient=tk.HORIZONTAL)
        main_paned.pack(fill=tk.BOTH, expand=True)

        # ==========================================
        # 1. LEFT CONTROLS: SCAN & CARD INPUTS
        # ==========================================
        left_container = ttk.Frame(main_paned, padding=8)
        main_paned.add(left_container, weight=0)

        canvas_left = tk.Canvas(left_container, width=320, borderwidth=0, highlightthickness=0)
        scroll_left = ttk.Scrollbar(left_container, orient=tk.VERTICAL, command=canvas_left.yview)
        self.left_ctrl_frame = ttk.Frame(canvas_left)
        self.ctrl_frame = self.left_ctrl_frame  # backward compatibility

        self.left_ctrl_frame.bind("<Configure>", lambda e: canvas_left.configure(scrollregion=canvas_left.bbox("all")))
        canvas_left.create_window((0, 0), window=self.left_ctrl_frame, anchor="nw")
        canvas_left.configure(yscrollcommand=scroll_left.set)

        canvas_left.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scroll_left.pack(side=tk.RIGHT, fill=tk.Y)

        # Mousewheel scrolling helpers
        def _scroll_left(event):
            canvas_left.yview_scroll(int(-1 * (event.delta / 120)), "units")
        canvas_left.bind("<Enter>", lambda e: canvas_left.bind_all("<MouseWheel>", _scroll_left))
        canvas_left.bind("<Leave>", lambda e: canvas_left.unbind_all("<MouseWheel>"))

        # SECTION 0: SCANNER MASTER BUTTON
        sec_wizard = ttk.LabelFrame(self.left_ctrl_frame, text=" ⚡ Automatic Scanner Integration ", padding=10)
        sec_wizard.pack(fill=tk.X, pady=(0, 10))

        # CONNECTION MODE SELECTOR: USB vs Wi-Fi
        conn_box = ttk.Frame(sec_wizard)
        conn_box.pack(fill=tk.X, pady=(0, 6))
        ttk.Label(conn_box, text="Connect:", font=("Segoe UI", 8, "bold")).pack(side=tk.LEFT, padx=(0, 6))
        self.conn_mode_var = tk.StringVar(value="usb")
        try:
            if os.path.exists("settings.json"):
                with open("settings.json", "r", encoding="utf-8") as _f:
                    self.conn_mode_var.set(json.load(_f).get("connectionMode", "usb"))
        except Exception:
            pass
        ttk.Radiobutton(
            conn_box, text="🔌 USB", value="usb", variable=self.conn_mode_var, command=self.on_connection_mode_changed
        ).pack(side=tk.LEFT, padx=4)
        ttk.Radiobutton(
            conn_box, text="📶 Wi-Fi", value="wifi", variable=self.conn_mode_var, command=self.on_connection_mode_changed
        ).pack(side=tk.LEFT, padx=4)

        self.btn_wizard = tk.Button(
            sec_wizard,
            text="⚡ 1-Click Scan Both Sides (Guided)",
            bg="#2563eb",
            fg="white",
            font=("Segoe UI", 10, "bold"),
            pady=8,
            relief=tk.FLAT,
            command=self.start_two_sided_wizard
        )
        self.btn_wizard.pack(fill=tk.X, pady=(0, 4))

        cb_autocrop = ttk.Checkbutton(sec_wizard, text="Auto-detect & crop card from flatbed", variable=self.auto_crop_var)
        cb_autocrop.pack(anchor="w", pady=2)

        dpi_frame = ttk.Frame(sec_wizard)
        dpi_frame.pack(fill=tk.X, pady=(4, 4))
        ttk.Label(dpi_frame, text="Scan DPI:").pack(side=tk.LEFT)
        cb_dpi = ttk.Combobox(
            dpi_frame,
            textvariable=self.scan_dpi_var,
            values=["150", "200", "300", "600"],
            width=6,
            state="readonly"
        )
        cb_dpi.pack(side=tk.LEFT, padx=6)
        ttk.Label(dpi_frame, text="(300 DPI recommended for sharp text)", font=("Segoe UI", 7, "italic"), foreground="gray").pack(side=tk.LEFT)

        self.lbl_scan_status = ttk.Label(sec_wizard, text="⏳ Checking scanner status...", foreground="#64748b", font=("Segoe UI", 8, "italic"))
        self.lbl_scan_status.pack(anchor="w")

        self.prog_bar = ttk.Progressbar(sec_wizard, mode="indeterminate")

        # ABORT BUTTON
        self.btn_abort = tk.Button(
            sec_wizard,
            text="⏹️ Abort Scan",
            bg="#ef4444",
            fg="white",
            font=("Segoe UI", 9, "bold"),
            pady=4,
            relief=tk.FLAT,
            command=self.abort_scan
        )

        # SECTION 1: FRONT & BACK SIDES (SCAN OR UPLOAD + ROTATION)
        sec1 = ttk.LabelFrame(self.left_ctrl_frame, text=" 1. Front & Back Sides ", padding=10)
        sec1.pack(fill=tk.X, pady=(0, 10))

        # Front image
        ttk.Label(sec1, text="Top Half (Front Side):", font=("Segoe UI", 9, "bold")).pack(anchor="w")
        front_act_frame = ttk.Frame(sec1)
        front_act_frame.pack(fill=tk.X, pady=3)

        self.btn_scan_front = tk.Button(
            front_act_frame,
            text="⚡ Scan Front",
            bg="#0f9d58",
            fg="white",
            font=("Segoe UI", 9, "bold"),
            relief=tk.FLAT,
            command=lambda: self.trigger_scan("front")
        )
        self.btn_scan_front.pack(side=tk.LEFT, expand=True, fill=tk.X, padx=(0, 2))

        ttk.Button(front_act_frame, text="📁 Choose File...", command=lambda: self.load_image("front")).pack(side=tk.LEFT, expand=True, fill=tk.X, padx=(2, 0))

        front_tools = ttk.Frame(sec1)
        front_tools.pack(fill=tk.X, pady=2)
        ttk.Button(front_tools, text="⟲ 90°", width=5, command=lambda: self.rotate("front", 90)).pack(side=tk.LEFT, padx=1)
        ttk.Button(front_tools, text="⟳ 90°", width=5, command=lambda: self.rotate("front", -90)).pack(side=tk.LEFT, padx=1)
        ttk.Button(front_tools, text="🔄 180°", width=6, command=lambda: self.rotate("front", 180)).pack(side=tk.LEFT, padx=1)
        ttk.Button(front_tools, text="📐 Horizontal", command=lambda: self.align_horizontal("front")).pack(side=tk.LEFT, expand=True, fill=tk.X, padx=1)
        ttk.Button(front_tools, text="✕", width=3, command=lambda: self.clear_image("front")).pack(side=tk.LEFT, padx=1)

        self.lbl_front_info = ttk.Label(sec1, text="No front image loaded", foreground="gray", font=("Segoe UI", 8))
        self.lbl_front_info.pack(anchor="w", pady=(0, 10))

        # Back image
        ttk.Label(sec1, text="Bottom Half (Back Side):", font=("Segoe UI", 9, "bold")).pack(anchor="w")
        back_act_frame = ttk.Frame(sec1)
        back_act_frame.pack(fill=tk.X, pady=3)

        self.btn_scan_back = tk.Button(
            back_act_frame,
            text="⚡ Scan Back",
            bg="#0f9d58",
            fg="white",
            font=("Segoe UI", 9, "bold"),
            relief=tk.FLAT,
            command=lambda: self.trigger_scan("back")
        )
        self.btn_scan_back.pack(side=tk.LEFT, expand=True, fill=tk.X, padx=(0, 2))

        ttk.Button(back_act_frame, text="📁 Choose File...", command=lambda: self.load_image("back")).pack(side=tk.LEFT, expand=True, fill=tk.X, padx=(2, 0))

        back_tools = ttk.Frame(sec1)
        back_tools.pack(fill=tk.X, pady=2)
        ttk.Button(back_tools, text="⟲ 90°", width=5, command=lambda: self.rotate("back", 90)).pack(side=tk.LEFT, padx=1)
        ttk.Button(back_tools, text="⟳ 90°", width=5, command=lambda: self.rotate("back", -90)).pack(side=tk.LEFT, padx=1)
        ttk.Button(back_tools, text="🔄 180°", width=6, command=lambda: self.rotate("back", 180)).pack(side=tk.LEFT, padx=1)
        ttk.Button(back_tools, text="📐 Horizontal", command=lambda: self.align_horizontal("back")).pack(side=tk.LEFT, expand=True, fill=tk.X, padx=1)
        ttk.Button(back_tools, text="✕", width=3, command=lambda: self.clear_image("back")).pack(side=tk.LEFT, padx=1)

        self.lbl_back_info = ttk.Label(sec1, text="No back image loaded", foreground="gray", font=("Segoe UI", 8))
        self.lbl_back_info.pack(anchor="w")

        # SECTION 2: CARD ASPECT RATIO / PLACEHOLDER SHAPE
        sec_aspect = ttk.LabelFrame(self.left_ctrl_frame, text=" 2. Card Aspect Ratio ", padding=10)
        sec_aspect.pack(fill=tk.X, pady=(0, 10))

        aspect_frame = ttk.Frame(sec_aspect)
        aspect_frame.pack(fill=tk.X, pady=(0, 4))
        ttk.Label(aspect_frame, text="Placeholder Mode:", font=("Segoe UI", 9, "bold")).pack(side=tk.LEFT)
        self.cb_aspect = ttk.Combobox(
            aspect_frame,
            textvariable=self.aspect_mode,
            values=["auto", "id_card", "4:3", "3:4", "exact"],
            state="readonly",
            width=8
        )
        self.cb_aspect.pack(side=tk.LEFT, padx=4)
        self.lbl_aspect_hint = ttk.Label(aspect_frame, text="⚡ Auto-Detect (4:3, 3:4, ID)", font=("Segoe UI", 7, "italic"), foreground="#2563eb")
        self.lbl_aspect_hint.pack(side=tk.LEFT)
        self.cb_aspect.bind("<<ComboboxSelected>>", lambda e: self.on_aspect_mode_change())

        ttk.Label(sec_aspect, text="Auto-detect dynamically sizes placeholder slots for 4:3, 3:4, or standard wallet cards.", foreground="gray", font=("Segoe UI", 7, "italic"), wraplength=290).pack(anchor="w", pady=(2, 0))

        # ==========================================
        # 2. CENTER PREVIEW: LIVE A4 SIMULATION
        # ==========================================
        center_frame = ttk.Frame(main_paned, padding=8)
        main_paned.add(center_frame, weight=1)

        header_frame = ttk.Frame(center_frame)
        header_frame.pack(fill=tk.X, pady=(0, 6))
        ttk.Label(header_frame, text="Live A4 Simulation (Top Half = Front | Bottom Half = Back)", font=("Segoe UI", 10, "bold")).pack(side=tk.LEFT)

        self.preview_canvas = tk.Canvas(center_frame, bg="#1e293b", highlightthickness=0)
        self.preview_canvas.pack(fill=tk.BOTH, expand=True)
        self.preview_canvas.bind("<Configure>", lambda e: self.update_preview())
        self.preview_canvas.bind("<ButtonPress-1>", self.on_canvas_press)
        self.preview_canvas.bind("<B1-Motion>", self.on_canvas_motion)
        self.preview_canvas.bind("<ButtonRelease-1>", self.on_canvas_release)
        self.preview_canvas.bind("<Motion>", self.on_canvas_hover)

        # ==========================================
        # 3. RIGHT CONTROLS: SIZING, FILTERS & PRINT
        # ==========================================
        right_container = ttk.Frame(main_paned, padding=8)
        main_paned.add(right_container, weight=0)

        canvas_right = tk.Canvas(right_container, width=330, borderwidth=0, highlightthickness=0)
        scroll_right = ttk.Scrollbar(right_container, orient=tk.VERTICAL, command=canvas_right.yview)
        self.right_ctrl_frame = ttk.Frame(canvas_right)

        self.right_ctrl_frame.bind("<Configure>", lambda e: canvas_right.configure(scrollregion=canvas_right.bbox("all")))
        canvas_right.create_window((0, 0), window=self.right_ctrl_frame, anchor="nw")
        canvas_right.configure(yscrollcommand=scroll_right.set)

        canvas_right.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scroll_right.pack(side=tk.RIGHT, fill=tk.Y)

        def _scroll_right(event):
            canvas_right.yview_scroll(int(-1 * (event.delta / 120)), "units")
        canvas_right.bind("<Enter>", lambda e: canvas_right.bind_all("<MouseWheel>", _scroll_right))
        canvas_right.bind("<Leave>", lambda e: canvas_right.unbind_all("<MouseWheel>"))

        # SECTION 3: CARD SIZE & ALIGNMENT
        sec2 = ttk.LabelFrame(self.right_ctrl_frame, text=" 3. Card Size & Alignment ", padding=10)
        sec2.pack(fill=tk.X, pady=(0, 10))

        presets_frame = ttk.Frame(sec2)
        presets_frame.pack(fill=tk.X, pady=3)

        ttk.Radiobutton(presets_frame, text="Original Wallet (85.6 × 54 mm)", variable=self.size_preset, value="standard", command=self.on_preset_change).pack(anchor="w", pady=2)
        ttk.Radiobutton(presets_frame, text="Xerox / KYC Proof (110 × 69.4 mm)", variable=self.size_preset, value="xerox", command=self.on_preset_change).pack(anchor="w", pady=2)
        ttk.Radiobutton(presets_frame, text="Large Visibility (135 × 85.2 mm)", variable=self.size_preset, value="large", command=self.on_preset_change).pack(anchor="w", pady=2)

        ttk.Label(sec2, text="Custom Width (mm):").pack(anchor="w", pady=(6, 2))
        width_row = ttk.Frame(sec2)
        width_row.pack(fill=tk.X)
        self.slider_width = ttk.Scale(width_row, from_=60, to=180, variable=self.card_width_mm, command=lambda v: self.on_custom_width())
        self.slider_width.pack(side=tk.LEFT, fill=tk.X, expand=True)
        self.lbl_width = ttk.Label(width_row, text="85.6 mm", width=8, font=("Consolas", 8))
        self.lbl_width.pack(side=tk.RIGHT, padx=4)

        # Positions
        ttk.Label(sec2, text="Top Half Position (mm):").pack(anchor="w", pady=(6, 2))
        top_row = ttk.Frame(sec2)
        top_row.pack(fill=tk.X)
        ttk.Scale(top_row, from_=20, to=100, variable=self.top_pos_mm, command=lambda v: self.update_preview()).pack(side=tk.LEFT, fill=tk.X, expand=True)
        self.lbl_top = ttk.Label(top_row, text="48 mm", width=8, font=("Consolas", 8))
        self.lbl_top.pack(side=tk.RIGHT, padx=4)

        ttk.Label(sec2, text="Bottom Half Position (mm):").pack(anchor="w", pady=(6, 2))
        bot_row = ttk.Frame(sec2)
        bot_row.pack(fill=tk.X)
        self.slider_bot = ttk.Scale(bot_row, from_=90, to=270, variable=self.bottom_pos_mm, command=lambda v: self.on_bottom_slider_change())
        self.slider_bot.pack(side=tk.LEFT, fill=tk.X, expand=True)
        self.lbl_bot = ttk.Label(bot_row, text="196 mm", width=8, font=("Consolas", 8))
        self.lbl_bot.pack(side=tk.RIGHT, padx=4)

        # Border
        ttk.Label(sec2, text="Cutting Border:").pack(anchor="w", pady=(6, 2))
        cb_border = ttk.Combobox(sec2, textvariable=self.border_style, values=["solid", "dashed", "none"], state="readonly")
        cb_border.pack(fill=tk.X)
        cb_border.bind("<<ComboboxSelected>>", lambda e: self.update_preview())

        # Corner Curve
        ttk.Label(sec2, text="Card Corner Curve (Radius):").pack(anchor="w", pady=(6, 2))
        self.cb_curve = ttk.Combobox(
            sec2,
            textvariable=self.corner_curve_var,
            values=["3.18mm (Standard PVC)", "4.0mm (Deep Curve)", "2.0mm (Subtle)", "5.0mm (Extra Round)", "0.0mm (Square)"],
            state="readonly"
        )
        self.cb_curve.pack(fill=tk.X)
        self.cb_curve.bind("<<ComboboxSelected>>", lambda e: self.update_preview())

        # Direct Canvas Drag & Rotate Tooling
        ttk.Separator(sec2, orient=tk.HORIZONTAL).pack(fill=tk.X, pady=(10, 6))
        ttk.Label(sec2, text="🖱️ Canvas Drag & Corner Rotate:", font=("Segoe UI", 8, "bold")).pack(anchor="w")

        f_pos_frame = ttk.Frame(sec2)
        f_pos_frame.pack(fill=tk.X, pady=2)
        self.lbl_f_canvas_info = ttk.Label(f_pos_frame, text="Front: X: 105mm (0°)", font=("Segoe UI", 8))
        self.lbl_f_canvas_info.pack(side=tk.LEFT)
        ttk.Button(f_pos_frame, text="↔ Center", width=7, command=lambda: self.reset_card_transform("front", "pos")).pack(side=tk.RIGHT, padx=1)
        ttk.Button(f_pos_frame, text="↺ 0°", width=5, command=lambda: self.reset_card_transform("front", "rot")).pack(side=tk.RIGHT, padx=1)

        b_pos_frame = ttk.Frame(sec2)
        b_pos_frame.pack(fill=tk.X, pady=2)
        self.lbl_b_canvas_info = ttk.Label(b_pos_frame, text="Back: X: 105mm (0°)", font=("Segoe UI", 8))
        self.lbl_b_canvas_info.pack(side=tk.LEFT)
        ttk.Button(b_pos_frame, text="↔ Center", width=7, command=lambda: self.reset_card_transform("back", "pos")).pack(side=tk.RIGHT, padx=1)
        ttk.Button(b_pos_frame, text="↺ 0°", width=5, command=lambda: self.reset_card_transform("back", "rot")).pack(side=tk.RIGHT, padx=1)

        ttk.Label(sec2, text="💡 Drag card on canvas to position. Drag corners to rotate.", foreground="gray", font=("Segoe UI", 7, "italic"), wraplength=290).pack(anchor="w", pady=(2, 0))

        # SECTION 4: CENTRAL SEPARATION LINE
        sec_mid = ttk.LabelFrame(self.right_ctrl_frame, text=" 4. Center Separation Line ", padding=10)
        sec_mid.pack(fill=tk.X, pady=(0, 10))

        mid_hdr = ttk.Frame(sec_mid)
        mid_hdr.pack(fill=tk.X)
        ttk.Label(mid_hdr, text="Line Position (mm):", font=("Segoe UI", 8, "bold")).pack(side=tk.LEFT)
        ttk.Button(mid_hdr, text="↺ 148.5 mm", width=10, command=self.reset_mid_line).pack(side=tk.RIGHT)

        mid_row = ttk.Frame(sec_mid)
        mid_row.pack(fill=tk.X, pady=(2, 4))
        self.slider_mid = ttk.Scale(mid_row, from_=70, to=230, variable=self.mid_line_pos_mm, command=lambda v: self.on_mid_line_change())
        self.slider_mid.pack(side=tk.LEFT, fill=tk.X, expand=True)
        self.lbl_mid = ttk.Label(mid_row, text="148.5 mm", width=8, font=("Consolas", 8))
        self.lbl_mid.pack(side=tk.RIGHT, padx=4)

        ttk.Checkbutton(sec_mid, text="Auto-adjust Bottom Placeholder with Line", variable=self.sync_bottom_with_mid, command=self.on_sync_bottom_toggle).pack(anchor="w")
        ttk.Checkbutton(sec_mid, text="Show Center Separation Line in Preview", variable=self.show_mid_line, command=self.update_preview).pack(anchor="w")
        ttk.Checkbutton(sec_mid, text="Print Dashed Cut Line on Paper (Epson L3250)", variable=self.print_mid_line).pack(anchor="w")

        # SECTION 5: IMAGE ENHANCEMENT FILTERS
        sec3 = ttk.LabelFrame(self.right_ctrl_frame, text=" 5. Image Enhancement Filters ", padding=10)
        sec3.pack(fill=tk.X, pady=(0, 10))

        enh_top = ttk.Frame(sec3)
        enh_top.pack(fill=tk.X, pady=(0, 4))
        ttk.Label(enh_top, text="Presets:", font=("Segoe UI", 9, "bold")).pack(side=tk.LEFT)
        tk.Button(enh_top, text="✨ Auto-Enhance", bg="#059669", fg="white", font=("Segoe UI", 8, "bold"), relief=tk.FLAT, padx=6, command=self.auto_enhance).pack(side=tk.RIGHT)

        presets_grid = ttk.Frame(sec3)
        presets_grid.pack(fill=tk.X, pady=(2, 6))

        p_row1 = ttk.Frame(presets_grid)
        p_row1.pack(fill=tk.X, pady=1)
        ttk.Radiobutton(p_row1, text="✨ Magic Clean", variable=self.color_mode, value="clean", command=lambda: self.on_filter_preset_change("clean")).pack(side=tk.LEFT, expand=True, anchor="w")
        ttk.Radiobutton(p_row1, text="🎨 Original", variable=self.color_mode, value="color", command=lambda: self.on_filter_preset_change("color")).pack(side=tk.LEFT, expand=True, anchor="w")

        p_row2 = ttk.Frame(presets_grid)
        p_row2.pack(fill=tk.X, pady=1)
        ttk.Radiobutton(p_row2, text="⚡ Text & QR Sharp", variable=self.color_mode, value="sharp", command=lambda: self.on_filter_preset_change("sharp")).pack(side=tk.LEFT, expand=True, anchor="w")
        ttk.Radiobutton(p_row2, text="📸 Vibrant Photo", variable=self.color_mode, value="vibrant", command=lambda: self.on_filter_preset_change("vibrant")).pack(side=tk.LEFT, expand=True, anchor="w")

        p_row3 = ttk.Frame(presets_grid)
        p_row3.pack(fill=tk.X, pady=1)
        ttk.Radiobutton(p_row3, text="📄 Xerox B&W", variable=self.color_mode, value="xerox", command=lambda: self.on_filter_preset_change("xerox")).pack(side=tk.LEFT, expand=True, anchor="w")
        ttk.Radiobutton(p_row3, text="🌫️ Grayscale", variable=self.color_mode, value="gray", command=lambda: self.on_filter_preset_change("gray")).pack(side=tk.LEFT, expand=True, anchor="w")

        # Sliders
        ttk.Label(sec3, text="Brightness:").pack(anchor="w", pady=(4, 1))
        br_row = ttk.Frame(sec3)
        br_row.pack(fill=tk.X)
        ttk.Scale(br_row, from_=-50, to=50, variable=self.brightness_var, command=lambda v: self.on_filter_slider_change()).pack(side=tk.LEFT, fill=tk.X, expand=True)
        self.lbl_br = ttk.Label(br_row, text="+4%", width=6, font=("Consolas", 8))
        self.lbl_br.pack(side=tk.RIGHT)

        ttk.Label(sec3, text="Contrast:").pack(anchor="w", pady=(4, 1))
        ct_row = ttk.Frame(sec3)
        ct_row.pack(fill=tk.X)
        ttk.Scale(ct_row, from_=-50, to=100, variable=self.contrast_var, command=lambda v: self.on_filter_slider_change()).pack(side=tk.LEFT, fill=tk.X, expand=True)
        self.lbl_ct = ttk.Label(ct_row, text="+18%", width=6, font=("Consolas", 8))
        self.lbl_ct.pack(side=tk.RIGHT)

        ttk.Label(sec3, text="Text & QR Sharpness:").pack(anchor="w", pady=(4, 1))
        sh_row = ttk.Frame(sec3)
        sh_row.pack(fill=tk.X)
        ttk.Scale(sh_row, from_=0, to=100, variable=self.sharpness_var, command=lambda v: self.on_filter_slider_change()).pack(side=tk.LEFT, fill=tk.X, expand=True)
        self.lbl_sh = ttk.Label(sh_row, text="40%", width=6, font=("Consolas", 8))
        self.lbl_sh.pack(side=tk.RIGHT)

        ttk.Label(sec3, text="Background Whiten:").pack(anchor="w", pady=(4, 1))
        bw_row = ttk.Frame(sec3)
        bw_row.pack(fill=tk.X)
        ttk.Scale(bw_row, from_=0, to=100, variable=self.bg_whiten_var, command=lambda v: self.on_filter_slider_change()).pack(side=tk.LEFT, fill=tk.X, expand=True)
        self.lbl_bw = ttk.Label(bw_row, text="45%", width=6, font=("Consolas", 8))
        self.lbl_bw.pack(side=tk.RIGHT)

        # Margin Trim
        ttk.Label(sec3, text="Edge Margin Inset (px):", font=("Segoe UI", 8, "italic")).pack(anchor="w", pady=(4, 1))
        tr_row = ttk.Frame(sec3)
        tr_row.pack(fill=tk.X)
        ttk.Scale(tr_row, from_=0, to=20, variable=self.edge_trim_var, command=lambda v: self.on_filter_slider_change()).pack(side=tk.LEFT, fill=tk.X, expand=True)
        self.lbl_tr = ttk.Label(tr_row, text="0 px", width=6, font=("Consolas", 8))
        self.lbl_tr.pack(side=tk.RIGHT)

        ttk.Button(sec3, text="↺ Reset All Filters", command=self.reset_filters).pack(anchor="e", pady=(6, 0))

        # SECTION 6: PREFERENCES & SAVED SETTINGS
        sec_pref = ttk.LabelFrame(self.right_ctrl_frame, text=" 6. Preferences & Saved Settings ", padding=8)
        sec_pref.pack(fill=tk.X, pady=(0, 10))

        chk_autosave = ttk.Checkbutton(
            sec_pref,
            text="Auto-save settings (resumes where left off)",
            variable=self.auto_save_var
        )
        chk_autosave.pack(anchor="w", pady=(0, 4))

        pref_btn_row = ttk.Frame(sec_pref)
        pref_btn_row.pack(fill=tk.X, pady=(2, 4))

        btn_save_pref = ttk.Button(pref_btn_row, text="💾 Save as Default", command=lambda: self.save_settings(silent=False))
        btn_save_pref.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(0, 2))

        btn_reset_pref = ttk.Button(pref_btn_row, text="↺ Reset Defaults", command=self.reset_settings)
        btn_reset_pref.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(2, 0))

        self.lbl_save_status = ttk.Label(sec_pref, text="✓ Settings active from settings.json", foreground="#059669", font=("Segoe UI", 8))
        self.lbl_save_status.pack(anchor="w")

        # SECTION ACTIONS: NUMBER OF COPIES & PRINT
        sec_act = ttk.LabelFrame(self.right_ctrl_frame, text=" 🖨️ Number of Copies & Print ", padding=10)
        sec_act.pack(fill=tk.X, pady=(0, 10))

        copies_box = ttk.Frame(sec_act)
        copies_box.pack(fill=tk.X, pady=(0, 6))

        ttk.Label(copies_box, text="Copies to Print:", font=("Segoe UI", 9, "bold")).pack(side=tk.LEFT)

        btn_dec = ttk.Button(copies_box, text="−", width=3, command=lambda: self.change_copies(-1))
        btn_dec.pack(side=tk.LEFT, padx=(10, 2))

        self.spin_copies = ttk.Spinbox(
            copies_box,
            from_=1,
            to=99,
            width=4,
            textvariable=self.copies_var,
            justify="center",
            font=("Segoe UI", 10, "bold")
        )
        self.spin_copies.pack(side=tk.LEFT, padx=2)
        self.spin_copies.bind("<KeyRelease>", lambda e: self.on_copies_typed())
        self.spin_copies.bind("<<Increment>>", lambda e: self.on_copies_spin())
        self.spin_copies.bind("<<Decrement>>", lambda e: self.on_copies_spin())

        btn_inc = ttk.Button(copies_box, text="+", width=3, command=lambda: self.change_copies(1))
        btn_inc.pack(side=tk.LEFT, padx=(2, 6))

        # Quick preset buttons
        preset_row = ttk.Frame(sec_act)
        preset_row.pack(fill=tk.X, pady=(0, 8))

        self.copy_preset_btns = {}
        for num in [1, 2, 3, 5]:
            btn = ttk.Button(
                preset_row,
                text=f"{num} Copy" if num == 1 else f"{num} Copies",
                command=lambda n=num: self.set_copies(n)
            )
            btn.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=1)
            self.copy_preset_btns[num] = btn

        self.btn_print = tk.Button(
            sec_act,
            text="🖨️ Print 1 Copy to Epson L3250",
            bg="#0f9d58",
            fg="white",
            font=("Segoe UI", 11, "bold"),
            pady=8,
            command=self.print_now,
            relief=tk.FLAT
        )
        self.btn_print.pack(fill=tk.X, pady=(0, 6))

        self.btn_pdf = tk.Button(
            sec_act,
            text="📥 Save 300 DPI A4 PDF",
            bg="#1a73e8",
            fg="white",
            font=("Segoe UI", 10, "bold"),
            pady=6,
            command=self.save_pdf,
            relief=tk.FLAT
        )
        self.btn_pdf.pack(fill=tk.X)

        self.setup_doc_scanner_ui(doc_tab_frame)
        self.setup_photo_studio_ui(photo_tab_frame)

    def setup_doc_scanner_ui(self, parent):
        doc_paned = ttk.PanedWindow(parent, orient=tk.HORIZONTAL)
        doc_paned.pack(fill=tk.BOTH, expand=True)

        # ---------------------------------------------
        # LEFT PANE: Scanner Target & Page Management
        # ---------------------------------------------
        left_container = ttk.Frame(doc_paned, padding=8)
        doc_paned.add(left_container, weight=0)

        canvas_left = tk.Canvas(left_container, width=320, borderwidth=0, highlightthickness=0)
        scroll_left = ttk.Scrollbar(left_container, orient=tk.VERTICAL, command=canvas_left.yview)
        left_ctrl = ttk.Frame(canvas_left)

        left_ctrl.bind("<Configure>", lambda e: canvas_left.configure(scrollregion=canvas_left.bbox("all")))
        canvas_left.create_window((0, 0), window=left_ctrl, anchor="nw")
        canvas_left.configure(yscrollcommand=scroll_left.set)

        canvas_left.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scroll_left.pack(side=tk.RIGHT, fill=tk.Y)

        def _doc_scroll_l(event):
            canvas_left.yview_scroll(int(-1 * (event.delta / 120)), "units")
        canvas_left.bind("<Enter>", lambda e: canvas_left.bind_all("<MouseWheel>", _doc_scroll_l))
        canvas_left.bind("<Leave>", lambda e: canvas_left.unbind_all("<MouseWheel>"))

        # Section 1: Scan Target
        sec_scan = ttk.LabelFrame(left_ctrl, text=" ⚡ Document Scanner (Epson L3250) ", padding=10)
        sec_scan.pack(fill=tk.X, pady=(0, 10))

        ttk.Label(sec_scan, text="Document Size / Target:", font=("Segoe UI", 9, "bold")).pack(anchor="w")
        type_frame = ttk.Frame(sec_scan)
        type_frame.pack(fill=tk.X, pady=3)
        ttk.Radiobutton(type_frame, text="📄 Full A4 Page", variable=self.doc_scan_type, value="a4").pack(anchor="w")
        ttk.Radiobutton(type_frame, text="🪪 Small Doc / Slip", variable=self.doc_scan_type, value="small").pack(anchor="w")
        ttk.Radiobutton(type_frame, text="🔍 Auto-Detect (A4 vs Small)", variable=self.doc_scan_type, value="auto").pack(anchor="w")

        dpi_row = ttk.Frame(sec_scan)
        dpi_row.pack(fill=tk.X, pady=4)
        ttk.Label(dpi_row, text="DPI:").pack(side=tk.LEFT)
        cb_dpi = ttk.Combobox(dpi_row, textvariable=self.doc_scan_dpi, values=["150", "200", "300", "600"], width=6, state="readonly")
        cb_dpi.pack(side=tk.LEFT, padx=6)
        ttk.Label(dpi_row, text="(300 DPI recommended)", font=("Segoe UI", 7, "italic"), foreground="gray").pack(side=tk.LEFT)

        ttk.Checkbutton(sec_scan, text="✨ Auto-Enhance page on scan", variable=self.doc_auto_enhance).pack(anchor="w", pady=4)

        self.btn_doc_scan = tk.Button(
            sec_scan,
            text="➕ Scan Page 1",
            bg="#059669",
            fg="white",
            font=("Segoe UI", 10, "bold"),
            pady=8,
            relief=tk.FLAT,
            command=self.trigger_doc_scan
        )
        self.btn_doc_scan.pack(fill=tk.X, pady=(4, 2))

        self.lbl_doc_scan_status = ttk.Label(sec_scan, text="⏳ Checking scanner status...", font=("Segoe UI", 8, "italic"), foreground="#64748b")
        self.lbl_doc_scan_status.pack(anchor="w", pady=(2, 2))

        self.btn_doc_abort = tk.Button(
            sec_scan,
            text="⏹️ Abort Scan",
            bg="#ef4444",
            fg="white",
            font=("Segoe UI", 9, "bold"),
            pady=4,
            relief=tk.FLAT,
            command=self.abort_scan
        )

        self.doc_prog_bar = ttk.Progressbar(sec_scan, mode="indeterminate")

        ttk.Button(sec_scan, text="📁 Upload Image Page(s)...", command=self.load_doc_file).pack(fill=tk.X, pady=(4, 0))

        # Section 2: Scanned Pages List
        sec_list = ttk.LabelFrame(left_ctrl, text=" 📄 Scanned Pages ", padding=10)
        sec_list.pack(fill=tk.X, pady=(0, 10))

        self.lbl_doc_page_count = ttk.Label(sec_list, text="0 Pages in Document", font=("Segoe UI", 9, "bold"))
        self.lbl_doc_page_count.pack(anchor="w", pady=(0, 4))

        list_frame = ttk.Frame(sec_list)
        list_frame.pack(fill=tk.BOTH, expand=True)
        self.doc_listbox = tk.Listbox(list_frame, height=8, font=("Segoe UI", 9), selectmode=tk.SINGLE)
        list_scroll = ttk.Scrollbar(list_frame, orient=tk.VERTICAL, command=self.doc_listbox.yview)
        self.doc_listbox.config(yscrollcommand=list_scroll.set)
        self.doc_listbox.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        list_scroll.pack(side=tk.RIGHT, fill=tk.Y)
        self.doc_listbox.bind("<<ListboxSelect>>", self.on_doc_page_select)

        # Reorder / Rotate / Delete controls
        btn_row1 = ttk.Frame(sec_list)
        btn_row1.pack(fill=tk.X, pady=(4, 2))
        ttk.Button(btn_row1, text="⟲ 90°", width=6, command=lambda: self.rotate_doc_page(-90)).pack(side=tk.LEFT, padx=1)
        ttk.Button(btn_row1, text="⟳ 90°", width=6, command=lambda: self.rotate_doc_page(90)).pack(side=tk.LEFT, padx=1)
        ttk.Button(btn_row1, text="🔄 180°", width=6, command=lambda: self.rotate_doc_page(180)).pack(side=tk.LEFT, padx=1)
        ttk.Button(btn_row1, text="▲ Up", width=5, command=lambda: self.move_doc_page(-1)).pack(side=tk.LEFT, padx=1)
        ttk.Button(btn_row1, text="▼ Down", width=5, command=lambda: self.move_doc_page(1)).pack(side=tk.LEFT, padx=1)

        btn_row2 = ttk.Frame(sec_list)
        btn_row2.pack(fill=tk.X, pady=2)
        ttk.Button(btn_row2, text="🗑️ Delete Page", command=self.delete_doc_page).pack(side=tk.LEFT, expand=True, fill=tk.X, padx=1)
        ttk.Button(btn_row2, text="Clear All", command=self.clear_all_doc_pages).pack(side=tk.LEFT, padx=1)

        # ---------------------------------------------
        # CENTER PANE: Selected Page Simulation Canvas
        # ---------------------------------------------
        center_frame = ttk.Frame(doc_paned, padding=8)
        doc_paned.add(center_frame, weight=1)

        doc_header = ttk.Frame(center_frame)
        doc_header.pack(fill=tk.X, pady=(0, 6))

        self.btn_doc_prev = ttk.Button(doc_header, text="← Prev", width=7, command=lambda: self.navigate_doc_page(-1))
        self.btn_doc_prev.pack(side=tk.LEFT)
        self.lbl_doc_page_num = ttk.Label(doc_header, text="No Page Selected", font=("Segoe UI", 10, "bold"))
        self.lbl_doc_page_num.pack(side=tk.LEFT, padx=10)
        self.btn_doc_next = ttk.Button(doc_header, text="Next →", width=7, command=lambda: self.navigate_doc_page(1))
        self.btn_doc_next.pack(side=tk.LEFT)

        self.doc_canvas = tk.Canvas(center_frame, bg="#1e293b", highlightthickness=0)
        self.doc_canvas.pack(fill=tk.BOTH, expand=True)
        self.doc_canvas.bind("<Configure>", lambda e: self.render_current_doc_preview())

        # ---------------------------------------------
        # RIGHT PANE: Enhancements & Print Actions
        # ---------------------------------------------
        right_container = ttk.Frame(doc_paned, padding=8)
        doc_paned.add(right_container, weight=0)

        canvas_right = tk.Canvas(right_container, width=330, borderwidth=0, highlightthickness=0)
        scroll_right = ttk.Scrollbar(right_container, orient=tk.VERTICAL, command=canvas_right.yview)
        right_ctrl = ttk.Frame(canvas_right)

        right_ctrl.bind("<Configure>", lambda e: canvas_right.configure(scrollregion=canvas_right.bbox("all")))
        canvas_right.create_window((0, 0), window=right_ctrl, anchor="nw")
        canvas_right.configure(yscrollcommand=scroll_right.set)

        canvas_right.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scroll_right.pack(side=tk.RIGHT, fill=tk.Y)

        def _doc_scroll_r(event):
            canvas_right.yview_scroll(int(-1 * (event.delta / 120)), "units")
        canvas_right.bind("<Enter>", lambda e: canvas_right.bind_all("<MouseWheel>", _doc_scroll_r))
        canvas_right.bind("<Leave>", lambda e: canvas_right.unbind_all("<MouseWheel>"))

        # Section 3: Document Enhancements
        sec_enh = ttk.LabelFrame(right_ctrl, text=" ✨ Page Enhancement Filters ", padding=10)
        sec_enh.pack(fill=tk.X, pady=(0, 10))

        ttk.Label(sec_enh, text="Preset for Current Page:", font=("Segoe UI", 8, "bold")).pack(anchor="w", pady=(0, 2))
        p_frame1 = ttk.Frame(sec_enh)
        p_frame1.pack(fill=tk.X, pady=2)
        ttk.Button(p_frame1, text="✨ Magic Clean", command=lambda: self.set_doc_preset("clean")).pack(side=tk.LEFT, expand=True, fill=tk.X, padx=1)
        ttk.Button(p_frame1, text="📄 Xerox B&W", command=lambda: self.set_doc_preset("xerox")).pack(side=tk.LEFT, expand=True, fill=tk.X, padx=1)

        p_frame2 = ttk.Frame(sec_enh)
        p_frame2.pack(fill=tk.X, pady=2)
        ttk.Button(p_frame2, text="📸 Vibrant Color", command=lambda: self.set_doc_preset("vibrant")).pack(side=tk.LEFT, expand=True, fill=tk.X, padx=1)
        ttk.Button(p_frame2, text="🌫️ Grayscale", command=lambda: self.set_doc_preset("grayscale")).pack(side=tk.LEFT, expand=True, fill=tk.X, padx=1)

        # Sliders
        ttk.Label(sec_enh, text="Background Whiten:").pack(anchor="w", pady=(4, 0))
        scale_whiten = ttk.Scale(sec_enh, from_=0, to=100, variable=self.doc_bg_whiten, command=self.on_doc_slider_change)
        scale_whiten.pack(fill=tk.X)

        ttk.Label(sec_enh, text="Text Sharpness:").pack(anchor="w", pady=(4, 0))
        scale_sharp = ttk.Scale(sec_enh, from_=0, to=100, variable=self.doc_sharpness, command=self.on_doc_slider_change)
        scale_sharp.pack(fill=tk.X)

        ttk.Label(sec_enh, text="Contrast:").pack(anchor="w", pady=(4, 0))
        scale_cont = ttk.Scale(sec_enh, from_=-50, to=50, variable=self.doc_contrast, command=self.on_doc_slider_change)
        scale_cont.pack(fill=tk.X)

        ttk.Label(sec_enh, text="Brightness:").pack(anchor="w", pady=(4, 0))
        scale_bright = ttk.Scale(sec_enh, from_=-50, to=50, variable=self.doc_brightness, command=self.on_doc_slider_change)
        scale_bright.pack(fill=tk.X)

        ttk.Button(sec_enh, text="⚡ Apply to ALL Pages", command=self.apply_doc_enhancement_to_all).pack(fill=tk.X, pady=(8, 2))

        # Section 4: Print & Output
        sec_out = ttk.LabelFrame(right_ctrl, text=" 🖨️ Print & Output Actions ", padding=10)
        sec_out.pack(fill=tk.X, pady=(0, 10))

        cop_row = ttk.Frame(sec_out)
        cop_row.pack(fill=tk.X, pady=4)
        ttk.Label(cop_row, text="Copies to Print:").pack(side=tk.LEFT)
        ttk.Button(cop_row, text="−", width=3, command=lambda: self.change_doc_copies(-1)).pack(side=tk.LEFT, padx=3)
        sp_cop = ttk.Spinbox(cop_row, from_=1, to=99, textvariable=self.doc_copies, width=4)
        sp_cop.pack(side=tk.LEFT)
        ttk.Button(cop_row, text="+", width=3, command=lambda: self.change_doc_copies(1)).pack(side=tk.LEFT, padx=3)

        self.btn_doc_print = tk.Button(
            sec_out,
            text="🖨️ Print All Pages to Epson L3250",
            bg="#0f9d58",
            fg="white",
            font=("Segoe UI", 11, "bold"),
            pady=8,
            relief=tk.FLAT,
            command=self.print_doc_pages
        )
        self.btn_doc_print.pack(fill=tk.X, pady=(6, 4))

        self.btn_doc_pdf = tk.Button(
            sec_out,
            text="📥 Save Multi-Page 300 DPI PDF",
            bg="#1a73e8",
            fg="white",
            font=("Segoe UI", 10, "bold"),
            pady=6,
            relief=tk.FLAT,
            command=self.save_doc_pdf
        )
        self.btn_doc_pdf.pack(fill=tk.X, pady=2)

        ttk.Button(sec_out, text="💾 Save Current Page Image (PNG)", command=self.save_current_doc_image).pack(fill=tk.X, pady=2)

    # DOCUMENT SCANNER METHODS
    def trigger_doc_scan(self):
        if self.doc_is_scanning or self.is_scanning:
            messagebox.showinfo("Scanner Busy", "A scan is already in progress.")
            return

        if not getattr(self, "scanner_is_online", False):
            if not messagebox.askyesno("Scanner Offline", "Your Epson L3250 appears to be powered off or disconnected.\n\nPlease turn on the printer power switch.\n\nDo you want to attempt scanning anyway?"):
                return

        self.doc_is_scanning = True
        self.btn_doc_scan.pack_forget()
        self.btn_doc_abort.pack(fill=tk.X, pady=(4, 2))
        self.doc_prog_bar.pack(fill=tk.X, pady=(2, 6))
        self.doc_prog_bar.start(10)

        dpi = int(self.doc_scan_dpi.get())
        doc_type = self.doc_scan_type.get()
        auto_enhance = self.doc_auto_enhance.get()
        enhance_preset = self.doc_enhance_preset.get()

        def scan_worker():
            temp_path = os.path.join(CURRENT_DIR, f"desktop_doc_{int(time.time())}.png")
            res = scanner.scan_from_epson(
                temp_path,
                dpi=dpi,
                color_mode=1,
                do_autocrop=True,
                doc_type=doc_type,
                auto_enhance=False
            )
            self.root.after(0, lambda: self.handle_doc_scan_result(res, temp_path))

        self.doc_scan_thread = threading.Thread(target=scan_worker, daemon=True)
        self.doc_scan_thread.start()

    def handle_doc_scan_result(self, res, temp_path):
        self.doc_is_scanning = False
        self.doc_prog_bar.stop()
        self.doc_prog_bar.pack_forget()
        self.btn_doc_abort.pack_forget()
        self.btn_doc_scan.pack(fill=tk.X, pady=(4, 2))

        if res.get("aborted"):
            messagebox.showinfo("Aborted", "Document scan was aborted by user.")
            return

        if not res["success"] or not os.path.exists(temp_path):
            messagebox.showerror("Scan Failed", res.get("error", "Unknown scanning error."))
            return

        try:
            raw_img = Image.open(temp_path).convert("RGB")
            new_page = {
                "img": raw_img.copy(),
                "orig_img": raw_img.copy(),
                "rotation": 0,
                "doc_type": res.get("doc_type", self.doc_scan_type.get()),
                "preset": self.doc_enhance_preset.get() if self.doc_auto_enhance.get() else "original",
                "bg_whiten": float(self.doc_bg_whiten.get()),
                "sharpness": float(self.doc_sharpness.get()),
                "contrast": float(self.doc_contrast.get()),
                "brightness": float(self.doc_brightness.get())
            }
            if self.doc_auto_enhance.get():
                new_page["img"] = autocrop.enhance_document(
                    raw_img,
                    preset=self.doc_enhance_preset.get(),
                    bg_whiten=float(self.doc_bg_whiten.get()),
                    contrast=float(self.doc_contrast.get()),
                    sharpness=float(self.doc_sharpness.get()),
                    brightness=float(self.doc_brightness.get())
                )
            self.doc_pages.append(new_page)
            self.doc_pages.append(new_page)
            self.doc_current_idx = len(self.doc_pages) - 1

            self.render_doc_pages_list()
            self.render_current_doc_preview()

            try: os.remove(temp_path)
            except: pass
        except Exception as e:
            messagebox.showerror("Error", f"Failed to process scanned page: {e}")

    def load_doc_file(self):
        paths = filedialog.askopenfilenames(
            title="Select Document Images",
            filetypes=[("Image Files", "*.png;*.jpg;*.jpeg;*.bmp;*.webp;*.tif;*.tiff")]
        )
        if not paths:
            return

        for p in paths:
            try:
                pil_img = Image.open(p).convert("RGB")
                new_page = {
                    "img": pil_img,
                    "orig_img": pil_img.copy(),
                    "rotation": 0,
                    "doc_type": self.doc_scan_type.get(),
                    "preset": "clean",
                    "bg_whiten": float(self.doc_bg_whiten.get()),
                    "sharpness": float(self.doc_sharpness.get()),
                    "contrast": float(self.doc_contrast.get()),
                    "brightness": float(self.doc_brightness.get())
                }
                if self.doc_auto_enhance.get():
                    new_page["img"] = autocrop.enhance_document(pil_img, preset="clean")
                self.doc_pages.append(new_page)
            except Exception as e:
                print(f"Failed to load {p}: {e}")

        if self.doc_pages:
            self.doc_current_idx = len(self.doc_pages) - 1
            self.render_doc_pages_list()
            self.render_current_doc_preview()

    def render_doc_pages_list(self):
        self.doc_listbox.delete(0, tk.END)
        count = len(self.doc_pages)
        self.lbl_doc_page_count.config(text=f"{count} Page{'s' if count != 1 else ''} in Document")
        self.btn_doc_scan.config(text=f"➕ Scan Page {count + 1}")

        for i, p in enumerate(self.doc_pages):
            type_str = "Small Doc" if p.get("doc_type") == "small" else "A4 Page"
            preset_str = p.get("preset", "clean").capitalize()
            rot_str = f" {p['rotation']}°" if p.get("rotation", 0) != 0 else ""
            self.doc_listbox.insert(tk.END, f"Page {i + 1}: [{type_str} • {preset_str}{rot_str}]")

        if 0 <= self.doc_current_idx < count:
            self.doc_listbox.selection_clear(0, tk.END)
            self.doc_listbox.selection_set(self.doc_current_idx)
            self.doc_listbox.see(self.doc_current_idx)

    def on_doc_page_select(self, event):
        sel = self.doc_listbox.curselection()
        if sel:
            self.doc_current_idx = sel[0]
            self.render_current_doc_preview()

    def render_current_doc_preview(self):
        self.doc_canvas.delete("all")
        count = len(self.doc_pages)

        if count == 0 or self.doc_current_idx < 0 or self.doc_current_idx >= count:
            self.lbl_doc_page_num.config(text="No Pages Scanned")
            w = self.doc_canvas.winfo_width()
            h = self.doc_canvas.winfo_height()
            self.doc_canvas.create_text(w // 2, h // 2, text="No pages scanned yet.\nClick '➕ Scan Page 1' to start.", fill="gray", font=("Segoe UI", 12), justify=tk.CENTER)
            return

        self.lbl_doc_page_num.config(text=f"Page {self.doc_current_idx + 1} of {count}")

        c_w = self.doc_canvas.winfo_width()
        c_h = self.doc_canvas.winfo_height()
        if c_w < 50 or c_h < 50:
            return

        page = self.doc_pages[self.doc_current_idx]
        img = page["img"]
        rot = page.get("rotation", 0)

        if rot != 0:
            img = img.rotate(-rot, expand=True, resample=Image.Resampling.BICUBIC)

        # Draw A4 simulated page in center
        a4_aspect = 210.0 / 297.0
        avail_w = c_w - 40
        avail_h = c_h - 40

        if (avail_w / avail_h) > a4_aspect:
            sheet_h = avail_h
            sheet_w = int(sheet_h * a4_aspect)
        else:
            sheet_w = avail_w
            sheet_h = int(sheet_w / a4_aspect)

        sheet_x = (c_w - sheet_w) // 2
        sheet_y = (c_h - sheet_h) // 2

        self.doc_canvas.create_rectangle(sheet_x + 4, sheet_y + 4, sheet_x + sheet_w + 4, sheet_y + sheet_h + 4, fill="#0f172a", outline="")
        self.doc_canvas.create_rectangle(sheet_x, sheet_y, sheet_x + sheet_w, sheet_y + sheet_h, fill="white", outline="#cbd5e1", width=1)

        # Scale image inside sheet
        img_aspect = img.width / float(img.height)
        inner_w = sheet_w - 20
        inner_h = sheet_h - 20

        if img_aspect > (inner_w / float(inner_h)):
            tw = inner_w
            th = int(tw / img_aspect)
        else:
            th = inner_h
            tw = int(th * img_aspect)

        resized = img.resize((tw, th), Image.Resampling.LANCZOS)
        self.doc_preview_tk = ImageTk.PhotoImage(resized)

        draw_x = sheet_x + (sheet_w - tw) // 2
        draw_y = sheet_y + (sheet_h - th) // 2
        self.doc_canvas.create_image(draw_x, draw_y, anchor="nw", image=self.doc_preview_tk)

    def navigate_doc_page(self, delta):
        if not self.doc_pages:
            return
        new_idx = self.doc_current_idx + delta
        if 0 <= new_idx < len(self.doc_pages):
            self.doc_current_idx = new_idx
            self.render_doc_pages_list()
            self.render_current_doc_preview()

    def rotate_doc_page(self, deg):
        if 0 <= self.doc_current_idx < len(self.doc_pages):
            p = self.doc_pages[self.doc_current_idx]
            p["rotation"] = (p.get("rotation", 0) + deg + 360) % 360
            self.render_doc_pages_list()
            self.render_current_doc_preview()

    def delete_doc_page(self):
        if 0 <= self.doc_current_idx < len(self.doc_pages):
            if not messagebox.askyesno("Confirm", f"Delete Page {self.doc_current_idx + 1}?"):
                return
            del self.doc_pages[self.doc_current_idx]
            if self.doc_current_idx >= len(self.doc_pages):
                self.doc_current_idx = len(self.doc_pages) - 1
            self.render_doc_pages_list()
            self.render_current_doc_preview()

    def move_doc_page(self, delta):
        if 0 <= self.doc_current_idx < len(self.doc_pages):
            target = self.doc_current_idx + delta
            if 0 <= target < len(self.doc_pages):
                self.doc_pages[self.doc_current_idx], self.doc_pages[target] = self.doc_pages[target], self.doc_pages[self.doc_current_idx]
                self.doc_current_idx = target
                self.render_doc_pages_list()
                self.render_current_doc_preview()

    def clear_all_doc_pages(self):
        if not self.doc_pages:
            return
        if not messagebox.askyesno("Confirm", "Clear all scanned pages?"):
            return
        self.doc_pages = []
        self.doc_current_idx = -1
        self.render_doc_pages_list()
        self.render_current_doc_preview()

    def set_doc_preset(self, preset):
        if not (0 <= self.doc_current_idx < len(self.doc_pages)):
            return
        p = self.doc_pages[self.doc_current_idx]
        p["preset"] = preset
        if preset == "clean":
            self.doc_bg_whiten.set(45.0)
            self.doc_sharpness.set(40.0)
            self.doc_contrast.set(18.0)
            self.doc_brightness.set(4.0)
        elif preset == "xerox":
            self.doc_bg_whiten.set(0.0)
            self.doc_sharpness.set(80.0)
            self.doc_contrast.set(45.0)
            self.doc_brightness.set(0.0)
        elif preset == "vibrant":
            self.doc_bg_whiten.set(35.0)
            self.doc_sharpness.set(30.0)
            self.doc_contrast.set(25.0)
            self.doc_brightness.set(5.0)
        elif preset == "grayscale":
            self.doc_bg_whiten.set(35.0)
            self.doc_sharpness.set(35.0)
            self.doc_contrast.set(20.0)
            self.doc_brightness.set(0.0)

        self.apply_enhancement_to_page(p)
        self.render_doc_pages_list()
        self.render_current_doc_preview()

    def on_doc_slider_change(self, *args):
        if 0 <= self.doc_current_idx < len(self.doc_pages):
            p = self.doc_pages[self.doc_current_idx]
            self.apply_enhancement_to_page(p)
            self.render_current_doc_preview()

    def apply_enhancement_to_page(self, p):
        p["bg_whiten"] = float(self.doc_bg_whiten.get())
        p["sharpness"] = float(self.doc_sharpness.get())
        p["contrast"] = float(self.doc_contrast.get())
        p["brightness"] = float(self.doc_brightness.get())
        p["img"] = autocrop.enhance_document(
            p["orig_img"],
            preset=p.get("preset", "clean"),
            bg_whiten=p["bg_whiten"],
            contrast=p["contrast"],
            sharpness=p["sharpness"],
            brightness=p["brightness"]
        )

    def apply_doc_enhancement_to_all(self):
        if not self.doc_pages:
            return
        for p in self.doc_pages:
            p["preset"] = self.doc_enhance_preset.get()
            self.apply_enhancement_to_page(p)
        self.render_doc_pages_list()
        self.render_current_doc_preview()
        messagebox.showinfo("Success", "Enhancement applied to all pages!")

    def change_doc_copies(self, delta):
        try:
            curr = int(self.doc_copies.get())
        except:
            curr = 1
        new_val = max(1, min(99, curr + delta))
        self.doc_copies.set(new_val)

    def print_doc_pages(self):
        if not self.doc_pages:
            messagebox.showinfo("Empty Document", "Please scan or upload at least one page first.")
            return

        try:
            copies = max(1, min(99, int(self.doc_copies.get())))
        except:
            copies = 1

        try:
            temp_pdf = os.path.join(CURRENT_DIR, f"print_multipage_{int(time.time())}.pdf")
            self.generate_doc_pdf_file(temp_pdf, copies)

            if sys.platform == "win32":
                os.startfile(temp_pdf)
                copy_str = f"{copies} copies" if copies > 1 else "1 copy"
                messagebox.showinfo(
                    "Print Ready",
                    f"Multi-Page Document prepared with {copy_str} ({len(self.doc_pages) * copies} total pages).\n\n"
                    "1. Target: Epson L3250 Series\n"
                    "2. Paper Size: A4\n"
                    "3. Click Print in the document viewer!"
                )
        except Exception as e:
            messagebox.showerror("Print Error", f"Failed to generate print job: {e}")

    def save_doc_pdf(self):
        if not self.doc_pages:
            messagebox.showinfo("Empty Document", "Please scan or upload at least one page first.")
            return

        path = filedialog.asksaveasfilename(
            title="Save Multi-Page 300 DPI PDF",
            defaultextension=".pdf",
            filetypes=[("PDF Document", "*.pdf")]
        )
        if not path:
            return

        try:
            copies = max(1, min(99, int(self.doc_copies.get())))
        except:
            copies = 1

        try:
            self.generate_doc_pdf_file(path, copies)
            messagebox.showinfo("Success", f"Multi-Page PDF saved successfully:\n{path}\n\nReady for Epson L3250 printing!")
        except Exception as e:
            messagebox.showerror("Error", f"Failed to save PDF: {e}")

    def generate_doc_pdf_file(self, output_path, copies=1):
        A4_W, A4_H = 2480, 3508
        rendered_a4_pages = []

        for p in self.doc_pages:
            img = p["img"].copy()
            rot = p.get("rotation", 0)
            if rot != 0:
                img = img.rotate(-rot, expand=True, resample=Image.Resampling.BICUBIC)

            sheet = Image.new("RGB", (A4_W, A4_H), (255, 255, 255))
            margin = int(A4_W * 0.035)
            avail_w = A4_W - (margin * 2)
            avail_h = A4_H - (margin * 2)

            aspect = img.width / float(img.height)
            if aspect > (avail_w / float(avail_h)):
                tw = avail_w
                th = int(tw / aspect)
            else:
                th = avail_h
                tw = int(th * aspect)

            resized = img.resize((tw, th), Image.Resampling.LANCZOS)
            sheet.paste(resized, (margin + (avail_w - tw) // 2, margin + (avail_h - th) // 2))
            rendered_a4_pages.append(sheet)

        final_pages = []
        for _ in range(copies):
            final_pages.extend(rendered_a4_pages)

        if len(final_pages) == 1:
            final_pages[0].save(output_path, "PDF", resolution=300.0)
        else:
            final_pages[0].save(output_path, "PDF", resolution=300.0, save_all=True, append_images=final_pages[1:])

    def save_current_doc_image(self):
        if not (0 <= self.doc_current_idx < len(self.doc_pages)):
            messagebox.showinfo("No Page", "No page selected.")
            return

        path = filedialog.asksaveasfilename(
            title="Save Page Image",
            defaultextension=".png",
            filetypes=[("PNG Image", "*.png"), ("JPEG Image", "*.jpg")]
        )
        if not path:
            return

        p = self.doc_pages[self.doc_current_idx]
        img = p["img"]
        rot = p.get("rotation", 0)
        if rot != 0:
            img = img.rotate(-rot, expand=True, resample=Image.Resampling.BICUBIC)
        img.save(path)
        messagebox.showinfo("Saved", f"Page image saved to:\n{path}")

    # =========================================================================
    # PASSPORT PHOTO STUDIO UI & METHODS
    # =========================================================================
    def setup_photo_studio_ui(self, parent):
        photo_paned = ttk.PanedWindow(parent, orient=tk.HORIZONTAL)
        photo_paned.pack(fill=tk.BOTH, expand=True)

        # ---------------------------------------------
        # LEFT PANE: Photo Input & Size Framing
        # ---------------------------------------------
        left_container = ttk.Frame(photo_paned, padding=8)
        photo_paned.add(left_container, weight=0)

        canvas_left = tk.Canvas(left_container, width=310, borderwidth=0, highlightthickness=0)
        scroll_left = ttk.Scrollbar(left_container, orient=tk.VERTICAL, command=canvas_left.yview)
        left_ctrl = ttk.Frame(canvas_left)

        left_ctrl.bind("<Configure>", lambda e: canvas_left.configure(scrollregion=canvas_left.bbox("all")))
        canvas_left.create_window((0, 0), window=left_ctrl, anchor="nw")
        canvas_left.configure(yscrollcommand=scroll_left.set)

        canvas_left.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scroll_left.pack(side=tk.RIGHT, fill=tk.Y)

        def _photo_scroll_l(event):
            canvas_left.yview_scroll(int(-1 * (event.delta / 120)), "units")
        canvas_left.bind("<Enter>", lambda e: canvas_left.bind_all("<MouseWheel>", _photo_scroll_l))
        canvas_left.bind("<Leave>", lambda e: canvas_left.unbind_all("<MouseWheel>"))

        # Section 1: Photo Input
        sec_in = ttk.LabelFrame(left_ctrl, text=" ⚡ Photo Input (Epson L3250) ", padding=10)
        sec_in.pack(fill=tk.X, pady=(0, 10))

        self.btn_photo_scan = tk.Button(
            sec_in,
            text="📷 Scan Photo from Epson",
            bg="#059669",
            fg="white",
            font=("Segoe UI", 10, "bold"),
            pady=8,
            relief=tk.FLAT,
            command=self.trigger_photo_scan
        )
        self.btn_photo_scan.pack(fill=tk.X, pady=(0, 4))

        self.lbl_photo_scan_status = ttk.Label(sec_in, text="⏳ Checking scanner status...", font=("Segoe UI", 8, "italic"), foreground="#64748b")
        self.lbl_photo_scan_status.pack(anchor="w", pady=(0, 4))

        self.btn_photo_abort = tk.Button(
            sec_in,
            text="⏹️ Abort Scan",
            bg="#ef4444",
            fg="white",
            font=("Segoe UI", 9, "bold"),
            pady=4,
            relief=tk.FLAT,
            command=self.abort_scan
        )
        self.photo_prog_bar = ttk.Progressbar(sec_in, mode="indeterminate")

        ttk.Button(sec_in, text="📁 Upload Photo Image...", command=self.load_photo_file).pack(fill=tk.X, pady=(2, 6))

        # Photo Preview Thumbnail & Orientation
        sec_thumb = ttk.LabelFrame(left_ctrl, text=" 👤 Loaded Photo ", padding=8)
        sec_thumb.pack(fill=tk.X, pady=(0, 10))

        self.lbl_photo_thumb = tk.Label(sec_thumb, text="No Photo Loaded\nScan or Upload an image", bg="#f1f5f9", fg="#64748b", height=8, relief=tk.GROOVE)
        self.lbl_photo_thumb.pack(fill=tk.X, pady=(0, 6))

        rot_row = ttk.Frame(sec_thumb)
        rot_row.pack(fill=tk.X)
        ttk.Button(rot_row, text="⟲ 90°", width=7, command=lambda: self.rotate_photo(-90)).pack(side=tk.LEFT, padx=2)
        ttk.Button(rot_row, text="⟳ 90°", width=7, command=lambda: self.rotate_photo(90)).pack(side=tk.LEFT, padx=2)
        ttk.Button(rot_row, text="🔄 180°", width=7, command=lambda: self.rotate_photo(180)).pack(side=tk.LEFT, padx=2)

        # Section 2: Photo Dimensions
        sec_dim = ttk.LabelFrame(left_ctrl, text=" 📐 Photo Size Preset ", padding=10)
        sec_dim.pack(fill=tk.X, pady=(0, 10))

        self.photo_size_preset.set("Indian Passport / Visa (35 × 45 mm)")
        self.cb_photo_size = ttk.Combobox(
            sec_dim,
            textvariable=self.photo_size_preset,
            values=[
                "Indian Passport / Visa (35 × 45 mm)",
                "US Visa / OCI (51 × 51 mm / 2x2 in)",
                "Stamp Size (25 × 30 mm)",
                "PAN Card Photo (25 × 35 mm)",
                "Custom Size (mm)"
            ],
            state="readonly"
        )
        self.cb_photo_size.pack(fill=tk.X, pady=(0, 6))
        self.cb_photo_size.bind("<<ComboboxSelected>>", self.on_photo_size_preset_change)

        dim_row = ttk.Frame(sec_dim)
        dim_row.pack(fill=tk.X, pady=2)
        ttk.Label(dim_row, text="W (mm):").pack(side=tk.LEFT)
        sp_w = ttk.Spinbox(dim_row, from_=15.0, to=150.0, increment=1.0, textvariable=self.photo_w_mm, width=5, command=self.on_photo_slider_change)
        sp_w.pack(side=tk.LEFT, padx=(2, 8))
        sp_w.bind("<KeyRelease>", self.on_photo_slider_change)

        ttk.Label(dim_row, text="H (mm):").pack(side=tk.LEFT)
        sp_h = ttk.Spinbox(dim_row, from_=15.0, to=200.0, increment=1.0, textvariable=self.photo_h_mm, width=5, command=self.on_photo_slider_change)
        sp_h.pack(side=tk.LEFT, padx=2)
        sp_h.bind("<KeyRelease>", self.on_photo_slider_change)

        # ---------------------------------------------
        # CENTER PANE: Simulated A4 Sheet Canvas
        # ---------------------------------------------
        center_frame = ttk.Frame(photo_paned, padding=8)
        photo_paned.add(center_frame, weight=1)

        sheet_hdr = ttk.Frame(center_frame)
        sheet_hdr.pack(fill=tk.X, pady=(0, 6))

        self.lbl_photo_sheet_title = ttk.Label(sheet_hdr, text="📄 A4 Paper (210 × 297 mm) — 300 DPI Grid Simulation", font=("Segoe UI", 10, "bold"))
        self.lbl_photo_sheet_title.pack(side=tk.LEFT)

        btn_sheet_all = ttk.Button(sheet_hdr, text="✓ Select All", width=10, command=self.select_all_photo_slots)
        btn_sheet_all.pack(side=tk.RIGHT, padx=2)
        btn_sheet_none = ttk.Button(sheet_hdr, text="✗ Clear All", width=10, command=self.clear_all_photo_slots)
        btn_sheet_none.pack(side=tk.RIGHT, padx=2)

        self.photo_canvas = tk.Canvas(center_frame, bg="#1e293b", highlightthickness=0)
        self.photo_canvas.pack(fill=tk.BOTH, expand=True)
        self.photo_canvas.bind("<Configure>", lambda e: self.render_photo_preview_canvas())
        self.photo_canvas.bind("<Button-1>", self.toggle_photo_slot_at)

        lbl_hint = ttk.Label(center_frame, text="💡 Click any photo slot to toggle it On/Off. Grayed-out slots won't print — perfect for printing on partially cut photo sheets!", font=("Segoe UI", 8, "italic"), foreground="#475569")
        lbl_hint.pack(anchor="w", pady=(4, 0))

        # ---------------------------------------------
        # RIGHT PANE: Grid Layout, Enhancements & Print Actions
        # ---------------------------------------------
        right_container = ttk.Frame(photo_paned, padding=8)
        photo_paned.add(right_container, weight=0)

        canvas_right = tk.Canvas(right_container, width=330, borderwidth=0, highlightthickness=0)
        scroll_right = ttk.Scrollbar(right_container, orient=tk.VERTICAL, command=canvas_right.yview)
        right_ctrl = ttk.Frame(canvas_right)

        right_ctrl.bind("<Configure>", lambda e: canvas_right.configure(scrollregion=canvas_right.bbox("all")))
        canvas_right.create_window((0, 0), window=right_ctrl, anchor="nw")
        canvas_right.configure(yscrollcommand=scroll_right.set)

        canvas_right.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scroll_right.pack(side=tk.RIGHT, fill=tk.Y)

        def _photo_scroll_r(event):
            canvas_right.yview_scroll(int(-1 * (event.delta / 120)), "units")
        canvas_right.bind("<Enter>", lambda e: canvas_right.bind_all("<MouseWheel>", _photo_scroll_r))
        canvas_right.bind("<Leave>", lambda e: canvas_right.unbind_all("<MouseWheel>"))

        # Section 3: Grid Presets & Geometry
        sec_grid = ttk.LabelFrame(right_ctrl, text=" ▦ A4 Grid Layout ", padding=10)
        sec_grid.pack(fill=tk.X, pady=(0, 10))

        ttk.Label(sec_grid, text="Quick Grid Presets:", font=("Segoe UI", 8, "bold")).pack(anchor="w", pady=(0, 2))
        gp_row1 = ttk.Frame(sec_grid)
        gp_row1.pack(fill=tk.X, pady=2)
        ttk.Button(gp_row1, text="8 (2×4)", command=lambda: self.on_photo_grid_preset("8")).pack(side=tk.LEFT, expand=True, fill=tk.X, padx=1)
        ttk.Button(gp_row1, text="12 (3×4)", command=lambda: self.on_photo_grid_preset("12")).pack(side=tk.LEFT, expand=True, fill=tk.X, padx=1)
        ttk.Button(gp_row1, text="16 (4×4)", command=lambda: self.on_photo_grid_preset("16")).pack(side=tk.LEFT, expand=True, fill=tk.X, padx=1)

        gp_row2 = ttk.Frame(sec_grid)
        gp_row2.pack(fill=tk.X, pady=2)
        ttk.Button(gp_row2, text="24 (6×4)", command=lambda: self.on_photo_grid_preset("24")).pack(side=tk.LEFT, expand=True, fill=tk.X, padx=1)
        ttk.Button(gp_row2, text="30 (6×5)", command=lambda: self.on_photo_grid_preset("30")).pack(side=tk.LEFT, expand=True, fill=tk.X, padx=1)
        ttk.Button(gp_row2, text="32 (8×4)", command=lambda: self.on_photo_grid_preset("32")).pack(side=tk.LEFT, expand=True, fill=tk.X, padx=1)

        # Rows and Columns
        rc_row = ttk.Frame(sec_grid)
        rc_row.pack(fill=tk.X, pady=4)
        ttk.Label(rc_row, text="Rows:").pack(side=tk.LEFT)
        sp_r = ttk.Spinbox(rc_row, from_=1, to=10, textvariable=self.photo_rows, width=4, command=self.on_photo_slider_change)
        sp_r.pack(side=tk.LEFT, padx=(2, 10))
        sp_r.bind("<KeyRelease>", self.on_photo_slider_change)

        ttk.Label(rc_row, text="Cols:").pack(side=tk.LEFT)
        sp_c = ttk.Spinbox(rc_row, from_=1, to=8, textvariable=self.photo_cols, width=4, command=self.on_photo_slider_change)
        sp_c.pack(side=tk.LEFT, padx=(2, 10))
        sp_c.bind("<KeyRelease>", self.on_photo_slider_change)

        ttk.Label(rc_row, text="Count:").pack(side=tk.LEFT)
        self.sp_photo_count = ttk.Spinbox(rc_row, from_=1, to=40, textvariable=self.photo_fill_count, width=4, command=self.on_photo_slider_change)
        self.sp_photo_count.pack(side=tk.LEFT, padx=2)
        self.sp_photo_count.bind("<KeyRelease>", self.on_photo_slider_change)

        # Spacing / Gap (up to 1mm precision)
        gap_header = ttk.Frame(sec_grid)
        gap_header.pack(fill=tk.X, pady=(4, 1))
        ttk.Label(gap_header, text="Spacing / Gap (mm):").pack(side=tk.LEFT)
        ttk.Button(gap_header, text="-1", width=3, command=lambda: self.step_photo_margin("gap", -1)).pack(side=tk.LEFT, padx=(6, 1))
        ttk.Spinbox(gap_header, from_=0.0, to=25.0, increment=1.0, textvariable=self.photo_gap_mm, width=5, command=self.on_photo_slider_change).pack(side=tk.LEFT, padx=1)
        ttk.Button(gap_header, text="+1", width=3, command=lambda: self.step_photo_margin("gap", 1)).pack(side=tk.LEFT, padx=1)
        scale_gap = ttk.Scale(sec_grid, from_=0, to=25, variable=self.photo_gap_mm, command=self.on_photo_slider_change)
        scale_gap.pack(fill=tk.X)
        gap_chips = ttk.Frame(sec_grid)
        gap_chips.pack(fill=tk.X, pady=(1, 4))
        ttk.Button(gap_chips, text="0mm", width=4, command=lambda: self.set_photo_gap_exact(0)).pack(side=tk.LEFT, padx=1)
        ttk.Button(gap_chips, text="1mm", width=4, command=lambda: self.set_photo_gap_exact(1)).pack(side=tk.LEFT, padx=1)
        ttk.Button(gap_chips, text="2mm", width=4, command=lambda: self.set_photo_gap_exact(2)).pack(side=tk.LEFT, padx=1)
        ttk.Button(gap_chips, text="3mm", width=4, command=lambda: self.set_photo_gap_exact(3)).pack(side=tk.LEFT, padx=1)
        ttk.Button(gap_chips, text="5mm", width=4, command=lambda: self.set_photo_gap_exact(5)).pack(side=tk.LEFT, padx=1)

        # Top Margin (1mm edit precision)
        top_header = ttk.Frame(sec_grid)
        top_header.pack(fill=tk.X, pady=(4, 1))
        ttk.Label(top_header, text="Top Margin (mm):").pack(side=tk.LEFT)
        ttk.Button(top_header, text="-1", width=3, command=lambda: self.step_photo_margin("top", -1)).pack(side=tk.LEFT, padx=(6, 1))
        sp_top = ttk.Spinbox(top_header, from_=0.0, to=120.0, increment=1.0, textvariable=self.photo_top_margin_mm, width=5, command=self.on_photo_slider_change)
        sp_top.pack(side=tk.LEFT, padx=1)
        sp_top.bind("<KeyRelease>", self.on_photo_slider_change)
        ttk.Button(top_header, text="+1", width=3, command=lambda: self.step_photo_margin("top", 1)).pack(side=tk.LEFT, padx=1)
        scale_top = ttk.Scale(sec_grid, from_=0, to=100, variable=self.photo_top_margin_mm, command=self.on_photo_slider_change)
        scale_top.pack(fill=tk.X)

        # Left Margin (1mm edit precision & auto-center)
        left_header = ttk.Frame(sec_grid)
        left_header.pack(fill=tk.X, pady=(4, 1))
        ttk.Label(left_header, text="Left Margin (mm):").pack(side=tk.LEFT)
        ttk.Button(left_header, text="🎯 Center", command=self.auto_center_photo_grid).pack(side=tk.LEFT, padx=(4, 4))
        ttk.Button(left_header, text="-1", width=3, command=lambda: self.step_photo_margin("left", -1)).pack(side=tk.LEFT, padx=1)
        sp_left = ttk.Spinbox(left_header, from_=0.0, to=120.0, increment=1.0, textvariable=self.photo_left_margin_mm, width=5, command=self.on_photo_slider_change)
        sp_left.pack(side=tk.LEFT, padx=1)
        sp_left.bind("<KeyRelease>", self.on_photo_slider_change)
        ttk.Button(left_header, text="+1", width=3, command=lambda: self.step_photo_margin("left", 1)).pack(side=tk.LEFT, padx=1)
        scale_left = ttk.Scale(sec_grid, from_=0, to=100, variable=self.photo_left_margin_mm, command=self.on_photo_slider_change)
        scale_left.pack(fill=tk.X)

        # Border Style & Cut Guidelines
        b_row = ttk.Frame(sec_grid)
        b_row.pack(fill=tk.X, pady=4)
        ttk.Label(b_row, text="Border:").pack(side=tk.LEFT)
        cb_b = ttk.Combobox(b_row, textvariable=self.photo_border_style, values=["solid", "dashed", "white_margin", "none"], width=12, state="readonly")
        cb_b.pack(side=tk.LEFT, padx=4)
        cb_b.bind("<<ComboboxSelected>>", self.on_photo_slider_change)

        ttk.Checkbutton(sec_grid, text="✂️ Scissor Cutting Guidelines", variable=self.photo_cut_guides, command=self.on_photo_slider_change).pack(anchor="w", pady=(2, 0))

        # Section 4: Portrait Enhancements
        sec_enh = ttk.LabelFrame(right_ctrl, text=" ✨ Studio Portrait Enhancements ", padding=10)
        sec_enh.pack(fill=tk.X, pady=(0, 10))

        p1 = ttk.Frame(sec_enh)
        p1.pack(fill=tk.X, pady=2)
        ttk.Button(p1, text="✨ Studio Portrait", command=lambda: self.set_photo_enhancement_preset("studio")).pack(side=tk.LEFT, expand=True, fill=tk.X, padx=1)
        ttk.Button(p1, text="🔷 Light Blue BG", command=lambda: self.set_photo_enhancement_preset("lightblue")).pack(side=tk.LEFT, expand=True, fill=tk.X, padx=1)

        p2 = ttk.Frame(sec_enh)
        p2.pack(fill=tk.X, pady=2)
        ttk.Button(p2, text="⚪ White BG", command=lambda: self.set_photo_enhancement_preset("white")).pack(side=tk.LEFT, expand=True, fill=tk.X, padx=1)
        ttk.Button(p2, text="📸 Vibrant Glossy", command=lambda: self.set_photo_enhancement_preset("vibrant")).pack(side=tk.LEFT, expand=True, fill=tk.X, padx=1)
        ttk.Button(p2, text="Original", command=lambda: self.set_photo_enhancement_preset("original")).pack(side=tk.LEFT, padx=1)

        ttk.Label(sec_enh, text="Background Whitening:").pack(anchor="w", pady=(4, 0))
        ttk.Scale(sec_enh, from_=0, to=100, variable=self.photo_bg_whiten, command=self.on_photo_enhancement_slider_change).pack(fill=tk.X)

        ttk.Label(sec_enh, text="Face & Detail Sharpness:").pack(anchor="w", pady=(4, 0))
        ttk.Scale(sec_enh, from_=0, to=100, variable=self.photo_sharpness, command=self.on_photo_enhancement_slider_change).pack(fill=tk.X)

        ttk.Label(sec_enh, text="Contrast:").pack(anchor="w", pady=(4, 0))
        ttk.Scale(sec_enh, from_=-50, to=50, variable=self.photo_contrast, command=self.on_photo_enhancement_slider_change).pack(fill=tk.X)

        ttk.Label(sec_enh, text="Brightness:").pack(anchor="w", pady=(4, 0))
        ttk.Scale(sec_enh, from_=-50, to=50, variable=self.photo_brightness, command=self.on_photo_enhancement_slider_change).pack(fill=tk.X)

        ttk.Label(sec_enh, text="Color Saturation:").pack(anchor="w", pady=(4, 0))
        ttk.Scale(sec_enh, from_=50, to=200, variable=self.photo_saturation, command=self.on_photo_enhancement_slider_change).pack(fill=tk.X)

        # Section 5: Print Actions
        sec_out = ttk.LabelFrame(right_ctrl, text=" 🖨️ Print & Output Actions ", padding=10)
        sec_out.pack(fill=tk.X, pady=(0, 10))

        cop_row = ttk.Frame(sec_out)
        cop_row.pack(fill=tk.X, pady=4)
        ttk.Label(cop_row, text="Copies to Print:").pack(side=tk.LEFT)
        ttk.Button(cop_row, text="−", width=3, command=lambda: self.change_photo_copies(-1)).pack(side=tk.LEFT, padx=3)
        sp_p_cop = ttk.Spinbox(cop_row, from_=1, to=99, textvariable=self.photo_copies, width=4)
        sp_p_cop.pack(side=tk.LEFT)
        ttk.Button(cop_row, text="+", width=3, command=lambda: self.change_photo_copies(1)).pack(side=tk.LEFT, padx=3)

        self.btn_photo_print = tk.Button(
            sec_out,
            text="🖨️ Print Grid to Epson L3250",
            bg="#0f9d58",
            fg="white",
            font=("Segoe UI", 11, "bold"),
            pady=8,
            relief=tk.FLAT,
            command=self.print_photo_grid
        )
        self.btn_photo_print.pack(fill=tk.X, pady=(6, 4))

        self.btn_photo_pdf = tk.Button(
            sec_out,
            text="📥 Save 300 DPI A4 PDF",
            bg="#1a73e8",
            fg="white",
            font=("Segoe UI", 10, "bold"),
            pady=6,
            relief=tk.FLAT,
            command=self.save_photo_grid_pdf
        )
        self.btn_photo_pdf.pack(fill=tk.X, pady=2)

        self.btn_photo_png = ttk.Button(sec_out, text="💾 Save 300 DPI A4 Image (PNG)", command=self.save_photo_grid_image)
        self.btn_photo_png.pack(fill=tk.X, pady=2)

    def get_sample_photo(self):
        sample = Image.new("RGB", (350, 450), (226, 232, 240))
        d = ImageDraw.Draw(sample)
        d.ellipse([110, 80, 240, 230], fill=(148, 163, 184), outline=(100, 116, 139), width=3)
        d.pieslice([60, 240, 290, 480], start=180, end=360, fill=(148, 163, 184), outline=(100, 116, 139), width=3)
        d.line([(135, 145), (165, 145)], fill=(100, 116, 139), width=2)
        d.line([(185, 145), (215, 145)], fill=(100, 116, 139), width=2)
        d.arc([160, 175, 190, 195], start=0, end=180, fill=(100, 116, 139), width=2)
        d.text((95, 25), "PASSPORT PHOTO", fill=(51, 65, 85))
        d.text((115, 415), "35 × 45 mm", fill=(71, 85, 105))
        return sample

    def trigger_photo_scan(self):
        if getattr(self, "photo_is_scanning", False) or getattr(self, "is_scanning", False):
            messagebox.showinfo("Scanner Busy", "A scan is already in progress.")
            return

        if not getattr(self, "scanner_is_online", False):
            if not messagebox.askyesno("Scanner Offline", "Your Epson L3250 appears to be powered off or disconnected.\n\nPlease turn on the printer power switch.\n\nDo you want to attempt scanning anyway?"):
                return

        self.photo_is_scanning = True
        self.btn_photo_scan.pack_forget()
        self.btn_photo_abort.pack(fill=tk.X, pady=(0, 4))
        self.photo_prog_bar.pack(fill=tk.X, pady=(2, 6))
        self.photo_prog_bar.start(10)

        def scan_worker():
            temp_path = os.path.join(CURRENT_DIR, f"desktop_photo_{int(time.time())}.png")
            res = scanner.scan_from_epson(
                temp_path,
                dpi=300,
                color_mode=1,
                do_autocrop=True,
                doc_type="small",
                auto_enhance=False
            )
            self.root.after(0, lambda: self.handle_photo_scan_result(res, temp_path))

        self.photo_scan_thread = threading.Thread(target=scan_worker, daemon=True)
        self.photo_scan_thread.start()

    def handle_photo_scan_result(self, res, temp_path):
        self.photo_is_scanning = False
        self.photo_prog_bar.stop()
        self.photo_prog_bar.pack_forget()
        self.btn_photo_abort.pack_forget()
        self.btn_photo_scan.pack(fill=tk.X, pady=(0, 4))

        if res.get("aborted"):
            messagebox.showinfo("Aborted", "Photo scan was aborted by user.")
            return

        if not res["success"] or not os.path.exists(temp_path):
            messagebox.showerror("Scan Failed", res.get("error", "Unknown scanning error."))
            return

        try:
            self.photo_raw_img = Image.open(temp_path).convert("RGB")
            self.apply_photo_enhancement()
            try: os.remove(temp_path)
            except: pass
        except Exception as e:
            messagebox.showerror("Error", f"Failed to load scanned photo: {e}")

    def load_photo_file(self):
        path = filedialog.askopenfilename(
            title="Select Passport Photo Image",
            filetypes=[("Image Files", "*.png;*.jpg;*.jpeg;*.bmp;*.webp;*.tif;*.tiff")]
        )
        if not path:
            return
        try:
            self.photo_raw_img = Image.open(path).convert("RGB")
            self.apply_photo_enhancement()
        except Exception as e:
            messagebox.showerror("Error", f"Failed to load image: {e}")

    def rotate_photo(self, angle):
        if self.photo_raw_img is not None:
            self.photo_raw_img = self.photo_raw_img.rotate(-angle, expand=True, resample=Image.Resampling.BICUBIC)
            self.apply_photo_enhancement()

    def on_photo_size_preset_change(self, *args):
        val = self.photo_size_preset.get()
        if "35 × 45" in val or "Indian Passport" in val:
            self.photo_w_mm.set(35.0)
            self.photo_h_mm.set(45.0)
        elif "51 × 51" in val or "US Visa" in val:
            self.photo_w_mm.set(51.0)
            self.photo_h_mm.set(51.0)
            if self.photo_cols.get() > 4:
                self.on_photo_grid_preset("12")
        elif "25 × 30" in val or "Stamp" in val:
            self.photo_w_mm.set(25.0)
            self.photo_h_mm.set(30.0)
        elif "25 × 35" in val or "PAN" in val:
            self.photo_w_mm.set(25.0)
            self.photo_h_mm.set(35.0)
        self.auto_center_photo_grid(recalc_top=False)

    def on_photo_grid_preset(self, preset_key):
        self.photo_grid_preset.set(preset_key)
        if preset_key == "8":
            self.photo_rows.set(2)
            self.photo_cols.set(4)
            self.photo_fill_count.set(8)
            self.photo_gap_mm.set(3.0)
            self.photo_top_margin_mm.set(20.0)
            self.photo_left_margin_mm.set(30.5)
        elif preset_key == "12":
            self.photo_rows.set(3)
            self.photo_cols.set(4)
            self.photo_fill_count.set(12)
            self.photo_gap_mm.set(3.0)
            self.photo_top_margin_mm.set(20.0)
            self.photo_left_margin_mm.set(30.5)
        elif preset_key == "16":
            self.photo_rows.set(4)
            self.photo_cols.set(4)
            self.photo_fill_count.set(16)
            self.photo_gap_mm.set(3.0)
            self.photo_top_margin_mm.set(20.0)
            self.photo_left_margin_mm.set(30.5)
        elif preset_key == "24":
            self.photo_rows.set(6)
            self.photo_cols.set(4)
            self.photo_fill_count.set(24)
            self.photo_gap_mm.set(3.0)
            self.photo_top_margin_mm.set(6.0)
            self.photo_left_margin_mm.set(30.5)
        elif preset_key == "30":
            self.photo_rows.set(6)
            self.photo_cols.set(5)
            self.photo_fill_count.set(30)
            self.photo_gap_mm.set(3.0)
            self.photo_top_margin_mm.set(6.0)
            self.photo_left_margin_mm.set(11.5)
        elif preset_key == "32":
            self.photo_rows.set(8)
            self.photo_cols.set(4)
            self.photo_fill_count.set(32)
            self.photo_gap_mm.set(2.0)
            self.photo_top_margin_mm.set(6.0)
            self.photo_left_margin_mm.set(30.5)
        self.photo_disabled_slots.clear()
        self.render_photo_preview_canvas()

    def step_photo_margin(self, which, delta):
        try:
            if which == "top":
                curr = float(self.photo_top_margin_mm.get())
                new_val = max(0.0, min(120.0, round(curr + delta)))
                self.photo_top_margin_mm.set(new_val)
            elif which == "left":
                curr = float(self.photo_left_margin_mm.get())
                new_val = max(0.0, min(120.0, round(curr + delta)))
                self.photo_left_margin_mm.set(new_val)
            elif which == "gap":
                curr = float(self.photo_gap_mm.get())
                new_val = max(0.0, min(25.0, round(curr + delta, 1)))
                self.photo_gap_mm.set(new_val)
            self.render_photo_preview_canvas()
        except:
            pass

    def set_photo_gap_exact(self, val_mm):
        try:
            self.photo_gap_mm.set(float(val_mm))
            self.auto_center_photo_grid(recalc_top=False)
        except:
            pass

    def auto_center_photo_grid(self, recalc_top=False):
        try:
            w = float(self.photo_w_mm.get())
            h = float(self.photo_h_mm.get())
            cols = int(self.photo_cols.get())
            rows = int(self.photo_rows.get())
            gap = float(self.photo_gap_mm.get())

            grid_w = (cols * w) + ((cols - 1) * gap)
            left_mm = max(0.0, (210.0 - grid_w) / 2.0)
            self.photo_left_margin_mm.set(round(left_mm, 1))

            if recalc_top or rows > 3:
                grid_h = (rows * h) + ((rows - 1) * gap)
                top_mm = max(0.0, (297.0 - grid_h) / 2.0)
                self.photo_top_margin_mm.set(round(top_mm, 1))
            self.render_photo_preview_canvas()
        except:
            pass

    def on_photo_slider_change(self, *args):
        try:
            max_slots = int(self.photo_rows.get()) * int(self.photo_cols.get())
            if hasattr(self, 'sp_photo_count'):
                self.sp_photo_count.config(to=max_slots)
            if self.photo_fill_count.get() > max_slots:
                self.photo_fill_count.set(max_slots)
        except:
            pass
        self.render_photo_preview_canvas()

    def on_photo_enhancement_slider_change(self, *args):
        self.apply_photo_enhancement()

    def set_photo_enhancement_preset(self, preset):
        self.photo_preset.set(preset)
        if preset == "studio":
            self.photo_bg_whiten.set(40.0)
            self.photo_sharpness.set(35.0)
            self.photo_contrast.set(12.0)
            self.photo_brightness.set(2.0)
            self.photo_saturation.set(115.0)
        elif preset == "lightblue":
            self.photo_bg_whiten.set(0.0)
            self.photo_sharpness.set(35.0)
            self.photo_contrast.set(12.0)
            self.photo_brightness.set(0.0)
            self.photo_saturation.set(120.0)
        elif preset == "white":
            self.photo_bg_whiten.set(70.0)
            self.photo_sharpness.set(40.0)
            self.photo_contrast.set(15.0)
            self.photo_brightness.set(5.0)
            self.photo_saturation.set(110.0)
        elif preset == "vibrant":
            self.photo_bg_whiten.set(20.0)
            self.photo_sharpness.set(45.0)
            self.photo_contrast.set(20.0)
            self.photo_brightness.set(5.0)
            self.photo_saturation.set(135.0)
        elif preset == "original":
            self.photo_bg_whiten.set(0.0)
            self.photo_sharpness.set(0.0)
            self.photo_contrast.set(0.0)
            self.photo_brightness.set(0.0)
            self.photo_saturation.set(100.0)
        self.apply_photo_enhancement()

    def apply_photo_enhancement(self):
        if self.photo_raw_img is None:
            self.render_photo_preview_canvas()
            return

        try:
            bg_mode = "lightblue" if self.photo_preset.get() == "lightblue" else "white"
            self.photo_enhanced_img = autocrop.enhance_passport_photo(
                self.photo_raw_img,
                preset=self.photo_preset.get(),
                brightness=float(self.photo_brightness.get()),
                contrast=float(self.photo_contrast.get()),
                sharpness=float(self.photo_sharpness.get()),
                saturation=float(self.photo_saturation.get()),
                bg_mode=bg_mode
            )
            thumb = self.photo_enhanced_img.copy()
            thumb.thumbnail((180, 180), Image.Resampling.LANCZOS)
            self.photo_thumb_tk = ImageTk.PhotoImage(thumb)
            self.lbl_photo_thumb.config(image=self.photo_thumb_tk, text="")
        except Exception as e:
            print(f"Error applying photo enhancement: {e}")
            self.photo_enhanced_img = self.photo_raw_img.copy()

        self.render_photo_preview_canvas()

    def render_photo_preview_canvas(self):
        if not hasattr(self, "photo_canvas"):
            return
        c_w = self.photo_canvas.winfo_width()
        c_h = self.photo_canvas.winfo_height()
        if c_w < 50 or c_h < 50:
            return

        self.photo_canvas.delete("all")
        self.photo_slot_rects.clear()

        # Simulated A4 Sheet aspect (210 x 297)
        a4_aspect = 210.0 / 297.0
        avail_w = c_w - 30
        avail_h = c_h - 30

        if (avail_w / float(avail_h)) > a4_aspect:
            sheet_h = avail_h
            sheet_w = int(sheet_h * a4_aspect)
        else:
            sheet_w = avail_w
            sheet_h = int(sheet_w / a4_aspect)

        sheet_x = (c_w - sheet_w) // 2
        sheet_y = (c_h - sheet_h) // 2

        # A4 Drop shadow & Sheet background
        self.photo_canvas.create_rectangle(sheet_x + 5, sheet_y + 5, sheet_x + sheet_w + 5, sheet_y + sheet_h + 5, fill="#0f172a", outline="")
        self.photo_canvas.create_rectangle(sheet_x, sheet_y, sheet_x + sheet_w, sheet_y + sheet_h, fill="white", outline="#94a3b8", width=1)

        # Scale factor from mm to canvas pixels
        px_per_mm = sheet_w / 210.0

        w_mm = float(self.photo_w_mm.get())
        h_mm = float(self.photo_h_mm.get())
        cols = int(self.photo_cols.get())
        rows = int(self.photo_rows.get())
        gap_mm = float(self.photo_gap_mm.get())
        top_mm = float(self.photo_top_margin_mm.get())
        left_mm = float(self.photo_left_margin_mm.get())
        fill_count = int(self.photo_fill_count.get())
        border_style = self.photo_border_style.get()
        cut_guides = self.photo_cut_guides.get()

        p_w_sim = max(5, int(w_mm * px_per_mm))
        p_h_sim = max(5, int(h_mm * px_per_mm))
        gap_sim = max(0, int(gap_mm * px_per_mm))
        left_sim = sheet_x + int(left_mm * px_per_mm)
        top_sim = sheet_y + int(top_mm * px_per_mm)

        photo_to_use = self.photo_enhanced_img if self.photo_enhanced_img is not None else self.get_sample_photo()
        photo_sim_pil = photo_to_use.resize((p_w_sim, p_h_sim), Image.Resampling.BILINEAR)
        self.photo_sim_tk = ImageTk.PhotoImage(photo_sim_pil)

        grid_w_sim = (cols * p_w_sim) + ((cols - 1) * gap_sim)
        grid_h_sim = (rows * p_h_sim) + ((rows - 1) * gap_sim)

        # Draw scissor cutting guidelines if enabled
        if cut_guides:
            for r in range(rows + 1):
                if r == 0:
                    gy = top_sim - gap_sim // 2
                elif r == rows:
                    gy = top_sim + (rows - 1) * (p_h_sim + gap_sim) + p_h_sim + gap_sim // 2
                else:
                    gy = top_sim + r * (p_h_sim + gap_sim) - gap_sim // 2
                if sheet_y <= gy <= sheet_y + sheet_h:
                    self.photo_canvas.create_line(max(sheet_x, left_sim - 20), gy, min(sheet_x + sheet_w, left_sim + grid_w_sim + 20), gy, fill="#cbd5e1", dash=(4, 4), width=1)

            for c in range(cols + 1):
                if c == 0:
                    gx = left_sim - gap_sim // 2
                elif c == cols:
                    gx = left_sim + (cols - 1) * (p_w_sim + gap_sim) + p_w_sim + gap_sim // 2
                else:
                    gx = left_sim + c * (p_w_sim + gap_sim) - gap_sim // 2
                if sheet_x <= gx <= sheet_x + sheet_w:
                    self.photo_canvas.create_line(gx, max(sheet_y, top_sim - 20), gx, min(sheet_y + sheet_h, top_sim + grid_h_sim + 20), fill="#cbd5e1", dash=(4, 4), width=1)

        # Draw photo slots
        active_count = 0
        for r in range(rows):
            for c in range(cols):
                idx = r * cols + c
                sx1 = left_sim + c * (p_w_sim + gap_sim)
                sy1 = top_sim + r * (p_h_sim + gap_sim)
                sx2 = sx1 + p_w_sim
                sy2 = sy1 + p_h_sim

                self.photo_slot_rects.append((sx1, sy1, sx2, sy2, idx))

                is_active = (idx < fill_count) and (idx not in self.photo_disabled_slots)

                if is_active:
                    active_count += 1
                    self.photo_canvas.create_image(sx1, sy1, anchor="nw", image=self.photo_sim_tk)

                    if border_style == "solid":
                        self.photo_canvas.create_rectangle(sx1, sy1, sx2, sy2, outline="#000000", width=1)
                    elif border_style == "dashed":
                        self.photo_canvas.create_rectangle(sx1, sy1, sx2, sy2, outline="#64748b", dash=(3, 3), width=1)
                    elif border_style == "white_margin":
                        self.photo_canvas.create_rectangle(sx1 - 2, sy1 - 2, sx2 + 2, sy2 + 2, outline="#000000", width=1)

                    self.photo_canvas.create_rectangle(sx1, sy1, sx1 + 18, sy1 + 14, fill="#1e293b", outline="")
                    self.photo_canvas.create_text(sx1 + 9, sy1 + 7, text=str(idx + 1), fill="#ffffff", font=("Segoe UI", 7, "bold"))
                else:
                    self.photo_canvas.create_rectangle(sx1, sy1, sx2, sy2, fill="#f8fafc", outline="#cbd5e1", dash=(3, 3), width=1)
                    self.photo_canvas.create_text((sx1 + sx2) // 2, (sy1 + sy2) // 2, text=f"✕ OFF\n#{idx + 1}", fill="#94a3b8", font=("Segoe UI", 8), justify=tk.CENTER)

        self.lbl_photo_sheet_title.config(
            text=f"📄 A4 Paper ({cols}×{rows} Grid — {active_count} of {rows * cols} Photos Active)"
        )

    def toggle_photo_slot_at(self, event):
        for (sx1, sy1, sx2, sy2, idx) in self.photo_slot_rects:
            if sx1 <= event.x <= sx2 and sy1 <= event.y <= sy2:
                if idx in self.photo_disabled_slots:
                    self.photo_disabled_slots.remove(idx)
                else:
                    self.photo_disabled_slots.add(idx)
                self.render_photo_preview_canvas()
                break

    def select_all_photo_slots(self):
        self.photo_disabled_slots.clear()
        self.render_photo_preview_canvas()

    def clear_all_photo_slots(self):
        total = int(self.photo_rows.get()) * int(self.photo_cols.get())
        self.photo_disabled_slots = set(range(total))
        self.render_photo_preview_canvas()

    def change_photo_copies(self, delta):
        curr = int(self.photo_copies.get())
        new_val = max(1, min(99, curr + delta))
        self.photo_copies.set(new_val)

    def generate_photo_grid_pil(self):
        photo_to_use = self.photo_enhanced_img if self.photo_enhanced_img is not None else self.get_sample_photo()
        rows = int(self.photo_rows.get())
        cols = int(self.photo_cols.get())
        w_mm = float(self.photo_w_mm.get())
        h_mm = float(self.photo_h_mm.get())
        gap_mm = float(self.photo_gap_mm.get())
        top_mm = float(self.photo_top_margin_mm.get())
        left_mm = float(self.photo_left_margin_mm.get())
        fill_count = int(self.photo_fill_count.get())
        border_style = self.photo_border_style.get()
        cut_guides = self.photo_cut_guides.get()

        active_indices = [i for i in range(fill_count) if i not in self.photo_disabled_slots]

        return autocrop.render_passport_grid_a4(
            photo_input=photo_to_use,
            rows=rows,
            cols=cols,
            photo_w_mm=w_mm,
            photo_h_mm=h_mm,
            gap_mm=gap_mm,
            top_margin_mm=top_mm,
            left_margin_mm=left_mm,
            fill_count=fill_count,
            border_style=border_style,
            cut_guides=cut_guides,
            active_indices=active_indices
        )

    def print_photo_grid(self):
        if self.photo_enhanced_img is None and not messagebox.askyesno("Sample Photo", "No actual photo is loaded yet.\n\nDo you want to print the sample grid?"):
            return

        try:
            copies = max(1, min(99, int(self.photo_copies.get())))
        except:
            copies = 1

        try:
            temp_pdf = os.path.join(CURRENT_DIR, f"print_photo_grid_{int(time.time())}.pdf")
            sheet_img = self.generate_photo_grid_pil()

            pages = [sheet_img] * copies
            if len(pages) == 1:
                pages[0].save(temp_pdf, "PDF", resolution=300.0)
            else:
                pages[0].save(temp_pdf, "PDF", resolution=300.0, save_all=True, append_images=pages[1:])

            if sys.platform == "win32":
                os.startfile(temp_pdf)
                copy_str = f"{copies} copies" if copies > 1 else "1 copy"
                messagebox.showinfo(
                    "Print Ready",
                    f"Passport Photo Grid prepared with {copy_str} ({copies} A4 sheet{'s' if copies > 1 else ''}).\n\n"
                    "1. Target: Epson L3250 Series\n"
                    "2. Paper Size: A4 (Glossy Photo Paper or Plain)\n"
                    "3. Scale: 100% / Actual Size\n\n"
                    "Click Print in the viewer to print!"
                )
        except Exception as e:
            messagebox.showerror("Print Error", f"Failed to generate photo print job: {e}")

    def save_photo_grid_pdf(self):
        try:
            copies = max(1, min(99, int(self.photo_copies.get())))
        except:
            copies = 1

        path = filedialog.asksaveasfilename(
            title="Save 300 DPI A4 Photo Grid PDF",
            defaultextension=".pdf",
            filetypes=[("PDF Document", "*.pdf")]
        )
        if not path:
            return

        try:
            sheet_img = self.generate_photo_grid_pil()
            pages = [sheet_img] * copies
            if len(pages) == 1:
                pages[0].save(path, "PDF", resolution=300.0)
            else:
                pages[0].save(path, "PDF", resolution=300.0, save_all=True, append_images=pages[1:])
            messagebox.showinfo("Success", f"Passport Photo Grid PDF saved successfully:\n{path}")
        except Exception as e:
            messagebox.showerror("Error", f"Failed to save PDF: {e}")

    def save_photo_grid_image(self):
        path = filedialog.asksaveasfilename(
            title="Save 300 DPI A4 Photo Grid Image",
            defaultextension=".png",
            filetypes=[("PNG Image", "*.png"), ("JPEG Image", "*.jpg")]
        )
        if not path:
            return

        try:
            sheet_img = self.generate_photo_grid_pil()
            sheet_img.save(path)
            messagebox.showinfo("Success", f"Passport Photo Grid Image saved successfully:\n{path}")
        except Exception as e:
            messagebox.showerror("Error", f"Failed to save Image: {e}")

    def on_connection_mode_changed(self):
        mode = self.conn_mode_var.get()
        target_name = "EPSON L3250 Series" if mode == "usb" else "EPSON L3250 Series (Network)"
        scanner.set_windows_default_printer(target_name)
        try:
            cur = {}
            if os.path.exists("settings.json"):
                with open("settings.json", "r", encoding="utf-8") as f:
                    cur = json.load(f)
            cur["connectionMode"] = mode
            with open("settings.json", "w", encoding="utf-8") as f:
                json.dump(cur, f, indent=2)
        except Exception:
            pass
        self.check_scanner_hardware_status(force=True)

    def check_scanner_hardware_status(self, force=False):
        mode_val = self.conn_mode_var.get() if hasattr(self, "conn_mode_var") else "usb"
        def worker():
            st = scanner.check_scanner_online(force=force, connection_mode=mode_val)
            try:
                self.root.after(0, lambda: self.update_scanner_status_ui(st))
            except:
                pass
        threading.Thread(target=worker, daemon=True).start()
        try:
            self.root.after(4000, self.check_scanner_hardware_status)
        except:
            pass

    def update_scanner_status_ui(self, st):
        self.scanner_is_online = st.get("online", False)
        is_active_scan = (getattr(self, "is_scanning", False) or 
                          getattr(self, "doc_is_scanning", False) or 
                          getattr(self, "photo_is_scanning", False) or
                          st.get("busy", False) or 
                          st.get("scanning", False))

        if is_active_scan:
            txt = "🟡 Epson L3250 Scanning in progress..."
            fg = "#d97706"
        elif self.scanner_is_online:
            txt = f"🟢 {st.get('message', 'Epson L3250 Ready')}"
            fg = "#059669"
        else:
            txt = f"🔴 {st.get('message', 'Scanner Offline (Powered Off)')}"
            fg = "#ef4444"

        if hasattr(self, "lbl_scan_status"):
            if not getattr(self, "is_scanning", False) or is_active_scan:
                self.lbl_scan_status.config(text=txt, foreground=fg)
        if hasattr(self, "lbl_doc_scan_status"):
            if not getattr(self, "doc_is_scanning", False) or is_active_scan:
                self.lbl_doc_scan_status.config(text=txt, foreground=fg)
        if hasattr(self, "lbl_photo_scan_status"):
            if not getattr(self, "photo_is_scanning", False) or is_active_scan:
                self.lbl_photo_scan_status.config(text=txt, foreground=fg)

    # SCANNING & ABORT LOGIC
    def abort_scan(self):
        """Immediately aborts the in-progress scanner operation"""
        scanner.abort_current_scan()
        self.is_scanning = False
        self.set_scan_ui_busy(False)
        self.lbl_scan_status.config(text="⚠️ Scan aborted by user", foreground="#ef4444")
        if hasattr(self, "doc_is_scanning") and self.doc_is_scanning:
            self.doc_is_scanning = False
            if hasattr(self, "btn_doc_abort"):
                self.btn_doc_abort.pack_forget()
            if hasattr(self, "btn_doc_scan"):
                self.btn_doc_scan.pack(fill=tk.X, pady=(4, 2))
            if hasattr(self, "doc_prog_bar"):
                self.doc_prog_bar.stop()
                self.doc_prog_bar.pack_forget()
            if hasattr(self, "lbl_doc_scan_status"):
                self.lbl_doc_scan_status.config(text="⚠️ Scan aborted by user", foreground="#ef4444")
        if hasattr(self, "photo_is_scanning") and self.photo_is_scanning:
            self.photo_is_scanning = False
            if hasattr(self, "btn_photo_abort"):
                self.btn_photo_abort.pack_forget()
            if hasattr(self, "btn_photo_scan"):
                self.btn_photo_scan.pack(fill=tk.X, pady=(0, 4))
            if hasattr(self, "photo_prog_bar"):
                self.photo_prog_bar.stop()
                self.photo_prog_bar.pack_forget()
            if hasattr(self, "lbl_photo_scan_status"):
                self.lbl_photo_scan_status.config(text="⚠️ Scan aborted by user", foreground="#ef4444")

    def trigger_scan(self, side, callback=None):
        if self.is_scanning:
            messagebox.showwarning("Busy", "A scan is already in progress. Click 'Abort Scan' to cancel.")
            return

        if not getattr(self, "scanner_is_online", False):
            if not messagebox.askyesno("Scanner Offline", "Your Epson L3250 appears to be powered off or disconnected.\n\nPlease turn on the printer power switch.\n\nDo you want to attempt scanning anyway?"):
                return

        try:
            chosen_dpi = int(self.scan_dpi_var.get())
        except:
            chosen_dpi = 300

        self.is_scanning = True
        self.set_scan_ui_busy(True, f"Scanning {side.upper()} ({chosen_dpi} DPI) from Epson L3250...")

        def run_thread():
            import time
            out_file = os.path.join(tempfile.gettempdir(), f"scanned_{side}_{int(time.time())}.png")
            res = scanner.scan_from_epson(
                output_path=out_file,
                dpi=chosen_dpi,
                color_mode=1,
                do_autocrop=self.auto_crop_var.get()
            )

            self.root.after(0, lambda: self.handle_scan_result(side, res, callback))

        self.scan_thread = threading.Thread(target=run_thread, daemon=True)
        self.scan_thread.start()

    def handle_scan_result(self, side, res, callback=None):
        self.is_scanning = False
        self.set_scan_ui_busy(False)

        if res.get("aborted"):
            self.lbl_scan_status.config(text="⚠️ Scan aborted by user", foreground="#ef4444")
            if callback:
                callback(False)
            return

        if not res["success"]:
            messagebox.showerror("Scanner Error", res["error"])
            self.lbl_scan_status.config(text="Scan failed", foreground="#ef4444")
            if callback:
                callback(False)
            return

        try:
            img = Image.open(res["path"]).convert("RGB")
            crop_msg = " [Auto-Cropped]" if res.get("cropped") else ""
            if side == "front":
                self.front_img = img
                self.lbl_front_info.config(text=f"Scanned {img.width}x{img.height}{crop_msg}", foreground="#059669")
            else:
                self.back_img = img
                self.lbl_back_info.config(text=f"Scanned {img.width}x{img.height}{crop_msg}", foreground="#059669")

            self.detect_and_apply_aspect(side, img, res.get("ratio_type"), res.get("aspect"))
            self.lbl_scan_status.config(text=f"Scan of {side} successful!", foreground="#059669")
            if callback:
                callback(True)
        except Exception as e:
            messagebox.showerror("Error", f"Failed to load scanned image: {e}")
            if callback:
                callback(False)

    def set_scan_ui_busy(self, busy, msg="Scanning..."):
        if busy:
            self.lbl_scan_status.config(text=msg, foreground="#2563eb")
            self.prog_bar.pack(fill=tk.X, pady=(4, 0))
            self.prog_bar.start(10)
            self.btn_abort.pack(fill=tk.X, pady=(4, 0))
            self.btn_wizard.config(state=tk.DISABLED)
            self.btn_scan_front.config(state=tk.DISABLED)
            self.btn_scan_back.config(state=tk.DISABLED)
        else:
            self.prog_bar.stop()
            self.prog_bar.pack_forget()
            self.btn_abort.pack_forget()
            self.btn_wizard.config(state=tk.NORMAL)
            self.btn_scan_front.config(state=tk.NORMAL)
            self.btn_scan_back.config(state=tk.NORMAL)

    def start_two_sided_wizard(self):
        step1 = messagebox.askokcancel(
            "Step 1: Scan Front Side",
            "Please place the FRONT of your Aadhaar card on the Epson scanner glass and click OK.\n\n"
            "The card will be scanned and automatically cropped.\n(You can abort anytime with the red Abort button)"
        )
        if not step1:
            return

        def on_front_done(success):
            if not success:
                return
            step2 = messagebox.askokcancel(
                "Step 2: Scan Back Side",
                "Front side scanned successfully!\n\n"
                "Now please turn the card over to the BACK side on the scanner glass and click OK."
            )
            if not step2:
                return
            self.trigger_scan("back", on_back_done)

        def on_back_done(success):
            if success:
                messagebox.showinfo(
                    "Done!",
                    "Both sides have been scanned and arranged on A4!\n\n"
                    "Front is on the Top Half, Back is on the Bottom Half.\n"
                    "Click 'Print to Epson L3250' when ready."
                )

        self.trigger_scan("front", on_front_done)

    def on_aspect_mode_change(self):
        mode = self.aspect_mode.get()
        if mode == "auto":
            self.lbl_aspect_hint.config(text="⚡ Auto-Detect (4:3, 3:4, ID)")
        elif mode == "4:3":
            self.lbl_aspect_hint.config(text="4:3 Landscape Ratio")
        elif mode == "3:4":
            self.lbl_aspect_hint.config(text="3:4 Portrait Ratio")
        elif mode == "id_card":
            self.lbl_aspect_hint.config(text="Standard ID (85.6×54)")
        elif mode == "exact":
            self.lbl_aspect_hint.config(text="Exact Scanned Image")

        if self.front_img:
            self.detect_and_apply_aspect("front", self.front_img)
        else:
            if mode == "4:3":
                self.front_aspect = 4.0 / 3.0
                self.front_ratio_name = "4:3 Landscape"
            elif mode == "3:4":
                self.front_aspect = 3.0 / 4.0
                self.front_ratio_name = "3:4 Portrait"
            else:
                self.front_aspect = 85.6 / 54.0
                self.front_ratio_name = "Standard ID (85.6×54)"

        if self.back_img:
            self.detect_and_apply_aspect("back", self.back_img)
        else:
            if mode == "4:3":
                self.back_aspect = 4.0 / 3.0
                self.back_ratio_name = "4:3 Landscape"
            elif mode == "3:4":
                self.back_aspect = 3.0 / 4.0
                self.back_ratio_name = "3:4 Portrait"
            else:
                self.back_aspect = 85.6 / 54.0
                self.back_ratio_name = "Standard ID (85.6×54)"

        self.update_preview()

    def detect_and_apply_aspect(self, side, img, ratio_type=None, aspect=None):
        if not img:
            return
        raw_aspect = aspect if aspect is not None else (img.width / float(img.height))
        mode = self.aspect_mode.get()

        ratio = 85.6 / 54.0
        name = "Standard ID (85.6×54)"

        if mode == "auto":
            if ratio_type == "4:3" or (1.20 <= raw_aspect <= 1.45):
                ratio = 4.0 / 3.0
                name = "4:3 Landscape"
            elif ratio_type == "3:4" or (0.68 <= raw_aspect <= 0.82):
                ratio = 3.0 / 4.0
                name = "3:4 Portrait"
            elif ratio_type == "id_card_portrait" or (0.58 <= raw_aspect < 0.68):
                ratio = 54.0 / 85.6
                name = "Standard ID Portrait (54×85.6)"
            elif ratio_type == "id_card" or (1.48 <= raw_aspect <= 1.75):
                ratio = 85.6 / 54.0
                name = "Standard ID (85.6×54)"
            else:
                ratio = raw_aspect
                name = f"{raw_aspect:.2f}:1 Landscape" if raw_aspect >= 1.0 else f"1:{1.0/raw_aspect:.2f} Portrait"
        elif mode == "4:3":
            ratio = 4.0 / 3.0
            name = "4:3 Landscape"
        elif mode == "3:4":
            ratio = 3.0 / 4.0
            name = "3:4 Portrait"
        elif mode == "id_card":
            if raw_aspect < 0.9:
                ratio = 54.0 / 85.6
                name = "Standard ID Portrait (54×85.6)"
            else:
                ratio = 85.6 / 54.0
                name = "Standard ID (85.6×54)"
        elif mode == "exact":
            ratio = raw_aspect
            name = f"{raw_aspect:.2f}:1 Exact"

        if side == "front":
            self.front_aspect = ratio
            self.front_ratio_name = name
            lbl = self.lbl_front_info
        else:
            self.back_aspect = ratio
            self.back_ratio_name = name
            lbl = self.lbl_back_info

        if lbl:
            txt = lbl.cget("text")
            if " [" in txt:
                txt = txt.split(" [")[0]
            lbl.config(text=f"{txt} [{name}]")

        self.update_preview()

    def get_card_dimensions(self, side, scale_mm):
        ratio = self.front_aspect if side == "front" else self.back_aspect
        base_mm = self.card_width_mm.get()

        # Portrait document fitting: if ratio < 1.0, height is primary dimension
        if ratio >= 1.0:
            w_mm = base_mm
            h_mm = w_mm / ratio
        else:
            h_mm = base_mm
            w_mm = h_mm * ratio

        mid_mm = self.mid_line_pos_mm.get()
        max_w_mm = 190.0
        if side == "front":
            max_h_mm = max(20.0, mid_mm - 16.0)
        else:
            max_h_mm = max(20.0, (297.0 - mid_mm) - 16.0)

        if h_mm > max_h_mm:
            h_mm = max_h_mm
            w_mm = h_mm * ratio
        if w_mm > max_w_mm:
            w_mm = max_w_mm
            h_mm = w_mm / ratio

        w_px = max(10, int(round(w_mm * scale_mm)))
        h_px = max(10, int(round(h_mm * scale_mm)))
        return w_px, h_px

    def on_bottom_slider_change(self):
        bot_mm = self.bottom_pos_mm.get()
        self.lbl_bot.config(text=f"{bot_mm:.1f} mm")
        # Track offset from centered position in bottom half
        mid_mm = self.mid_line_pos_mm.get()
        _, b_h = self.get_card_dimensions("back", 1.0)
        bottom_region_h = 297.0 - mid_mm
        centered_bot = mid_mm + (bottom_region_h - b_h) / 2.0
        self.bottom_offset_from_center = bot_mm - centered_bot
        self.update_preview()

    def on_mid_line_change(self):
        mid_mm = self.mid_line_pos_mm.get()
        self.lbl_mid.config(text=f"{mid_mm:.1f} mm")

        if self.sync_bottom_with_mid.get():
            _, b_h = self.get_card_dimensions("back", 1.0)
            bottom_region_h = 297.0 - mid_mm
            centered_bot = mid_mm + (bottom_region_h - b_h) / 2.0
            target_bot = centered_bot + self.bottom_offset_from_center
            new_bot = max(mid_mm + 5.0, min(297.0 - b_h - 5.0, target_bot))
            self.bottom_pos_mm.set(round(new_bot, 1))
            self.lbl_bot.config(text=f"{new_bot:.1f} mm")
            if hasattr(self, 'slider_bot'):
                self.slider_bot.config(from_=max(10, mid_mm + 5), to=max(mid_mm + 15, 297 - b_h - 5))

        # Check top card collision
        _, f_h = self.get_card_dimensions("front", 1.0)
        if self.top_pos_mm.get() + f_h > mid_mm - 5.0:
            new_top = max(5.0, mid_mm - f_h - 5.0)
            self.top_pos_mm.set(round(new_top, 1))
            self.lbl_top.config(text=f"{new_top:.1f} mm")

        self.update_preview()

    def on_sync_bottom_toggle(self):
        if self.sync_bottom_with_mid.get():
            self.on_mid_line_change()

    def reset_mid_line(self):
        self.mid_line_pos_mm.set(148.5)
        self.lbl_mid.config(text="148.5 mm")
        self.bottom_offset_from_center = 0.0
        self.on_mid_line_change()

    def on_preset_change(self):
        preset = self.size_preset.get()
        if preset == "standard":
            self.card_width_mm.set(85.6)
        elif preset == "xerox":
            self.card_width_mm.set(110.0)
        elif preset == "large":
            self.card_width_mm.set(135.0)
        self.lbl_width.config(text=f"{self.card_width_mm.get():.1f} mm")
        self.update_preview()

    def on_custom_width(self):
        self.size_preset.set("")
        self.lbl_width.config(text=f"{self.card_width_mm.get():.1f} mm")
        self.update_preview()

    def load_image(self, side):
        path = filedialog.askopenfilename(
            title=f"Select {side.capitalize()} Aadhaar Image",
            filetypes=[("Image files", "*.jpg *.jpeg *.png *.webp *.bmp"), ("All files", "*.*")]
        )
        if not path:
            return
        try:
            img = Image.open(path).convert("RGB")
            if side == "front":
                self.front_img = img
                self.lbl_front_info.config(text=f"{os.path.basename(path)} ({img.width}x{img.height})", foreground="black")
            else:
                self.back_img = img
                self.lbl_back_info.config(text=f"{os.path.basename(path)} ({img.width}x{img.height})", foreground="black")
            self.detect_and_apply_aspect(side, img)
        except Exception as e:
            messagebox.showerror("Error", f"Failed to load image: {e}")

    def rotate(self, side, angle=-90):
        if side == "front" and self.front_img:
            self.front_img = self.front_img.rotate(angle, expand=True)
            self.detect_and_apply_aspect(side, self.front_img)
        elif side == "back" and self.back_img:
            self.back_img = self.back_img.rotate(angle, expand=True)
            self.detect_and_apply_aspect(side, self.back_img)

    def align_horizontal(self, side):
        img = self.front_img if side == "front" else self.back_img
        if img:
            if img.height > img.width:
                self.rotate(side, -90)
            else:
                messagebox.showinfo("Orientation", f"{side.capitalize()} card is already horizontal.\nUse the rotation buttons (⟲ 90°, ⟳ 90° or 🔄 180°) if text is upside down.")

    def clear_image(self, side):
        if side == "front":
            self.front_img = None
            self.front_aspect = 85.6 / 54.0
            self.front_ratio_name = "Standard ID (85.6×54)"
            self.lbl_front_info.config(text="No front image loaded", foreground="gray")
        else:
            self.back_img = None
            self.back_aspect = 85.6 / 54.0
            self.back_ratio_name = "Standard ID (85.6×54)"
            self.lbl_back_info.config(text="No back image loaded", foreground="gray")
        self.update_preview()

    def on_filter_preset_change(self, preset):
        self.color_mode.set(preset)
        if preset == "clean":
            self.brightness_var.set(4.0)
            self.contrast_var.set(18.0)
            self.sharpness_var.set(40.0)
            self.saturation_var.set(110.0)
            self.bg_whiten_var.set(45.0)
        elif preset == "color":
            self.brightness_var.set(0.0)
            self.contrast_var.set(0.0)
            self.sharpness_var.set(0.0)
            self.saturation_var.set(100.0)
            self.bg_whiten_var.set(0.0)
        elif preset == "sharp":
            self.brightness_var.set(2.0)
            self.contrast_var.set(25.0)
            self.sharpness_var.set(80.0)
            self.saturation_var.set(100.0)
            self.bg_whiten_var.set(35.0)
        elif preset == "vibrant":
            self.brightness_var.set(3.0)
            self.contrast_var.set(15.0)
            self.sharpness_var.set(30.0)
            self.saturation_var.set(145.0)
            self.bg_whiten_var.set(25.0)
        elif preset == "xerox":
            self.brightness_var.set(5.0)
            self.contrast_var.set(40.0)
            self.sharpness_var.set(50.0)
            self.saturation_var.set(0.0)
            self.bg_whiten_var.set(60.0)
        elif preset == "gray":
            self.brightness_var.set(0.0)
            self.contrast_var.set(10.0)
            self.sharpness_var.set(25.0)
            self.saturation_var.set(0.0)
            self.bg_whiten_var.set(20.0)
        self.on_filter_slider_change()

    def auto_enhance(self):
        self.on_filter_preset_change("clean")

    def reset_filters(self):
        self.edge_trim_var.set(0)
        self.on_filter_preset_change("color")

    def on_filter_slider_change(self):
        br = self.brightness_var.get()
        ct = self.contrast_var.get()
        sh = self.sharpness_var.get()
        bw = self.bg_whiten_var.get()
        tr = self.edge_trim_var.get()

        if hasattr(self, 'lbl_br'): self.lbl_br.config(text=f"{'+' if br > 0 else ''}{br:.0f}%")
        if hasattr(self, 'lbl_ct'): self.lbl_ct.config(text=f"{'+' if ct > 0 else ''}{ct:.0f}%")
        if hasattr(self, 'lbl_sh'): self.lbl_sh.config(text=f"{sh:.0f}%")
        if hasattr(self, 'lbl_bw'): self.lbl_bw.config(text=f"{bw:.0f}%")
        if hasattr(self, 'lbl_tr'): self.lbl_tr.config(text=f"{tr} px")

        self.update_preview()

    def process_image_filter(self, img):
        if not img:
            return None
        processed = img.copy()

        # 1. Edge margin trim if requested
        trim = int(self.edge_trim_var.get())
        if trim > 0 and processed.width > trim * 2 and processed.height > trim * 2:
            processed = processed.crop((trim, trim, processed.width - trim, processed.height - trim))

        mode = self.color_mode.get()
        preset = "clean"
        if mode == "xerox":
            preset = "xerox"
        elif mode == "gray":
            preset = "grayscale"
        elif mode == "original":
            preset = "original"
        elif mode == "vibrant":
            preset = "vibrant"

        return autocrop.enhance_document(
            processed,
            preset=preset,
            bg_whiten=self.bg_whiten_var.get(),
            contrast=self.contrast_var.get(),
            sharpness=self.sharpness_var.get(),
            saturation=self.saturation_var.get(),
            brightness=self.brightness_var.get()
        )

    def generate_a4_image(self, width_px, height_px, for_print=False):
        page = Image.new("RGBA", (width_px, height_px), (255, 255, 255, 255))
        draw = ImageDraw.Draw(page)

        scale_mm = width_px / 210.0
        f_w, f_h = self.get_card_dimensions("front", scale_mm)
        b_w, b_h = self.get_card_dimensions("back", scale_mm)

        f_cx = int(round(self.front_x_mm.get() * scale_mm))
        f_cy = int(round((self.top_pos_mm.get() + (f_h / scale_mm) / 2.0) * scale_mm))
        f_angle = self.front_canvas_angle.get()

        b_cx = int(round(self.back_x_mm.get() * scale_mm))
        b_cy = int(round((self.bottom_pos_mm.get() + (b_h / scale_mm) / 2.0) * scale_mm))
        b_angle = self.back_canvas_angle.get()

        # Helper to draw rotated card and border
        def draw_card(img, ratio_name, cx, cy, w, h, angle_deg, side_label):
            r_mm = self.get_corner_curve_mm()
            r_px = int(round(r_mm * scale_mm)) if r_mm > 0 else 0

            # 1. Image
            if img:
                proc = self.process_image_filter(img).convert("RGBA")
                if r_mm > 0:
                    proc = autocrop.apply_card_corner_curve(proc, radius_mm=r_mm)
                resized = proc.resize((w, h), Image.Resampling.LANCZOS)
                if angle_deg != 0:
                    rot = resized.rotate(-angle_deg, expand=True, resample=Image.Resampling.BICUBIC)
                else:
                    rot = resized
                rw, rh = rot.size
                tl_x = int(round(cx - rw / 2.0))
                tl_y = int(round(cy - rh / 2.0))
                page.paste(rot, (tl_x, tl_y), rot)
            else:
                self.draw_rotated_rect(draw, cx, cy, w, h, angle_deg, fill="#f8fafc", outline="#cbd5e1", width=1, radius_px=r_px)
                draw.text((cx - 60, cy - 6), f"[{side_label}: {ratio_name}]", fill="#64748b")

            # 2. Border
            border = self.border_style.get()
            if border == "solid":
                bw = max(1, int(round(scale_mm * 0.3)))
                self.draw_rotated_rect(draw, cx, cy, w, h, angle_deg, outline="black", width=bw, radius_px=r_px)
            elif border == "dashed":
                self.draw_rotated_rect(draw, cx, cy, w, h, angle_deg, outline="#475569", width=1, radius_px=r_px)

        draw_card(self.front_img, self.front_ratio_name, f_cx, f_cy, f_w, f_h, f_angle, "Front Side")
        draw_card(self.back_img, self.back_ratio_name, b_cx, b_cy, b_w, b_h, b_angle, "Back Side")

        # Central Separation / cut guideline
        should_draw = (self.show_mid_line.get() and not for_print) or (self.print_mid_line.get() and for_print)
        if should_draw:
            mid_y = int(round(self.mid_line_pos_mm.get() * scale_mm))
            line_color = "#ef4444" if for_print else "#cbd5e1"
            line_w = max(1, int(round(scale_mm * 0.15)))
            draw.line([(0, mid_y), (width_px, mid_y)], fill=line_color, width=line_w)

        return page.convert("RGB")

    def get_corner_curve_mm(self):
        val_str = self.corner_curve_var.get()
        if "3.18" in val_str:
            return 3.18
        elif "4.0" in val_str:
            return 4.0
        elif "2.0" in val_str:
            return 2.0
        elif "5.0" in val_str:
            return 5.0
        elif "0.0" in val_str or "Square" in val_str:
            return 0.0
        return 3.18

    def draw_rotated_rect(self, draw, cx, cy, w, h, angle_deg, outline="black", fill=None, width=1, radius_px=0):
        if angle_deg == 0 and radius_px > 0:
            x1 = int(round(cx - w / 2.0))
            y1 = int(round(cy - h / 2.0))
            x2 = int(round(cx + w / 2.0))
            y2 = int(round(cy + h / 2.0))
            draw.rounded_rectangle([(x1, y1), (x2, y2)], radius=radius_px, outline=outline, fill=fill, width=width)
            return
        import math
        rad = math.radians(angle_deg)
        cos_a = math.cos(rad)
        sin_a = math.sin(rad)
        hw = w / 2.0
        hh = h / 2.0
        corners = [(-hw, -hh), (hw, -hh), (hw, hh), (-hw, hh)]
        poly = []
        for (rx, ry) in corners:
            px = cx + rx * cos_a - ry * sin_a
            py = cy + rx * sin_a + ry * cos_a
            poly.append((int(round(px)), int(round(py))))
        if fill:
            draw.polygon(poly, fill=fill, outline=outline, width=width)
        else:
            draw.polygon(poly, outline=outline, width=width)

    def get_card_screen_info(self, side):
        """Returns (cx, cy, cw, ch, angle_deg, screen_corners_list) in canvas screen coordinates"""
        import math
        scale = getattr(self, 'preview_scale_mm', 1.0)
        ox = getattr(self, 'preview_origin_x', 0)
        oy = getattr(self, 'preview_origin_y', 0)

        cw, ch = self.get_card_dimensions(side, scale)
        if side == "front":
            x_mm = self.front_x_mm.get()
            y_mm = self.top_pos_mm.get()
            angle = self.front_canvas_angle.get()
        else:
            x_mm = self.back_x_mm.get()
            y_mm = self.bottom_pos_mm.get()
            angle = self.back_canvas_angle.get()

        cx = ox + x_mm * scale
        cy = oy + (y_mm + (ch / scale) / 2.0) * scale

        rad = math.radians(angle)
        cos_a = math.cos(rad)
        sin_a = math.sin(rad)
        hw = cw / 2.0
        hh = ch / 2.0
        local_corners = [(-hw, -hh), (hw, -hh), (hw, hh), (-hw, hh)]
        screen_corners = []
        for rx, ry in local_corners:
            px = cx + rx * cos_a - ry * sin_a
            py = cy + rx * sin_a + ry * cos_a
            screen_corners.append((px, py))

        return cx, cy, cw, ch, angle, screen_corners

    def on_canvas_hover(self, event):
        if getattr(self, 'drag_mode', None):
            return
        mx, my = event.x, event.y
        # Check corners first
        for side in ["front", "back"]:
            cx, cy, cw, ch, angle, corners = self.get_card_screen_info(side)
            for c_idx, (px, py) in enumerate(corners):
                dist = ((mx - px)**2 + (my - py)**2)**0.5
                if dist <= 12:
                    self.preview_canvas.config(cursor="crosshair")
                    return

        # Check inside cards
        for side in ["front", "back"]:
            cx, cy, cw, ch, angle, corners = self.get_card_screen_info(side)
            if self._point_in_poly(mx, my, corners):
                self.preview_canvas.config(cursor="fleur")
                return

        self.preview_canvas.config(cursor="")

    def _point_in_poly(self, x, y, poly):
        n = len(poly)
        inside = False
        p1x, p1y = poly[0]
        for i in range(n + 1):
            p2x, p2y = poly[i % n]
            if y > min(p1y, p2y):
                if y <= max(p1y, p2y):
                    if x <= max(p1x, p2x):
                        if p1y != p2y:
                            xinters = (y - p1y) * (p2x - p1x) / (p2y - p1y) + p1x
                        if p1x == p2x or x <= xinters:
                            inside = not inside
            p1x, p1y = p2x, p2y
        return inside

    def on_canvas_press(self, event):
        import math
        mx, my = event.x, event.y

        # Check corners first for rotate
        for side in ["front", "back"]:
            cx, cy, cw, ch, angle, corners = self.get_card_screen_info(side)
            for c_idx, (px, py) in enumerate(corners):
                dist = ((mx - px)**2 + (my - py)**2)**0.5
                if dist <= 14:
                    self.drag_mode = ("rotate", side)
                    self.drag_center = (cx, cy)
                    self.drag_start_mouse_angle = math.degrees(math.atan2(my - cy, mx - cx))
                    self.drag_start_card_angle = angle
                    self.preview_canvas.config(cursor="crosshair")
                    return

        # Check body for translation
        for side in ["front", "back"]:
            cx, cy, cw, ch, angle, corners = self.get_card_screen_info(side)
            if self._point_in_poly(mx, my, corners):
                self.drag_mode = ("move", side)
                self.drag_start_mouse = (mx, my)
                self.drag_start_card_pos = (
                    self.front_x_mm.get() if side == "front" else self.back_x_mm.get(),
                    self.top_pos_mm.get() if side == "front" else self.bottom_pos_mm.get()
                )
                self.preview_canvas.config(cursor="fleur")
                return

    def on_canvas_motion(self, event):
        import math
        if not getattr(self, 'drag_mode', None):
            return
        mode, side = self.drag_mode
        scale = getattr(self, 'preview_scale_mm', 1.0)

        if mode == "rotate":
            cx, cy = self.drag_center
            curr_angle = math.degrees(math.atan2(event.y - cy, event.x - cx))
            diff = curr_angle - self.drag_start_mouse_angle
            new_angle = self.drag_start_card_angle + diff
            while new_angle > 180: new_angle -= 360
            while new_angle < -180: new_angle += 360

            # Soft snap near cardinal angles
            for snap in [0, 90, -90, 180, -180]:
                if abs(new_angle - snap) < 3.0:
                    new_angle = snap
                    break

            new_angle = round(new_angle, 1)
            if side == "front":
                self.front_canvas_angle.set(new_angle)
            else:
                self.back_canvas_angle.set(new_angle)
            self.update_preview()
            self.update_canvas_info_labels()

        elif mode == "move":
            start_mx, start_my = self.drag_start_mouse
            start_cx, start_cy = self.drag_start_card_pos
            dx_mm = (event.x - start_mx) / scale
            dy_mm = (event.y - start_my) / scale

            new_x = round(start_cx + dx_mm, 1)
            new_y = round(start_cy + dy_mm, 1)

            cw_px, ch_px = self.get_card_dimensions(side, 1.0)
            cw_mm, ch_mm = float(cw_px), float(ch_px)
            new_x = max(cw_mm / 2.0, min(210.0 - cw_mm / 2.0, new_x))
            new_y = max(5.0, min(297.0 - ch_mm - 5.0, new_y))

            if side == "front":
                self.front_x_mm.set(new_x)
                self.top_pos_mm.set(new_y)
                self.lbl_top.config(text=f"{new_y:.0f} mm")
            else:
                self.back_x_mm.set(new_x)
                self.bottom_pos_mm.set(new_y)
                self.lbl_bot.config(text=f"{new_y:.0f} mm")
            self.update_preview()
            self.update_canvas_info_labels()

    def on_canvas_release(self, event):
        self.drag_mode = None
        self.preview_canvas.config(cursor="")
        self.save_settings(silent=True)

    def reset_card_transform(self, side, transform_type):
        if transform_type in ["pos", "all"]:
            mid_mm = self.mid_line_pos_mm.get()
            scale_1mm = 1.0
            f_w, f_h = self.get_card_dimensions("front", scale_1mm)
            b_w, b_h = self.get_card_dimensions("back", scale_1mm)
            if side == "front":
                self.front_x_mm.set(105.0)
                new_top = max(5.0, round((mid_mm - f_h) / 2.0, 1))
                self.top_pos_mm.set(new_top)
                self.lbl_top.config(text=f"{new_top:.0f} mm")
            else:
                self.back_x_mm.set(105.0)
                bot_region = 297.0 - mid_mm
                new_bot = max(mid_mm + 5.0, round(mid_mm + (bot_region - b_h) / 2.0, 1))
                self.bottom_pos_mm.set(new_bot)
                self.lbl_bot.config(text=f"{new_bot:.0f} mm")
        if transform_type in ["rot", "all"]:
            if side == "front":
                self.front_canvas_angle.set(0.0)
            else:
                self.back_canvas_angle.set(0.0)
        self.update_preview()
        self.update_canvas_info_labels()
        self.save_settings(silent=True)

    def update_canvas_info_labels(self):
        fx = self.front_x_mm.get()
        fa = self.front_canvas_angle.get()
        if hasattr(self, 'lbl_f_canvas_info'):
            self.lbl_f_canvas_info.config(text=f"Front: X: {fx:.1f}mm ({fa:.1f}°)")

        bx = self.back_x_mm.get()
        ba = self.back_canvas_angle.get()
        if hasattr(self, 'lbl_b_canvas_info'):
            self.lbl_b_canvas_info.config(text=f"Back: X: {bx:.1f}mm ({ba:.1f}°)")

    def update_preview(self):
        c_w = self.preview_canvas.winfo_width()
        c_h = self.preview_canvas.winfo_height()
        if c_w < 50 or c_h < 50:
            c_w, c_h = 600, 700

        pad = 20
        avail_w = c_w - pad * 2
        avail_h = c_h - pad * 2

        if avail_w / 210.0 < avail_h / 297.0:
            preview_w = int(avail_w)
            preview_h = int(round(preview_w * 297.0 / 210.0))
        else:
            preview_h = int(avail_h)
            preview_w = int(round(preview_h * 210.0 / 297.0))

        if preview_w < 50 or preview_h < 50:
            return

        scale_mm = preview_w / 210.0
        origin_x = (c_w - preview_w) // 2
        origin_y = (c_h - preview_h) // 2

        self.preview_scale_mm = scale_mm
        self.preview_origin_x = origin_x
        self.preview_origin_y = origin_y
        self.preview_w = preview_w
        self.preview_h = preview_h

        page_preview = self.generate_a4_image(preview_w, preview_h)
        self.tk_preview = ImageTk.PhotoImage(page_preview)

        self.preview_canvas.delete("all")
        self.preview_canvas.create_image(origin_x, origin_y, anchor="nw", image=self.tk_preview)

        # Draw 4 visual corner handle dots on each card
        for side in ["front", "back"]:
            cx, cy, cw, ch, angle, corners = self.get_card_screen_info(side)
            for px, py in corners:
                r = 4.5
                self.preview_canvas.create_oval(
                    px - r, py - r, px + r, py + r,
                    fill="#3b82f6", outline="white", width=1.5
                )

        self.lbl_top.config(text=f"{self.top_pos_mm.get():.0f} mm")
        self.lbl_bot.config(text=f"{self.bottom_pos_mm.get():.0f} mm")
        self.update_canvas_info_labels()

    def change_copies(self, delta):
        try:
            curr = int(self.copies_var.get())
        except (ValueError, tk.TclError):
            curr = 1
        new_val = max(1, min(99, curr + delta))
        self.set_copies(new_val)

    def set_copies(self, num):
        try:
            num = max(1, min(99, int(num)))
        except (ValueError, tk.TclError):
            num = 1
        self.copies_var.set(num)
        self.update_copies_ui()

    def on_copies_typed(self):
        try:
            val = self.copies_var.get()
            if 1 <= val <= 99:
                self.update_copies_ui()
        except (ValueError, tk.TclError):
            pass

    def on_copies_spin(self):
        self.root.after(10, self.update_copies_ui)

    def update_copies_ui(self):
        try:
            c = max(1, min(99, int(self.copies_var.get())))
        except (ValueError, tk.TclError):
            c = 1

        if c == 1:
            self.btn_print.config(text="🖨️ Print 1 Copy to Epson L3250")
            self.btn_pdf.config(text="📥 Save 300 DPI A4 PDF")
        else:
            self.btn_print.config(text=f"🖨️ Print {c} Copies to Epson L3250")
            self.btn_pdf.config(text=f"📥 Save 300 DPI A4 PDF ({c} Pages)")

    def save_pdf(self):
        if not self.front_img and not self.back_img:
            if not messagebox.askyesno("Confirm", "No images loaded. Do you still want to save empty placeholder PDF?"):
                return

        path = filedialog.asksaveasfilename(
            title="Save 300 DPI A4 PDF",
            defaultextension=".pdf",
            filetypes=[("PDF Document", "*.pdf")]
        )
        if not path:
            return

        try:
            copies = max(1, min(99, int(self.copies_var.get())))
        except:
            copies = 1

        try:
            highres_a4 = self.generate_a4_image(A4_WIDTH_PX, A4_HEIGHT_PX, for_print=True)
            if copies > 1:
                highres_a4.save(path, "PDF", resolution=float(DPI), save_all=True, append_images=[highres_a4] * (copies - 1))
            else:
                highres_a4.save(path, "PDF", resolution=float(DPI))

            copy_msg = f"{copies} page{'s' if copies > 1 else ''}"
            messagebox.showinfo("Success", f"A4 PDF saved successfully ({copy_msg}):\n{path}\n\nReady for Epson L3250 printing!")
        except Exception as e:
            messagebox.showerror("Error", f"Failed to save PDF: {e}")

    def print_now(self):
        try:
            copies = max(1, min(99, int(self.copies_var.get())))
        except:
            copies = 1

        try:
            temp_pdf = os.path.join(os.environ.get("TEMP", "."), f"HKMTECH_A4_Epson_Print_{copies}_copies.pdf")
            highres_a4 = self.generate_a4_image(A4_WIDTH_PX, A4_HEIGHT_PX, for_print=True)
            if copies > 1:
                highres_a4.save(temp_pdf, "PDF", resolution=float(DPI), save_all=True, append_images=[highres_a4] * (copies - 1))
            else:
                highres_a4.save(temp_pdf, "PDF", resolution=float(DPI))

            if sys.platform == "win32":
                os.startfile(temp_pdf)
                copy_str = f"{copies} copies" if copies > 1 else "1 copy"
                messagebox.showinfo(
                    "Print Ready",
                    f"Print document generated with {copy_str} and opened.\n\n"
                    "1. Target: Epson L3250 Series\n"
                    "2. Paper Size: A4\n"
                    "3. Scale: 100% (Actual Size)\n"
                    f"4. The document contains {copies} identical A4 page(s).\n"
                    "5. Click Print in the viewer!"
                )
        except Exception as e:
            messagebox.showerror("Error", f"Failed to launch print: {e}")

    def load_settings(self):
        settings_path = os.path.join(CURRENT_DIR, "settings.json")
        if not os.path.exists(settings_path):
            return
        try:
            with open(settings_path, "r", encoding="utf-8") as f:
                data = json.load(f)
            if not isinstance(data, dict):
                return

            if "scanDpi" in data:
                self.scan_dpi_var.set(str(data["scanDpi"]))
                if hasattr(self, 'doc_scan_dpi'):
                    self.doc_scan_dpi.set(str(data["scanDpi"]))
            if "autoCrop" in data:
                self.auto_crop_var.set(bool(data["autoCrop"]))
            if "aspectMode" in data:
                self.aspect_mode.set(str(data["aspectMode"]))
            if "sizePreset" in data:
                self.size_preset.set(str(data["sizePreset"]))
            if "cardWidthMm" in data:
                self.card_width_mm.set(float(data["cardWidthMm"]))
            elif "widthMm" in data:
                self.card_width_mm.set(float(data["widthMm"]))
            if "topPosMm" in data:
                self.top_pos_mm.set(float(data["topPosMm"]))
            if "bottomPosMm" in data:
                self.bottom_pos_mm.set(float(data["bottomPosMm"]))
            if "frontXMm" in data:
                self.front_x_mm.set(float(data["frontXMm"]))
            if "backXMm" in data:
                self.back_x_mm.set(float(data["backXMm"]))
            if "frontAngle" in data:
                self.front_canvas_angle.set(float(data["frontAngle"]))
            if "backAngle" in data:
                self.back_canvas_angle.set(float(data["backAngle"]))
            if "borderStyle" in data:
                self.border_style.set(str(data["borderStyle"]))
            elif "borderClass" in data:
                bc = data["borderClass"]
                self.border_style.set("dashed" if bc == "border-cut" else ("none" if bc == "border-none" else "solid"))
            if "colorMode" in data:
                self.color_mode.set(str(data["colorMode"]))
            if "brightness" in data:
                self.brightness_var.set(float(data["brightness"]))
            if "contrast" in data:
                self.contrast_var.set(float(data["contrast"]))
            if "sharpness" in data:
                self.sharpness_var.set(float(data["sharpness"]))
            if "saturation" in data:
                self.saturation_var.set(float(data["saturation"]))
            if "bgWhiten" in data:
                self.bg_whiten_var.set(float(data["bgWhiten"]))
            if "edgeTrimPx" in data:
                self.edge_trim_var.set(int(data["edgeTrimPx"]))
            if "midLinePosMm" in data:
                self.mid_line_pos_mm.set(float(data["midLinePosMm"]))
            if "showMidLine" in data:
                self.show_mid_line.set(bool(data["showMidLine"]))
            if "printMidLine" in data:
                self.print_mid_line.set(bool(data["printMidLine"]))
            if "syncBottomWithMid" in data:
                self.sync_bottom_with_mid.set(bool(data["syncBottomWithMid"]))
            if "copies" in data:
                self.copies_var.set(max(1, min(99, int(data["copies"]))))
            if "autoSave" in data:
                self.auto_save_var.set(bool(data["autoSave"]))

            # Refresh labels and visual controls
            if hasattr(self, 'lbl_mid'):
                self.lbl_mid.config(text=f"{self.mid_line_pos_mm.get():.1f} mm")
            if hasattr(self, 'lbl_top'):
                self.lbl_top.config(text=f"{self.top_pos_mm.get():.0f} mm")
            if hasattr(self, 'lbl_bot'):
                self.lbl_bot.config(text=f"{self.bottom_pos_mm.get():.0f} mm")
            if hasattr(self, 'update_canvas_info_labels'):
                self.update_canvas_info_labels()
            if hasattr(self, 'on_filter_slider_change'):
                self.on_filter_slider_change()
            if hasattr(self, 'update_copies_ui'):
                self.update_copies_ui()
            if hasattr(self, 'lbl_save_status'):
                self.lbl_save_status.config(text="✓ Settings loaded from settings.json", foreground="#059669")
        except Exception as e:
            print(f"Error loading settings: {e}")

    def get_settings_dict(self):
        try:
            dpi_val = int(self.scan_dpi_var.get())
        except:
            dpi_val = 300
        try:
            copies_val = int(self.copies_var.get())
        except:
            copies_val = 1

        b_style = str(self.border_style.get())
        b_class = "border-cut" if b_style == "dashed" else ("border-none" if b_style == "none" else "border-solid")

        return {
            "scanDpi": dpi_val,
            "autoCrop": bool(self.auto_crop_var.get()),
            "aspectMode": str(self.aspect_mode.get()),
            "sizePreset": str(self.size_preset.get()),
            "cardWidthMm": float(self.card_width_mm.get()),
            "topPosMm": float(self.top_pos_mm.get()),
            "bottomPosMm": float(self.bottom_pos_mm.get()),
            "frontXMm": float(self.front_x_mm.get()),
            "backXMm": float(self.back_x_mm.get()),
            "frontAngle": float(self.front_canvas_angle.get()),
            "backAngle": float(self.back_canvas_angle.get()),
            "borderStyle": b_style,
            "borderClass": b_class,
            "colorMode": str(self.color_mode.get()),
            "brightness": float(self.brightness_var.get()),
            "contrast": float(self.contrast_var.get()),
            "sharpness": float(self.sharpness_var.get()),
            "saturation": float(self.saturation_var.get()),
            "bgWhiten": float(self.bg_whiten_var.get()),
            "edgeTrimPx": int(self.edge_trim_var.get()),
            "midLinePosMm": float(self.mid_line_pos_mm.get()),
            "showMidLine": bool(self.show_mid_line.get()),
            "printMidLine": bool(self.print_mid_line.get()),
            "syncBottomWithMid": bool(self.sync_bottom_with_mid.get()),
            "copies": max(1, min(99, copies_val)),
            "autoSave": bool(self.auto_save_var.get())
        }

    def save_settings(self, silent=False):
        settings_path = os.path.join(CURRENT_DIR, "settings.json")
        try:
            data = self.get_settings_dict()
            with open(settings_path, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=2)
            if hasattr(self, "lbl_save_status"):
                self.lbl_save_status.config(text="✓ Settings saved to settings.json", foreground="#059669")
            if not silent:
                messagebox.showinfo("Settings Saved", "Your settings have been saved successfully!\n\nThey will be restored automatically every time you start the app.")
        except Exception as e:
            if not silent:
                messagebox.showerror("Error", f"Failed to save settings: {e}")

    def reset_settings(self):
        if not messagebox.askyesno("Confirm Reset", "Reset all settings to factory defaults?"):
            return
        self.scan_dpi_var.set("300")
        self.auto_crop_var.set(True)
        self.aspect_mode.set("auto")
        self.size_preset.set("standard")
        self.card_width_mm.set(85.6)
        self.top_pos_mm.set(48.0)
        self.bottom_pos_mm.set(196.0)
        self.front_x_mm.set(105.0)
        self.back_x_mm.set(105.0)
        self.front_canvas_angle.set(0.0)
        self.back_canvas_angle.set(0.0)
        self.update_canvas_info_labels()
        self.border_style.set("none")
        self.color_mode.set("clean")
        self.brightness_var.set(4.0)
        self.contrast_var.set(18.0)
        self.sharpness_var.set(40.0)
        self.saturation_var.set(110.0)
        self.bg_whiten_var.set(45.0)
        self.edge_trim_var.set(0)
        self.mid_line_pos_mm.set(148.5)
        self.show_mid_line.set(True)
        self.print_mid_line.set(False)
        self.sync_bottom_with_mid.set(True)
        self.copies_var.set(1)
        self.auto_save_var.set(True)

        self.update_copies_ui()
        self.on_filter_preset_change("clean")
        self.on_mid_line_change()
        self.save_settings(silent=True)
        if hasattr(self, "lbl_save_status"):
            self.lbl_save_status.config(text="↺ Reset to defaults", foreground="#2563eb")
        messagebox.showinfo("Reset", "Settings have been reset to factory defaults.")

    def on_closing(self):
        if self.auto_save_var.get():
            self.save_settings(silent=True)
        self.root.destroy()

if __name__ == "__main__":
    root = tk.Tk()
    app = AadhaarPrinterApp(root)
    root.mainloop()
