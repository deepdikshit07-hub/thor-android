# Third-party model attribution

THOR's experimental speaker-embedding feature uses the WeSpeaker ResNet34 embedding model distributed as
`wespeaker_emb_fp16.tflite` by `litert-community/Speaker-Diarization-LiteRT`.

- Model repository: https://huggingface.co/litert-community/Speaker-Diarization-LiteRT
- WeSpeaker VoxCeleb ResNet34-LM weights: CC-BY-4.0
- The upstream model card describes the speaker embedding model as a 256-dimensional output and documents its input frontend.
- The Kotlin Kaldi-compatible filterbank frontend is adapted from the upstream Android sample:
  https://github.com/geneing/LiteRT-Models-Pocket-TTS/tree/main/diarization
- The upstream sample reports performance on Pixel 8a; this is not a performance claim for THOR or Oppo A16.

This notice is provided for attribution and does not replace the full upstream license terms.
