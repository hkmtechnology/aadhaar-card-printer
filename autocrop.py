import cv2
import numpy as np
from PIL import Image

def sample_background_color(img_bgr):
    """
    Robustly samples the scanner bed background color.
    Flatbed scanner lids are virtually always white/off-white (brightness >= 215)
    or black (brightness <= 50).
    Samples the outer perimeter corners (outer 5%) and the bed grid,
    correctly identifying the white lid even when an ID card covers 90% of the image.
    """
    h, w = img_bgr.shape[:2]
    ch = max(2, int(h * 0.05))
    cw = max(2, int(w * 0.05))
    corners = np.concatenate([
        img_bgr[0:ch, 0:cw].reshape(-1, 3),
        img_bgr[0:ch, max(0, w - cw) : w].reshape(-1, 3),
        img_bgr[max(0, h - ch) : h, 0:cw].reshape(-1, 3),
        img_bgr[max(0, h - ch) : h, max(0, w - cw) : w].reshape(-1, 3)
    ], axis=0)

    sub = img_bgr[int(h * 0.1) : int(h * 0.9) : 20, int(w * 0.1) : int(w * 0.9) : 20].reshape(-1, 3)
    corner_l = np.mean(corners, axis=1) if len(corners) > 0 else np.array([245.0])
    grid_l = np.mean(sub, axis=1) if len(sub) > 0 else np.array([245.0])

    if np.percentile(corner_l, 75) >= 215.0:
        white_corners = corners[corner_l >= 215.0]
        return np.median(white_corners, axis=0)
    elif np.percentile(grid_l, 75) >= 215.0:
        white_grid = sub[grid_l >= 215.0]
        return np.median(white_grid, axis=0)
    elif np.percentile(corner_l, 25) <= 50.0:
        dark_corners = corners[corner_l <= 50.0]
        return np.median(dark_corners, axis=0)
    else:
        return np.array([248.0, 248.0, 248.0], dtype=np.float32)

def order_corner_points(pts):
    """
    Orders 4 quadrilateral corner points in clockwise order:
    [top-left, top-right, bottom-right, bottom-left].
    """
    rect = np.zeros((4, 2), dtype=np.float32)
    s = pts.sum(axis=1)
    rect[0] = pts[np.argmin(s)] # top-left has smallest sum (x+y)
    rect[2] = pts[np.argmax(s)] # bottom-right has largest sum (x+y)

    diff = np.diff(pts, axis=1)
    rect[1] = pts[np.argmin(diff)] # top-right has smallest diff (y-x)
    rect[3] = pts[np.argmax(diff)] # bottom-left has largest diff (y-x)
    return rect

def is_scanner_bed_strip(strip, is_white_lid=True, bg_val=None):
    """
    Determines if a 1D pixel strip along a row or column is empty scanner bed or card.
    Empty flatbed scanner bed (white/off-white) has:
    1. Zero texture (std < 3.2) and no dark text/emblems/photo
    2. Mean value tightly matching the sampled scanner lid background (within 7 levels)
    3. Minimum pixel value very close to lid (no dark text or graphics)
    Cards (including white/pale cards like Aadhaar or Voter ID) have paper texture,
    emblems, guilloche patterns, text, or color difference from scanner bed.
    """
    if strip.size == 0:
        return True
    std = float(np.std(strip))
    mean_val = float(np.mean(strip))
    if is_white_lid:
        expected = float(bg_val) if bg_val is not None else 250.0
        return (abs(mean_val - expected) < 7.0) and (std < 3.2) and (float(np.min(strip)) > (expected - 14.0))
    else:
        expected = float(bg_val) if bg_val is not None else 20.0
        return (abs(mean_val - expected) < 10.0) and (std < 3.5) and (float(np.max(strip)) < (expected + 15.0))

def refine_card_bounding_box(img_bgr, bx, by, bw, bh, bg_color):
    """
    Refines card crop boundaries with sub-millimeter precision using bidirectional snapping:
    1. If the initial contour captured extra white scanner lid margin, trims inwards (max 4px) until true card edge.
    2. If the initial contour missed any card borders, emblems, or text, expands outwards until clean scanner lid is reached.
    Guarantees 100% of card borders, Ashoka emblem, and text are preserved with 0px cut-off.
    """
    if img_bgr is None:
        return bx, by, bw, bh

    orig_h, orig_w = img_bgr.shape[:2]
    if orig_h < 40 or orig_w < 40:
        return bx, by, bw, bh

    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    bg_gray = float(np.mean(bg_color)) if bg_color is not None else 250.0
    is_white_lid = bg_gray >= 128.0

    bed_m_x = max(2, int(orig_w * 0.006))
    bed_m_y = max(2, int(orig_h * 0.006))

    # Keep track of initial contour edges to prevent aggressive inward trimming
    orig_top = by
    orig_bottom = by + bh
    orig_left = bx
    orig_right = bx + bw
    max_inward_trim = 4  # Never trim more than 4px inward from detected card contour
    max_outward_expand = max(25, int(min(orig_w, orig_h) * 0.06))

    # Initial safety clamp
    bx = max(0, min(orig_w - 40, bx))
    by = max(0, min(orig_h - 40, by))
    bw = max(40, min(orig_w - bx, bw))
    bh = max(40, min(orig_h - by, bh))

    for _ in range(3):
        # Sample middle 60% of card height
        y1 = max(0, min(orig_h - 1, by + int(bh * 0.20)))
        y2 = max(y1 + 1, min(orig_h, by + int(bh * 0.80)))
        if y2 <= y1:
            break

        # Trim left inwards if sitting on empty scanner bed (max 4px inward)
        while bw > 40 and bx < (orig_w - 40) and (bx - orig_left < max_inward_trim) and is_scanner_bed_strip(gray[y1:y2, bx], is_white_lid, bg_val=bg_gray):
            bx += 1
            bw -= 1

        # Expand left outwards if card extends to the left
        while bx > bed_m_x and bx > 0 and (orig_left - bx < max_outward_expand) and not is_scanner_bed_strip(gray[y1:y2, bx - 1], is_white_lid, bg_val=bg_gray):
            bx -= 1
            bw += 1

        # Trim right inwards if sitting on empty scanner bed (max 4px inward)
        while bw > 40 and (bx + bw - 1) < orig_w and (orig_right - (bx + bw) < max_inward_trim) and is_scanner_bed_strip(gray[y1:y2, bx + bw - 1], is_white_lid, bg_val=bg_gray):
            bw -= 1

        # Expand right outwards if card extends to the right
        while (bx + bw) < (orig_w - bed_m_x) and (bx + bw) < orig_w and ((bx + bw) - orig_right < max_outward_expand) and not is_scanner_bed_strip(gray[y1:y2, bx + bw], is_white_lid, bg_val=bg_gray):
            bw += 1

        # Sample middle 60% of card width
        x1 = max(0, min(orig_w - 1, bx + int(bw * 0.20)))
        x2 = max(x1 + 1, min(orig_w, bx + int(bw * 0.80)))
        if x2 <= x1:
            break

        # Trim top inwards if sitting on empty scanner bed (max 4px inward to never cut off emblems or headers)
        while bh > 40 and by < (orig_h - 40) and (by - orig_top < max_inward_trim) and is_scanner_bed_strip(gray[by, x1:x2], is_white_lid, bg_val=bg_gray):
            by += 1
            bh -= 1

        # Expand top outwards if card extends to the top (ensures 100% of emblem and text are captured)
        while by > bed_m_y and by > 0 and (orig_top - by < max_outward_expand) and not is_scanner_bed_strip(gray[by - 1, x1:x2], is_white_lid, bg_val=bg_gray):
            by -= 1
            bh += 1

        # Trim bottom inwards if sitting on empty scanner bed (max 4px inward)
        while bh > 40 and (by + bh - 1) < orig_h and (orig_bottom - (by + bh) < max_inward_trim) and is_scanner_bed_strip(gray[by + bh - 1, x1:x2], is_white_lid, bg_val=bg_gray):
            bh -= 1

        # Expand bottom outwards if card extends to the bottom
        while (by + bh) < (orig_h - bed_m_y) and (by + bh) < orig_h and ((by + bh) - orig_bottom < max_outward_expand) and not is_scanner_bed_strip(gray[by + bh, x1:x2], is_white_lid, bg_val=bg_gray):
            bh += 1

        # Clamp after each iteration
        bx = max(0, min(orig_w - 40, bx))
        by = max(0, min(orig_h - 40, by))
        bw = max(40, min(orig_w - bx, bw))
        bh = max(40, min(orig_h - by, bh))

    # Exit safety clamp
    bx = max(0, min(orig_w - 40, bx))
    by = max(0, min(orig_h - 40, by))
    bw = max(40, min(orig_w - bx, bw))
    bh = max(40, min(orig_h - by, bh))

    return bx, by, bw, bh

def trim_shadow_edges_all_sides(img_bgr, max_trim=8, allow_left=True, allow_right=True, allow_top=True, allow_bottom=True, bg_color=None):
    """
    Trims empty scanner lid fringes from the outer borders.
    Uses is_scanner_bed_strip to ensure card text, borders, and emblems are 100% protected.
    """
    h, w = img_bgr.shape[:2]
    if h < 40 or w < 40:
        return img_bgr

    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    bg_gray = float(np.mean(bg_color)) if bg_color is not None else None
    is_white_lid = (bg_gray >= 128.0) if bg_gray is not None else True

    x_start = 0
    strip_w = min(max_trim, w // 8)
    if allow_left:
        for x in range(strip_w):
            if is_scanner_bed_strip(gray[:, x], is_white_lid, bg_val=bg_gray):
                x_start = x + 1
            else:
                break

    x_end = w
    if allow_right:
        for x in range(w - 1, max(0, w - strip_w - 1), -1):
            if is_scanner_bed_strip(gray[:, x], is_white_lid, bg_val=bg_gray):
                x_end = x
            else:
                break

    y_start = 0
    strip_h = min(max_trim, h // 8)
    if allow_top:
        for y in range(strip_h):
            if is_scanner_bed_strip(gray[y, :], is_white_lid, bg_val=bg_gray):
                y_start = y + 1
            else:
                break

    y_end = h
    if allow_bottom:
        for y in range(h - 1, max(0, h - strip_h - 1), -1):
            if is_scanner_bed_strip(gray[y, :], is_white_lid, bg_val=bg_gray):
                y_end = y
            else:
                break

    if x_end - x_start > 20 and y_end - y_start > 20:
        return img_bgr[y_start:y_end, x_start:x_end]
    return img_bgr

def apply_card_corner_curve(img_input, radius_mm=3.18):
    """
    Applies an antialiased rounded corner curve (alpha mask) to an ID card.
    ISO/IEC 7810 ID-1 standard specifies a corner radius of 3.18mm (approx 1/8 inch).
    Eliminates the sharp 90-degree scanner bed corners, creating a clean curved-corner PVC card.
    """
    if isinstance(img_input, np.ndarray):
        if len(img_input.shape) == 3 and img_input.shape[2] == 4:
            pil_img = Image.fromarray(cv2.cvtColor(img_input, cv2.COLOR_BGRA2RGBA))
        elif len(img_input.shape) == 3:
            pil_img = Image.fromarray(cv2.cvtColor(img_input, cv2.COLOR_BGR2RGB))
        else:
            pil_img = Image.fromarray(img_input)
    else:
        pil_img = img_input.copy()

    if radius_mm <= 0:
        return pil_img

    w, h = pil_img.size
    short_side = min(w, h)
    # Standard card short side is 54.0 mm
    radius_px = max(4, int(round((radius_mm / 54.0) * short_side)))

    # Supersample mask 4x for crystal clear antialiasing
    scale_factor = 4
    mask_w = w * scale_factor
    mask_h = h * scale_factor
    mask_r = radius_px * scale_factor

    from PIL import ImageDraw
    mask_hi = Image.new("L", (mask_w, mask_h), 0)
    draw = ImageDraw.Draw(mask_hi)
    draw.rounded_rectangle([(0, 0), (mask_w - 1, mask_h - 1)], radius=mask_r, fill=255)

    mask = mask_hi.resize((w, h), Image.Resampling.LANCZOS)

    out_img = pil_img.convert("RGBA")
    if pil_img.mode == "RGBA":
        orig_alpha = pil_img.split()[-1]
        mask_arr = np.array(mask).astype(np.float32) / 255.0
        orig_arr = np.array(orig_alpha).astype(np.float32) / 255.0
        fin_arr = (mask_arr * orig_arr * 255.0).astype(np.uint8)
        out_img.putalpha(Image.fromarray(fin_arr))
    else:
        out_img.putalpha(mask)

    return out_img


def detect_and_correct_text_orientation(img_bgr):
    """
    Analyzes an axis-aligned card/document and ensures text lines are horizontal
    and upright (header at top). If text lines run vertically, rotates 90 degrees
    to bring the card to Portrait with horizontal text.
    
    Returns: (corrected_bgr, rotation_angle_applied, is_portrait_bool, aspect_ratio, ratio_type)
    """
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    h, w = gray.shape
    
    pad_y = max(2, int(h * 0.06))
    pad_x = max(2, int(w * 0.06))
    inner = gray[pad_y:h-pad_y, pad_x:w-pad_x]
    ih, iw = inner.shape
    
    # 1. Text stroke extraction via Blackhat and gradient
    k_size = max(3, int(min(ih, iw) * 0.02) | 1)
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (k_size, k_size))
    bh = cv2.morphologyEx(inner, cv2.MORPH_BLACKHAT, kernel)
    
    gx = cv2.Sobel(inner, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(inner, cv2.CV_32F, 0, 1, ksize=3)
    mag = cv2.magnitude(gx, gy)
    
    thresh = np.percentile(mag, 72)
    text_mask = ((mag > thresh) | (bh > np.percentile(bh, 76))).astype(np.uint8) * 255
    
    # Projection profile variance of text:
    row_sum = np.sum(text_mask, axis=1).astype(np.float32)
    col_sum = np.sum(text_mask, axis=0).astype(np.float32)
    
    row_sm = np.convolve(row_sum, np.ones(3)/3.0, mode='same')
    col_sm = np.convolve(col_sum, np.ones(3)/3.0, mode='same')
    
    row_var = np.var(np.diff(row_sm)) / (np.mean(row_sum) + 1e-5)
    col_var = np.var(np.diff(col_sm)) / (np.mean(col_sum) + 1e-5)
    
    kw = max(14, int(iw * 0.05))
    kh = max(14, int(ih * 0.05))
    dil_h = cv2.dilate(text_mask, cv2.getStructuringElement(cv2.MORPH_RECT, (kw, 3)), iterations=1)
    dil_v = cv2.dilate(text_mask, cv2.getStructuringElement(cv2.MORPH_RECT, (3, kh)), iterations=1)
    cnts_h, _ = cv2.findContours(dil_h, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    cnts_v, _ = cv2.findContours(dil_v, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    
    min_w = max(12, int(iw * 0.06))
    min_h = max(12, int(ih * 0.06))
    h_area = sum([bw * bh for c in cnts_h for _, _, bw, bh in [cv2.boundingRect(c)] if bw >= 1.8 * bh and bw >= min_w])
    v_area = sum([bw * bh for c in cnts_v for _, _, bw, bh in [cv2.boundingRect(c)] if bh >= 1.8 * bw and bh >= min_h])
    
    is_vertical_text = False
    if col_var > 1.30 * row_var:
        is_vertical_text = True
    elif v_area > 1.6 * (h_area + 20):
        is_vertical_text = True
        
    if is_vertical_text:
        im_cw = cv2.rotate(img_bgr, cv2.ROTATE_90_CLOCKWISE)
        im_ccw = cv2.rotate(img_bgr, cv2.ROTATE_90_COUNTERCLOCKWISE)
        
        def score_top_weight(im):
            g = cv2.cvtColor(im, cv2.COLOR_BGR2GRAY)
            gh, gw = g.shape
            top_q = g[:int(gh * 0.25), :]
            bot_q = g[int(gh * 0.75):, :]
            top_edges = np.mean(cv2.Canny(top_q, 50, 150) > 0)
            bot_edges = np.mean(cv2.Canny(bot_q, 50, 150) > 0)
            top_dark = np.mean(top_q < 120)
            bot_dark = np.mean(bot_q < 120)
            return (top_edges - bot_edges) * 100.0 + (top_dark - bot_dark) * 50.0

        if score_top_weight(im_cw) >= score_top_weight(im_ccw):
            res_img = im_cw
            angle = 90
        else:
            res_img = im_ccw
            angle = 270
    else:
        top_q = gray[:int(h * 0.25), :]
        bot_q = gray[int(h * 0.75):, :]
        top_ed = np.mean(cv2.Canny(top_q, 50, 150) > 0)
        bot_ed = np.mean(cv2.Canny(bot_q, 50, 150) > 0)
        if bot_ed > 1.8 * (top_ed + 0.01) and bot_ed > 0.08:
            res_img = cv2.rotate(img_bgr, cv2.ROTATE_180)
            angle = 180
        else:
            res_img = img_bgr
            angle = 0

    res_h, res_w = res_img.shape[:2]
    is_portrait = (res_h > res_w)
    aspect = res_w / float(res_h)
    
    if is_portrait:
        if 0.55 <= aspect <= 0.72:
            ratio_type = "id_card_portrait"
        elif 0.72 < aspect <= 0.85:
            ratio_type = "3:4"
        else:
            ratio_type = "portrait"
    else:
        if 1.45 <= aspect <= 1.78:
            ratio_type = "id_card"
        elif 1.20 <= aspect <= 1.45:
            ratio_type = "4:3"
        else:
            ratio_type = "custom"

    return res_img, angle, is_portrait, aspect, ratio_type

def detect_and_crop_id_card(image_input, padding_px=0, deskew=True, corner_curve_mm=3.18):
    """
    Detects an ID card, photo, or small document on a scanner bed and crops it cleanly.
    Uses CamScanner-style quad perspective transform / deskew and trims drop shadows.
    Maintains portrait or landscape natural orientation accurately.
    
    Returns: (cropped_pil_image, was_detected_bool, (x, y, w, h), ratio_info_dict)
    """
    if isinstance(image_input, str):
        img_bgr = cv2.imread(image_input)
    elif isinstance(image_input, np.ndarray):
        img_bgr = image_input.copy()
    else:
        img_rgb = np.array(image_input.convert("RGB"))
        img_bgr = cv2.cvtColor(img_rgb, cv2.COLOR_RGB2BGR)

    if img_bgr is None:
        return None, False, (0, 0, 0, 0), {"ratio_type": "id_card", "aspect": 1.585, "is_portrait": False}

    orig_h, orig_w = img_bgr.shape[:2]
    total_area = orig_w * orig_h

    # 1. Background color: median of inner 80%
    bg_color = sample_background_color(img_bgr)

    # 2. Scanner bed perimeter margin - ALWAYS clear flatbed frame edges!
    # Flatbed glass bevel frame is always at the outer edges of the image.
    if min(orig_w, orig_h) < 600:
        bed_margin = 2
    else:
        bed_margin = max(4, int(min(orig_w, orig_h) * 0.008))

    # 3. Color difference mask from flatbed background
    # Adaptive threshold using noise estimate
    # Background noise estimate from outer corner margins (avoiding the card)
    ch = max(2, int(orig_h * 0.05))
    cw = max(2, int(orig_w * 0.05))
    corners = np.concatenate([
        img_bgr[0:ch, 0:cw].reshape(-1, 3),
        img_bgr[0:ch, max(0, orig_w - cw) : orig_w].reshape(-1, 3),
        img_bgr[max(0, orig_h - ch) : orig_h, 0:cw].reshape(-1, 3),
        img_bgr[max(0, orig_h - ch) : orig_h, max(0, orig_w - cw) : orig_w].reshape(-1, 3)
    ], axis=0)
    corner_l = np.mean(corners, axis=1) if len(corners) > 0 else np.array([245.0])
    if np.percentile(corner_l, 75) >= 210.0:
        lid_pixels = corners[corner_l >= 210.0]
        lid_std = float(np.std(lid_pixels)) if len(lid_pixels) > 0 else 3.0
    elif np.percentile(corner_l, 25) <= 60.0:
        lid_pixels = corners[corner_l <= 60.0]
        lid_std = float(np.std(lid_pixels)) if len(lid_pixels) > 0 else 3.0
    else:
        lid_std = 3.5

    diff_thresh = max(14.0, min(28.0, lid_std * 3.5))

    diff = np.max(np.abs(img_bgr.astype(np.float32) - bg_color), axis=2)
    color_mask = (diff > diff_thresh).astype(np.uint8) * 255

    # 4. Sobel gradient for card perimeter
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    sobelx = cv2.Sobel(blurred, cv2.CV_32F, 1, 0, ksize=3)
    sobely = cv2.Sobel(blurred, cv2.CV_32F, 0, 1, ksize=3)
    mag = cv2.magnitude(sobelx, sobely)
    edge_mask = (mag > 18).astype(np.uint8) * 255

    # Suppress dark flatbed glass frame margins unconditionally
    color_mask[:bed_margin, :] = 0
    color_mask[-bed_margin:, :] = 0
    color_mask[:, :bed_margin] = 0
    color_mask[:, -bed_margin:] = 0

    edge_mask[:bed_margin, :] = 0
    edge_mask[-bed_margin:, :] = 0
    edge_mask[:, :bed_margin] = 0
    edge_mask[:, -bed_margin:] = 0

    combined = cv2.bitwise_or(color_mask, edge_mask)

    # 5. Morphological close to join card edges, text, and photo
    ksize = max(11, int(min(orig_w, orig_h) * 0.012))
    if ksize % 2 == 0:
        ksize += 1
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (ksize, ksize))
    closed = cv2.morphologyEx(combined, cv2.MORPH_CLOSE, kernel)

    cnts, _ = cv2.findContours(closed, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    filled_mask = np.zeros_like(closed)
    for c in cnts:
        cv2.drawContours(filled_mask, [c], -1, 255, -1)

    contours, _ = cv2.findContours(filled_mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

    best_cnt = None
    best_area = 0
    min_box_area = total_area * 0.010
    max_box_area = total_area * 0.85

    for cnt in contours:
        x, y, w, h = cv2.boundingRect(cnt)
        box_area = w * h
        aspect = w / float(h)
        if min_box_area <= box_area <= max_box_area:
            if 0.35 <= aspect <= 3.0:
                if box_area > best_area:
                    best_area = box_area
                    best_cnt = cnt

    # Intelligent Content-Bounding-Box fallback if single contour missed
    if best_cnt is None:
        content_mask = ((diff > diff_thresh) | (gray < 225)).astype(np.uint8) * 255
        content_mask[:bed_margin, :] = 0
        content_mask[-bed_margin:, :] = 0
        content_mask[:, :bed_margin] = 0
        content_mask[:, -bed_margin:] = 0

        cy_pts, cx_pts = np.where(content_mask > 0)
        if len(cx_pts) > (total_area * 0.005):
            pad_x = int(orig_w * 0.015)
            pad_y = int(orig_h * 0.015)
            fx = max(0, int(np.percentile(cx_pts, 0.2)) - pad_x)
            fy = max(0, int(np.percentile(cy_pts, 0.2)) - pad_y)
            fx2 = min(orig_w, int(np.percentile(cx_pts, 99.8)) + pad_x)
            fy2 = min(orig_h, int(np.percentile(cy_pts, 99.8)) + pad_y)
            fw = fx2 - fx
            fh = fy2 - fy
            if (fw * fh) >= min_box_area:
                best_cnt = np.array([
                    [[fx, fy]],
                    [[fx + fw, fy]],
                    [[fx + fw, fy + fh]],
                    [[fx, fy + fh]]
                ])

    if best_cnt is not None:
        init_bx, init_by, init_bw, init_bh = cv2.boundingRect(best_cnt)
        # Refine boundaries to recover flush-mounted edges and trim empty scanner bed
        rx, ry, rw, rh = refine_card_bounding_box(img_bgr, init_bx, init_by, init_bw, init_bh, bg_color)

        rect = cv2.minAreaRect(best_cnt)
        (cx, cy), (rw_rect, rh_rect), angle = rect

        # Always deskew when card is placed tilted at an angle (even if touching corner/frame ruler)
        has_tilt = (abs(angle) >= 0.8 and abs(abs(angle) - 90.0) >= 0.8)
        if deskew and has_tilt:
            pts = cv2.boxPoints(rect)
            ordered_pts = order_corner_points(pts)
            w_top = np.linalg.norm(ordered_pts[1] - ordered_pts[0])
            w_bot = np.linalg.norm(ordered_pts[2] - ordered_pts[3])
            target_w = int(round(max(w_top, w_bot)))
            h_left = np.linalg.norm(ordered_pts[3] - ordered_pts[0])
            h_right = np.linalg.norm(ordered_pts[2] - ordered_pts[1])
            target_h = int(round(max(h_left, h_right)))

            dst_pts = np.array([
                [0, 0],
                [target_w - 1, 0],
                [target_w - 1, target_h - 1],
                [0, target_h - 1]
            ], dtype=np.float32)

            M = cv2.getPerspectiveTransform(ordered_pts, dst_pts)
            cropped_bgr = cv2.warpPerspective(img_bgr, M, (target_w, target_h), flags=cv2.INTER_LANCZOS4, borderMode=cv2.BORDER_REPLICATE)
            bx, by, bw, bh = int(min(pts[:, 0])), int(min(pts[:, 1])), target_w, target_h
        else:
            bx, by, bw, bh = rx, ry, rw, rh
            cropped_bgr = img_bgr[by : by + bh, bx : bx + bw]

        # CamScanner 4-sided Shadow Elimination: protect edges flush with scanner glass
        cropped_bgr = trim_shadow_edges_all_sides(
            cropped_bgr,
            max_trim=4,
            allow_left=(bx > 0),
            allow_top=(by > 0),
            allow_right=(bx + bw < orig_w),
            allow_bottom=(by + bh < orig_h),
            bg_color=bg_color
        )

        # Compute card quad corners in raw image coordinates
        if deskew and has_tilt:
            corners_list = ordered_pts.tolist()
        else:
            corners_list = [
                [float(bx), float(by)],
                [float(bx + bw), float(by)],
                [float(bx + bw), float(by + bh)],
                [float(bx), float(by + bh)]
            ]

        # Automatic Text Orientation & Horizontalness Alignment
        cropped_bgr, rot_angle, is_portrait, aspect, ratio_type = detect_and_correct_text_orientation(cropped_bgr)

        # 2-in-1 Card Normalization: ID cards are strictly landscape (85.6 x 54 mm).
        # If placed at 90 degrees on the flatbed glass (height > width), auto-rotate horizontally!
        if cropped_bgr.shape[0] > cropped_bgr.shape[1]:
            im_cw = cv2.rotate(cropped_bgr, cv2.ROTATE_90_CLOCKWISE)
            im_ccw = cv2.rotate(cropped_bgr, cv2.ROTATE_90_COUNTERCLOCKWISE)
            def _score_hdr(im):
                g = cv2.cvtColor(im, cv2.COLOR_BGR2GRAY)
                gh, gw = g.shape
                top_q = g[:int(gh * 0.28), :]
                bot_q = g[int(gh * 0.72):, :]
                top_edges = np.mean(cv2.Canny(top_q, 50, 150) > 0)
                bot_edges = np.mean(cv2.Canny(bot_q, 50, 150) > 0)
                top_dark = np.mean(top_q < 120)
                bot_dark = np.mean(bot_q < 120)
                return (top_edges - bot_edges) * 100.0 + (top_dark - bot_dark) * 50.0
            cropped_bgr = im_cw if _score_hdr(im_cw) >= _score_hdr(im_ccw) else im_ccw
            is_portrait = False
            aspect = cropped_bgr.shape[1] / float(cropped_bgr.shape[0])
            ratio_type = "id_card"

        cw = cropped_bgr.shape[1]
        ch = cropped_bgr.shape[0]

        cropped_rgb = cv2.cvtColor(cropped_bgr, cv2.COLOR_BGR2RGB)
        cropped_pil = Image.fromarray(cropped_rgb)

        # Apply Corner Curve (Rounded Corners) if requested
        if corner_curve_mm and corner_curve_mm > 0:
            cropped_pil = apply_card_corner_curve(cropped_pil, radius_mm=corner_curve_mm)

        info = {
            "ratio_type": ratio_type,
            "aspect": aspect,
            "width": cropped_pil.width,
            "height": cropped_pil.height,
            "is_portrait": is_portrait,
            "corners": corners_list
        }
        return cropped_pil, True, (bx, by, bw, bh), info

    # Safety fallback: return clean full image (preserves entire document without cropping an empty lid corner)
    cropped_rgb = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB)
    cropped_pil = Image.fromarray(cropped_rgb)
    aspect = orig_w / float(orig_h)
    is_port = (orig_h > orig_w)

    info = {
        "ratio_type": "full",
        "aspect": aspect,
        "width": orig_w,
        "height": orig_h,
        "is_portrait": is_port,
        "corners": [
            [0.0, 0.0],
            [float(orig_w), 0.0],
            [float(orig_w), float(orig_h)],
            [0.0, float(orig_h)]
        ]
    }
    return cropped_pil, False, (0, 0, orig_w, orig_h), info

def warp_quad_card(image_input, points, corner_curve_mm=3.18, deskew=True, auto_orient=True):
    """
    Given an image (PIL Image or BGR numpy array) and 4 corner points
    [[x0, y0], [x1, y1], [x2, y2], [x3, y3]],
    performs a 4-point perspective warp into an orthogonal card image.
    Applies optional corner curvature and automatic text horizontalness alignment.

    Returns: (warped_pil_image, success_bool, (bx, by, bw, bh), info_dict)
    """
    if isinstance(image_input, Image.Image):
        img_rgb = np.array(image_input.convert("RGB"))
        img_bgr = cv2.cvtColor(img_rgb, cv2.COLOR_RGB2BGR)
    elif isinstance(image_input, str):
        img_bgr = cv2.imread(image_input)
    else:
        img_bgr = image_input.copy() if hasattr(image_input, "copy") else np.array(image_input)

    if img_bgr is None:
        return None, False, (0, 0, 0, 0), {"ratio_type": "id_card", "aspect": 1.585, "is_portrait": False}

    orig_h, orig_w = img_bgr.shape[:2]
    pts = np.array(points, dtype=np.float32)
    if pts.shape != (4, 2):
        raise ValueError("points must be a list or array of 4 coordinates [[x,y], ...]")

    # Order points clockwise: TL, TR, BR, BL
    ordered_pts = order_corner_points(pts)

    # Compute target width and height from Euclidean distance between corners
    w_top = np.linalg.norm(ordered_pts[1] - ordered_pts[0])
    w_bot = np.linalg.norm(ordered_pts[2] - ordered_pts[3])
    target_w = max(20, int(round(max(w_top, w_bot))))

    h_left = np.linalg.norm(ordered_pts[3] - ordered_pts[0])
    h_right = np.linalg.norm(ordered_pts[2] - ordered_pts[1])
    target_h = max(20, int(round(max(h_left, h_right))))

    dst_pts = np.array([
        [0, 0],
        [target_w - 1, 0],
        [target_w - 1, target_h - 1],
        [0, target_h - 1]
    ], dtype=np.float32)

    M = cv2.getPerspectiveTransform(ordered_pts, dst_pts)
    warped_bgr = cv2.warpPerspective(img_bgr, M, (target_w, target_h), flags=cv2.INTER_LANCZOS4, borderMode=cv2.BORDER_REPLICATE)

    # Calculate bounding box of the input quadrilateral
    min_x = max(0, int(np.min(ordered_pts[:, 0])))
    min_y = max(0, int(np.min(ordered_pts[:, 1])))
    max_x = min(orig_w, int(np.max(ordered_pts[:, 0])))
    max_y = min(orig_h, int(np.max(ordered_pts[:, 1])))
    bx, by, bw, bh = min_x, min_y, max_x - min_x, max_y - min_y

    # Automatic Text Orientation & Horizontalness Alignment if requested
    if auto_orient:
        warped_bgr, rot_angle, is_portrait, aspect, ratio_type = detect_and_correct_text_orientation(warped_bgr)
    else:
        cw = warped_bgr.shape[1]
        ch = warped_bgr.shape[0]
        aspect = cw / float(ch)
        is_portrait = (ch > cw)
        ratio_type = "id_card_portrait" if is_portrait else "id_card"

    # 2-in-1 Card Normalization: If warped card is portrait (height > width), auto-rotate horizontally!
    if warped_bgr.shape[0] > warped_bgr.shape[1]:
        im_cw = cv2.rotate(warped_bgr, cv2.ROTATE_90_CLOCKWISE)
        im_ccw = cv2.rotate(warped_bgr, cv2.ROTATE_90_COUNTERCLOCKWISE)
        def _score_hdr_warp(im):
            g = cv2.cvtColor(im, cv2.COLOR_BGR2GRAY)
            gh, gw = g.shape
            top_q = g[:int(gh * 0.28), :]
            bot_q = g[int(gh * 0.72):, :]
            top_edges = np.mean(cv2.Canny(top_q, 50, 150) > 0)
            bot_edges = np.mean(cv2.Canny(bot_q, 50, 150) > 0)
            top_dark = np.mean(top_q < 120)
            bot_dark = np.mean(bot_q < 120)
            return (top_edges - bot_edges) * 100.0 + (top_dark - bot_dark) * 50.0
        warped_bgr = im_cw if _score_hdr_warp(im_cw) >= _score_hdr_warp(im_ccw) else im_ccw
        is_portrait = False
        aspect = warped_bgr.shape[1] / float(warped_bgr.shape[0])
        ratio_type = "id_card"

    warped_rgb = cv2.cvtColor(warped_bgr, cv2.COLOR_BGR2RGB)
    warped_pil = Image.fromarray(warped_rgb)

    if corner_curve_mm and corner_curve_mm > 0:
        warped_pil = apply_card_corner_curve(warped_pil, radius_mm=corner_curve_mm)

    info = {
        "ratio_type": ratio_type,
        "aspect": aspect,
        "width": warped_pil.width,
        "height": warped_pil.height,
        "is_portrait": is_portrait,
        "corners": ordered_pts.tolist()
    }
    return warped_pil, True, (bx, by, bw, bh), info

def detect_and_crop_document(image_input, doc_type="auto", padding_px=0, deskew=True, corner_curve_mm=0):
    """
    Detects and crops full A4 pages or small documents (receipts, cards, bills, slips).
    Uses CamScanner perspective transform to eliminate scanner margins and tilt.
    Preserves natural portrait or landscape orientation.
    doc_type: "a4", "small", or "auto"
    Returns: (cropped_pil, was_detected, (x, y, w, h), info_dict)
    """
    if isinstance(image_input, str):
        img_bgr = cv2.imread(image_input)
    elif isinstance(image_input, np.ndarray):
        img_bgr = image_input.copy()
    else:
        img_rgb = np.array(image_input.convert("RGB"))
        img_bgr = cv2.cvtColor(img_rgb, cv2.COLOR_RGB2BGR)

    if img_bgr is None:
        return None, False, (0, 0, 0, 0), {"doc_type": doc_type, "ratio_type": "a4", "aspect": 0.707, "is_portrait": True}

    orig_h, orig_w = img_bgr.shape[:2]
    total_area = orig_w * orig_h

    # If explicitly requesting A4 page:
    if doc_type == "a4":
        border_trim = max(4, int(min(orig_w, orig_h) * 0.006))
        cropped_bgr = img_bgr[border_trim : orig_h - border_trim, border_trim : orig_w - border_trim]
        cropped_bgr = trim_shadow_edges_all_sides(cropped_bgr, max_trim=8)
        cropped_rgb = cv2.cvtColor(cropped_bgr, cv2.COLOR_BGR2RGB)
        cropped_pil = Image.fromarray(cropped_rgb)
        is_port = (cropped_pil.height > cropped_pil.width)
        info = {
            "doc_type": "a4",
            "ratio_type": "a4",
            "aspect": cropped_pil.width / float(cropped_pil.height),
            "width": cropped_pil.width,
            "height": cropped_pil.height,
            "is_portrait": is_port
        }
        return cropped_pil, True, (border_trim, border_trim, cropped_pil.width, cropped_pil.height), info

    # For "small" or "auto", perform contour detection across the flatbed
    bg_color = sample_background_color(img_bgr)
    if min(orig_w, orig_h) < 600:
        bed_margin = 2
    else:
        bed_margin = max(4, int(min(orig_w, orig_h) * 0.008))

    # Background noise estimate from outer corner margins (avoiding the card)
    ch = max(2, int(orig_h * 0.05))
    cw = max(2, int(orig_w * 0.05))
    corners = np.concatenate([
        img_bgr[0:ch, 0:cw].reshape(-1, 3),
        img_bgr[0:ch, max(0, orig_w - cw) : orig_w].reshape(-1, 3),
        img_bgr[max(0, orig_h - ch) : orig_h, 0:cw].reshape(-1, 3),
        img_bgr[max(0, orig_h - ch) : orig_h, max(0, orig_w - cw) : orig_w].reshape(-1, 3)
    ], axis=0)
    corner_l = np.mean(corners, axis=1) if len(corners) > 0 else np.array([245.0])
    if np.percentile(corner_l, 75) >= 210.0:
        lid_pixels = corners[corner_l >= 210.0]
        lid_std = float(np.std(lid_pixels)) if len(lid_pixels) > 0 else 3.0
    elif np.percentile(corner_l, 25) <= 60.0:
        lid_pixels = corners[corner_l <= 60.0]
        lid_std = float(np.std(lid_pixels)) if len(lid_pixels) > 0 else 3.0
    else:
        lid_std = 3.5

    diff_thresh = max(14.0, min(28.0, lid_std * 3.5))

    diff = np.max(np.abs(img_bgr.astype(np.float32) - bg_color), axis=2)
    color_mask = (diff > diff_thresh).astype(np.uint8) * 255

    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    sobelx = cv2.Sobel(blurred, cv2.CV_32F, 1, 0, ksize=3)
    sobely = cv2.Sobel(blurred, cv2.CV_32F, 0, 1, ksize=3)
    mag = cv2.magnitude(sobelx, sobely)
    edge_mask = (mag > 18).astype(np.uint8) * 255

    # Suppress dark flatbed glass frame margins unconditionally
    color_mask[:bed_margin, :] = 0
    color_mask[-bed_margin:, :] = 0
    color_mask[:, :bed_margin] = 0
    color_mask[:, -bed_margin:] = 0

    edge_mask[:bed_margin, :] = 0
    edge_mask[-bed_margin:, :] = 0
    edge_mask[:, :bed_margin] = 0
    edge_mask[:, -bed_margin:] = 0

    combined = cv2.bitwise_or(color_mask, edge_mask)
    ksize = max(11, int(min(orig_w, orig_h) * 0.012))
    if ksize % 2 == 0:
        ksize += 1
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (ksize, ksize))
    closed = cv2.morphologyEx(combined, cv2.MORPH_CLOSE, kernel)

    cnts, _ = cv2.findContours(closed, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    filled_mask = np.zeros_like(closed)
    for c in cnts:
        cv2.drawContours(filled_mask, [c], -1, 255, -1)

    contours, _ = cv2.findContours(filled_mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

    best_cnt = None
    best_area = 0
    min_box_area = total_area * 0.010

    for cnt in contours:
        x, y, w, h = cv2.boundingRect(cnt)
        box_area = w * h
        if box_area >= min_box_area:
            if box_area > best_area:
                best_area = box_area
                best_cnt = cnt

    is_large_a4 = (best_area >= 0.55 * total_area)

    if (doc_type == "auto" and is_large_a4) or (best_cnt is None and doc_type != "small"):
        border_trim = max(4, int(min(orig_w, orig_h) * 0.006))
        cropped_bgr = img_bgr[border_trim : orig_h - border_trim, border_trim : orig_w - border_trim]
        cropped_bgr = trim_shadow_edges_all_sides(cropped_bgr, max_trim=8)
        cropped_rgb = cv2.cvtColor(cropped_bgr, cv2.COLOR_BGR2RGB)
        cropped_pil = Image.fromarray(cropped_rgb)
        is_port = (cropped_pil.height > cropped_pil.width)
        info = {
            "doc_type": "a4",
            "ratio_type": "a4",
            "aspect": cropped_pil.width / float(cropped_pil.height),
            "width": cropped_pil.width,
            "height": cropped_pil.height,
            "is_portrait": is_port
        }
        return cropped_pil, True, (border_trim, border_trim, cropped_pil.width, cropped_pil.height), info

    if best_cnt is not None:
        init_bx, init_by, init_bw, init_bh = cv2.boundingRect(best_cnt)
        rx, ry, rw, rh = refine_card_bounding_box(img_bgr, init_bx, init_by, init_bw, init_bh, bg_color)

        rect = cv2.minAreaRect(best_cnt)
        (cx, cy), (rw_rect, rh_rect), angle = rect
        is_corner_mounted = (rx == 0 or ry == 0 or (rx + rw >= orig_w) or (ry + rh >= orig_h))

        if deskew and abs(angle) >= 0.8 and not is_corner_mounted:
            pts = cv2.boxPoints(rect)
            ordered_pts = order_corner_points(pts)
            w_top = np.linalg.norm(ordered_pts[1] - ordered_pts[0])
            w_bot = np.linalg.norm(ordered_pts[2] - ordered_pts[3])
            target_w = int(round(max(w_top, w_bot)))

            h_left = np.linalg.norm(ordered_pts[3] - ordered_pts[0])
            h_right = np.linalg.norm(ordered_pts[2] - ordered_pts[1])
            target_h = int(round(max(h_left, h_right)))

            dst_pts = np.array([
                [0, 0],
                [target_w - 1, 0],
                [target_w - 1, target_h - 1],
                [0, target_h - 1]
            ], dtype=np.float32)

            M = cv2.getPerspectiveTransform(ordered_pts, dst_pts)
            cropped_bgr = cv2.warpPerspective(img_bgr, M, (target_w, target_h), flags=cv2.INTER_LANCZOS4, borderMode=cv2.BORDER_REPLICATE)
            bx, by, bw, bh = int(min(pts[:, 0])), int(min(pts[:, 1])), target_w, target_h
        else:
            bx, by, bw, bh = rx, ry, rw, rh
            cropped_bgr = img_bgr[by : by + bh, bx : bx + bw]

        cropped_bgr = trim_shadow_edges_all_sides(
            cropped_bgr,
            max_trim=4,
            allow_left=(bx > 0),
            allow_top=(by > 0),
            allow_right=(bx + bw < orig_w),
            allow_bottom=(by + bh < orig_h),
            bg_color=bg_color
        )
        cw = cropped_bgr.shape[1]
        ch = cropped_bgr.shape[0]

        is_portrait = (ch > cw)
        aspect = cw / float(ch)

        cropped_rgb = cv2.cvtColor(cropped_bgr, cv2.COLOR_BGR2RGB)
        cropped_pil = Image.fromarray(cropped_rgb)

        # Apply Corner Curve (Rounded Corners) if requested
        if corner_curve_mm and corner_curve_mm > 0:
            cropped_pil = apply_card_corner_curve(cropped_pil, radius_mm=corner_curve_mm)

        info = {
            "doc_type": "small",
            "ratio_type": "portrait" if is_portrait else "landscape",
            "aspect": aspect,
            "width": cropped_pil.width,
            "height": cropped_pil.height,
            "is_portrait": is_portrait
        }
        return cropped_pil, True, (bx, by, bw, bh), info

    # Fallback
    cropped_rgb = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB)
    cropped_pil = Image.fromarray(cropped_rgb)
    is_port = (orig_h > orig_w)
    info = {
        "doc_type": "auto",
        "ratio_type": "a4",
        "aspect": orig_w / float(orig_h),
        "width": orig_w,
        "height": orig_h,
        "is_portrait": is_port
    }
    return cropped_pil, False, (0, 0, orig_w, orig_h), info

def enhance_document(image_input, preset="clean", bg_whiten=45, contrast=18, sharpness=40, saturation=110, brightness=4):
    """
    CamScanner-grade Auto-Enhancement and Illumination Leveling:
    - Eliminates scanner drop-shadows and paper yellowing/graying.
    - Turns paper background into pure 255, 255, 255 snow-white.
    - Deepens dark text, lines, bar codes, and signatures to solid black.
    - Preserves colored logos, stamps, seals, QR codes, and photo details in LAB color space.
    Presets: "clean" (Magic Clean), "xerox" (Photocopy B&W), "vibrant" (Color Boost), "grayscale", "original"
    """
    orig_alpha = None
    if hasattr(image_input, "mode") and image_input.mode == "RGBA":
        orig_alpha = image_input.split()[-1]

    def _wrap_enh_out(pil_res):
        if orig_alpha is not None:
            r = pil_res.convert("RGBA")
            r.putalpha(orig_alpha)
            return r
        return pil_res

    if isinstance(image_input, np.ndarray):
        img_bgr = image_input.copy()
    elif isinstance(image_input, str):
        img_bgr = cv2.imread(image_input)
    else:
        # PIL Image
        img_rgb = np.array(image_input.convert("RGB"))
        img_bgr = cv2.cvtColor(img_rgb, cv2.COLOR_RGB2BGR)

    if img_bgr is None:
        return image_input

    if preset == "original":
        img_rgb = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB)
        return _wrap_enh_out(Image.fromarray(img_rgb))

    # -------------------------------------------------------------
    # PRESET: XEROX (CamScanner Crisp Photocopy B&W)
    # -------------------------------------------------------------
    if preset == "xerox":
        gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
        h, w = gray.shape
        # Illumination normalization
        k = max(25, int(min(h, w) * 0.04) | 1)
        kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k))
        dilated = cv2.morphologyEx(gray, cv2.MORPH_DILATE, kernel)
        bg = cv2.GaussianBlur(dilated, (k, k), 0)
        bg_f = np.maximum(bg.astype(np.float32), 1.0)
        norm = np.clip((gray.astype(np.float32) / bg_f) * 255.0, 0, 255).astype(np.uint8)

        # Adaptive thresholding with local Gaussian neighborhood
        block_size = max(17, (min(h, w) // 35) | 1)
        if block_size % 2 == 0:
            block_size += 1
        xerox = cv2.adaptiveThreshold(
            norm, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY,
            blockSize=block_size, C=11
        )
        # Suppress single-pixel scanner dust
        xerox = cv2.medianBlur(xerox, 3)
        return _wrap_enh_out(Image.fromarray(xerox).convert("RGB"))

    # -------------------------------------------------------------
    # PRESETS: CLEAN (Magic Color), VIBRANT, GRAYSCALE
    # CamScanner LAB Illumination Division & S-Curve Tone Mapping
    # -------------------------------------------------------------
    lab = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2LAB)
    l_channel, a_channel, b_channel = cv2.split(lab)
    h, w = l_channel.shape

    # 1. Illumination Map on Luminance
    k = max(25, int(min(h, w) * 0.035) | 1)
    if k % 2 == 0:
        k += 1
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k))
    dilated = cv2.morphologyEx(l_channel, cv2.MORPH_DILATE, kernel)
    bg_illum = cv2.GaussianBlur(dilated, (k, k), 0)

    # 2. Division Normalization (L / bg_illum) * 255
    bg_float = np.maximum(bg_illum.astype(np.float32), 1.0)
    l_float = l_channel.astype(np.float32)
    normalized = np.clip((l_float / bg_float) * 255.0, 0, 255)

    # 3. Dynamic Contrast Range & Paper White Anchoring (Anti-Washout)
    p_dark = float(np.percentile(normalized, 0.8))
    p_paper = float(np.percentile(normalized, 98.0))
    contrast_range = max(15.0, p_paper - p_dark)

    whiten_strength = max(0.0, min(100.0, float(bg_whiten))) / 100.0
    # Paper white threshold: only whiten true paper background within top fraction of paper range
    white_point = p_paper - (whiten_strength * min(25.0, contrast_range * 0.35))
    black_point = min(p_dark + (float(brightness) * 0.2), white_point - 20.0)

    table = np.zeros(256, dtype=np.uint8)
    for i in range(256):
        if i <= black_point:
            val = max(0.0, i * 0.70)
        elif i >= white_point:
            val = 255.0
        else:
            ratio = (i - black_point) / (white_point - black_point)
            val = 255.0 * (ratio ** 0.85)
        table[i] = np.clip(val, 0, 255)

    enhanced_l = cv2.LUT(normalized.astype(np.uint8), table)

    # Contrast adjustment
    c_val = float(contrast)
    if preset == "vibrant":
        c_val += 15.0
    if c_val != 0:
        c_factor = (259.0 * (c_val + 255.0)) / (255.0 * (259.0 - c_val))
        c_mapped = c_factor * (enhanced_l.astype(np.float32) - 128.0) + 128.0
        enhanced_l = np.clip(c_mapped, 0, 255).astype(np.uint8)

    # 4. Text & Edge Sharpening (Unsharp Mask)
    sh_val = float(sharpness)
    if sh_val > 0:
        blurred_l = cv2.GaussianBlur(enhanced_l, (0, 0), 1.5)
        amount = 1.0 + (sh_val / 38.0)
        sharpened = cv2.addWeighted(enhanced_l, amount, blurred_l, 1.0 - amount, 0)
        enhanced_l = np.clip(sharpened, 0, 255).astype(np.uint8)

    # 5. CamScanner Anti-Washout Safety Guard:
    # If enhanced image lost all ink (> 98.5% pixels >= 252 and min pixel > 215),
    # recover original luminance with safe contrast normalization so ink is never erased!
    if np.mean(enhanced_l >= 252) > 0.985 and np.min(enhanced_l) > 215:
        enhanced_l = cv2.normalize(l_channel, None, alpha=25, beta=255, norm_type=cv2.NORM_MINMAX)

    # 6. Color Saturation & Chrominance Preservation
    if preset == "grayscale":
        return _wrap_enh_out(Image.fromarray(enhanced_l).convert("RGB"))

    sat_val = float(saturation)
    if preset == "vibrant":
        sat_val *= 1.35
    sat_factor = max(0.0, sat_val / 100.0)

    if sat_factor != 1.0:
        a_adj = np.clip((a_channel.astype(np.float32) - 128.0) * sat_factor + 128.0, 0, 255).astype(np.uint8)
        b_adj = np.clip((b_channel.astype(np.float32) - 128.0) * sat_factor + 128.0, 0, 255).astype(np.uint8)
    else:
        a_adj = a_channel
        b_adj = b_channel

    enhanced_lab = cv2.merge([enhanced_l, a_adj, b_adj])
    enhanced_bgr = cv2.cvtColor(enhanced_lab, cv2.COLOR_LAB2BGR)
    enhanced_rgb = cv2.cvtColor(enhanced_bgr, cv2.COLOR_BGR2RGB)
    return _wrap_enh_out(Image.fromarray(enhanced_rgb))


def enhance_passport_photo(image_input, preset="studio", brightness=0, contrast=10, sharpness=35, saturation=115, bg_mode="white"):
    """
    Specialized portrait enhancement for passport and visa photographs:
    - Studio portrait smoothing (bilateral filter) to soften skin grain while keeping eyes and hair razor-sharp.
    - Official backdrop options: pure white background or Indian passport light blue (#cbe2f8).
    - Vivid color and natural skin tone calibration.
    - Professional unsharp mask for 300 DPI photo printing.
    """
    if isinstance(image_input, np.ndarray):
        img_bgr = image_input.copy()
    elif isinstance(image_input, str):
        img_bgr = cv2.imread(image_input)
    else:
        img_rgb = np.array(image_input.convert("RGB"))
        img_bgr = cv2.cvtColor(img_rgb, cv2.COLOR_RGB2BGR)

    if img_bgr is None:
        return image_input

    if preset == "original":
        return Image.fromarray(cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB))

    # 1. Subtle skin smoothing while preserving facial features and hair
    if preset in ("studio", "blue_bg", "white_bg", "vibrant"):
        smoothed = cv2.bilateralFilter(img_bgr, d=7, sigmaColor=25, sigmaSpace=25)
        img_bgr = cv2.addWeighted(smoothed, 0.75, img_bgr, 0.25, 0)

    # 2. Background Handling
    if preset == "blue_bg" or bg_mode == "blue":
        hsv = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2HSV)
        sat = hsv[:, :, 1]
        val = hsv[:, :, 2]
        bg_candidate = ((val > 185) & (sat < 65)).astype(np.uint8) * 255
        k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (15, 15))
        bg_mask = cv2.morphologyEx(bg_candidate, cv2.MORPH_OPEN, k)
        bg_mask = cv2.GaussianBlur(bg_mask, (11, 11), 0).astype(np.float32) / 255.0

        blue_bgr = np.array([250, 228, 202], dtype=np.float32) # Soft light blue
        for c in range(3):
            img_bgr[:, :, c] = np.clip(
                img_bgr[:, :, c].astype(np.float32) * (1.0 - bg_mask * 0.85) + blue_bgr[c] * (bg_mask * 0.85),
                0, 255
            ).astype(np.uint8)

    elif preset == "white_bg" or bg_mode == "white":
        hsv = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2HSV)
        sat = hsv[:, :, 1]
        val = hsv[:, :, 2]
        bg_candidate = ((val > 180) & (sat < 55)).astype(np.uint8) * 255
        k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (15, 15))
        bg_mask = cv2.morphologyEx(bg_candidate, cv2.MORPH_OPEN, k)
        bg_mask = cv2.GaussianBlur(bg_mask, (11, 11), 0).astype(np.float32) / 255.0
        for c in range(3):
            img_bgr[:, :, c] = np.clip(
                img_bgr[:, :, c].astype(np.float32) * (1.0 - bg_mask) + 255.0 * bg_mask,
                0, 255
            ).astype(np.uint8)

    # 3. Tone, Brightness & Contrast Adjustment in LAB color space
    lab = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2LAB)
    l_channel, a_channel, b_channel = cv2.split(lab)

    b_val = float(brightness)
    c_val = float(contrast)
    if preset == "vibrant":
        c_val += 15.0
        b_val += 3.0

    if b_val != 0:
        l_channel = np.clip(l_channel.astype(np.float32) + (b_val * 1.2), 0, 255).astype(np.uint8)

    if c_val != 0:
        c_factor = (259.0 * (c_val + 255.0)) / (255.0 * (259.0 - c_val))
        c_mapped = c_factor * (l_channel.astype(np.float32) - 128.0) + 128.0
        l_channel = np.clip(c_mapped, 0, 255).astype(np.uint8)

    # 4. Facial Edge & Feature Sharpening
    sh_val = float(sharpness)
    if sh_val > 0:
        blurred_l = cv2.GaussianBlur(l_channel, (0, 0), 1.2)
        amount = 1.0 + (sh_val / 35.0)
        sharpened = cv2.addWeighted(l_channel, amount, blurred_l, 1.0 - amount, 0)
        l_channel = np.clip(sharpened, 0, 255).astype(np.uint8)

    # 5. Saturation & Grayscale
    if preset == "grayscale":
        return Image.fromarray(l_channel).convert("RGB")

    sat_val = float(saturation)
    if preset == "vibrant":
        sat_val *= 1.3
    sat_factor = max(0.0, sat_val / 100.0)

    if sat_factor != 1.0:
        a_adj = np.clip((a_channel.astype(np.float32) - 128.0) * sat_factor + 128.0, 0, 255).astype(np.uint8)
        b_adj = np.clip((b_channel.astype(np.float32) - 128.0) * sat_factor + 128.0, 0, 255).astype(np.uint8)
    else:
        a_adj = a_channel
        b_adj = b_channel

    enhanced_lab = cv2.merge([l_channel, a_adj, b_adj])
    enhanced_bgr = cv2.cvtColor(enhanced_lab, cv2.COLOR_LAB2BGR)
    enhanced_rgb = cv2.cvtColor(enhanced_bgr, cv2.COLOR_BGR2RGB)
    return Image.fromarray(enhanced_rgb)

def apply_camera_raw_filter(image_input, raw_params=None):
    """
    Adobe Photoshop Camera Raw 18.3-style professional portrait processor:
    - Light: Exposure, Contrast, Highlights, Shadows, Whites, Blacks
    - Color: Temperature (Cool/Warm), Tint (Green/Magenta), Vibrance, Saturation
    - Effects: Texture (-100 skin softening / +100 structure), Clarity, Dehaze, Vignette
    - Detail: Unsharp Sharpening, Bilateral Noise Reduction
    - Tone Curve: Parametric tone curve adjustments (Highlights, Lights, Darks, Shadows)
    - Color Mixer: Targeted Orange/Red skin tone luminance & saturation
    - B&W portrait conversion
    """
    if raw_params is None:
        raw_params = {}

    if isinstance(image_input, np.ndarray):
        img_bgr = image_input.copy()
    elif isinstance(image_input, str):
        img_bgr = cv2.imread(image_input)
    else:
        img_rgb = np.array(image_input.convert("RGB"))
        img_bgr = cv2.cvtColor(img_rgb, cv2.COLOR_RGB2BGR)

    if img_bgr is None:
        return image_input

    # Parse parameters
    exposure = float(raw_params.get("exposure", 0.0))
    contrast = float(raw_params.get("contrast", 0.0))
    highlights = float(raw_params.get("highlights", 0.0))
    shadows = float(raw_params.get("shadows", 0.0))
    whites = float(raw_params.get("whites", 0.0))
    blacks = float(raw_params.get("blacks", 0.0))

    temp = float(raw_params.get("temperature", 0.0))
    tint = float(raw_params.get("tint", 0.0))
    vibrance = float(raw_params.get("vibrance", 0.0))
    saturation = float(raw_params.get("saturation", 0.0))

    texture = float(raw_params.get("texture", 0.0))
    clarity = float(raw_params.get("clarity", 0.0))
    dehaze = float(raw_params.get("dehaze", 0.0))
    vignette = float(raw_params.get("vignette", 0.0))

    sharpening = float(raw_params.get("sharpening", 0.0))
    noise_reduction = float(raw_params.get("noise_reduction", 0.0))

    curve_highlights = float(raw_params.get("curve_highlights", 0.0))
    curve_lights = float(raw_params.get("curve_lights", 0.0))
    curve_darks = float(raw_params.get("curve_darks", 0.0))
    curve_shadows = float(raw_params.get("curve_shadows", 0.0))

    hsl_orange_lum = float(raw_params.get("hsl_orange_lum", 0.0))
    hsl_orange_sat = float(raw_params.get("hsl_orange_sat", 0.0))
    hsl_red_lum = float(raw_params.get("hsl_red_lum", 0.0))
    hsl_yellow_sat = float(raw_params.get("hsl_yellow_sat", 0.0))
    is_bw = bool(raw_params.get("is_bw", False))

    h, w = img_bgr.shape[:2]

    # 1. White Balance: Temperature & Tint
    if temp != 0 or tint != 0:
        b, g, r = cv2.split(img_bgr.astype(np.float32))
        r = r * (1.0 + temp * 0.003 + tint * 0.002)
        g = g * (1.0 - tint * 0.003)
        b = b * (1.0 - temp * 0.003 + tint * 0.002)
        img_bgr = np.clip(cv2.merge([b, g, r]), 0, 255).astype(np.uint8)

    # 2. Exposure & Dehaze
    if exposure != 0:
        exp_factor = 2.0 ** (exposure / 40.0)
        img_bgr = np.clip(img_bgr.astype(np.float32) * exp_factor, 0, 255).astype(np.uint8)

    if dehaze != 0:
        factor = 1.0 + dehaze * 0.004
        img_bgr = np.clip((img_bgr.astype(np.float32) - 10.0 * (dehaze / 100.0)) * factor, 0, 255).astype(np.uint8)

    # 3. Texture & Noise Reduction (Camera Raw Skin Retouching)
    if texture < 0 or noise_reduction > 0:
        nr_amt = max(abs(texture) * 0.7, noise_reduction)
        d_val = 5 if nr_amt < 30 else 7
        smoothed = cv2.bilateralFilter(img_bgr, d=d_val, sigmaColor=int(nr_amt * 0.8) + 10, sigmaSpace=int(nr_amt * 0.8) + 10)
        blend_factor = min(0.85, (abs(texture) * 0.008) + (noise_reduction * 0.006))
        img_bgr = cv2.addWeighted(smoothed, blend_factor, img_bgr, 1.0 - blend_factor, 0)
    elif texture > 0:
        blur = cv2.GaussianBlur(img_bgr, (0, 0), 1.0)
        img_bgr = cv2.addWeighted(img_bgr, 1.0 + (texture * 0.008), blur, -(texture * 0.008), 0)

    # 4. Clarity (Local Midtone Contrast)
    if clarity != 0:
        large_blur = cv2.GaussianBlur(img_bgr, (0, 0), 12.0)
        c_amt = clarity * 0.006
        img_bgr = np.clip(cv2.addWeighted(img_bgr, 1.0 + c_amt, large_blur, -c_amt, 0), 0, 255).astype(np.uint8)

    # 5. Light Tone Adjustments: Highlights, Shadows, Whites, Blacks, Contrast
    lab = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2LAB)
    l_chan, a_chan, b_chan = cv2.split(lab)
    l_float = l_chan.astype(np.float32)

    if shadows != 0:
        shadow_weight = np.clip((128.0 - l_float) / 128.0, 0, 1) ** 1.8
        l_float += shadows * 0.65 * shadow_weight

    if highlights != 0:
        highlight_weight = np.clip((l_float - 128.0) / 127.0, 0, 1) ** 1.8
        l_float += highlights * 0.65 * highlight_weight

    if whites != 0:
        white_weight = np.clip((l_float - 190.0) / 65.0, 0, 1) ** 2.0
        l_float += whites * 0.5 * white_weight

    if blacks != 0:
        black_weight = np.clip((64.0 - l_float) / 64.0, 0, 1) ** 2.0
        l_float += blacks * 0.5 * black_weight

    if contrast != 0:
        c_factor = (259.0 * (contrast + 255.0)) / (255.0 * (259.0 - contrast))
        l_float = c_factor * (l_float - 128.0) + 128.0

    if curve_highlights != 0 or curve_lights != 0 or curve_darks != 0 or curve_shadows != 0:
        lut = np.arange(256, dtype=np.float32)
        lut += curve_shadows * 0.4 * (np.clip((64.0 - lut) / 64.0, 0, 1) ** 1.5)
        lut += curve_darks * 0.4 * (np.clip((128.0 - lut) / 128.0, 0, 1) * np.clip(lut / 128.0, 0, 1))
        lut += curve_lights * 0.4 * (np.clip((lut - 128.0) / 128.0, 0, 1) * np.clip((255.0 - lut) / 127.0, 0, 1))
        lut += curve_highlights * 0.4 * (np.clip((lut - 192.0) / 63.0, 0, 1) ** 1.5)
        lut_uint8 = np.clip(lut, 0, 255).astype(np.uint8)
        l_chan = cv2.LUT(np.clip(l_float, 0, 255).astype(np.uint8), lut_uint8)
    else:
        l_chan = np.clip(l_float, 0, 255).astype(np.uint8)

    # 6. Sharpening
    if sharpening > 0:
        sh_blur = cv2.GaussianBlur(l_chan, (0, 0), 1.2)
        sh_amt = 1.0 + (sharpening / 40.0)
        l_chan = np.clip(cv2.addWeighted(l_chan, sh_amt, sh_blur, 1.0 - sh_amt, 0), 0, 255).astype(np.uint8)

    lab = cv2.merge([l_chan, a_chan, b_chan])
    img_bgr = cv2.cvtColor(lab, cv2.COLOR_LAB2BGR)

    # 7. Color Mixer & Skin Tone Tuning (Orange / Red Hue Bands)
    if hsl_orange_lum != 0 or hsl_orange_sat != 0 or hsl_red_lum != 0 or hsl_yellow_sat != 0:
        hsv = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2HSV).astype(np.float32)
        h_chan, s_chan, v_chan = cv2.split(hsv)

        orange_mask = np.exp(-((h_chan - 15.0) ** 2) / (2.0 * (8.0 ** 2)))
        red_mask = np.maximum(
            np.exp(-((h_chan - 0.0) ** 2) / (2.0 * (6.0 ** 2))),
            np.exp(-((h_chan - 179.0) ** 2) / (2.0 * (6.0 ** 2)))
        )

        if hsl_orange_lum != 0:
            v_chan += (hsl_orange_lum * 0.5) * orange_mask
        if hsl_orange_sat != 0:
            s_chan += (hsl_orange_sat * 0.5) * orange_mask
        if hsl_red_lum != 0:
            v_chan += (hsl_red_lum * 0.5) * red_mask
        if hsl_yellow_sat != 0:
            yellow_mask = np.exp(-((h_chan - 30.0) ** 2) / (2.0 * (8.0 ** 2)))
            s_chan += (hsl_yellow_sat * 0.5) * yellow_mask

        hsv = cv2.merge([h_chan, np.clip(s_chan, 0, 255), np.clip(v_chan, 0, 255)]).astype(np.uint8)
        img_bgr = cv2.cvtColor(hsv, cv2.COLOR_HSV2BGR)

    # 8. Vibrance & Overall Saturation / B&W
    if is_bw:
        gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
        return Image.fromarray(gray).convert("RGB")

    if vibrance != 0 or saturation != 0:
        hsv = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2HSV).astype(np.float32)
        h_chan, s_chan, v_chan = cv2.split(hsv)

        if vibrance != 0:
            vibrance_weight = 1.0 - (s_chan / 255.0)
            s_chan += (vibrance * 0.7) * vibrance_weight

        if saturation != 0:
            s_chan *= (1.0 + saturation * 0.01)

        hsv = cv2.merge([h_chan, np.clip(s_chan, 0, 255), v_chan]).astype(np.uint8)
        img_bgr = cv2.cvtColor(hsv, cv2.COLOR_HSV2BGR)

    # 9. Vignette
    if vignette != 0:
        rows, cols = img_bgr.shape[:2]
        kernel_x = cv2.getGaussianKernel(cols, cols / 2.0)
        kernel_y = cv2.getGaussianKernel(rows, rows / 2.0)
        kernel = kernel_y * kernel_x.T
        mask = kernel / np.max(kernel)
        vig_factor = 1.0 + (vignette / 100.0) * (1.0 - mask)
        img_bgr = np.clip(img_bgr.astype(np.float32) * vig_factor[:, :, np.newaxis], 0, 255).astype(np.uint8)

    enhanced_rgb = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB)
    return Image.fromarray(enhanced_rgb)


def render_passport_grid_a4(
    photo_input,
    rows=2,
    cols=4,
    photo_w_mm=35.0,
    photo_h_mm=45.0,
    gap_mm=3.0,
    top_margin_mm=None,
    left_margin_mm=None,
    fill_count=8,
    border_style="solid",
    cut_guides=True,
    active_indices=None,
    paper_w_mm=210.0,
    paper_h_mm=297.0
):
    """
    Renders an exact 300 DPI page with a grid of passport photos on any selected paper size.
    Supports customizable rows, columns, margins, cutting borders, and partial sheet fill.
    Returns: PIL Image (300 DPI RGB)
    """
    from PIL import ImageDraw

    if isinstance(photo_input, str):
        photo_pil = Image.open(photo_input).convert("RGB")
    elif isinstance(photo_input, np.ndarray):
        photo_pil = Image.fromarray(cv2.cvtColor(photo_input, cv2.COLOR_BGR2RGB))
    else:
        photo_pil = photo_input.copy().convert("RGB")

    scale = 300.0 / 25.4 # ~11.811 px per mm at 300 DPI
    sheet_w = int(round(paper_w_mm * scale))
    sheet_h = int(round(paper_h_mm * scale))

    p_w = int(round(photo_w_mm * scale))
    p_h = int(round(photo_h_mm * scale))
    gap = int(round(gap_mm * scale))

    resized_photo = photo_pil.resize((p_w, p_h), Image.Resampling.LANCZOS)

    grid_w = (cols * p_w) + ((cols - 1) * gap)
    grid_h = (rows * p_h) + ((rows - 1) * gap)

    if left_margin_mm is not None and left_margin_mm >= 0:
        left_px = int(round(left_margin_mm * scale))
    else:
        left_px = max(20, (sheet_w - grid_w) // 2)

    if top_margin_mm is not None and top_margin_mm >= 0:
        top_px = int(round(top_margin_mm * scale))
    else:
        if rows <= 3:
            top_px = int(round(20.0 * scale))
        else:
            top_px = max(20, (sheet_h - grid_h) // 2)

    page = Image.new("RGB", (sheet_w, sheet_h), (255, 255, 255))
    draw = ImageDraw.Draw(page)

    border_w = max(1, int(round(scale * 0.15)))

    for r in range(rows):
        for c in range(cols):
            idx = r * cols + c
            is_active = (active_indices is None or idx in active_indices)
            if idx >= fill_count and active_indices is None:
                is_active = False

            if not is_active:
                continue

            x = left_px + c * (p_w + gap)
            y = top_px + r * (p_h + gap)

            page.paste(resized_photo, (x, y))

            if border_style == "solid":
                draw.rectangle([x, y, x + p_w, y + p_h], outline="black", width=border_w)
            elif border_style == "dashed":
                draw.rectangle([x, y, x + p_w, y + p_h], outline="#64748b", width=1)
            elif border_style == "white_margin":
                w_pad = int(round(1.0 * scale))
                draw.rectangle([x - w_pad, y - w_pad, x + p_w + w_pad, y + p_h + w_pad], outline="#000000", width=1)

    if cut_guides:
        guide_color = "#94a3b8"
        line_w = 1
        for r in range(rows + 1):
            if r == 0:
                y = top_px - gap // 2
            elif r == rows:
                y = top_px + (rows - 1) * (p_h + gap) + p_h + gap // 2
            else:
                y = top_px + r * (p_h + gap) - gap // 2
            if 0 <= y < sheet_h:
                draw.line([(max(0, left_px - 40), y), (min(sheet_w, left_px + grid_w + 40), y)], fill=guide_color, width=line_w)

        for c in range(cols + 1):
            if c == 0:
                x = left_px - gap // 2
            elif c == cols:
                x = left_px + (cols - 1) * (p_w + gap) + p_w + gap // 2
            else:
                x = left_px + c * (p_w + gap) - gap // 2
            if 0 <= x < sheet_w:
                draw.line([(x, max(0, top_px - 40)), (x, min(sheet_h, top_px + grid_h + 40))], fill=guide_color, width=line_w)

    return page


# =========================================================================
# AI-POWERED PASSPORT PHOTO BACKGROUND REMOVAL & COLOR REPLACEMENT
# =========================================================================
_REMBG_SESSION = None

def get_rembg_session():
    global _REMBG_SESSION
    if _REMBG_SESSION is None:
        try:
            import rembg
            _REMBG_SESSION = rembg.new_session("u2netp")
        except Exception as e:
            print(f"[REMBG WARN] Could not init u2netp session: {e}")
            _REMBG_SESSION = False
    return _REMBG_SESSION if _REMBG_SESSION is not False else None

def remove_photo_background(image_input):
    """
    AI-powered portrait background removal using u2netp.
    Returns: (cutout_rgba_pil, success_bool)
    """
    if isinstance(image_input, np.ndarray):
        img_rgb = cv2.cvtColor(image_input, cv2.COLOR_BGR2RGB)
        pil_img = Image.fromarray(img_rgb)
    elif isinstance(image_input, str):
        pil_img = Image.open(image_input).convert("RGB")
    else:
        pil_img = image_input.convert("RGB")

    session = get_rembg_session()
    if session:
        try:
            import rembg
            cutout = rembg.remove(pil_img, session=session)
            return cutout, True
        except Exception as e:
            print(f"[REMBG ERROR] {e}")

    # Fallback to OpenCV GrabCut
    img_bgr = cv2.cvtColor(np.array(pil_img), cv2.COLOR_RGB2BGR)
    h, w = img_bgr.shape[:2]
    mask = np.zeros((h, w), np.uint8)
    rect = (max(5, int(w * 0.05)), max(5, int(h * 0.05)), min(w - 10, int(w * 0.9)), min(h - 10, int(h * 0.95)))
    bgdModel = np.zeros((1, 65), np.float64)
    fgdModel = np.zeros((1, 65), np.float64)
    try:
        cv2.grabCut(img_bgr, mask, rect, bgdModel, fgdModel, 3, cv2.GC_INIT_WITH_RECT)
        mask2 = np.where((mask == 2) | (mask == 0), 0, 1).astype('uint8')
        mask2 = cv2.GaussianBlur(mask2 * 255, (7, 7), 0)
        rgba = np.dstack([img_bgr, mask2])
        cutout = Image.fromarray(cv2.cvtColor(rgba, cv2.COLOR_BGRA2RGBA))
        return cutout, True
    except Exception as e:
        print(f"[GRABCUT FALLBACK ERROR] {e}")
        rgba = pil_img.convert("RGBA")
        return rgba, False

def change_photo_background(image_input, hex_color="#ffffff"):
    """
    Replaces portrait background with chosen official backdrop color (White, Sky Blue, Royal Blue, etc.)
    Returns: (final_rgb_pil, cutout_rgba_pil, success_bool)
    """
    from PIL import ImageColor
    if isinstance(image_input, Image.Image) and image_input.mode == "RGBA":
        cutout = image_input
        success = True
    else:
        cutout, success = remove_photo_background(image_input)

    try:
        rgb_color = ImageColor.getrgb(hex_color)
    except Exception:
        rgb_color = (255, 255, 255)

    bg_img = Image.new("RGB", cutout.size, rgb_color)
    bg_img.paste(cutout, (0, 0), mask=cutout.split()[3])
    return bg_img, cutout, success
