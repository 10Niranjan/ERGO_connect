# syntax=docker/dockerfile:1
FROM node:22-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# Runs as the image's built-in non-root "nginx" user, which can't bind <1024 - nginx.conf
# listens on 8080 to match (security-reviewer L2).
FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -qO- http://localhost:8080/ >/dev/null || exit 1
