using FluentValidation;

namespace Tmap.Api.Features.Tasks;

public sealed class CreateTaskValidator : AbstractValidator<CreateTaskRequest>
{
    public CreateTaskValidator()
    {
        RuleFor(x => x.Title).NotEmpty().MaximumLength(500);
        RuleFor(x => x.Notes).MaximumLength(20_000).When(x => x.Notes is not null);
        RuleFor(x => x.DurationMinutes).GreaterThanOrEqualTo(0).When(x => x.DurationMinutes.HasValue);
        RuleFor(x => x.Priority).InclusiveBetween(1, 4).When(x => x.Priority.HasValue);
        RuleFor(x => x.ReminderMinutes).GreaterThanOrEqualTo(0).When(x => x.ReminderMinutes.HasValue);
        RuleFor(x => x.ScheduledEnd)
            .GreaterThanOrEqualTo(x => x.ScheduledStart!.Value)
            .When(x => x.ScheduledStart.HasValue && x.ScheduledEnd.HasValue)
            .WithMessage("ScheduledEnd must be on or after ScheduledStart.");
        RuleFor(x => x.Rank).NotEmpty().MaximumLength(255).When(x => x.Rank is not null);
    }
}

public sealed class UpdateTaskValidator : AbstractValidator<UpdateTaskRequest>
{
    public UpdateTaskValidator()
    {
        RuleFor(x => x.Title).NotEmpty().MaximumLength(500).When(x => x.Title is not null);
        RuleFor(x => x.Notes).MaximumLength(20_000).When(x => x.Notes is not null);
        RuleFor(x => x.DurationMinutes).GreaterThanOrEqualTo(0).When(x => x.DurationMinutes.HasValue);
        RuleFor(x => x.ActualTimeMinutes).GreaterThanOrEqualTo(0).When(x => x.ActualTimeMinutes.HasValue);
        // Optional<int?>: validate only a present, non-null value (explicit null clears the field).
        RuleFor(x => x.Priority.Value!.Value)
            .InclusiveBetween(1, 4)
            .When(x => x.Priority is { HasValue: true, Value: not null });
        RuleFor(x => x.ReminderMinutes.Value!.Value)
            .GreaterThanOrEqualTo(0)
            .When(x => x.ReminderMinutes is { HasValue: true, Value: not null });
        // Range check only when BOTH bounds are present and non-null.
        RuleFor(x => x.ScheduledEnd.Value!.Value)
            .GreaterThanOrEqualTo(x => x.ScheduledStart.Value!.Value)
            .When(x => x.ScheduledStart is { HasValue: true, Value: not null }
                    && x.ScheduledEnd is { HasValue: true, Value: not null })
            .WithMessage("ScheduledEnd must be on or after ScheduledStart.");
        RuleFor(x => x.Rank).NotEmpty().MaximumLength(255).When(x => x.Rank is not null);
    }
}

public sealed class ReorderTasksValidator : AbstractValidator<IReadOnlyList<ReorderItem>>
{
    public ReorderTasksValidator()
    {
        RuleFor(x => x).NotEmpty().WithMessage("Reorder payload must contain at least one item.");
        RuleForEach(x => x).ChildRules(item =>
        {
            item.RuleFor(i => i.Id).NotEmpty();
            item.RuleFor(i => i.Rank).NotEmpty().MaximumLength(255);
        });
    }
}
