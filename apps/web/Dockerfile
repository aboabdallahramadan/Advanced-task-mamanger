# syntax=docker/dockerfile:1

# ---- Build stage ----------------------------------------------------------
# Build context is the REPO ROOT — the npm workspaces (@tmap/app,
# @tmap/api-client) must resolve, so we cannot build from apps/web alone.
FROM node:20 AS build
WORKDIR /repo

# The production API origin is baked into the bundle at build time (Vite static
# replacement of import.meta.env.VITE_API_BASE_URL). Supplied as a build-arg so
# Coolify can set it without editing this Dockerfile. Defaulting it to the prod
# origin guarantees the bundle never ships pointing at the localhost fallback.
ARG VITE_API_BASE_URL=https://api-tasks.qmindtech.net
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}

# Install workspace deps. We deliberately do NOT copy package-lock.json and use `npm install`
# (not `npm ci`): the committed lockfile records the native optional binaries (rollup/esbuild) only
# for the lockfile-generating host's platform — an npm limitation (npm/cli#4828) — so with that
# lockfile present this linux image fails with "Cannot find module @rollup/rollup-linux-x64-gnu".
# Resolving fresh from package.json fetches the correct linux binaries; versions stay bounded by the
# package.json semver ranges. (Regenerating a cross-platform lockfile re-resolves the whole tree and
# would silently bump ~600 deps, so it is intentionally avoided here.)
COPY package.json ./
COPY apps/web/package.json apps/web/
COPY packages/app/package.json packages/app/
COPY packages/api-client/package.json packages/api-client/
RUN npm install --no-audit --no-fund

# Copy the rest of the workspace and build only the web app → apps/web/dist.
COPY . .
RUN npm run build:web

# ---- Runtime stage --------------------------------------------------------
FROM nginx:alpine AS runtime
# Replace the default server block with the SPA config (fallback + caching).
COPY apps/web/nginx.conf /etc/nginx/conf.d/default.conf
# Serve the built static bundle.
COPY --from=build /repo/apps/web/dist /usr/share/nginx/html
EXPOSE 80
# nginx:alpine's default CMD already runs nginx in the foreground.
