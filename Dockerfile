# Build stage
FROM golang:1.24-alpine AS builder

WORKDIR /app

# Install templ
RUN go install github.com/a-h/templ/cmd/templ@v0.2.793

# Copy go mod files first for caching
COPY go.mod go.sum ./
RUN go mod download

# Copy source
COPY . .

# Generate templ files
RUN templ generate

# Build binary
RUN CGO_ENABLED=0 go build -ldflags="-s -w" -o goshelf .

# Runtime stage
FROM alpine:3.20

RUN apk --no-cache add ca-certificates

WORKDIR /app

COPY --from=builder /app/goshelf .

EXPOSE 8080

ENV LISTEN_ADDR=:8080
ENV DB_PATH=/data/goshelf.db
ENV MEDIA_PATH=/audiobooks

VOLUME ["/data"]

ENTRYPOINT ["/app/goshelf"]
