using Microsoft.AspNetCore.Identity;
using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Infrastructure.Identity;

public static class IdentityConfig
{
    public static IServiceCollection AddTmapIdentity(this IServiceCollection services)
    {
        // Data Protection backs the default token providers (email confirm / password reset),
        // which UserManager activates via AddDefaultTokenProviders(). AddIdentityCore does not
        // register it, so without this the UserManager<ApplicationUser> ctor cannot be resolved.
        services.AddDataProtection();

        services
            .AddIdentityCore<ApplicationUser>(o =>
            {
                // Password policy (SP1 §3.1): length over composition.
                o.Password.RequiredLength = 10;
                o.Password.RequireDigit = false;
                o.Password.RequireLowercase = false;
                o.Password.RequireUppercase = false;
                o.Password.RequireNonAlphanumeric = false;
                o.Password.RequiredUniqueChars = 1;

                o.User.RequireUniqueEmail = true;

                // Brute-force lockout (SP1 §3.2).
                o.Lockout.AllowedForNewUsers = true;
                o.Lockout.MaxFailedAccessAttempts = 5;
                o.Lockout.DefaultLockoutTimeSpan = TimeSpan.FromMinutes(15);
            })
            .AddRoles<IdentityRole<Guid>>()
            .AddEntityFrameworkStores<AppDbContext>()
            .AddDefaultTokenProviders();

        return services;
    }
}
