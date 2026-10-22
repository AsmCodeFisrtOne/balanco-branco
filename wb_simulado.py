"""
Balanço de branco simulado (software) para câmera USB / endoscopia.

Fluxo igual ao de uma CCU comercial:
  1. Aponte a câmera para a referência branca (gaze/alvo) preenchendo o quadrado central.
  2. Tecle W -> mede R,G,B médios na ROI, calcula os ganhos no espaço LINEAR e TRAVA.
  3. Os ganhos ficam fixos (sem AWB contínuo) até novo W ou R (reset).

A correção é feita por 3 LUTs de 256 entradas (sRGB -> linear -> ganho -> sRGB),
o que é barato e portável para MCU/FPGA (3 x 256 bytes).

Teclas:  W = balanço de branco   R = reset   S = salvar ganhos   Q/ESC = sair

Uso:
  pip install opencv-python numpy            (opcional: pip install pyvirtualcam)
  python wb_simulado.py --cam 0
  python wb_simulado.py --cam 1 --width 1280 --height 720 --virtual
"""
import argparse
import json
import os

import cv2
import numpy as np

GAINS_FILE = "wb_ganhos.json"


# ---------- conversões sRGB <-> linear ----------
def srgb_to_lin(v):
    v = np.asarray(v, dtype=np.float64)
    return np.where(v <= 0.04045, v / 12.92, ((v + 0.055) / 1.055) ** 2.4)


def lin_to_srgb(v):
    v = np.clip(np.asarray(v, dtype=np.float64), 0.0, 1.0)
    return np.where(v <= 0.0031308, v * 12.92, 1.055 * np.power(v, 1 / 2.4) - 0.055)


LIN_TABLE = srgb_to_lin(np.arange(256) / 255.0)


# ---------- LUTs ----------
def build_lut(gain):
    out = lin_to_srgb(LIN_TABLE * gain)
    return np.clip(np.round(out * 255.0), 0, 255).astype(np.uint8)


def build_luts(gains_rgb):
    return [build_lut(g) for g in gains_rgb]  # [R, G, B]


def apply_wb(frame_bgr, luts_rgb):
    b, g, r = cv2.split(frame_bgr)
    return cv2.merge([cv2.LUT(b, luts_rgb[2]),
                      cv2.LUT(g, luts_rgb[1]),
                      cv2.LUT(r, luts_rgb[0])])


# ---------- medição ----------
def roi_rect(shape, frac):
    h, w = shape[:2]
    s = max(4, int(min(h, w) * frac))
    return (w - s) // 2, (h - s) // 2, s


def measure(frame_bgr, frac):
    x0, y0, s = roi_rect(frame_bgr.shape, frac)
    roi = frame_bgr[y0:y0 + s, x0:x0 + s].reshape(-1, 3)
    mean_rgb = roi.mean(axis=0)[::-1]                 # R, G, B (0..255)
    sat_rgb = (roi >= 250).mean(axis=0)[::-1]         # fração saturada por canal
    return mean_rgb, sat_rgb


def compute_gains(mean_rgb, norm):
    lin = np.maximum(srgb_to_lin(mean_rgb / 255.0), 1e-4)
    if norm == "g":
        target = lin[1]          # verde como referência (ganhos podem passar de 1)
    else:
        target = lin.min()       # todos os ganhos <= 1 (não estoura nada)
    return target / lin


# ---------- persistência ----------
def load_gains():
    if os.path.exists(GAINS_FILE):
        try:
            with open(GAINS_FILE, "r", encoding="utf-8") as f:
                g = json.load(f)["gains_rgb"]
            return np.array(g, dtype=np.float64)
        except Exception:
            pass
    return np.ones(3)


def save_gains(gains, mean_rgb):
    with open(GAINS_FILE, "w", encoding="utf-8") as f:
        json.dump({"gains_rgb": [round(float(x), 5) for x in gains],
                   "ref_mean_rgb": [round(float(x), 2) for x in mean_rgb]}, f, indent=2)


# ---------- UI ----------
def put(img, text, y, color=(255, 255, 255)):
    cv2.putText(img, text, (10, y), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (0, 0, 0), 3, cv2.LINE_AA)
    cv2.putText(img, text, (10, y), cv2.FONT_HERSHEY_SIMPLEX, 0.55, color, 1, cv2.LINE_AA)


def fmt(v):
    return "R %6.1f  G %6.1f  B %6.1f" % tuple(v)


def main():
    ap = argparse.ArgumentParser(description="Balanço de branco simulado")
    ap.add_argument("--cam", type=int, default=0, help="índice da câmera")
    ap.add_argument("--width", type=int, default=0)
    ap.add_argument("--height", type=int, default=0)
    ap.add_argument("--roi", type=float, default=0.25, help="lado da ROI central (fração)")
    ap.add_argument("--norm", choices=["min", "g"], default="min",
                    help="min: ganhos <= 1 (recomendado) | g: referência no verde")
    ap.add_argument("--virtual", action="store_true", help="enviar saída corrigida p/ câmera virtual")
    args = ap.parse_args()

    backend = cv2.CAP_DSHOW if os.name == "nt" else cv2.CAP_ANY
    cap = cv2.VideoCapture(args.cam, backend)
    if not cap.isOpened():
        raise SystemExit("Não foi possível abrir a câmera %d" % args.cam)
    if args.width and args.height:
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, args.width)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, args.height)
    # Tenta desligar o AWB da própria câmera (nem toda câmera aceita)
    cap.set(cv2.CAP_PROP_AUTO_WB, 0)

    ok, frame = cap.read()
    if not ok:
        raise SystemExit("Falha ao ler frame")
    h, w = frame.shape[:2]

    vcam = None
    if args.virtual:
        try:
            import pyvirtualcam
            vcam = pyvirtualcam.Camera(width=w, height=h, fps=30,
                                       fmt=pyvirtualcam.PixelFormat.BGR)
            print("Câmera virtual:", vcam.device)
        except Exception as e:
            print("Câmera virtual indisponível:", e)

    gains = load_gains()
    luts = build_luts(gains)
    status = "Ganhos carregados" if not np.allclose(gains, 1) else "Sem balanço (ganhos = 1)"

    while True:
        ok, frame = cap.read()
        if not ok:
            break
        corr = apply_wb(frame, luts)

        m_in, sat = measure(frame, args.roi)
        m_out, _ = measure(corr, args.roi)

        x0, y0, s = roi_rect(frame.shape, args.roi)
        left, right = frame.copy(), corr.copy()
        for img in (left, right):
            cv2.rectangle(img, (x0, y0), (x0 + s, y0 + s), (0, 255, 255), 2)

        put(left, "ORIGINAL   " + fmt(m_in), 22)
        put(right, "CORRIGIDO  " + fmt(m_out), 22)
        put(right, "Ganhos  R %.3f  G %.3f  B %.3f" % tuple(gains), 46)
        put(right, status, 70, (0, 255, 0))
        if (sat > 0.02).any():
            put(left, "ATENCAO: canal saturado na ROI - reduza luz/exposicao", 46, (0, 0, 255))
        put(left, "W=balanco  R=reset  S=salvar  Q=sair", h - 12)

        view = np.hstack([left, right])
        if view.shape[1] > 1600:
            k = 1600 / view.shape[1]
            view = cv2.resize(view, None, fx=k, fy=k)
        cv2.imshow("Balanco de branco simulado", view)

        if vcam is not None:
            vcam.send(corr)

        key = cv2.waitKey(1) & 0xFF
        if key in (ord("q"), 27):
            break
        elif key == ord("w"):
            if (sat > 0.02).any():
                status = "Recusado: referencia saturada"
            else:
                gains = compute_gains(m_in, args.norm)
                luts = build_luts(gains)
                status = "Balanco travado"
                print("Ref RGB:", np.round(m_in, 1), "-> ganhos:", np.round(gains, 4))
        elif key == ord("r"):
            gains = np.ones(3)
            luts = build_luts(gains)
            status = "Reset (ganhos = 1)"
        elif key == ord("s"):
            save_gains(gains, m_in)
            status = "Salvo em " + GAINS_FILE

    cap.release()
    if vcam is not None:
        vcam.close()
    cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
