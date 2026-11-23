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
        ModelDef("anime_hayao", "AnimeGANv3 Hayao (Anime Art)", "animeganv3_hayao.onnx",
      listOf("https://huggingface.co/vumichien/AnimeGANv3_Hayao_36/resolve/main/AnimeGANv3_Hayao_36.onnx"), 9),
    ModelDef("anime_shinkai", "AnimeGANv3 Shinkai (3D/Cinema Art)", "animeganv3_shinkai.onnx",
      listOf("https://huggingface.co/vumichien/AnimeGANv3_Shinkai_53/resolve/main/AnimeGANv3_Shinkai_53.onnx"), 9),
    ModelDef("colorizer", "B&W Photo Colorizer ONNX", "colorizer.onnx",
      listOf("https://huggingface.co/nicolalandro/ddcolor/resolve/main/ddcolor_modelscope.onnx"), 45),
    ModelDef("lama", "LaMa Inpainter (lama_fp32.onnx)", "lama_fp32.onnx",
      listOf("https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx"), 208, core = true,
      note = "Magic Object Eraser. Community ONNX mirror."),
    ModelDef("sam_encoder", "MobileSAM Encoder", "mobile_sam_encoder.onnx",
      listOf("https://huggingface.co"), 40, core = true,
      note = "Smart Tap auto-select (encoder)."),
    ModelDef("sam_decoder", "MobileSAM Decoder", "mobile_sam_decoder.onnx",
      listOf("https://huggingface.co"), 16, core = true,
      note = "Smart Tap auto-select (decoder)."),
    ModelDef("u2netp", "U2NetP Salient Segmentation", "u2netp.onnx",
      listOf("https://huggingface.co/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx"), 5, core = true,
      note = "1-click cutout. Official rembg release."),
    ModelDef("rmbg", "RMBG-1.4 Background Removal", "rmbg_1_4.onnx",
      listOf("https://huggingface.co/Xenova/modnet/resolve/main/onnx/model.onnx", "https://huggingface.co/briaai/RMBG-1.4/resolve/main/onnx/model.onnx"), 176,
      note = "Higher-quality cutout. BRIA license applies."),
    ModelDef("midas", "MiDaS v2.1 Small Depth", "midas_v21_small.onnx",
      listOf("https://huggingface.co"), 66, core = true,
      note = "DSLR Portrait Blur depth map. Import if mirrors fail."),
    ModelDef("realesrgan", "Real-ESRGAN x4plus", "realesrgan_x4plus.onnx",
      listOf("https://huggingface.co", "https://huggingface.co"), 64,
      note = "4K tiled upscaler."),
    ModelDef("face_pack", "SCRFD-500M + ArcFace (buffalo_s)", "buffalo_s.zip",
      listOf("https://huggingface.co/deepinsight/insightface/releases/download/v0.7/buffalo_s.zip"), 16,
      zipMap = mapOf("det_500m.onnx" to "scrfd_500m.onnx", "w600k_mbf.onnx" to "arcface.onnx"),
      produces = listOf("scrfd_500m.onnx", "arcface.onnx"),
      note = "Official InsightFace pack: face detection + identity embedding."),
    ModelDef("inswapper", "InSwapper-128 Face Swap", "inswapper_128.onnx",
      listOf("https://huggingface.co/ezioruan/inswapper_128.onnx/resolve/main/inswapper_128.onnx"), 529,
      note = "Research / non-commercial license. Not officially hosted — import your own copy if the mirror fails."),
    ModelDef("gfpgan", "GFPGAN 1.4 Face Enhancer", "gfpgan_1.4.onnx",
      listOf("https://huggingface.co"), 340,
      note = "Optional post-swap enhancement. Import if mirror fails."),
    ModelDef("sd_unet", "SD-Turbo UNet (ONNX)", "sd_unet.onnx", emptyList(), 2600,
      note = "Import an Optimum ONNX export folder (unet/model.onnx)."),
    ModelDef("sd_text_encoder", "SD-Turbo Text Encoder (ONNX)", "sd_text_encoder.onnx", emptyList(), 1300,
      note = "text_encoder/model.onnx"),
    ModelDef("sd_vae_encoder", "SD-Turbo VAE Encoder (ONNX)", "sd_vae_encoder.onnx", emptyList(), 130,
      note = "vae_encoder/model.onnx"),
    ModelDef("sd_vae_decoder", "SD-Turbo VAE Decoder (ONNX)", "sd_vae_decoder.onnx", emptyList(), 190,
      note = "vae_decoder/model.onnx"),
    ModelDef("sd_vocab", "CLIP Tokenizer vocab.json", "sd_vocab.json", emptyList(), 2,
      note = "tokenizer/vocab.json"),
    ModelDef("sd_merges", "CLIP Tokenizer merges.txt", "sd_merges.txt", emptyList(), 1,
      note = "tokenizer/merges.txt")
  )
  val byId = all.associateBy { it.id }
  val core = all.filter { it.core }
  val sdIds = listOf("sd_unet", "sd_text_encoder", "sd_vae_encoder", "sd_vae_decoder", "sd_vocab", "sd_merges")
}
