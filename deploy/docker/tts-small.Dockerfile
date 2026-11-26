# Small Laura voice: a single-speaker VITS (Piper layout) distilled from the Qwen3-TTS clone, ONNX on the CPU.
# English phonemes come from the system espeak-ng library (runtime use only); Korean from g2pk2.
FROM python:3.12-slim AS base
RUN apt-get update && apt-get install -y --no-install-recommends libespeak-ng1 espeak-ng-data \
    && rm -rf /var/lib/apt/lists/* && useradd -u 10001 -m app
COPY server/requirements-tts-small.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt \
    && python -m nltk.downloader -d /usr/local/share/nltk_data cmudict
COPY server/laura_g2p.py /app/
WORKDIR /app

FROM base
COPY server/tts_small_server.py /app/
COPY models/tts-small /app/model
USER 10001
ENV HOME=/home/app PYTHONUNBUFFERED=1
EXPOSE 8091
CMD ["uvicorn", "tts_small_server:app", "--host", "0.0.0.0", "--port", "8091"]
