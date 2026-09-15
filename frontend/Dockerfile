# Multi-stage build: compile the Angular production bundle with Node, then serve the static
# output from a lightweight nginx image -- no Node process runs in the final container. Same
# build step deploy/build.sh runs directly on an EC2 host (`npm ci && npm run build`), just
# containerized, with nginx replacing the reverse-proxy role a host-installed nginx would play in
# the non-Docker deployment (see deploy/nginx/pharos-dashboard.conf).

FROM node:24-alpine AS build
WORKDIR /build

# Dependency install in its own layer -- only re-runs when the lockfile changes, not on every
# source edit.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /build/dist/dashboard/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
