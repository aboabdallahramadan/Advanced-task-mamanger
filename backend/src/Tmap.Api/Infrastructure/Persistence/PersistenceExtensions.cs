using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;

namespace Tmap.Api.Infrastructure.Persistence;

public static class PersistenceExtensions
{
    /// <summary>
    /// Registers <see cref="AppDbContext"/> with a fixed connection string.
    /// Used directly by tests that supply a Testcontainers connection string.
    /// </summary>
    public static IServiceCollection AddPersistence(this IServiceCollection services, string connectionString)
    {
        services.AddDbContext<AppDbContext>(options =>
        {
            options.UseNpgsql(connectionString);
            options.UseSnakeCaseNamingConvention();
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
        services.AddDbContext<AppDbContext>((sp, options) =>
        {
            var configuration = sp.GetRequiredService<IConfiguration>();
            var connectionString = configuration.GetConnectionString("Postgres") ?? string.Empty;
            options.UseNpgsql(connectionString);
            options.UseSnakeCaseNamingConvention();
        });

        return services;
    }
}
