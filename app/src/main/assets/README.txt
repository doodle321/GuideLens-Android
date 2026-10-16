ML MODELS (required at build time)
==================================
The two TensorFlow Lite models (~7 MB total) cannot be shipped inside this
source zip. They are fetched automatically by the build:

    ./gradlew assembleDebug          # preBuild runs :app:downloadModels

or manually:

    bash tools/download-models.sh    (Linux/macOS)
    powershell tools/download-models.ps1   (Windows)

or copy them from your previous GuideLens checkout into this directory:
    detect.tflite               (SSD MobileNet object detection, REQUIRED)
    deeplab_cityscapes.tflite   (DeepLabV3 scene segmentation, optional)

The build verifies the TFL3 flatbuffer magic and file size, and once you pin
SHA-256 hashes in app/models.sha256 it enforces them (supply-chain guard).
