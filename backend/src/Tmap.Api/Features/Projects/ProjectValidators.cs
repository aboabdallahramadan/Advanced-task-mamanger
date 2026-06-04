using FluentValidation;

namespace Tmap.Api.Features.Projects;

public sealed class CreateProjectValidator : AbstractValidator<CreateProjectRequest>
{
    public CreateProjectValidator()
    {
        RuleFor(x => x.Name).NotEmpty().MaximumLength(200);
        RuleFor(x => x.Color).NotEmpty().MaximumLength(32);
        RuleFor(x => x.Emoji).MaximumLength(16);
        RuleFor(x => x.Rank).NotEmpty().MaximumLength(255).When(x => x.Rank is not null);
    }
}

public sealed class UpdateProjectValidator : AbstractValidator<UpdateProjectRequest>
{
    public UpdateProjectValidator()
    {
        RuleFor(x => x.Name).NotEmpty().MaximumLength(200);
        RuleFor(x => x.Color).NotEmpty().MaximumLength(32);
        RuleFor(x => x.Emoji).MaximumLength(16);
        RuleFor(x => x.Rank).NotEmpty().MaximumLength(255);
    }
}

public sealed class ReorderProjectsValidator : AbstractValidator<IReadOnlyList<ReorderItem>>
{
    public ReorderProjectsValidator()
    {
        RuleFor(x => x).NotEmpty();
        RuleForEach(x => x).ChildRules(item =>
        {
            item.RuleFor(i => i.Id).NotEmpty();
            item.RuleFor(i => i.Rank).NotEmpty().MaximumLength(255);
        });
    }
}
