using FluentValidation;
using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Features.Recurrence;

public sealed class CreateRecurringTaskValidator : AbstractValidator<CreateRecurringTaskRequest>
{
    public CreateRecurringTaskValidator()
    {
        RuleFor(x => x.Task).NotNull();
        RuleFor(x => x.Task.Title).NotEmpty().MaximumLength(500);
        RuleFor(x => x.Task.DurationMinutes).GreaterThanOrEqualTo(0);
        RuleFor(x => x.Rule).NotNull().SetValidator(new RuleInputValidator());
    }
}

public sealed class RuleInputValidator : AbstractValidator<RecurrenceRuleInput>
{
    public RuleInputValidator()
    {
        RuleFor(r => r.Interval).GreaterThanOrEqualTo(1);
        RuleFor(r => r.DaysOfWeek).NotNull();
        RuleForEach(r => r.DaysOfWeek).InclusiveBetween(0, 6);
        When(r => r.Frequency == RecurrenceFrequency.Weekly, () =>
            RuleFor(r => r.DaysOfWeek).NotEmpty()
                .WithMessage("Weekly recurrence requires at least one day of week."));
        When(r => r.EndType == RecurrenceEndType.Count, () =>
            RuleFor(r => r.EndCount).NotNull().GreaterThan(0));
        When(r => r.EndType == RecurrenceEndType.Date, () =>
            RuleFor(r => r.EndDate).NotNull());
    }
}

public sealed class UpdateRuleValidator : AbstractValidator<UpdateRuleRequest>
{
    public UpdateRuleValidator()
    {
        RuleFor(r => r.Interval).GreaterThanOrEqualTo(1);
        RuleFor(r => r.DaysOfWeek).NotNull();
        RuleForEach(r => r.DaysOfWeek).InclusiveBetween(0, 6);
        When(r => r.Frequency == RecurrenceFrequency.Weekly, () =>
            RuleFor(r => r.DaysOfWeek).NotEmpty());
        When(r => r.EndType == RecurrenceEndType.Count, () =>
            RuleFor(r => r.EndCount).NotNull().GreaterThan(0));
        When(r => r.EndType == RecurrenceEndType.Date, () =>
            RuleFor(r => r.EndDate).NotNull());
    }
}

public sealed class UpdateSeriesValidator : AbstractValidator<UpdateSeriesRequest>
{
    public UpdateSeriesValidator()
    {
        When(x => x.Title is not null, () => RuleFor(x => x.Title!).NotEmpty().MaximumLength(500));
        When(x => x.DurationMinutes is not null, () => RuleFor(x => x.DurationMinutes!.Value).GreaterThanOrEqualTo(0));
    }
}

public sealed class DeleteSeriesFutureValidator : AbstractValidator<DeleteSeriesFutureRequest>
{
    public DeleteSeriesFutureValidator()
    {
        RuleFor(x => x.FromDate).NotEmpty();
    }
}
