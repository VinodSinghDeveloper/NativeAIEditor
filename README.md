# Native AI Editor

100% native Kotlin + Jetpack Compose + ONNX Runtime Mobile + OpenCV. No WebView, no Python, no server.

## Build
Push this folder to GitHub. The `Build APK` workflow (Gradle 8.7, JDK 17, `gradle assembleDebug`)
produces `app-debug.apk` as an Actions artifact. Or locally: `gradle assembleDebug`.

## Models
- Auto-downloaded on first launch (with consent + progress): LaMa, MobileSAM enc/dec, U2NetP, MiDaS.
- Official stable URLs: U2NetP/RMBG (rembg releases), SCRFD+ArcFace (insightface buffalo_s.zip).
- Community mirrors can rot: every model supports Import file and Custom URL in the Models screen,
  and downloads are validated by actually opening an ONNX session.
- inswapper_128.onnx: research/non-commercial license; import your own copy if the mirror fails.
- SD-Turbo: import an Optimum ONNX export folder
  (optimum-cli export onnx --model stabilityai/sd-turbo sd-turbo-onnx on a desktop)
  containing text_encoder/, unet/, vae_encoder/, vae_decoder/, vocab.json, merges.txt.
  The native LAB/HSV outfit restyler works without any model.

All inference is offline after models are present. Exports go to Pictures/NativeAIEditor via MediaStore.
