# Forecasting service image for the local Docker Compose stack (ADR 0005, ADR 0007, ADR 0028).
#
# Two stages: uv installs the locked runtime dependencies and the service into a virtual environment, then only that
# environment is copied onto a clean Python image and run as a non-root user. Dependencies are installed before the
# sources are copied, so a code change reinstalls only the service itself.
# Tests and development tools are not installed: they run in CI (forecasting workflow) and with `uv run pytest`.
#
# No BuildKit-only instruction (such as cache mounts): the orchestrator's process tests build this image with
# Testcontainers, which uses Docker's classic builder.
#
# Base images are pinned to an exact release, so the same Dockerfile builds the same image over time; Dependabot
# proposes the updates (.github/dependabot.yml). Both stages use the same Python release: the virtual environment
# refers to the interpreter by path and is only valid on the release it was created with.

# uv is copied from its official image: a single static binary, nothing else
FROM ghcr.io/astral-sh/uv:0.13.7 AS uv

# ---------------------------------------------------------------- build
FROM python:3.13.18-slim-trixie AS build
COPY --from=uv /uv /usr/local/bin/uv
# Bytecode compiled at build time, so the first request does not pay for it; no download cache left in the layers;
# the image's Python only, never a downloaded one.
ENV UV_COMPILE_BYTECODE=1 \
    UV_NO_CACHE=1 \
    UV_PYTHON_DOWNLOADS=never \
    UV_PROJECT_ENVIRONMENT=/app/.venv
WORKDIR /app

# Dependencies first, in their own layer: rebuilt only when the lockfile changes
COPY pyproject.toml uv.lock ./
RUN uv sync --locked --no-dev --no-install-project

COPY src src
# Installed as a regular package, not in editable mode, so the runtime stage needs no sources next to it
RUN uv sync --locked --no-dev --no-editable

# ---------------------------------------------------------------- runtime
FROM python:3.13.18-slim-trixie
RUN groupadd --system --gid 10001 provisio \
    && useradd --system --uid 10001 --gid provisio --no-create-home provisio
COPY --from=build /app/.venv /app/.venv
ENV PATH="/app/.venv/bin:$PATH" \
    PYTHONUNBUFFERED=1
# Numeric, so tools that check for a non-root user can verify it without reading /etc/passwd
USER 10001:10001
EXPOSE 8000
# The healthcheck lives in docker-compose.yml, next to the other services' checks
ENTRYPOINT ["uvicorn", "provisio_forecasting.app:app", "--host", "0.0.0.0", "--port", "8000"]
