"""Export the ISC-licensed musicnn 0.1.0 MTT checkpoint with a waveform frontend.

Python 3.11: install tensorflow-cpu==2.15.1 librosa==0.10.2 numpy==1.26.4,
then `pip install --no-deps musicnn==0.1.0`. Run from any directory.
The source package includes the original checkpoint; no training/audio is bundled.
"""
import os
os.environ['TF_CPP_MIN_LOG_LEVEL']='2'
import tensorflow as tf, librosa, numpy as np
from musicnn import models,configuration as config
import musicnn
from pathlib import Path
tf.compat.v1.disable_eager_execution()
w=tf.compat.v1.placeholder(tf.float32,[48000],name='waveform')
# Match librosa's centered Hann STFT and Slaney-normalized mel filters.
padded=tf.pad(w,[[256,256]],mode='REFLECT')
frames=tf.signal.frame(padded,512,256)[:187]
spectrum=tf.signal.rfft(frames*tf.signal.hann_window(512,periodic=True))
power=tf.math.square(tf.math.real(spectrum))+tf.math.square(tf.math.imag(spectrum))
filters=librosa.filters.mel(sr=16000,n_fft=512,n_mels=96)
mel=tf.matmul(power,tf.constant(filters.T))
spec=tf.expand_dims(tf.math.log(1.0+10000.0*mel)/np.log(10.0),0)
with tf.name_scope('model'):
 y=models.define_model(spec,False,'MTT_musicnn',50)[0]
 scores=tf.nn.sigmoid(y,name='tags')
with tf.compat.v1.Session() as s:
 tf.compat.v1.train.Saver().restore(s,str(Path(musicnn.__file__).parent/'MTT_musicnn')+'/')
 converter=tf.compat.v1.lite.TFLiteConverter.from_session(s,[w],[scores])
 converter.target_spec.supported_ops=[tf.lite.OpsSet.TFLITE_BUILTINS]
 model=converter.convert()
 destination = Path(__file__).resolve().parents[1] / 'app/src/main/assets/models'
 destination.joinpath('musicnn-vocals.tflite').write_bytes(model)
 destination.joinpath('musicnn-labels.txt').write_text('\n'.join(config.MTT_LABELS) + '\n')
 # Verify conversion against the restored waveform graph.
 rng=np.random.default_rng(7); pcm=rng.normal(0,.05,48000).astype(np.float32)
 reference=s.run(scores,{w:pcm})
 expected_mel = librosa.feature.melspectrogram(y=pcm, sr=16000, n_fft=512,
     hop_length=256, n_mels=96, pad_mode="reflect").T[:187]
 np.testing.assert_allclose(s.run(spec, {w:pcm}),
     np.log10(1 + 10000 * expected_mel)[None], rtol=.001, atol=.0001)
 lite=tf.lite.Interpreter(model_content=model);lite.allocate_tensors();lite.set_tensor(lite.get_input_details()[0]['index'],pcm);lite.invoke()
 np.testing.assert_allclose(lite.get_tensor(lite.get_output_details()[0]['index']),reference,rtol=.001,atol=.0001)
 print('Exported',len(model),'bytes; TFLite matches TensorFlow')
