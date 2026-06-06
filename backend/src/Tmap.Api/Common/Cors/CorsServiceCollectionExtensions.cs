namespace Tmap.Api.Common.Cors;

public static class CorsServiceCollectionExtensions
{
    /// <summary>The single CORS policy name applied app-wide.</summary>
    public const string PolicyName = "TmapCors";

    public static IServiceCollection AddTmapCors(
        this IServiceCollection services,
        IConfiguration configuration)
    {
        services
            .AddOptions<CorsOptions>()
            .Bind(configuration.GetSection(CorsOptions.SectionName))
            .ValidateOnStart();

        services.AddCors(options =>
        {
            options.AddPolicy(PolicyName, policy =>
            {
                var corsOptions = configuration
                    .GetSection(CorsOptions.SectionName)
                    .Get<CorsOptions>() ?? new CorsOptions();

                if (corsOptions.AllowedOrigins.Length > 0)
                {
                    policy
                        .WithOrigins(corsOptions.AllowedOrigins)
                        .AllowAnyHeader()
                        .AllowAnyMethod()
                        .AllowCredentials();
                }
                // No named origins => no cross-origin browser access is granted.
                // Desktop clients send no Origin header and are never gated by CORS.
            });
        });

        return services;
    }
}
