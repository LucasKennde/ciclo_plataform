FROM node:22.22.3-alpine AS build
WORKDIR /workspace
RUN corepack enable
COPY frontend/package.json frontend/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY frontend ./
ARG APP
RUN pnpm exec ng build ui \
    && pnpm exec ng build api-client \
    && pnpm exec ng build auth \
    && pnpm exec ng build ${APP}

FROM nginx:1.29-alpine
ARG APP
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist/${APP}/browser /usr/share/nginx/html
EXPOSE 80
