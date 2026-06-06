using FluentValidation;

namespace Tmap.Api.Features.FocusSessions;

public sealed class CreateFocusSessionValidator : AbstractValidator<CreateFocusSessionRequest>
{
    public CreateFocusSessionValidator()
    {
        RuleFor(x => x.Project).NotNull().MaximumLength(200);
        RuleFor(x => x.Minutes).GreaterThan(0);
        RuleFor(x => x.EndedAt).GreaterThanOrEqualTo(x => x.StartedAt);
    }
}
