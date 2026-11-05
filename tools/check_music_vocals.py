"""Compare bundled vocal models on local audio (requires ffmpeg, numpy, tflite-runtime).

Usage: python tools/check_music_vocals.py /path/to/song.mp3 ...
Uses the app's three structural windows. Audio stays local and is never bundled.
"""
import subprocess
import sys
from pathlib import Path
import numpy as np
from tflite_runtime.interpreter import Interpreter

MODELS = Path(__file__).resolve().parents[1] / 'app/src/main/assets/models'
VOICE = [13, 19, 20, 27, 35, 36, 39, 40, 48, 49]
NO_VOICE = [21, 28, 44]
YAMNET_VOICE = [0, 1, 2, 3, 5, 12, 24, 25, 26, 27, 28, 29, 30, 31, 32, 249, 250]


def load(name):
    model = Interpreter(model_path=str(MODELS / name), num_threads=2)
    model.allocate_tensors()
    return model


def infer(model, pcm):
    model.set_tensor(model.get_input_details()[0]['index'], np.asarray(pcm, dtype=np.float32))
    model.invoke()
    scores = model.get_tensor(model.get_output_details()[0]['index']).ravel()
    assert np.isfinite(scores).all()
    return scores


def activity(scores):
    voice = float(scores[VOICE].max())
    return voice / (voice + float(scores[NO_VOICE].max()) + .01)


if __name__ == '__main__':
    music = load('musicnn-vocals.tflite')
    yamnet = load('yamnet.tflite')
    assert music.get_input_details()[0]['shape'].tolist() == [48000]
    assert music.get_output_details()[0]['shape'].tolist() == [1, 50]
    for filename in sys.argv[1:]:
        pcm = np.frombuffer(subprocess.check_output([
            'ffmpeg', '-v', 'error', '-i', filename, '-f', 'f32le', '-ar', '16000', '-ac', '1', '-'
        ]), dtype=np.float32)
        duration = len(pcm)
        ranges = [(0, min(384000, duration * 35 // 100)),
                  (int(duration * .48), min(max(int(duration * .22), 224000), 672000)),
                  (duration - min(max(int(duration * .28), 288000), 608000, duration // 2), duration)]
        old_active, active, total = 0, 0, 0
        for start, length in ranges[:2] + [(ranges[2][0], duration - ranges[2][0])]:
            window = pcm[start:start + length]
            last = max(0, len(window) - 48000)
            offsets = list(range(0, last + 1, 24000))
            if offsets[-1] != last:
                offsets.append(last)
            sections = []
            for n, offset in enumerate(offsets):
                wave = window[offset:offset + 48000]
                score = activity(infer(music, np.pad(wave, (0, 48000 - len(wave))))) if np.mean(wave ** 2) >= 1e-8 else 0
                sections.append((offset, offsets[n + 1] if n + 1 < len(offsets) else len(window), score))
            for offset in range(0, len(window) - 15600 + 1, 7800):
                end = len(window) if offset + 23400 > len(window) else offset + 7800
                y = float(infer(yamnet, window[offset:offset + 15600])[YAMNET_VOICE].max())
                m = max((s for a, b, s in sections if a < end and b > offset), default=0)
                old_active += (end - offset) * (y > .12)
                active += (end - offset) * (max(y, m) >= .5)
                total += end - offset
        print(f'{Path(filename).name}: YAMNet-only={old_active / total:.1%}, combined={active / total:.1%}')
