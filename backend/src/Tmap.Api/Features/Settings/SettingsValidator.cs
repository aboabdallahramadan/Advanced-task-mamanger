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
    }
}
