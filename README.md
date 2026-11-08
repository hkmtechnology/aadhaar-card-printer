# HKMTECH - 2-in-1 Card & Document Printer Suite (for Epson EcoTank L3250)

A high-performance, offline-first scanning, photo enhancement, card layout, and multi-page document printing suite developed for the **Epson EcoTank L3250** series (and compatible WIA scanners).

---

## 🌟 Modules & Features

### 🪪 1. 2-in-1 ID Card Mode
- **Dual-Side Formatting**: Effortlessly place Front on the top half and Back on the bottom half of standard A4 paper.
- **Tight Edge-Snapping CamScanner Auto-Crop**: Detects the subtle physical thickness of Aadhaar, Voter, and PAN cards against the white scanner lid with zero background flatbed waste.
- **CamScanner Perspective Tilt Deskew**: Straightens cards placed tilted at arbitrary angles (5°, 15°, 30°, 45°, 75°, 90°) even when touching the scanner bed rulers.
- **Intelligent Text Horizontalness & Automatic Portrait Alignment**: Analyzes text stroke direction and ensures text lines are horizontal and upright (emblem & header at top). Automatically sets standard ID card portrait (54×85.6 mm) or landscape (85.6×54 mm) dimensions.
- **Interactive Manual Crop Adjustment Modal**: 8-point corner and edge handles with live aspect ratio snapping (Standard ID, Portrait, 4:3, 3:4, Free) and on-demand text orientation.
- **Card Corner Curves**: Standard PVC Card rounded corner curve (3.18 mm radius) matching physical ID cards.
- **Interactive Canvas Drag & Corner Rotation**: Click & drag cards directly on the simulated A4 sheet, with magnetic snap to cardinal angles.
- **Movable Central Separation Guideline**: Adjustable divider slider (70–230 mm) with automatic synchronized centering of the bottom half.

### 📸 2. Passport Photo Studio
- **Custom A4 Grid Design**: Place multiple passport photos across standard A4 photo paper.
- **Customizable Margins**: Edit top, left, horizontal, and vertical margins with precision down to 1 mm increments.
- **Photo Enhancement**: Built-in portrait contrast, skin tone balance, lighting, and border guides.

### 📑 3. Multi-Page Document Scanner
- **Full A4 & Small Receipts/Slips**: Batch scan multi-page documents, contracts, certificates, and invoices.
- **Magic Clean Whiten Filter**: Whitens scanner background to pure white ($255, 255, 255$) to conserve printer ink while keeping text and logos ultra-crisp.
- **Multi-Page 300 DPI PDF Export**: Save scanned sets directly as high-resolution PDFs.
- **Direct Multi-Page Printing**: Seamlessly sends paginated prints directly to the Epson L3250.

---

## 🖥️ Layout Overview

| **Left Sidebar (Inputs & Scans)** | **Center Workspace (Live Simulation)** | **Right Sidebar (Adjustments & Print)** |
|:---|:---:|:---|
| ⚡ **Automatic Scanner Integration** (1-Click Guided Scan, Scan DPI selector 150–600, instant Abort, Auto-Crop switch) | 📄 **Live A4 Paper Simulation** (True 210 × 297 mm ratio, interactive Zoom controls) | 📏 **Card Size & Alignment** (Wallet ID 85.6 mm, Xerox 110 mm, Large 135 mm, position sliders) |
| 🪪 **1. Front & Back Sides** (Scan Front / Choose file, Scan Back / Choose file) | ✂️ **Real-time Guidelines** (Front half, Back half, movable red separation line) | ✂️ **Central Separation Line** (Slider 70–230 mm, reset, linked bottom placeholder) |
| 🔄 **Rotation & Alignment Tools** (⟲ 90°, ⟳ 90°, 🔄 180°, 📐 Orient by Text, 🔄 To Portrait, 🔄 To Landscape) | 🖼️ **Card Placeholders** (Slot tags, live card previews, drag-and-drop support) | ✨ **Image Enhancement Filters** (Magic Clean, Ultra Sharp, Vibrant, Xerox, Grayscale, Margin Trim) |
| 📐 **2. Card Aspect Ratio** (Auto-detect 4:3, 3:4, Standard ID, or exact scan shape) | 🎯 **WYSIWYG Accuracy** (Identical to final Epson L3250 printout) | 💾 **Preferences & Saved Settings** (Auto-save toggle, Save Defaults, Reset Defaults) |
| — | — | 🖨️ **Action Controls** (Copies selector 1–99, Print 1-Click, Save A4 PDF, Save Image) |

---

## 🚀 How to Launch the App

### Option 1: Modern Web Desktop App (Recommended)
👉 Double-click **`Start_App.bat`**
- Starts the local Python bridge server and opens the application in clean standalone app mode.

### Option 2: Python Desktop GUI
👉 Double-click **`Start_Desktop_GUI.bat`**
- Native Tkinter desktop GUI alternative.

---

## 🛠️ System Requirements & Setup

- **Operating System**: Windows 10 / Windows 11 (64-bit)
- **Python**: Python 3.9+
- **Scanner**: Epson EcoTank L3250 Series (or any WIA-compatible scanner)
- **Install Python Dependencies**:
  ```bash
  pip install -r requirements.txt
  ```

---

## 🖨️ Recommended Print Settings (Epson L3250)

- **Paper Size**: A4
- **Orientation**: Portrait
- **Scale**: **100% / Actual Size** (preserves exact 85.6 × 54.0 mm wallet card dimensions)
- **Quality**: Standard or High
- **Color**: Color or Grayscale
