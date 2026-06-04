using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Tmap.Api.Common;

namespace Tmap.Api.Infrastructure.Persistence;

public static class PersistenceExtensions
{
    /// <summary>
    /// Registers <see cref="AppDbContext"/> with a fixed connection string.
    /// Used directly by tests that supply a Testcontainers connection string.
    /// </summary>
    public static IServiceCollection AddPersistence(this IServiceCollection services, string connectionString)
    {
        AddOwnershipInterceptors(services);

        services.AddDbContext<AppDbContext>((sp, options) =>
        {
            options.UseNpgsql(connectionString);
            options.UseSnakeCaseNamingConvention();
            options.AddInterceptors(
                sp.GetRequiredService<UserIdConnectionInterceptor>(),
                sp.GetRequiredService<OwnershipSaveChangesInterceptor>());
        });

        return services;
    }

    /// <summary>
    /// Registers <see cref="AppDbContext"/> reading the connection string lazily from
    /// <see cref="IConfiguration"/> at resolve time so test harnesses can override it
    /// via <c>WebApplicationFactory.ConfigureAppConfiguration</c>.
    /// </summary>
    public static IServiceCollection AddPersistence(this IServiceCollection services)
    {
        AddOwnershipInterceptors(services);

        services.AddDbContext<AppDbContext>((sp, options) =>
        {
            var configuration = sp.GetRequiredService<IConfiguration>();
            var connectionString = configuration.GetConnectionString("Postgres") ?? string.Empty;
            options.UseNpgsql(connectionString);
            options.UseSnakeCaseNamingConvention();
            options.AddInterceptors(
                sp.GetRequiredService<UserIdConnectionInterceptor>(),
                sp.GetRequiredService<OwnershipSaveChangesInterceptor>());
        });

        return services;
    }

    // Interceptors depend on the scoped ICurrentUser, so register them scoped and
    // resolve them when configuring the DbContext (which is itself scoped).
    private static void AddOwnershipInterceptors(IServiceCollection services)
    {
        services.AddScoped<UserIdConnectionInterceptor>();
        services.AddScoped<OwnershipSaveChangesInterceptor>();
    }
}
