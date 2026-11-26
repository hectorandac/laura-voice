# Qwen3-TTS voice clone (faster-qwen3-tts). torch cu126 wheels carry CUDA/cuDNN and run on sm_75 (Turing);
# the driver comes from the host. Model weights download to ~/.cache (mount a volume there).
FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends sox libsox-fmt-all \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -u 10001 -m app && pip install --no-cache-dir uv
COPY server/requirements-tts-qwen.txt /tmp/requirements.txt
RUN uv pip install --system --no-cache --index-strategy unsafe-best-match -r /tmp/requirements.txt
COPY server/tts_qwen_server.py /app/
COPY server/voices /app/voices
WORKDIR /app
USER 10001
ENV HOME=/home/app PYTHONUNBUFFERED=1 HF_HUB_DISABLE_TELEMETRY=1 PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True
EXPOSE 8091
CMD ["uvicorn", "tts_qwen_server:app", "--host", "0.0.0.0", "--port", "8091"]
