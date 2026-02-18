# ExecuChat

A dual-mode Android AI assistant that runs **fully offline** with on-device inference via ExecuTorch, 
or **online** through a self-hosted vLLM server with web search and deep research capabilities.

### Architecture Overview

[Execu Chat architecture](docs/execu_chat_arc.png)
[Execu Chat welcome](docs/execu_arc_home.jpeg)

### Offline Mode

On-device LLM inference — nothing leaves the phone.

- Run Executorch program on phone- llama, qwen, llava, etc
- Has internal kv_cache management (remembers)
- Whisper speech-to-text (asr)
- Image Q&A with multimodal models (LLaVA)
- XNNPACK and Vulkan backend options
- Custom system prompts, generation stats, memory monitoring

> 📖 [Why ExecuTorch, model export, backends & benchmarks →](docs/offline.md)

### Online Mode


Cloud-powered inference via self-hosted docker stack with vLLM for model inference, searXNG for 
search, and python/LangChain for deep-research. 6 services/containers all on same network

- **vLLM** serve hf models on gpu, OpenAI-API
- **searcXNG** privacy metasearch engine, JSON API
- **Python** to run LangChain, as fast-api for sse progress
- **Prometheus** for live metric collection
- **gratana** for monitoring dashboards
- **Redis** Task queue & caching for research agent

> 📖 [Server setup, Docker Compose & configuration →](docs/online.md)
>
> 📖 [How the deep research agent works →](docs/deep-research.md)


### Prerequisites

- This setup is with Linux Ubuntu 24
- Android device with 8gb+ RAM (12+gb to run Llava) OFFLINE MODE
- NVIDIA GPU with 16GB+ VRAM ONLINE MODE
- Docker & Docker Compose


