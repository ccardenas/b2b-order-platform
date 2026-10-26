# Dependencias de produccion en una etapa propia: la imagen final no lleva
# TypeScript, Jest ni el resto de devDependencies.
FROM node:22.23.4-alpine3.24 AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev

# Build con todas las dependencias (tsc es devDependency).
FROM node:22.23.4-alpine3.24 AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY tsconfig.json tsconfig.build.json ./
COPY src ./src
RUN npm run build

FROM node:22.23.4-alpine3.24
ENV NODE_ENV=production PORT=3000
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY package.json ./
# Usuario sin privilegios que ya trae la imagen oficial.
USER node
EXPOSE 3000
# node directo y no "npm start": npm no reenvia SIGTERM al proceso hijo y
# enableShutdownHooks no llegaria a cerrar el servidor.
CMD ["node", "dist/main.js"]
