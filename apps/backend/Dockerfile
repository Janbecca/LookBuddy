FROM python:3.12-slim AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PATH="/app/.venv/bin:$PATH"

RUN groupadd --gid 10001 lookbuddy \
    && useradd --uid 10001 --gid lookbuddy --home-dir /app lookbuddy \
    && pip install --no-cache-dir uv==0.9.15

WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project

COPY src ./src
COPY tools ./tools
RUN uv sync --frozen --no-dev --no-editable \
    && mkdir /data && chown -R lookbuddy:lookbuddy /app /data
USER lookbuddy

EXPOSE 8080
CMD ["uvicorn", "lookbuddy.main:app", "--app-dir", "src", "--host", "0.0.0.0", "--port", "8080", "--proxy-headers", "--forwarded-allow-ips=*"]
