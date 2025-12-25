import json
import time
from pathlib import Path

import cv2
import numpy as np
import mss
import mss.tools
import easyocr
from ultralytics import YOLO


def xyxy_to_box(xyxy):
    x1, y1, x2, y2 = [float(v) for v in xyxy]
    return {
        "x1": x1, "y1": y1, "x2": x2, "y2": y2,
        "w": x2 - x1, "h": y2 - y1,
        "cx": (x1 + x2) / 2.0,
        "cy": (y1 + y2) / 2.0,
    }


def ocr_bbox_to_box(quad):
    pts = np.array(quad, dtype=np.float32)
    x1 = float(np.min(pts[:, 0]))
    y1 = float(np.min(pts[:, 1]))
    x2 = float(np.max(pts[:, 0]))
    y2 = float(np.max(pts[:, 1]))
    return {
        "x1": x1, "y1": y1, "x2": x2, "y2": y2,
        "w": x2 - x1, "h": y2 - y1,
        "cx": (x1 + x2) / 2.0,
        "cy": (y1 + y2) / 2.0,
    }


def capture_screen_png(path: str) -> None:
    out_path = Path(path)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    with mss.mss() as sct:
        monitor = sct.monitors[0]  # all screens combined
        img = sct.grab(monitor)
        mss.tools.to_png(img.rgb, img.size, output=str(out_path))


def run_vision_on_image(
    image_path: str,
    yolo: YOLO,
    reader: easyocr.Reader,
    yolo_conf: float,
):
    img_bgr = cv2.imread(image_path)
    if img_bgr is None:
        raise FileNotFoundError(f"Could not read image: {image_path}")
    h, w = img_bgr.shape[:2]

    yolo_res = yolo(image_path, conf=yolo_conf, verbose=False)[0]
    yolo_items = []
    if yolo_res.boxes is not None and len(yolo_res.boxes) > 0:
        names = yolo_res.names
        for i, box in enumerate(yolo_res.boxes):
            cls_id = int(box.cls[0].item())
            conf = float(box.conf[0].item())
            xyxy = box.xyxy[0].tolist()
            yolo_items.append(
                {
                    "id": f"yolo_{i}",
                    "label": str(names.get(cls_id, cls_id)),
                    "confidence": conf,
                    "box": xyxy_to_box(xyxy),
                }
            )

    img_rgb = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB)
    try:
        ocr_raw = reader.readtext(img_rgb)
    except ValueError:
        img_gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
        ocr_raw = reader.readtext(img_gray)

    ocr_items = []
    for i, (bbox, text, conf) in enumerate(ocr_raw):
        ocr_items.append(
            {
                "id": f"ocr_{i}",
                "text": text,
                "confidence": float(conf),
                "box": ocr_bbox_to_box(bbox),
                "quad": [[float(p[0]), float(p[1])] for p in bbox],
            }
        )

    return {
        "meta": {
            "schema": "ui_snapshot_v1",
            "created_unix": int(time.time()),
            "image_path": image_path,
            "image_size": {"w": w, "h": h},
            "yolo": {"model": "yolov8n.pt", "conf_threshold": yolo_conf},
            "ocr": {"engine": "easyocr", "gpu": bool(getattr(reader, "gpu", False))},
        },
        "detections": {"yolo": yolo_items, "ocr": ocr_items},
    }


def atomic_write_text(path: Path, text: str) -> None:
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(text, encoding="utf-8")
    tmp.replace(path)


def main():
    out_dir = Path("snapshots")
    out_dir.mkdir(parents=True, exist_ok=True)

    interval_sec = 1.0
    yolo_conf = 0.25

    yolo = YOLO("yolov8n.pt")
    reader = easyocr.Reader(["en", "de"], gpu=True)

    latest_png = out_dir / "latest.png"
    latest_json = out_dir / "latest.json"

    frame_id = 0

    while True:
        frame_id += 1

        capture_screen_png(str(latest_png))

        snap = run_vision_on_image(
            image_path=str(latest_png),
            yolo=yolo,
            reader=reader,
            yolo_conf=yolo_conf,
        )

        snap["meta"]["frame_id"] = frame_id

        atomic_write_text(latest_json, json.dumps(snap, indent=2))
        print(f"Updated snapshot: frame_id={frame_id}")

        time.sleep(interval_sec)


if __name__ == "__main__":
    main()
