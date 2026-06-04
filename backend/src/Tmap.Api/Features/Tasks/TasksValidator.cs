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
        RuleFor(x => x.Priority).InclusiveBetween(1, 4).When(x => x.Priority.HasValue);
        RuleFor(x => x.ReminderMinutes).GreaterThanOrEqualTo(0).When(x => x.ReminderMinutes.HasValue);
        RuleFor(x => x.ScheduledEnd)
            .GreaterThanOrEqualTo(x => x.ScheduledStart!.Value)
            .When(x => x.ScheduledStart.HasValue && x.ScheduledEnd.HasValue)
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
