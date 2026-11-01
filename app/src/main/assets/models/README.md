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

Beat tracking is a separate spectral-onset heuristic. Its local beat timestamps are
not downbeat/bar labels and must not be extrapolated outside measured windows.
