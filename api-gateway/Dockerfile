FROM nginx:alpine

RUN rm -f /etc/nginx/conf.d/default.conf
COPY nginx.conf /etc/nginx/nginx.conf

# Task 421／Requirement 143：具名 volume 第一次建立時會複製 image 內該路徑當下的屬主／權限；
# 這是唯一讓非 root 的 nginx user 事後仍能寫入這個掛載路徑的方式（volume 若在鏡像沒有預先
# 建立該目錄的情況下被建立，預設屬主是 root，read_only: true 的容器內 nginx user 屆時無法補救）。
RUN mkdir -p /var/log/nginx-shared && chown nginx:nginx /var/log/nginx-shared

USER nginx

EXPOSE 9090

CMD ["nginx", "-g", "daemon off;"]
