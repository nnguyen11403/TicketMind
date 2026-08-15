# syntax=docker/dockerfile:1.7

FROM node:22-alpine AS build
WORKDIR /app

# Dependencies first so a source-only change doesn't reinstall node_modules.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# Vite inlines VITE_* variables at build time — there is no runtime lookup, so
# this URL is baked into the bundle and the image is environment-specific.
# It must be the address the BROWSER can reach, not a compose service name:
# the request is made from the user's machine, where `backend` does not
# resolve. Changing the API host means rebuilding the image.
ARG VITE_API_BASE_URL=http://localhost:8080
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

FROM nginx:1.27-alpine
RUN apk add --no-cache wget
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY security-headers.conf /etc/nginx/security-headers.conf

# The CSP has to name the API origin in connect-src, and that origin is the
# same build-time value the bundle was compiled against. ARG does not cross
# stages, hence the redeclaration. Substituted here rather than at container
# start because nginx would otherwise read ${CSP_CONNECT_SRC} as one of its own
# variables and refuse to load the config. `nginx -t` then fails the build
# rather than the deploy if any of this is malformed.
ARG VITE_API_BASE_URL=http://localhost:8080
RUN sed -i "s|\${CSP_CONNECT_SRC}|${VITE_API_BASE_URL}|g" /etc/nginx/security-headers.conf \
    && nginx -t
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s \
  CMD wget --quiet --tries=1 --spider http://localhost/healthz || exit 1
