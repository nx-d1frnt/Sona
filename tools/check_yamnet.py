"""Smoke check the bundled model on the official TensorFlow speech_whistling2.wav fixture.

Run with Python 3.11, numpy<2 and tflite-runtime==2.14.0:
    python tools/check_yamnet.py /path/to/speech_whistling2.wav
Fixture: https://storage.googleapis.com/audioset/speech_whistling2.wav
"""
import sys
import numpy as np
from tflite_runtime.interpreter import Interpreter
import wave
from pathlib import Path
model=Path(__file__).resolve().parents[1] / 'app/src/main/assets/models/yamnet.tflite'
interpreter=Interpreter(model_path=str(model), num_threads=1)
interpreter.allocate_tensors()
a=interpreter.get_input_details()[0]; b=interpreter.get_output_details()[0]
print('Model input:', a['shape'], a['dtype'], 'output:', b['shape'], b['dtype'])
assert int(np.prod(a['shape']))==15600 and int(np.prod(b['shape']))==521
indices=[0,1,2,3,5,12,24,25,26,27,28,29,30,31,32,249,250]
def classify(pcm):
 interpreter.set_tensor(a['index'], np.asarray(pcm,dtype=np.float32).reshape(a['shape']))
 interpreter.invoke()
 scores=interpreter.get_tensor(b['index']).flatten()
 assert np.all(np.isfinite(scores))
 return float(scores[indices].max())
silence=classify(np.zeros(15600))
print('Silence vocal score:',silence)
assert silence<0.3
with wave.open(sys.argv[1]) as w:
 assert w.getsampwidth()==2
 samples=np.frombuffer(w.readframes(w.getnframes()),dtype='<i2').astype(np.float32)/32768
 samples=samples.reshape(-1,w.getnchannels()).mean(axis=1)
 rate=w.getframerate()
 if rate!=16000:
  samples=np.interp(np.arange(int(len(samples)*16000/rate))*rate/16000,np.arange(len(samples)),samples).astype(np.float32)
scores=[classify(samples[i:i+15600]) for i in range(0,len(samples)-15600+1,15600)]
print('Official speech/whistling vocal scores:',scores)
assert max(scores)>0.3
print('PASS: model loads, expected tensor contract, silence/voice smoke check')
