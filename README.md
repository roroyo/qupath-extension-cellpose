# QuPath Cellpose Extension

A prototype QuPath extension that runs [Cellpose](https://github.com/MouseLand/cellpose) segmentation on selected annotations using a local Python environment.

## How it works

1. Select one or more annotations in QuPath
2. Click **Extensions → Cellpose → Run Cellpose on selected annotations**
3. The extension exports each annotation ROI as a PNG patch, calls the bundled Python script, and imports the detected cell polygons back as detection objects

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

### 2. Install Java 21 (JBR) via conda

```bash
conda install -c conda-forge openjdk=21 -y
```

Verify:

```bash
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

Verify MPS is available:

```bash
python -c "import torch; print(torch.backends.mps.is_available())"
# True
```

### 4. Set environment variable (optional)

By default, the extension searches `PATH` for `python3` / `python`. To pin it to a specific interpreter:

```bash
export QUPATH_CELLPOSE_PYTHON=/path/to/your/python3
```

Add this to your shell profile to make it permanent.

---

## Building the extension

### Prerequisites

- The extension must be built **inside the QuPath source tree**. Clone QuPath first:

```bash
git clone https://github.com/qupath/qupath.git
cd qupath
```

- Copy or clone this extension into the QuPath root:

```bash
# inside the qupath/ root
git clone https://github.com/roroyo/qupath-extension-cellpose.git qupath-extension-cellpose
```

- Register the extension in `settings.gradle.kts` by adding:

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

## Python script

The bundled script [`scripts/cellpose_segment.py`](scripts/cellpose_segment.py) accepts the following arguments:

| Argument | Description |
|---|---|
| `--input` | Path to the input image patch (PNG) |
| `--output` | Path to write the output JSON (polygons) |
| `--labels-output` | Path to write the label mask (PNG, uint16) |
| `--model` | Cellpose model type (default: `cyto`) |
| `--diameter` | Estimated cell diameter in pixels (default: auto) |

The script outputs a JSON file with detected cell polygons, which the Java extension reads to create QuPath detection objects.

---

## Environment variables

| Variable | Description |
|---|---|
| `QUPATH_CELLPOSE_PYTHON` | Override the Python executable path |
| `QUPATH_CELLPOSE_SCRIPT` | Override the path to `cellpose_segment.py` |
