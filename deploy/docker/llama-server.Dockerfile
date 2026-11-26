# llama-server from PrismML's llama.cpp fork (Ternary Bonsai 2 PTQ1_0 / PQ2_0 kernels), CUDA build.
# CUDA_ARCH defaults to Turing (RTX 20xx); set it to your GPU's compute capability.
ARG CUDA_VERSION=12.8.1
FROM nvidia/cuda:${CUDA_VERSION}-devel-ubuntu24.04 AS build
ARG LLAMA_REPO=https://github.com/PrismML-Eng/llama.cpp
ARG LLAMA_REF=d12ca13bb
ARG CUDA_ARCH=75
ARG JOBS=""
RUN apt-get update && apt-get install -y --no-install-recommends git cmake build-essential libcurl4-openssl-dev ca-certificates \
    && rm -rf /var/lib/apt/lists/*
RUN git clone --filter=blob:none ${LLAMA_REPO} /src && git -C /src checkout ${LLAMA_REF}
WORKDIR /src
RUN cmake -B build -DGGML_CUDA=ON -DGGML_CUDA_FA_ALL_QUANTS=ON -DCMAKE_CUDA_ARCHITECTURES=${CUDA_ARCH} \
      -DCMAKE_BUILD_TYPE=Release -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF \
      -DCMAKE_EXE_LINKER_FLAGS=-Wl,--allow-shlib-undefined \
    && cmake --build build --config Release -j"${JOBS:-$(nproc)}" --target llama-server \
    && mkdir -p /out/lib && cp build/bin/llama-server /out/ && find build -name '*.so*' -exec cp -P {} /out/lib/ \;

FROM nvidia/cuda:${CUDA_VERSION}-runtime-ubuntu24.04
RUN apt-get update && apt-get install -y --no-install-recommends libcurl4 libgomp1 curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /out/llama-server /usr/local/bin/llama-server
COPY --from=build /out/lib/ /usr/local/lib/
RUN ldconfig
RUN useradd -u 10001 -m llama
USER 10001
EXPOSE 8090
HEALTHCHECK CMD curl -fs http://127.0.0.1:8090/health || exit 1
ENTRYPOINT ["llama-server"]
CMD ["--host", "0.0.0.0", "--port", "8090", "--models-preset", "/config/models.ini", "--models-max", "1"]
