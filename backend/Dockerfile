# syntax=docker/dockerfile:1

# ---- Build stage ----------------------------------------------------------
# Build context is backend/ (the API has no dependency on the npm workspaces),
# so every COPY path below is relative to backend/.
FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src

# Restore first (cached when only source — not project files — changes).
COPY Tmap.sln ./
COPY src/Tmap.Api/Tmap.Api.csproj src/Tmap.Api/
COPY tests/Tmap.Api.Tests/Tmap.Api.Tests.csproj tests/Tmap.Api.Tests/
RUN dotnet restore src/Tmap.Api/Tmap.Api.csproj

# Copy the rest of the source and publish the API only.
# OpenApiGenerateDocumentsOnBuild=false skips the dev-only OpenAPI emit (the
# csproj points it at ../../../packages/api-client, absent from this context).
COPY . .
RUN dotnet publish src/Tmap.Api/Tmap.Api.csproj \
    -c Release \
    -o /app \
    -p:OpenApiGenerateDocumentsOnBuild=false

# ---- Runtime stage --------------------------------------------------------
FROM mcr.microsoft.com/dotnet/aspnet:10.0 AS runtime
WORKDIR /app

# Coolify runs its HTTP health check from INSIDE the container, and the aspnet
# runtime image ships neither curl nor wget — install curl for the HEALTHCHECK.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Run as a non-root user (defense in depth — the app needs no root privilege).
RUN useradd --create-home --shell /usr/sbin/nologin --user-group appuser
COPY --from=build --chown=appuser:appuser /app ./
USER appuser

# Kestrel binds plain HTTP on 8080; Traefik terminates TLS at the edge.
ENV ASPNETCORE_HTTP_PORTS=8080
EXPOSE 8080

# /health/ready returns 200 only once Postgres is reachable AND migrations ran.
# --start-period (40s) exceeds the synchronous migrate-before-serve window so the
# first/cold deploy does not flap. A Dockerfile HEALTHCHECK takes precedence over
# Coolify's UI check.
HEALTHCHECK --start-period=40s --interval=10s --timeout=5s --retries=5 \
    CMD curl -fsS http://localhost:8080/health/ready || exit 1

ENTRYPOINT ["dotnet", "Tmap.Api.dll"]
