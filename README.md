# QuPath Cellpose Extension

A prototype QuPath extension that runs [Cellpose](https://github.com/MouseLand/cellpose) segmentation on selected annotations using a local Python environment.

## How it works

1. Select one or more annotations in QuPath
2. Click **Extensions → Cellpose → Run Cellpose on selected annotations**
3. The extension exports each annotation ROI as a PNG patch, calls the bundled Python script (`scripts/cellpose_segment.py`), and imports the detected cell polygons back as detection objects

Segmentation parameters (model, diameter, flow threshold, cell probability threshold) can be tuned via **Extensions → Cellpose → Cellpose Settings…** without restarting QuPath.

---

## Requirements

- QuPath 0.6+, built with Java 21
- Python 3.10+
- Conda (recommended)

---

## Environment setup

### 1. Create conda environment

```bash
conda create -n qupath-java21 python=3.10 -y
conda activate qupath-java21
```

### 2. Install Java 21 via conda

```bash
conda install -c conda-forge openjdk=21 -y
java -version   # should print openjdk 21.x
```

### 3. Install Cellpose

```bash
pip install cellpose
```

To enable Apple Silicon GPU (MPS) acceleration, also install the matching PyTorch:

```bash
pip install torch torchvision
```

---

## Building the extension

### Prerequisites

The extension must be built **inside the QuPath source tree**. Clone QuPath first:

```bash
git clone https://github.com/qupath/qupath.git
cd qupath
```

Clone this extension into the QuPath root:

```bash
git clone https://github.com/roroyo/qupath-extension-cellpose.git qupath-extension-cellpose
```

Copy the `scripts/` folder into the QuPath root so the extension can find `cellpose_segment.py` at runtime:

```bash
cp -r qupath-extension-cellpose/scripts ./scripts
```

Expected directory layout under `qupath/`:

```
qupath/
├── scripts/
│   └── cellpose_segment.py            ← required at runtime
├── qupath-extension-cellpose/
│   ├── build.gradle.kts
│   ├── scripts/
│   │   └── cellpose_segment.py        ← canonical source
│   └── src/
├── settings.gradle.kts
└── gradlew
```

Register the extension in `settings.gradle.kts`:

```kotlin
include("qupath-extension-cellpose")
```

### Build and run

```bash
conda activate qupath-java21
export JAVA_HOME=$CONDA_PREFIX

./gradlew --no-daemon run
```

---

## Configuration

### Settings dialog

Open **Extensions → Cellpose → Cellpose Settings…** to adjust:

| Parameter | Default | Description |
|---|---|---|
| Model | `cpsam` | Cellpose model (`cpsam`, `cyto3`, `cyto2`, `nuclei`, …) |
| Diameter | `0` (auto) | Estimated cell diameter in pixels; `0` lets Cellpose auto-detect |
| Flow threshold | `0.4` | Maximum flow error; increase to accept more irregular shapes |
| Cellprob threshold | `0.0` | Cell probability cutoff; decrease to detect faint cells |

Settings persist for the duration of the QuPath session.

### Environment variables

| Variable | Purpose |
|---|---|
| `QUPATH_CELLPOSE_PYTHON` | Path or command for the Python executable (overrides PATH search) |
| `QUPATH_CELLPOSE_SCRIPT` | Absolute path to `cellpose_segment.py` (overrides default search) |

---

## Python script: `scripts/cellpose_segment.py`

The script is invoked by the extension for each annotation region. It accepts:

```
cellpose_segment.py
  --input <image.png>
  --output <result.json>
  --labels-output <labels.png>
  --model <name>               default: cpsam
  --diameter <float>           default: auto
  --flow-threshold <float>     default: 0.4
  --cellprob-threshold <float> default: 0.0
```

It outputs:
- A JSON file with detected polygon coordinates (imported by QuPath)
- A 16-bit grayscale PNG with instance labels

Supports 8-bit and 16-bit grayscale and RGB input images.

---

## Output

Detected cells are added as `Cellpose` detection objects (red) under each processed annotation. A summary dialog reports the number of detections created.
