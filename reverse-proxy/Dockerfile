# Dockerfile for Nginx acting as reverse proxy
FROM nginx:alpine

# Copy custom nginx configuration if needed
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 5000

CMD ["nginx", "-g", "daemon off;"]
