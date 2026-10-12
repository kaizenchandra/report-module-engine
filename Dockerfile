FROM node:24.19.0-bookworm-slim AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY app app
COPY lib lib
COPY public public
COPY next.config.ts tsconfig.json next-env.d.ts ./
ARG CORE_URL=http://report-core:8080
ENV CORE_URL=$CORE_URL
RUN npm run build
FROM node:24.19.0-bookworm-slim
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build --chown=node:node /app /app
USER node
EXPOSE 3005
CMD ["npm", "run", "start"]
