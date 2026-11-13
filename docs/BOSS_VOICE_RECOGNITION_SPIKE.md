# THOR Boss voice recognition — experimental branch

Branch: `experiment/boss-speaker-verification`

## Selected embedding model

WeSpeaker ResNet34 speaker embeddings (256 dimensions), exported as FP16 TFLite/LiteRT. The Android reference implementation reports a 13.4 MB model and uses a 5.015-second, 16 kHz mono waveform converted to 500 x 80 Kaldi log-mel filterbank frames with cepstral mean normalization.

Reference implementation:
- https://github.com/geneing/LiteRT-Models-Pocket-TTS/tree/main/diarization
- https://huggingface.co/pyannote/wespeaker-voxceleb-resnet34-LM

The reference app reports Pixel 8a testing only. That is not evidence of performance on the Oppo A16.

## Current experimental code

- `ThorSpeakerMatch.kt` provides normalized embedding averaging, cosine similarity, and serialization.
- `ThorBossProfile.kt` has a separate local slot for a future verified speaker embedding.
- These are groundwork only. No speaker model inference is connected yet, no threshold is calibrated, and THOR does not currently identify a speaker.

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

- Add and verify the actual model artifact and its license/provenance; the reference build script generates the TFLite model from WeSpeaker/Pyannote source weights, it does not commit the final model artifact.
- Port the matching Kaldi fbank frontend and test it against reference vectors.
- Add an offline-capable multilingual ASR path for English, Hindi, and Bengali, or explicitly document language/device limitations.
- Enroll multiple independent Boss utterances and average their embeddings.
- Collect positive and non-Boss test utterances on the target phone, then calibrate false-accept/false-reject thresholds. Do not copy a diarization clustering threshold as an identity threshold.
- Test sustained listening, background noise, CPU/RAM/thermal impact, and microphone lifecycle on Oppo A16.
- Keep voice match as a convenience label, never as authentication for sensitive actions; voice replay and imitation are possible.

## Status

Experimental groundwork only; no user-facing recognition is enabled. Keep this branch separate from `thor-2.0-upgrade` until the full pipeline builds and passes device tests.
