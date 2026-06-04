using FluentValidation;
using Serilog;
using Tmap.Api.Common;
using Tmap.Api.Features.Auth;
using Tmap.Api.Features.Health;
using Tmap.Api.Infrastructure.Identity;
using Tmap.Api.Infrastructure.Jwt;
using Tmap.Api.Infrastructure.Persistence;

var builder = WebApplication.CreateBuilder(args);

builder.Host.UseSerilog(
    (context, services, configuration) =>
        configuration.ReadFrom.Configuration(context.Configuration).ReadFrom.Services(services)
);

builder.Services.AddProblemDetails();

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

// JWT: options, service, and bearer validation.
builder.Services.Configure<JwtOptions>(builder.Configuration.GetSection(JwtOptions.SectionName));
builder.Services.AddScoped<IJwtService, JwtService>();

// FluentValidation: auto-register all validators in the assembly.
builder.Services.AddValidatorsFromAssemblyContaining<RegisterRequestValidator>();

// Rate limiting: sliding-window policy keyed by IP + email for auth endpoints.
builder.Services.AddTmapRateLimiting();

var app = builder.Build();

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
                ctx.Items["rl_email"] = e.GetString()!.ToLowerInvariant();
        }
        catch (System.Text.Json.JsonException) { /* not JSON / no email: fall back to IP-only key */ }
        ctx.Request.Body.Position = 0; // rewind for model binding
    }
    await next();
});

app.UseRateLimiter();

app.MapHealthEndpoints();
app.MapAuthEndpoints();

app.Run();

// Required so WebApplicationFactory<Program> in the test project can reference the entry point.
public partial class Program { }
