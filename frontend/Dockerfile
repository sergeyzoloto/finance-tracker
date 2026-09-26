FROM node:24-alpine AS dev
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
CMD ["npm", "run", "dev", "--", "--host"]

FROM dev AS build
RUN npm run build

# Production (deploy/app/docker-compose.yml): the static build alone. The server's Caddy serves it from the volume
# finance-tracker-www; this image copies it there, then exits.
FROM busybox:1.37 AS static
COPY --from=build /app/dist /dist
# Hashed assets first, then index.html in one rename, so a browser never gets an index.html whose assets aren't there
# yet. Every current asset is rewritten, so an asset older than 7 days belongs only to older builds; until then, a
# page opened before the update can still load its lazy chunks.
COPY --chmod=755 <<'EOF' /publish
#!/bin/sh
set -eu
touch /www/.writable 2>/dev/null || { echo "Cannot write to /www, the finance-tracker-www volume" >&2; exit 1; }
rm /www/.writable
mkdir -p /www/assets
cp -R /dist/assets/. /www/assets/
for f in /dist/*; do
  case "${f##*/}" in assets | index.html) ;; *) cp -R "$f" /www/ ;; esac
done
cp /dist/index.html /www/.index.html.new
mv /www/.index.html.new /www/index.html
find /www/assets -type f -mtime +7 -delete
echo "Published $(ls /dist/assets | wc -l) assets and index.html"
EOF
RUN mkdir /www && chown 65534:65534 /www
USER 65534:65534
CMD ["/publish"]

# The default target: nginx serves the build and proxies the backend (docker-compose.yml, for local runs).
FROM nginx:1.30-alpine
# nginx.conf is a template; these defaults fit docker-compose.yml.
ENV NGINX_PORT=80 BACKEND_URL=http://backend:8080
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
