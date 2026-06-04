using Microsoft.AspNetCore.Identity;

namespace Tmap.Api.Infrastructure.Entities;

public class ApplicationUser : IdentityUser<Guid>
{
    public string TimeZoneId { get; set; } = "UTC"; // IANA tz, for report bucketing
}
