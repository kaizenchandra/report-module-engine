FROM golang:1.27.2 AS build
WORKDIR /src
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -o /delivery .
FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /delivery /delivery
EXPOSE 8090
ENTRYPOINT ["/delivery"]
