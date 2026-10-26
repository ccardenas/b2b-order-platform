# Etapa de build: compila un binario estatico (CGO_ENABLED=0) para que la
# imagen final no necesite libc ni el toolchain de Go.
FROM golang:1.26.10-alpine3.24 AS build
WORKDIR /src

# Sin dependencias externas no hay go.sum ni nada que descargar: basta go.mod.
COPY go.mod ./
COPY cmd ./cmd
COPY internal ./internal
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/products-api ./cmd/products-api

# Etapa final: alpine y no distroless, porque el healthcheck de compose usa el
# wget de busybox. Con distroless habria que agregar un subcomando de
# healthcheck al binario solo para eso.
FROM alpine:3.24.3
RUN adduser -D -H -u 10001 app
COPY --from=build /out/products-api /usr/local/bin/products-api
USER app
ENV PORT=8080
EXPOSE 8080
# Forma exec: el binario es PID 1 y recibe SIGTERM, que signal.NotifyContext
# convierte en un server.Shutdown ordenado.
ENTRYPOINT ["/usr/local/bin/products-api"]
