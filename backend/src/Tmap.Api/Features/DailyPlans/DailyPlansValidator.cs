using FluentValidation;

namespace Tmap.Api.Features.DailyPlans;

public sealed class UpsertDailyPlanValidator : AbstractValidator<UpsertDailyPlanRequest>
{
    public UpsertDailyPlanValidator()
    {
        RuleFor(x => x.PlannedTaskIds).NotNull();
        RuleFor(x => x.PlannedMinutes).GreaterThanOrEqualTo(0);
    }
}
