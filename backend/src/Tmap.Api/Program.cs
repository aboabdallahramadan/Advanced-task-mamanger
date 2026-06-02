using Microsoft.EntityFrameworkCore;
using Serilog;
using Tmap.Api.Common;
using Tmap.Api.Features.Health;
using Tmap.Api.Infrastructure;

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

// AppDbContext is Scoped. Connection string comes from configuration / user-secrets in dev
// and is overridden by the test harness (P0-8). Placeholder context defined in P0-4; P1
// adds the real entities, RLS, triggers, and query filters.
builder.Services.AddDbContext<AppDbContext>(
    (sp, options) =>
    {
        var connectionString = builder.Configuration.GetConnectionString("Postgres");
        options.UseNpgsql(connectionString).UseSnakeCaseNamingConvention();
    }
);

var app = builder.Build();

app.UseSerilogRequestLogging();
app.UseExceptionHandler();
app.UseStatusCodePages();

app.MapHealthEndpoints();

app.Run();

// Required so WebApplicationFactory<Program> in the test project can reference the entry point.
public partial class Program { }
