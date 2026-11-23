package com.nativeai.editor.ai

data class ModelDef(
  val id: String,
  val title: String,
  val fileName: String,
  val mirrors: List<String>,
  val approxMB: Int,
  val core: Boolean = false,
  val zipMap: Map<String, String>? = null,
  val produces: List<String> = listOf(fileName),
  val note: String? = null
)

object ModelCatalog {
  // Community mirrors are best-effort; every model also supports local import / custom URL.
  val all = listOf(
    ModelDef("anime_hayao", "AnimeGANv3 Hayao (Anime Art)", "animeganv3_hayao.onnx", listOf("https://huggingface.co/vumichien/AnimeGANv3_Hayao_36/resolve/main/AnimeGANv3_Hayao_36.onnx"), 9),
    ModelDef("anime_shinkai", "AnimeGANv3 Shinkai (3D/Cinema Art)", "animeganv3_shinkai.onnx", listOf("https://://github.comTachibanaYoshino/AnimeGANv3/releases/download/v1.1.0/AnimeGANv3_Shinkai_53.onnx"), 9),
    ModelDef("colorizer", "B&W Photo Colorizer ONNX", "colorizer.onnx", listOf("https://huggingface.co/nicolalandro/ddcolor/resolve/main/ddcolor_modelscope.onnx"), 45),
    ModelDef("lama", "LaMa Inpainter (Magic Eraser)", "lama_fp32.onnx", listOf("https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx"), 208, core = true),
    ModelDef("sam_enc", "MobileSAM Encoder", "mobile_sam_encoder.onnx", listOf("https://huggingface.co/Acly/MobileSAM/resolve/main/mobile_sam_image_encoder.onnx"), 40, core = true),
    ModelDef("sam_dec", "MobileSAM Decoder", "mobile_sam_decoder.onnx", listOf("https://huggingface.co/Acly/MobileSAM/resolve/main/sam_mask_decoder_single.onnx"), 16, core = true),
    ModelDef("u2netp", "U2NetP Auto Cutout", "u2netp.onnx", listOf("https://://github.comdanielgatis/rembg/releases/download/v0.0.0/u2netp.onnx"), 5, core = true),
    ModelDef("rmbg", "RMBG-1.4 Studio Cutout", "rmbg_1_4.onnx", listOf("https://huggingface.co/Xenova/modnet/resolve/main/onnx/model.onnx"), 176),
    ModelDef("midas", "MiDaS v2.1 Depth (DSLR Bokeh)", "midas_v21_small.onnx", listOf("https://huggingface.co/julienkay/sentis-MiDaS/resolve/main/onnx/midas_v21_small_256.onnx"), 66, core = true),
    ModelDef("esrgan", "Real-ESRGAN x4plus (4K Upscaler)", "realesrgan_x4plus.onnx", listOf("https://huggingface.co/facefusion/models-3.0.0/resolve/main/realesrgan_x4plus.onnx"), 64),
    ModelDef("face_det", "SCRFD Face Detector", "det_500m.onnx", listOf("https://huggingface.co/public-data/insightface/resolve/main/models/buffalo_s.zip"), 3, zipEntry = "det_500m.onnx"),
    ModelDef("face_rec", "ArcFace Identity", "w600k_mbf.onnx", listOf("https://huggingface.co/public-data/insightface/resolve/main/models/buffalo_s.zip"), 13, zipEntry = "w600k_mbf.onnx"),
    ModelDef("inswapper", "InSwapper-128 Face Swap", "inswapper_128.onnx", listOf("https://huggingface.co/ezioruan/inswapper_128.onnx/resolve/main/inswapper_128.onnx"), 529),
    ModelDef("gfpgan", "GFPGAN 1.4 Face Enhancer", "gfpgan_1.4.onnx", listOf("https://huggingface.co/facefusion/models-3.0.0/resolve/main/gfpgan_1.4.onnx"), 340)
  )
  val core = all.filter { it.core }
  val sdIds = listOf("sd_unet", "sd_text_encoder", "sd_vae_encoder", "sd_vae_decoder", "sd_vocab", "sd_merges")
}
