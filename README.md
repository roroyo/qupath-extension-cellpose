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
