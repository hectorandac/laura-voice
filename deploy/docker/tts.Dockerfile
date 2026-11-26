# Supertonic TTS on onnxruntime-gpu (CUDA/cuDNN come from the nvidia-* wheels, the driver from the host).
FROM python:3.12-slim
RUN useradd -u 10001 -m app && pip install --no-cache-dir uv
COPY server/requirements-tts.txt /tmp/requirements.txt
# supertonic depends on the CPU onnxruntime, which shadows the GPU build: keep only onnxruntime-gpu.
RUN uv pip install --system --no-cache -r /tmp/requirements.txt \
    && uv pip uninstall --system onnxruntime \
    && uv pip install --system --no-cache --reinstall --no-deps "onnxruntime-gpu==1.32.0"
COPY server/tts_server.py /app/
WORKDIR /app
USER 10001
# Supertonic keeps its models in ~/.cache; mount a volume there to avoid re-downloading.
ENV HOME=/home/app PYTHONUNBUFFERED=1
EXPOSE 8091
CMD ["uvicorn", "tts_server:app", "--host", "0.0.0.0", "--port", "8091"]
