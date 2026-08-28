# ── Stage 1: Build ──────────────────────────────────────────
FROM node:22-alpine AS builder

WORKDIR /app
COPY package*.json ./
RUN npm ci --silent

COPY . .
RUN npm run build

# ── Stage 2: Serve with Nginx ────────────────────────────────
FROM nginx:alpine

RUN apk add --no-cache openssl

COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY docker-entrypoint-tls.sh /docker-entrypoint.d/40-frontend-tls-fallback.sh
RUN chmod +x /docker-entrypoint.d/40-frontend-tls-fallback.sh

EXPOSE 80 443

CMD ["nginx", "-g", "daemon off;"]
