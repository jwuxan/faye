# One image per Next.js app: docker build --build-arg APP=login|demo-client
FROM node:24-slim
ARG APP
ENV APP=${APP}
RUN npm install -g pnpm@11.20.0
WORKDIR /web
COPY . .
RUN pnpm install --frozen-lockfile && pnpm --filter "@faye/${APP}" build
CMD ["sh", "-c", "exec pnpm --filter \"@faye/${APP}\" start"]
