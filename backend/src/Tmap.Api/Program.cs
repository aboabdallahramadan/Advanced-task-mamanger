using System.Text.Json.Serialization;
using FluentValidation;
using Serilog;
using Tmap.Api.Common;
using Tmap.Api.Common.Cors;
using Tmap.Api.Common.Errors;
using Tmap.Api.Common.OpenApi;
using Tmap.Api.Features.Auth;
using Tmap.Api.Features.DailyPlans;
using Tmap.Api.Features.FocusSessions;
using Tmap.Api.Features.Health;
using Tmap.Api.Features.NoteGroups;
using Tmap.Api.Features.Notes;
using Tmap.Api.Features.Projects;
using Tmap.Api.Features.Recurrence;
using Tmap.Api.Features.Reports;
using Tmap.Api.Features.Settings;
using Tmap.Api.Features.Subtasks;
using Tmap.Api.Features.Sync;
using Tmap.Api.Features.Tasks;
using Tmap.Api.Infrastructure.Identity;
using Tmap.Api.Infrastructure.Jwt;
using Tmap.Api.Infrastructure.Persistence;
using Microsoft.AspNetCore.HttpOverrides;

var builder = WebApplication.CreateBuilder(args);

builder.Host.UseSerilog(
    (context, services, configuration) =>
        configuration.ReadFrom.Configuration(context.Configuration).ReadFrom.Services(services)
);

builder.Services.AddTmapProblemDetails();
builder.Services.AddExceptionHandler<GlobalExceptionHandler>();

// Configure System.Text.Json for Minimal APIs: serialize enums as strings so client DTOs
// can send "Inbox" instead of 0 for enum values.
builder.Services.ConfigureHttpJsonOptions(options =>
{
    options.SerializerOptions.Converters.Add(new JsonStringEnumConverter());
    // Presence-tracking Optional<T>: lets PATCH handlers distinguish absent (leave unchanged)
    // from explicit null (clear) for nullable task fields. Serialized as the inner value.
    options.SerializerOptions.Converters.Add(new OptionalJsonConverterFactory());
});

// HTTP-scoped current-user accessor (reads the 'sub' claim). P1 hardens the fail-closed
// behavior; P2 makes auth actually populate the claim.
builder.Services.AddHttpContextAccessor();
builder.Services.AddScoped<ICurrentUser, CurrentUser>();

// AppDbContext is Scoped. Connection string is read lazily from IConfiguration so the test
// harness can override it via WebApplicationFactory.ConfigureAppConfiguration. P1 adds the
// real entities, RLS, triggers, and query filters.
builder.Services.AddPersistence();

// ASP.NET Core Identity wired onto AppDbContext with SP1 password policy and lockout settings.
builder.Services.AddTmapIdentity();

// JWT: options (with startup validation), service, and bearer validation.
builder.Services
    .AddOptions<JwtOptions>()
    .Bind(builder.Configuration.GetSection(JwtOptions.SectionName))
    .Validate(
        o =>
            !string.IsNullOrWhiteSpace(o.Issuer)
            && !string.IsNullOrWhiteSpace(o.Audience)
            && !string.IsNullOrWhiteSpace(o.ActiveKeyId)
            && o.SigningKeys.ContainsKey(o.ActiveKeyId)
            && o.SigningKeys.Values.All(v => v.Length >= 32),
        "Jwt config is invalid. Issuer and Audience must be non-empty; "
        + "ActiveKeyId must be present and exist in SigningKeys; "
        + "every signing key must be at least 32 characters.")
    .ValidateOnStart();
builder.Services.AddScoped<IJwtService, JwtService>();

// JWT bearer authentication + authorization middleware services. Without this the
// auth middleware is absent and RequireAuthorization() endpoints throw (no 401).
builder.Services.AddTmapJwtAuth(builder.Configuration);

// FluentValidation: auto-register all validators in the assembly (including internal types for slice validators).
builder.Services.AddValidatorsFromAssemblyContaining<Program>(includeInternalTypes: true);

// Rate limiting: sliding-window policy keyed by IP + email for auth endpoints.
builder.Services.AddTmapRateLimiting();

// CORS: config-bound per-environment origin allowlist. Credentials are allowed only for
// explicitly named origins (never combined with AllowAnyOrigin).
builder.Services.AddTmapCors(builder.Configuration);

// Readiness: a DB-reachability health check (Npgsql SELECT 1) on the runtime connection.
// The probe runs as app_user and touches no RLS table, so it is safe (no tenant context).
// Mapped at /health/ready (S0-4 HealthEndpoints); /health stays pure liveness.
builder.Services
    .AddHealthChecks()
    .AddNpgSql(
        _ => builder.Configuration.GetConnectionString("Postgres") ?? string.Empty,
        name: "postgres");

// OpenAPI 3.1 document with stable Info and JWT Bearer security scheme.
builder.Services.AddOpenApi("v1", options =>
{
    options.AddDocumentTransformer<DocumentInfoTransformer>();
    options.AddDocumentTransformer<BearerSecuritySchemeTransformer>();
    // Render Optional<T> request properties as their inner nullable type so the generated
    // client schema stays identical to a plain nullable field. Force these wrapper types to be
    // INLINED (no $ref component) so the schema transformer can rewrite them into the inner type.
    var defaultRefId = options.CreateSchemaReferenceId;
    options.CreateSchemaReferenceId = jsonTypeInfo =>
        OptionalType.GetInnerType(jsonTypeInfo.Type) is not null
            ? null
            : defaultRefId(jsonTypeInfo);
    options.AddSchemaTransformer<OptionalSchemaTransformer>();
});

// Reverse-proxy (Traefik/Coolify): trust ONLY the single proxy hop for the real scheme and client IP.
// ForwardLimit=1 caps processing to one hop. Clearing the framework-default KnownNetworks/KnownProxies
// and then re-adding only loopback + the Docker bridge range is deliberate and security-critical: an
// EMPTY known-list disables the middleware's known-peer check entirely (its internal checkKnownIps
// becomes false), which would honor X-Forwarded-* from ANY direct or same-Docker-network client —
// letting it forge its client IP to evade the per-IP auth rate cap (C7) or poison the request log.
// Trusting loopback (in-process tests + same-host) and 172.16.0.0/12 (the Docker bridge range where
// Coolify's Traefik reaches this container) means forwarded headers from any other peer are ignored.
// Uses the non-obsolete KnownIPNetworks (System.Net.IPNetwork); KnownNetworks is obsolete (ASPDEPR005).
builder.Services.Configure<ForwardedHeadersOptions>(options =>
{
    options.ForwardedHeaders = ForwardedHeaders.XForwardedFor | ForwardedHeaders.XForwardedProto;
    options.ForwardLimit = 1;
    options.KnownIPNetworks.Clear();
    options.KnownProxies.Clear();
    options.KnownIPNetworks.Add(new System.Net.IPNetwork(System.Net.IPAddress.Parse("127.0.0.0"), 8));
    options.KnownIPNetworks.Add(new System.Net.IPNetwork(System.Net.IPAddress.IPv6Loopback, 128));
    options.KnownIPNetworks.Add(new System.Net.IPNetwork(System.Net.IPAddress.Parse("172.16.0.0"), 12));
});

// HSTS: clear the default localhost exclusion so integration tests (which hit https://localhost
// via the in-process test server) can assert the header is present in Production.
builder.Services.AddHsts(options => options.ExcludedHosts.Clear());

var app = builder.Build();

// Two-role DB bootstrap (C5): when ConnectionStrings:Migrator is set (prod), migrate the schema
// as the owner role then provision the locked-down runtime app_user, synchronously BEFORE the
// host serves any traffic. No-op when unset (local dev / tests). Not a hosted service: a
// BackgroundService would race Kestrel accepting requests before the schema/role exist.
await Tmap.Api.Infrastructure.Persistence.DbBootstrapper.RunAsync(builder.Configuration);

// FIRST middleware: rewrite scheme + RemoteIpAddress from the trusted proxy's forwarded headers
// before HSTS (needs the https scheme) and the rate limiter (keys on RemoteIpAddress) observe them.
app.UseForwardedHeaders();

if (!app.Environment.IsDevelopment())
{
    // Traefik terminates TLS and does the http->https redirect at the edge. An in-container
    // UseHttpsRedirection would redirect to a port the container does not expose (redirect loop).
    // Keep HSTS only; with UseForwardedHeaders above, the app knows the scheme is https.
    app.UseHsts();
}

// OpenAPI document is dev/test tooling — never expose the API surface publicly in Production.
if (!app.Environment.IsProduction())
{
    app.MapOpenApi();
}

app.UseSerilogRequestLogging();
app.UseExceptionHandler();
app.UseStatusCodePages();

// Body-peek middleware: stash email from JSON body in HttpContext.Items["rl_email"]
// so the rate-limit partitioner (which runs before model binding) can key by email.
app.Use(async (ctx, next) =>
{
    var path = ctx.Request.Path.Value ?? "";
    if (HttpMethods.IsPost(ctx.Request.Method) &&
        (path.EndsWith("/auth/login") || path.EndsWith("/auth/register") || path.EndsWith("/auth/refresh")))
    {
        ctx.Request.EnableBuffering();
        try
        {
            using var doc = await System.Text.Json.JsonDocument.ParseAsync(ctx.Request.Body, default, ctx.RequestAborted);
            if (doc.RootElement.TryGetProperty("email", out var e) && e.ValueKind == System.Text.Json.JsonValueKind.String)
            {
                ctx.Items["rl_email"] = e.GetString()!.ToLowerInvariant();
            }
        }
        catch (System.Text.Json.JsonException) { /* not JSON / no email: fall back to IP-only key */ }
        ctx.Request.Body.Position = 0; // rewind for model binding
    }
    await next();
});

app.UseRateLimiter();

// CORS runs after routing and before authentication so the OPTIONS preflight is handled
// for every endpoint without per-endpoint RequireCors.
app.UseCors(Tmap.Api.Common.Cors.CorsServiceCollectionExtensions.PolicyName);

app.UseAuthentication();
app.UseAuthorization();

// Health is an ops endpoint — not under /api/v1 and excluded from the OpenAPI document.
app.MapHealthEndpoints();

// All data/auth slices hang off a single top-level version group.
var apiV1 = app.MapGroup("/api/v1");

apiV1.MapAuthEndpoints();
apiV1.MapTasks();
apiV1.MapSubtasks();
apiV1.MapProjects();
apiV1.MapNoteGroups();
apiV1.MapNotes();
apiV1.MapFocusSessionsEndpoints();
apiV1.MapDailyPlansEndpoints();
apiV1.MapSettingsEndpoints();
apiV1.MapReportsEndpoints();
apiV1.MapRecurrenceEndpoints();
apiV1.MapSyncEndpoints();

app.Run();

// Required so WebApplicationFactory<Program> in the test project can reference the entry point.
public partial class Program { }
