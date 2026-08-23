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

FROM python-base AS runtime

# .NET 8 runtime via Microsoft's official Debian 12 (bookworm) apt repository —
# not hash-pinned by this project, unlike the (nonexistent) proprietary Yuanta
# component below. Needed at request time by pythonnet's `import clr` inside
# sdk_gateway.PythonNetYuantaSparkGateway.ensure_component_loaded(); never
# exercised by this repo's automated tests (no real DLL/account available).
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates gnupg wget \
    && wget -q https://packages.microsoft.com/config/debian/12/packages-microsoft-prod.deb -O /tmp/packages-microsoft-prod.deb \
    && dpkg -i /tmp/packages-microsoft-prod.deb \
    && rm -f /tmp/packages-microsoft-prod.deb \
    && apt-get update \
    && apt-get install -y --no-install-recommends dotnet-runtime-8.0 \
    && apt-get purge -y --auto-remove gnupg wget \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --gid 10002 yuanta \
    && useradd --uid 10002 --gid 10002 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin yuanta
COPY requirements.txt /tmp/requirements.txt
RUN python -m pip install --no-cache-dir -r /tmp/requirements.txt && rm -f /tmp/requirements.txt
COPY --chown=10002:10002 src/ /app/src/
ENV PYTHONPATH=/app/src
WORKDIR /app
USER 10002:10002
EXPOSE 8080
CMD ["uvicorn", "yuanta_broker_service.app:app", "--host", "0.0.0.0", "--port", "8080", "--no-access-log"]
