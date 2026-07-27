# ── Stage 1: Build ──────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: Runtime ─────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# curl:   required for outbound TWSE / NASDAQ / FinMind HTTP calls + healthcheck
# rclone: 公開資訊 JSON 同步上傳 Google Drive（Requirement 50 / Task 241）。只用 copyto 上傳，
#         不實作任何刪除 Drive 檔案的路徑。設定檔由 docker-compose 唯讀掛入單一
#         ~/.config/rclone/rclone.conf（含三個 section），本服務只使用 [GDriveOutput]。
#         已知取捨：該檔同時含 DB 備份的 refresh token 與 crypt 解密密碼，故本服務（全 stack 唯一
#         對外打第三方者，攻擊面最大）也讀得到它們。取捨全文與復原方式見 spec/steering/tech.md §4。
#
# 注意：本 image 的中文路徑處理依賴 base image 自帶的 locale（LANG/LC_ALL=en_US.UTF-8），
# 它使 sun.jnu.encoding=UTF-8，ProcessBuilder 才能把中文目錄名正確傳給 rclone。
# **不得**設 ENV LANG=C／LC_ALL=C 或改用不帶 UTF-8 locale 的 base image，否則中文目錄名會變成 "?"
# 而靜默寫到錯誤位置（rclone 不會報錯）。JVM 的 -Dsun.jnu.encoding 參數對此無效（實測），別靠它補救。
RUN apk add --no-cache curl rclone

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=builder /app/target/*.jar app.jar
RUN chown appuser:appgroup app.jar
USER appuser

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar", \
  "--spring.profiles.active=postgres"]
