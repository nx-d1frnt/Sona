# YAMNet for offline vocal activity detection

This app bundles the unmodified TensorFlow YAMNet classification TFLite model (float32,
15,600 mono samples at 16 kHz, 521 AudioSet scores). No microphone or network is used.

Source: https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/audio_classification/android/lite-model_yamnet_classification_tflite_1.tflite
Official Android sample: https://github.com/tensorflow/examples/blob/master/lite/examples/audio_classification/android/app/download_model.gradle
Model documentation: https://www.tensorflow.org/hub/tutorials/yamnet
Upstream model code and licensing: https://github.com/tensorflow/models/tree/master/research/audioset/yamnet

SHA-256: `10c95ea3eb9a7bb4cb8bddf6feb023250381008177ac162ce169694d05c317de`

License: Apache License 2.0; see LICENSE.txt. The embedded `yamnet_label_list.txt`
is the source of the AudioSet class indices used by YamnetVocalDetector.

We take the maximum of selected speech/singing/rap/humming/vocal music scores.
These are model confidence scores, not calibrated probabilities or isolated vocal stems.
A low score is only a preference for transition placement; unanalysed audio is unknown.
The detector cannot guarantee that a phrase has ended or that a passage has no vocals.

## Music-specific vocal detection

YAMNet's AudioSet vocal scores alone substantially under-detect singing and rap
inside mastered music. The detector now combines YAMNet with the original
`MTT_musicnn` tagger from https://github.com/jordipons/musicnn (PyPI musicnn 0.1.0).
This is the ISC-licensed upstream model, not the separately licensed Essentia
classification heads. See MUSICNN_LICENSE.txt for the required copyright/license.

`musicnn-vocals.tflite` contains the original float32 weights plus a waveform
frontend: 48,000 mono samples at 16 kHz -> 187 frames of 96 Slaney mel bands ->
50 tags (musicnn-labels.txt). Centered 512-sample periodic Hann STFT, hop 256,
log10(1 + 10000 * mel power). All operations use standard TFLite builtins.
Rebuild with tools/export_musicnn.py; runtime needs no Python or extra TFLite library.

SHA-256: `1c4144bf166430006785d71c2a7f3bbb12503cd0a6ee831370f0020606a419eb`
Original checkpoint SHA-256 (.data-00000-of-00001):
`041f3935e981a65cea739842d30c188d3a7d2adf4410955c8668eb3de4a5acab`

The maximum explicit vocal tag is compared with the maximum no-vocal tag as
`voice / (voice + noVoice + 0.01)`. This is relative evidence, not a calibrated
probability. Three-second music contexts retain a 1.5-second output hop, with adaptive
inference: evaluate alternating contexts first, then measure intervening contexts
unless both neighbours score >= 0.75. Between two strong vocal measurements, reuse
the stronger score conservatively. Quiet/uncertain contexts always retain dense
checks, so brief voice entries between weak neighbours are still measured. This
can smooth a short pause inside a strongly vocal passage rather than inventing an
instrumental gap. Digital silence skips music inference entirely.

The float model uses up to four CPU threads and explicitly disables XNNPACK:
on the Motorola/Realme benchmarks, these CPU kernels were faster for this graph.
YAMNet retains its
approximately 488 ms hop. Each stored section takes the strongest overlapping
music context or YAMNet score. Music context can blur phrase boundaries, so
transition pause detection remains conservative. Digital silence is gated out.

The displayed percentage counts sections with combined score >= 0.5 within
sampled windows. Overlapping windows count once; unsampled/invalid audio is unknown.
Instrumental mix filtering uses the same coverage estimate. Cache schema v3 stores
vocal model version 2. Legacy vocal scores are unknown until refreshed, with other
cached features preserved. Debug logs tagged `SonaAnalysis` report combined scores.

Run tools/check_music_vocals.py against local files for the dense reference
comparison of YAMNet-only and combined model activity. Build diagnostics with `./gradlew -PvocalDiagnostics=true assembleDebug
assembleDebugAndroidTest`, install both APKs, then run:
`adb shell am instrument -w -e songIds ID1,ID2
com.sona.player.test/com.sona.player.automix.VocalDetectorDiagnostics`.
The opt-in runner verifies real decoding, cache migration/enrichment and persistence;
it updates the selected tracks' cached analysis. Use `-e force true` to time a full
uncached pass; omit it to verify cache reuse. Use `-e benchmark true -e songIds ID`
to compare model CPU settings/NNAPI on decoded audio without changing the cache.
Some vendor NNAPI implementations do not support this graph; production uses CPU.

A full uncached analysis of "Gone or Staying" on the Motorola edge 60 measured
24.0s before CPU/sampling changes and 7.6s afterwards, with 67% vocal coverage in
both runs. This is a single-track result, not a full-library timing guarantee.
Already-valid version 2 vocal results are retained; this optimization does not
invalidate the analysis cache.

Beat tracking is a separate spectral-onset heuristic. Its local beat timestamps are
not downbeat/bar labels and must not be extrapolated outside measured windows.
