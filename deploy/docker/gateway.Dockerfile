# Voice gateway: ASR + turn-taking + LLM/TTS orchestration + client WebSocket, and the web client.
# Plain HTTP on 8080; TLS for devices is terminated in front of it (Helm chart sidecar or any proxy).
# No access log and uvicorn at warning: older clients still send the access key in the URL (?k=).
FROM python:3.12-slim
# gcc: Triton compiles a small launcher at runtime; git: NeMo is pinned to a commit.
RUN apt-get update && apt-get install -y --no-install-recommends gcc libc6-dev git libsndfile1 \
    && rm -rf /var/lib/apt/lists/* && useradd -u 10001 -m app && pip install --no-cache-dir uv
COPY server/requirements.txt /tmp/requirements.txt
RUN uv pip install --system --no-cache --index-strategy unsafe-best-match -r /tmp/requirements.txt
COPY server/ /app/
COPY clients/web/ /app/client/
WORKDIR /app
USER 10001
ENV HOME=/home/app HF_HOME=/cache/hf TMPDIR=/tmp PYTHONUNBUFFERED=1 CLIENT_DIR=/app/client
EXPOSE 8080
CMD ["uvicorn", "assistant_server:app", "--host", "0.0.0.0", "--port", "8080", "--ws-ping-interval", "20", "--no-access-log", "--log-level", "warning"]
