using FluentValidation;

namespace Tmap.Api.Features.Subtasks;

public sealed class CreateSubtaskValidator : AbstractValidator<CreateSubtaskRequest>
{
    public CreateSubtaskValidator()
    {
        RuleFor(x => x.Title).NotEmpty().MaximumLength(500);
    }
}

public sealed class UpdateSubtaskValidator : AbstractValidator<UpdateSubtaskRequest>
{
    public UpdateSubtaskValidator()
    {
        RuleFor(x => x.Title).NotEmpty().MaximumLength(500).When(x => x.Title is not null);
        RuleFor(x => x.SortOrder).GreaterThanOrEqualTo(0).When(x => x.SortOrder.HasValue);
    }
}
