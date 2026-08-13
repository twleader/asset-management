FROM nginx:alpine

RUN rm -f /etc/nginx/conf.d/default.conf
COPY nginx.conf /etc/nginx/nginx.conf

USER nginx

EXPOSE 9090

CMD ["nginx", "-g", "daemon off;"]
