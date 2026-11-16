# THOR Boss voice recognition — experimental branch

Branch: `experiment/boss-speaker-verification`

## Selected embedding model

WeSpeaker ResNet34 speaker embeddings (256 dimensions), exported as FP16 TFLite/LiteRT. The Android reference implementation reports a 13.4 MB model and uses a 5.015-second, 16 kHz mono waveform converted to 500 x 80 Kaldi log-mel filterbank frames with cepstral mean normalization.

Reference implementation:
- https://github.com/geneing/LiteRT-Models-Pocket-TTS/tree/main/diarization
- https://huggingface.co/pyannote/wespeaker-voxceleb-resnet34-LM

The reference app reports Pixel 8a testing only. That is not evidence of performance on the Oppo A16.

## Current experimental code

- `ThorSpeakerEngine.kt` captures 5.015 seconds of 16 kHz mono PCM with Android AudioRecord, computes the upstream-compatible 80-bin Kaldi fbank frontend, and runs the 256-D WeSpeaker model through LiteRT CPU.
- The build workflow downloads the 13.4 MB model plus the two small frontend assets from their upstream repositories and bundles them into the experimental APK.
- First-run enrollment records three separate utterances, averages their normalized embeddings, and stores the resulting template encrypted with a device-bound Android Keystore AES-GCM key.
- `ThorSpeakerMatch.kt` provides normalized embedding averaging, cosine similarity, and serialization.
- The embedding is now generated on-device, but automatic matching is still deliberately disabled: no threshold has been calibrated against genuine non-owner samples on the Oppo A16, and the live command/wake audio path is not yet gated by a speaker decision.
- The Android Keystore encryption is device-bound. It protects the local biometric template but does not make it portable to another phone.

## Why the live speech path must change

The current THOR implementation uses Android `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` and the wake-word `SpeechRecognizer`. Those APIs provide recognized text, but do not provide a dependable raw PCM stream for the embedding model. The five-second AAC enrollment clip is not a model embedding and cannot be compared directly to an incoming voice.

To identify the speaker for each spoken command, the app needs a unified audio capture path that:
1. Captures 16 kHz mono PCM once.
2. Runs voice activity detection and speaker embedding on that PCM.
3. Sends the same captured utterance to multilingual speech recognition.
4. Processes the command only after speaker classification.
5. Runs the speaker model locally so identity matching is available offline and online.

The current app's online/offline AI response selection can remain downstream of this audio pipeline. The speech-to-text engine is a separate decision: Android's current recognizer cannot be assumed to work offline or provide PCM on Android 10 (the user's target device).

## Required before enabling automatic Boss labels

- Verify the downloaded model and frontend assets with pinned checksums in CI.
- Compare the Kotlin fbank output and model embeddings against upstream reference vectors in automated tests.
- Collect positive and non-owner test utterances with informed consent on the Oppo A16, then calibrate false-accept/false-reject thresholds. Do not copy a diarization clustering threshold as an identity threshold.
- Integrate a single audio pipeline with command recognition. The current SpeechRecognizer intent does not expose the same raw PCM to the speaker model, so current voice commands are not yet speaker-gated.
- Test sustained listening, background noise, CPU/RAM/thermal impact, and microphone lifecycle on Oppo A16.
- For a portable Master identity, add explicit account sign-in and securely sync the encrypted profile, or implement a passphrase-protected export/import flow. Never treat a typed name alone as proof of identity.
- Keep voice match as a convenience label, never as authentication for sensitive actions; voice replay and imitation are possible.

## Status

Experimental embedding extraction and three-utterance enrollment are implemented. End-to-end speaker verification is not yet enabled because threshold calibration and live audio-path integration remain. Keep this branch separate from `thor-2.0-upgrade` until the full pipeline builds and passes device tests.
