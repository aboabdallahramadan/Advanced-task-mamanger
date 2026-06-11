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
using Tmap.Api.Features.Tasks;
using Tmap.Api.Infrastructure.Identity;
using Tmap.Api.Infrastructure.Jwt;
using Tmap.Api.Infrastructure.Persistence;

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

// HSTS: clear the default localhost exclusion so integration tests (which hit https://localhost
// via the in-process test server) can assert the header is present in Production.
builder.Services.AddHsts(options => options.ExcludedHosts.Clear());

var app = builder.Build();

if (!app.Environment.IsDevelopment())
{
    app.UseHsts();
    app.UseHttpsRedirection();
}

app.MapOpenApi();

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

app.Run();

// Required so WebApplicationFactory<Program> in the test project can reference the entry point.
public partial class Program { }
