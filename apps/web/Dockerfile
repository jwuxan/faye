# One image for the three Next.js apps, built once: compose picks the app with APP (login, admin or
# demo-client) and its port with PORT. Each app ships as Next's standalone output, so the image holds
# only what the servers need at runtime, not the workspace's dev dependencies.
FROM node:24.22.2-slim@sha256:c86ecead9e55a5210ac1727de38a4ca840bf03dcaf34dc9749addbf383b1920c AS build
RUN npm install -g pnpm@11.20.0
WORKDIR /web
COPY . .
# One app at a time, so the build fits a small Docker VM
RUN pnpm install --frozen-lockfile && pnpm --filter "./apps/*" --workspace-concurrency=1 build
RUN for app in login admin demo-client; do \
      mkdir -p /out/$app && cp -r apps/$app/.next/standalone/. /out/$app/ \
      && cp -r apps/$app/.next/static /out/$app/apps/$app/.next/static; \
    done

FROM node:24.22.2-slim@sha256:c86ecead9e55a5210ac1727de38a4ca840bf03dcaf34dc9749addbf383b1920c
ENV NODE_ENV=production HOSTNAME=0.0.0.0
COPY --from=build /out /app
# The demo client's secrets volume (generated passwords and client secrets) is mounted here; owned by
# the user every service that opens the database runs as (deploy/compose.yaml).
RUN mkdir -m 700 /secrets && chown 100:101 /secrets
CMD ["sh", "-c", "cd /app/$APP && exec node apps/$APP/server.js"]
