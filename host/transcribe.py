"""Explicit local transcription to a private draft; never submits an Agent turn."""
import argparse
import json
import os
from pathlib import Path
import sys
import time

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
parser = argparse.ArgumentParser()
parser.add_argument('audio', type=Path)
parser.add_argument('--model', default='small')
parser.add_argument('--language', default='zh')
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--allow-model-download', action='store_true')
args = parser.parse_args()
if not args.audio.is_file() or not 0 < args.audio.stat().st_size <= 30 * 1024 * 1024:
    raise SystemExit('Audio must be an existing nonempty file under 30 MiB')
from faster_whisper import WhisperModel
import av
import numpy as np

# Decode locally instead of relying on faster-whisper's older PyAV open kwargs.
parts = []
sample_count = 0
with av.open(str(args.audio), mode='r') as container:
    resampler = av.AudioResampler(format='fltp', layout='mono', rate=16000)
    for frame in container.decode(audio=0):
        for converted in resampler.resample(frame):
            samples = converted.to_ndarray().reshape(-1)
            sample_count += samples.size
            if sample_count > 16000 * 60:
                raise SystemExit('Development transcription is limited to 60 seconds')
            parts.append(samples)
    for converted in resampler.resample(None):
        parts.append(converted.to_ndarray().reshape(-1))
if not parts:
    raise SystemExit('No decodable audio samples')
audio = np.concatenate(parts).astype(np.float32, copy=False)
if audio.size > 16000 * 60:
    raise SystemExit('Development transcription is limited to 60 seconds')

cache = Path(os.environ.get('LOCALAPPDATA', Path.home())) / 'BandAgentDev/models'
started = time.monotonic()
model = WhisperModel(args.model, device='cpu', compute_type='int8', cpu_threads=4,
                     download_root=str(cache), local_files_only=not args.allow_model_download)
segments, info = model.transcribe(audio, language=args.language, beam_size=5,
                                  condition_on_previous_text=False, vad_filter=True)
text = ''.join(segment.text for segment in segments).strip()
if not text:
    raise SystemExit('No speech was recognized; no draft created')
draft = {'status': 'unsubmitted', 'text': text, 'language': info.language,
         'durationSeconds': info.duration, 'elapsedSeconds': round(time.monotonic() - started, 3),
         'provider': 'local-faster-whisper', 'model': args.model}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(draft, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(draft, ensure_ascii=False))
