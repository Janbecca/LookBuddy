# Launch builds this file from the repository root.
FROM node:22-bookworm-slim AS web
WORKDIR /build
RUN corepack enable && corepack prepare pnpm@10.28.0 --activate
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY apps/web/package.json apps/web/package.json
RUN pnpm install --frozen-lockfile --filter @lookbuddy/web
COPY apps/web apps/web
RUN pnpm build:web

FROM python:3.12-slim AS runtime
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PATH="/app/.venv/bin:$PATH" \
    LOOKBUDDY_ENV=competition \
    LOOKBUDDY_DATABASE_URL=sqlite:////data/lookbuddy.db \
    LOOKBUDDY_WEB_DIST=/app/web
RUN groupadd --gid 10001 lookbuddy \
    && useradd --uid 10001 --gid lookbuddy --home-dir /app lookbuddy \
    && pip install --no-cache-dir uv==0.9.15
WORKDIR /app
COPY apps/backend/pyproject.toml apps/backend/uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project
COPY apps/backend/src ./src
COPY apps/backend/tools ./tools
RUN uv sync --frozen --no-dev --no-editable \
    && mkdir /data && chown -R lookbuddy:lookbuddy /app /data
COPY --from=web /build/apps/web/dist ./web
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/health/ready', timeout=3)"
CMD ["uvicorn", "lookbuddy.main:app", "--app-dir", "src", "--host", "0.0.0.0", "--port", "8080", "--workers", "1", "--proxy-headers", "--forwarded-allow-ips=*"]
