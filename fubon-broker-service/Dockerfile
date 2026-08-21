# syntax=docker/dockerfile:1.7
FROM python:3.13.7-slim-bookworm AS python-base

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

FROM python-base AS test
WORKDIR /app
COPY requirements.txt requirements-test.txt ./
RUN python -m pip install --no-cache-dir -r requirements.txt -r requirements-test.txt
COPY src/ ./src/
COPY tests/ ./tests/
ENV PYTHONPATH=/app/src
CMD ["pytest", "-q"]

FROM python-base AS sdk-builder

ARG FUBON_SDK_URL=https://www.fbs.com.tw/TradeAPI_SDK/fubon_binary/fubon_neo-2.2.9-cp37-abi3-manylinux_2_17_x86_64.manylinux2014_x86_64.zip
ARG FUBON_SDK_ZIP_SHA256=9592e7afb9eba2412ac4a5852df0a138850f6c9803136e7dae503d3606d3b432
ARG FUBON_SDK_WHEEL_SHA256=6cf623a601e4b42d4255e79e72d0cf60ac4d20247784bbeb8a478adfcb1864e3

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl unzip \
    && rm -rf /var/lib/apt/lists/*
RUN python -m venv /opt/fubon-venv
COPY requirements.txt /tmp/requirements.txt
RUN /opt/fubon-venv/bin/pip install --no-cache-dir -r /tmp/requirements.txt
RUN set -eu; \
    curl --fail --silent --show-error --location "$FUBON_SDK_URL" --output /tmp/fubon-sdk.zip; \
    printf '%s  %s\n' "$FUBON_SDK_ZIP_SHA256" /tmp/fubon-sdk.zip | sha256sum --check --strict; \
    mkdir /tmp/fubon-sdk; \
    unzip -q /tmp/fubon-sdk.zip -d /tmp/fubon-sdk; \
    wheel="$(find /tmp/fubon-sdk -type f -name 'fubon_neo-2.2.9-*.whl')"; \
    test -n "$wheel"; \
    test "$(find /tmp/fubon-sdk -type f -name '*.whl' | wc -l)" -eq 1; \
    printf '%s  %s\n' "$FUBON_SDK_WHEEL_SHA256" "$wheel" | sha256sum --check --strict; \
    /opt/fubon-venv/bin/pip install --no-cache-dir "$wheel"; \
    /opt/fubon-venv/bin/pip check; \
    rm -rf /tmp/fubon-sdk /tmp/fubon-sdk.zip /tmp/requirements.txt

FROM python-base AS runtime

RUN groupadd --gid 10001 fubon \
    && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin fubon
COPY --from=sdk-builder /opt/fubon-venv /opt/fubon-venv
COPY --chown=10001:10001 src/ /app/src/
ENV PATH=/opt/fubon-venv/bin:$PATH \
    PYTHONPATH=/app/src
WORKDIR /app
USER 10001:10001
EXPOSE 8080
CMD ["uvicorn", "fubon_broker_service.app:app", "--host", "0.0.0.0", "--port", "8080", "--no-access-log"]
