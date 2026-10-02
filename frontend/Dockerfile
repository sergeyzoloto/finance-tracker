FROM node:24-alpine AS dev
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
CMD ["npm", "run", "dev", "--", "--host"]

FROM dev AS build
RUN npm run build
# The web image never depends on the modes of the build context. Vite copies public/ into dist with each file's mode,
# and the server's clone, written under the deploy scripts' umask 077, had privacy.html as 600: nginx (uid 101) answered
# /privacy with 403 after F7's deploy (deploy/RUNBOOK.md, "Deployed revisions"). Every folder 0755, every file 0644.
RUN find dist -type d -exec chmod 0755 {} + && find dist -type f -exec chmod 0644 {} +

# Production (deploy/app/docker-compose.yml): the container finance-tracker-web, which serves the static build behind
# the auth server's Caddy. nginx runs as its unprivileged user (uid 101) on port 8080; the image holds nothing else.
FROM nginxinc/nginx-unprivileged:1.30.5-alpine-slim@sha256:307ca0bded4dda86dade6f5bd2a2551601bec9bac3fd73a21613fabcd9eca66a AS web
# Owned by root and readable by all, whatever the checkout's modes: nginx reads these files and never writes them. A
# configuration nginx can't read would stop it at start, and web would never be healthy. --chmod applies to a folder's
# files and folders alike, so dist's modes are set in the build stage above instead. BuildKit, which --chmod needs, is
# what CI and the server build with (the api's Dockerfile needs it too, for RUN --mount).
COPY --chmod=0644 web.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
# nginx alone, without the image's entrypoint scripts, which try to rewrite the configuration.
ENTRYPOINT ["nginx", "-g", "daemon off;"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1

# The default target: nginx serves the build and proxies the backend (docker-compose.yml, for local runs).
FROM nginx:1.30-alpine
# nginx.conf is a template; these defaults fit docker-compose.yml.
ENV NGINX_PORT=80 BACKEND_URL=http://backend:8080
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
