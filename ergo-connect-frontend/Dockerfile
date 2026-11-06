# syntax=docker/dockerfile:1
# Runs on the CI runner's CPU: dist/ is static, so the arm64 image only emulates the final rm.
FROM --platform=$BUILDPLATFORM node:22-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# Runs as the image's built-in non-root "nginx" user, which can't bind <1024 - nginx.conf
# listens on 8080 to match (security-reviewer L2).
FROM nginxinc/nginx-unprivileged:1.30-alpine
# The base image lags Alpine's security fixes (Trivy gate in demo-images.yml: brotli, curl, expat, zlib, 2026-11-06), so
# pull the patched packages at build time, then drop back to the base image's non-root user (uid 101 "nginx").
USER root
RUN apk upgrade --no-cache
USER 101
COPY --from=build /build/dist /usr/share/nginx/html
# A template, not a static conf: the base image's entrypoint envsubst's it into
# conf.d/default.conf on container start, substituting only real container env vars (nginx's
# own $scheme/$http_x_forwarded_proto etc. are untouched, since they're not env vars). The
# default below matches plain Docker/docker-compose.yml; Container Apps overrides it (Phase 8k).
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
# Drop the stock conf and restrict substitution to the one variable: a failed render must fail loudly, not fall
# back to a stock server without the security headers (security-reviewer L6).
RUN rm /etc/nginx/conf.d/default.conf
ENV NGINX_ENVSUBST_FILTER=^(BACKEND_UPSTREAM|BACKEND_HOST_HEADER)$
ENV BACKEND_UPSTREAM=http://backend:8080
ENV BACKEND_HOST_HEADER=\$http_host
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/ >/dev/null || exit 1
