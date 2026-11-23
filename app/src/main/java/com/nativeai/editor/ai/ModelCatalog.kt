package com.nativeai.editor.ai

data class ModelDef(
  val id: String, val title: String, val relPath: String, val approxMB: Int,
  val defaultUrls: List<String> = emptyList(), val zipEntry: String? = null,
  val core: Boolean = false, val note: String? = null
)

object ModelCatalog {
  val all = listOf(
    ModelDef("lama", "LaMa Magic Eraser", "lama_fp32.onnx", 196,
      listOf("https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx"), core = true),
    ModelDef("u2netp", "U2NetP Auto Cutout & Crowd", "u2netp.onnx", 5,
      listOf("https://://github.comdanielgatis/rembg/releases/download/v0.0.0/u2netp.onnx"), core = true),
    ModelDef("rmbg", "RMBG-1.4 Studio Cutout", "rmbg_1_4.onnx", 176,
      listOf("https://huggingface.co/briaai/RMBG-1.4/resolve/main/onnx/model.onnx", "https://huggingface.co/Xenova/modnet/resolve/main/onnx/model.onnx")),
    ModelDef("sam_enc", "MobileSAM Encoder (Tap Select)", "mobile_sam_encoder.onnx", 40,
      listOf("https://huggingface.co/Acly/MobileSAM/resolve/main/mobile_sam_image_encoder.onnx"), core = true),
    ModelDef("sam_dec", "MobileSAM Decoder", "mobile_sam_decoder.onnx", 16,
      listOf("https://huggingface.co/Acly/MobileSAM/resolve/main/sam_mask_decoder_single.onnx"), core = true),
    ModelDef("midas", "MiDaS Depth (DSLR Bokeh)", "midas_v21_small.onnx", 64,
      listOf("https://huggingface.co/julienkay/sentis-MiDaS/resolve/main/onnx/midas_v21_small_256.onnx"), core = true),
    ModelDef("anime_hayao", "AnimeGANv3 Hayao (Anime Art)", "animeganv3_hayao.onnx", 9,
      listOf("https://huggingface.co/vumichien/AnimeGANv3_Hayao_36/resolve/main/AnimeGANv3_Hayao_36.onnx")),
    ModelDef("anime_shinkai", "AnimeGANv3 Shinkai (3D/Cinema Art)", "animeganv3_shinkai.onnx", 9,
      listOf("https://huggingface.co/vumichien/AnimeGANv3_Shinkai_53/resolve/main/AnimeGANv3_Shinkai_53.onnx")),
    ModelDef("colorizer", "B&W Photo Colorizer ONNX", "colorizer.onnx", 45,
      listOf("https://huggingface.co/nicolalandro/ddcolor/resolve/main/ddcolor_modelscope.onnx"),
      note = "Restores natural color to Black & White photos (built-in warm restorer works offline too)."),
    ModelDef("face_det", "SCRFD Face Detector", "det_500m.onnx", 3,
      listOf("https://://github.comdeepinsight/insightface/releases/download/v0.7/buffalo_s.zip"), zipEntry = "det_500m.onnx"),
    ModelDef("face_rec", "ArcFace Identity", "w600k_mbf.onnx", 13,
      listOf("https://://github.comdeepinsight/insightface/releases/download/v0.7/buffalo_s.zip"), zipEntry = "w600k_mbf.onnx"),
    ModelDef("inswapper", "InSwapper-128 Face Swap", "inswapper_128.onnx", 529,
      listOf("https://huggingface.co/ezioruan/inswapper_128.onnx/resolve/main/inswapper_128.onnx")),
    ModelDef("gfpgan", "GFPGAN 1.4 HD Face Restore", "gfpgan_1.4.onnx", 332,
      listOf("https://huggingface.co/facefusion/models-3.0.0/resolve/main/gfpgan_1.4.onnx")),
    ModelDef("esrgan", "Real-ESRGAN 4K Upscaler", "realesrgan_x4plus.onnx", 67,
      listOf("https://huggingface.co/qualcomm/Real-ESRGAN-x4plus/resolve/main/Real-ESRGAN-x4plus.onnx"))
  )
  fun byId(id: String): ModelDef = all.first { it.id == id }
}
