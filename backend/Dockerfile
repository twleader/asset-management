# ── Stage 1: Build ──────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /app
COPY pom.xml .
# Download dependencies first (cache layer)
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: Runtime ─────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Install:
# - curl: required for Yahoo Finance API calls
# - postgresql16-client: pg_dump / pg_restore (must match server major version)
# - rclone: upload backups to Google Drive (crypt remote)
# - fontconfig + freetype + ttf-dejavu: headless AWT 拉丁字型 / 點陣化（缺則繪文字拋例外）
# - font-noto-cjk: 走勢圖 legend 繁中（股價/月線MA20/季線MA60/年線MA240/K/D）字型，
#   提供 /usr/share/fonts/noto/NotoSansCJK-Regular.ttc（其中 TC face 由 Font.createFonts 挑出）
# fc-cache：build 期先建好字型快取，避免容器首次繪圖冷快取找不到字型
RUN apk add --no-cache curl postgresql16-client rclone fontconfig freetype ttf-dejavu font-noto-cjk \
    && fc-cache -f

# Create non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=builder /app/target/*.jar app.jar

RUN chown appuser:appgroup app.jar
USER appuser

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-Djava.awt.headless=true", \
  "-jar", "app.jar", \
  "--spring.profiles.active=postgres"]
