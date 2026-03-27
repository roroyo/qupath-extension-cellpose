import argparse
import json
from pathlib import Path

import cv2
import numpy as np
from cellpose.models import CellposeModel


def parse_args():
    parser = argparse.ArgumentParser(description="Run Cellpose on a single image patch and export polygons.")
    parser.add_argument("--input", required=True, help="Input image path")
    parser.add_argument("--output", required=True, help="Output JSON path")
    parser.add_argument("--labels-output", required=True, help="Output labels PNG path")
    parser.add_argument("--model", default="cyto3", help="Cellpose model type")
    parser.add_argument("--diameter", type=float, default=None, help="Estimated object diameter")
    return parser.parse_args()


def load_image(path: Path) -> np.ndarray:
    image = cv2.imread(str(path), cv2.IMREAD_UNCHANGED)
    if image is None:
        raise FileNotFoundError(f"Unable to read image: {path}")
    if image.ndim == 2:
        return image
    if image.ndim == 3 and image.shape[2] == 4:
        image = image[:, :, :3]
    return cv2.cvtColor(image, cv2.COLOR_BGR2RGB)


def masks_to_polygons(masks: np.ndarray):
    polygons = []
    max_label = int(masks.max())
    for label in range(1, max_label + 1):
        binary = (masks == label).astype(np.uint8)
        if binary.max() == 0:
            continue

        contours, _ = cv2.findContours(binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        if not contours:
            continue

        contour = max(contours, key=cv2.contourArea)
        if contour.shape[0] < 3:
            continue

        points = contour[:, 0, :].astype(float).tolist()
        polygons.append({
            "label": label,
            "points": [{"x": float(x), "y": float(y)} for x, y in points],
        })
    return polygons


def main():
    args = parse_args()
    input_path = Path(args.input)
    output_path = Path(args.output)
    labels_output_path = Path(args.labels_output)

    image = load_image(input_path)
    model = CellposeModel(model_type=args.model, gpu=True)
    masks, _, _ = model.eval(image, 
        diameter=args.diameter, 
        channels=[0, 0]
        )

    labels_output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    labels_to_write = np.clip(masks, 0, 65535).astype(np.uint16)
    cv2.imwrite(str(labels_output_path), labels_to_write)

    result = {
        "input": str(input_path),
        "model": args.model,
        "diameter": args.diameter,
        "num_instances": int(masks.max()),
        "polygons": masks_to_polygons(masks),
    }
    output_path.write_text(json.dumps(result, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
