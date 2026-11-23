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
    ModelDef("lama", "LaMa Inpainter (lama_fp32.onnx)", "lama_fp32.onnx",
      listOf("https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx"), 208, core = true,
      note = "Magic Object Eraser. Community ONNX mirror."),
    ModelDef("sam_encoder", "MobileSAM Encoder", "mobile_sam_encoder.onnx",
      listOf("https://huggingface.co/vietanhdev/anylabeling-assets/resolve/main/segment_anything/mobile_sam_encoder.onnx",
             "https://huggingface.co/qualcomm/MobileSam/resolve/main/mobile_sam_encoder.onnx"), 40, core = true,
      note = "Smart Tap auto-select (encoder)."),
    ModelDef("sam_decoder", "MobileSAM Decoder", "mobile_sam_decoder.onnx",
      listOf("https://huggingface.co/vietanhdev/anylabeling-assets/resolve/main/segment_anything/mobile_sam_decoder.onnx",
             "https://huggingface.co/qualcomm/MobileSam/resolve/main/mobile_sam_decoder.onnx"), 16, core = true,
      note = "Smart Tap auto-select (decoder)."),
    ModelDef("u2netp", "U2NetP Salient Segmentation", "u2netp.onnx",
      listOf("https://huggingface.co/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx"), 5, core = true,
      note = "1-click cutout. Official rembg release."),
    ModelDef("rmbg", "RMBG-1.4 Background Removal", "rmbg_1_4.onnx",
      listOf("https://huggingface.co/danielgatis/rembg/releases/download/v0.0.0/rmbg_1_4.onnx",
             "https://huggingface.co/briaai/RMBG-1.4/resolve/main/model.onnx"), 176,
      note = "Higher-quality cutout. BRIA license applies."),
    ModelDef("midas", "MiDaS v2.1 Small Depth", "midas_v21_small.onnx",
      listOf("https://huggingface.co/qualcomm/Midas/resolve/main/Midas.onnx",
             "https://huggingface.co/Xenova/midas-small/resolve/main/onnx/model.onnx"), 66, core = true,
      note = "DSLR Portrait Blur depth map. Import if mirrors fail."),
    ModelDef("realesrgan", "Real-ESRGAN x4plus", "realesrgan_x4plus.onnx",
      listOf("https://huggingface.co/qualcomm/Real-ESRGAN-General-x4v3/resolve/main/Real-ESRGAN-General-x4v3.onnx"), 64,
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
      listOf("https://huggingface.co/Xenova/gfpgan/resolve/main/GFPGANv1.4.onnx"), 340,
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
