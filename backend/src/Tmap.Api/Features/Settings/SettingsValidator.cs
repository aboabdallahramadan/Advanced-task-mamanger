using FluentValidation;

namespace Tmap.Api.Features.Settings;

public sealed class SaveSettingsValidator : AbstractValidator<SaveSettingsRequest>
{
    public SaveSettingsValidator()
    {
        RuleFor(x => x.Settings).NotNull();
        // Non-whitelisted keys are silently ignored by the handler, not rejected
        // (the legacy client may send local-only prefs); we only bound value size.
        RuleForEach(x => x.Settings)
            .Must(kv => kv.Value is null || kv.Value.Length <= 4000)
            .WithMessage("Setting value exceeds maximum length.");

        // When TimeZoneId is provided it must be a valid IANA / Windows tz recognised by the runtime.
        RuleFor(x => x.TimeZoneId)
            .Must(tz => tz is null || IsValidTimeZone(tz))
            .WithName("timeZoneId")
            .WithMessage("'{PropertyName}' is not a recognised time-zone identifier.");
    }

    private static bool IsValidTimeZone(string id)
    {
        try
        {
            TimeZoneInfo.FindSystemTimeZoneById(id);
            return true;
        }
        catch (TimeZoneNotFoundException)
        {
            return false;
        }
    }
}
