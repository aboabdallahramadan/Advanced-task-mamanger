using Serilog;
using Tmap.Api.Common;
using Tmap.Api.Features.Auth;
using Tmap.Api.Features.Health;
using Tmap.Api.Infrastructure.Identity;
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

var app = builder.Build();

app.UseSerilogRequestLogging();
app.UseExceptionHandler();
app.UseStatusCodePages();

app.MapHealthEndpoints();
app.MapAuthEndpoints();

app.Run();

// Required so WebApplicationFactory<Program> in the test project can reference the entry point.
public partial class Program { }
