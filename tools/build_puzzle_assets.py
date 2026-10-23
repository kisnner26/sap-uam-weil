#!/usr/bin/env python3
"""Corta cada item del Weil en una figura base (con su hueco) y 8 piezas sueltas.

Lee   frontend/assets/weill-pages/lamina-L.png  (las 5 laminas completas, 12 items cada una)
Crea  frontend/assets/weill-puzzle/l{L}i{II}-base.png y l{L}i{II}-p{1..8}.png
      frontend/puzzle-data.js  (tamano de cada base y posicion del hueco)

Las piezas se numeran por filas, de izquierda a derecha, igual que las opciones
1..8 de la clave de respuestas, asi que arrastrar la pieza N equivale a
responder N. Requiere: pip install opencv-python-headless numpy pillow

Uso:  python3 tools/build_puzzle_assets.py [--debug carpeta]
"""
import argparse
import json
import pathlib
import sys

import cv2
import numpy as np

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "frontend" / "assets" / "weill-pages"
OUT = ROOT / "frontend" / "assets" / "weill-puzzle"
DATA = ROOT / "frontend" / "puzzle-data.js"
SCALE = 2          # las imagenes originales son de ~390 px: se amplian para verse nitidas
INK = 150          # por debajo de este gris es tinta
OPTIONS = 8
MIN_FIT = 1.5      # ajuste minimo de la cuadricula de opciones (maximo teorico ~8)


class DetectionError(Exception):
    pass


def ink_mask(gray):
    return (gray < INK).astype(np.uint8)


def find_page_frames(gray):
    """Los 12 marcos de una lamina (3 columnas x 4 filas), en orden de lectura."""
    ink = ink_mask(gray)
    _, _, stats, _ = cv2.connectedComponentsWithStats(ink, connectivity=8)
    frames = [(int(x), int(y), int(w), int(h)) for x, y, w, h, _ in stats[1:] if w >= 270 and h >= 300]
    if len(frames) != 12:
        raise DetectionError(f"se esperaban 12 marcos y hay {len(frames)}")
    frames.sort(key=lambda b: (round(b[1] / 200.0), b[0]))
    return frames


def find_frame(ink):
    """Rectangulo del marco exterior: el componente conexo mas grande que casi llena la imagen."""
    h, w = ink.shape
    count, _, stats, _ = cv2.connectedComponentsWithStats(ink, connectivity=8)
    best = None
    for i in range(1, count):
        x, y, cw, ch, area = stats[i]
        if cw > 0.8 * w and ch > 0.55 * h and (best is None or cw * ch > best[2] * best[3]):
            best = (x, y, cw, ch)
    return best


def _ring(cw, ch, thickness=4):
    ring = np.zeros((ch, cw), np.float32)
    ring[:thickness, :] = 1
    ring[-thickness:, :] = 1
    ring[:, :thickness] = 1
    ring[:, -thickness:] = 1
    return ring


def find_option_cells(ink, frame):
    """Las 8 celdas de opciones, numeradas por filas de izquierda a derecha.

    Busca la cuadricula 2x4 que mejor encaja: el borde de cada celda debe ser
    tinta y las calles entre celdas deben estar en blanco. Una zona de rayado
    denso no cumple lo segundo, asi que no se confunde con las opciones.
    """
    fx, fy, fw, fh = [int(v) for v in frame]
    img_h, img_w = ink.shape
    integral = cv2.integral(ink)

    def box(x, y, w, h):  # suma de tinta en un rectangulo, vectorizada sobre x/y
        return integral[y + h, x + w] - integral[y, x + w] - integral[y + h, x] + integral[y, x]

    ink_f = ink.astype(np.float32)
    best = None
    for cw in range(66, 90, 4):
        for ch in range(42, 64, 4):
            ring = _ring(cw, ch)
            score_map = cv2.matchTemplate(ink_f, ring, cv2.TM_CCORR) / ring.sum()
            for gx in range(4, 13, 2):
                for gy in range(2, 11, 2):
                    px, py = cw + gx, ch + gy
                    total_h = py + ch
                    total_w = 3 * px + cw
                    xs = np.arange(max(0, fx), min(img_w - total_w, fx + 45) + 1)
                    y_lo = max(0, fy + fh - 34 - total_h)
                    y_hi = min(img_h - total_h, fy + fh + 2 - total_h)
                    if len(xs) == 0 or y_hi < y_lo:
                        continue
                    ys = np.arange(y_lo, y_hi + 1)
                    X, Y = np.meshgrid(xs, ys)
                    fit = np.zeros(X.shape, np.float32)
                    for r in range(2):
                        for c in range(4):
                            fit += score_map[Y + r * py, X + c * px]
                    gutters = np.zeros(X.shape, np.float32)
                    for c in range(1, 4):
                        gutters += box(X + c * px - gx, Y, gx, total_h)
                    gutters += box(X, Y + ch, total_w, gy)
                    objective = fit - 0.08 * gutters
                    k = np.unravel_index(np.argmax(objective), objective.shape)
                    value = float(objective[k])
                    if best is None or value > best[0]:
                        best = (value, int(X[k]), int(Y[k]), cw, ch, px, py)
    if best is None or best[0] < MIN_FIT:
        raise DetectionError(f"no se hallo la cuadricula de opciones (ajuste {best[0] if best else 0:.2f})")
    _, x0, y0, cw, ch, px, py = best
    return [(x0 + c * px, y0 + r * py, cw, ch) for r in range(2) for c in range(4)]


def find_patch(ink, frame, cells):
    """El hueco: region blanca cerrada, casi rectangular, sin tinta dentro y de tamano parecido a una celda."""
    fx, fy, fw, fh = [int(v) for v in frame]
    cell_w = float(np.median([c[2] for c in cells]))
    cell_h = float(np.median([c[3] for c in cells]))
    options_top = min(c[1] for c in cells)
    closed = cv2.dilate(ink, np.ones((3, 3), np.uint8), iterations=1)
    white = (closed == 0).astype(np.uint8)
    count, _, stats, _ = cv2.connectedComponentsWithStats(white, connectivity=4)
    candidates = []
    for i in range(1, count):
        x, y, cw, ch, area = stats[i]
        if y + ch > options_top - 2:
            continue
        if x <= fx + 3 or x + cw >= fx + fw - 3 or y <= fy + 3:
            continue
        extent = area / float(cw * ch)
        ratio = cw / float(ch)
        if (0.7 * cell_w <= cw <= 2.3 * cell_w and 0.7 * cell_h <= ch <= 2.6 * cell_h
                and extent > 0.9 and 1.2 <= ratio <= 3.2):
            size_gap = abs(cw - 1.35 * cell_w) / cell_w + abs(ch - 1.35 * cell_h) / cell_h
            candidates.append((3.0 * extent - size_gap, (int(x), int(y), int(cw), int(ch))))
    if candidates:
        candidates.sort(key=lambda c: -c[0])
        return candidates[0][1]
    return find_patch_by_ring(ink, frame, cells)


def find_patch_by_ring(ink, frame, cells):
    """Alterno cuando el borde del hueco toca el dibujo: busca un marco ondulado con el interior en blanco."""
    fx, fy, fw, fh = [int(v) for v in frame]
    cell_w = int(np.median([c[2] for c in cells]))
    cell_h = int(np.median([c[3] for c in cells]))
    options_top = min(c[1] for c in cells)
    integral = cv2.integral(ink)
    ink_f = ink.astype(np.float32)
    best = None
    for cw in range(int(0.9 * cell_w), int(1.9 * cell_w), 4):
        for ch in range(int(0.9 * cell_h), int(1.9 * cell_h), 4):
            if not 1.2 <= cw / ch <= 3.2:
                continue
            ring = _ring(cw, ch, 3)
            score = cv2.matchTemplate(ink_f, ring, cv2.TM_CCORR) / ring.sum()
            ys, xs = np.mgrid[0:score.shape[0], 0:score.shape[1]]
            ix0, iy0, iw, ih = xs + 5, ys + 5, cw - 10, ch - 10
            inner = (integral[iy0 + ih, ix0 + iw] - integral[iy0, ix0 + iw]
                     - integral[iy0 + ih, ix0] + integral[iy0, ix0]).astype(np.float32)
            objective = score - 8.0 * inner / float(iw * ih)
            valid = (ys + ch <= options_top - 2) & (xs >= fx + 4) & (xs + cw <= fx + fw - 2) & (ys >= fy + 4)
            objective = np.where(valid, objective, -9)
            k = np.unravel_index(np.argmax(objective), objective.shape)
            value = float(objective[k])
            if best is None or value > best[0]:
                best = (value, int(xs[k]), int(ys[k]), cw, ch)
    if best is None or best[0] < 0.45:
        raise DetectionError("no se encontro el hueco")
    _, x, y, cw, ch = best
    return (x + 3, y + 3, cw - 6, ch - 6)


def enhance(gray):
    """Amplia y refuerza el trazo para que la linea se vea nitida."""
    big = cv2.resize(gray, None, fx=SCALE, fy=SCALE, interpolation=cv2.INTER_CUBIC)
    big = np.clip((big.astype(np.float32) - 70.0) * 1.55, 0, 255).astype(np.uint8)
    return big


def process(page, frame_box, name, debug_dir=None, borrowed_cells=None):
    """Corta un item de la pagina: figura base, hueco y 8 piezas."""
    margin = 4
    fx0, fy0, fw0, fh0 = frame_box
    ox, oy = max(0, fx0 - margin), max(0, fy0 - margin)
    gray = page[oy:fy0 + fh0 + margin, ox:fx0 + fw0 + margin]
    ink = ink_mask(gray)
    frame = (fx0 - ox, fy0 - oy, fw0, fh0)
    cells = borrowed_cells(ox, oy) if borrowed_cells else find_option_cells(ink, frame)
    patch = find_patch(ink, frame, cells)

    fx, fy, fw, fh = frame
    options_top = min(c[1] for c in cells)
    inset = 4
    px, py, pw, ph = patch
    margin_patch = 6  # cubre el borde grueso y ondulado del hueco, que queda por fuera de la region blanca
    base_x0 = fx + inset
    # En las laminas cortadas por el borde del escaneo el marco derecho falta y el hueco puede
    # sobresalir un poco: la figura se amplia lo necesario para contenerlo.
    base_x1 = min(gray.shape[1], max(fx + fw - inset, px + pw + margin_patch + 2))
    base_y0 = fy + inset
    base_y1 = options_top - 5
    base = gray[base_y0:base_y1, base_x0:base_x1]

    zx, zy = px - margin_patch - base_x0, py - margin_patch - base_y0
    zone = (zx, zy, min(pw + 2 * margin_patch, base.shape[1] - zx), min(ph + 2 * margin_patch, base.shape[0] - zy))

    pieces = []
    for x, y, cw, ch in cells:
        m = 2
        pieces.append(gray[max(0, y - m):y + ch + m, max(0, x - m):x + cw + m])

    if debug_dir:
        vis = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)
        cv2.rectangle(vis, (fx, fy), (fx + fw, fy + fh), (200, 120, 0), 1)
        cv2.rectangle(vis, (px, py), (px + pw, py + ph), (0, 0, 255), 2)
        for n, (x, y, cw, ch) in enumerate(cells, 1):
            cv2.rectangle(vis, (x, y), (x + cw, y + ch), (0, 160, 0), 1)
            cv2.putText(vis, str(n), (x + 2, y + 12), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (255, 0, 255), 1)
        cv2.imwrite(str(pathlib.Path(debug_dir) / name), vis)

    return {
        "cells_page": [(x + ox, y + oy, cw, ch) for x, y, cw, ch in cells],
        "base": enhance(base),
        "pieces": [enhance(p) for p in pieces],
        "size": [int(base.shape[1] * SCALE), int(base.shape[0] * SCALE)],
        "patch": [int(v * SCALE) for v in zone],
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--debug", help="carpeta donde guardar las imagenes con las detecciones dibujadas")
    parser.add_argument("--dry-run", action="store_true", help="solo detectar, sin escribir archivos")
    args = parser.parse_args()
    if args.debug:
        pathlib.Path(args.debug).mkdir(parents=True, exist_ok=True)
    if not args.dry_run:
        OUT.mkdir(parents=True, exist_ok=True)

    manifest, failures = [], []
    for lamina in range(1, 6):
        page = cv2.imread(str(SRC / f"lamina-{lamina}.png"), cv2.IMREAD_GRAYSCALE)
        frames = find_page_frames(page)
        results = {}
        deferred = []
        for item, frame_box in enumerate(frames, 1):
            name = f"lamina-{lamina}-item-{item:02d}.png"
            try:
                results[item] = process(page, frame_box, name, args.debug)
            except DetectionError as error:
                if "cuadricula" in str(error):
                    deferred.append(item)  # se reintenta con la disposicion de otro item de su columna
                else:
                    failures.append(f"{name}: {error}")
                    results[item] = None
        for item in deferred:
            name = f"lamina-{lamina}-item-{item:02d}.png"
            donors = [d for d in range(1, 13) if d % 3 == item % 3 and results.get(d)]
            if not donors:
                failures.append(f"{name}: sin item de la misma columna para tomar la disposicion")
                results[item] = None
                continue
            donor = min(donors, key=lambda d: abs(d - item))
            dfx, dfy, dfw, dfh = frames[donor - 1]
            tfx, tfy, tfw, tfh = frames[item - 1]

            def borrowed(ox, oy, donor=donor, dfx=dfx, dfy=dfy, dfh=dfh, tfx=tfx, tfy=tfy, tfh=tfh):
                moved = []
                for x, y, cw, ch in results[donor]["cells_page"]:
                    nx = tfx + (x - dfx)
                    ny = (tfy + tfh) - ((dfy + dfh) - y)
                    moved.append((nx - ox, ny - oy, cw, ch))
                return moved
            try:
                results[item] = process(page, frames[item - 1], name, args.debug, borrowed)
            except DetectionError as error:
                failures.append(f"{name}: {error}")
                results[item] = None
        for item in range(1, 13):
            result = results.get(item)
            if result is None:
                manifest.append(None)
                continue
            key = f"l{lamina}i{item:02d}"
            if not args.dry_run:
                cv2.imwrite(str(OUT / f"{key}-base.png"), result["base"], [cv2.IMWRITE_PNG_COMPRESSION, 9])
                for n, piece in enumerate(result["pieces"], 1):
                    cv2.imwrite(str(OUT / f"{key}-p{n}.png"), piece, [cv2.IMWRITE_PNG_COMPRESSION, 9])
            manifest.append({"w": result["size"][0], "h": result["size"][1], "patch": result["patch"]})

    if failures:
        print("FALLARON:\n  " + "\n  ".join(failures), file=sys.stderr)
        return 1
    if not args.dry_run:
        body = json.dumps({"weil-form-a": manifest}, separators=(",", ":"))
        DATA.write_text(
            "// Generado por tools/build_puzzle_assets.py. No editar a mano.\n"
            f"window.SAP_UAM_PUZZLE = Object.freeze({body});\n", encoding="utf-8")
    print(f"ok: {len(manifest)} items")
    return 0


if __name__ == "__main__":
    sys.exit(main())
