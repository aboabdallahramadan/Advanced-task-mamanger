using FluentValidation;

namespace Tmap.Api.Features.Notes;

public sealed class CreateNoteValidator : AbstractValidator<CreateNoteRequest>
{
    public CreateNoteValidator()
    {
        // Notes may be UNTITLED: the client creates them empty (title "") and the editor
        // fills in a title/body later — so Title must be present but may be empty.
        RuleFor(x => x.Title).NotNull().MaximumLength(500);
        RuleFor(x => x.Content).MaximumLength(100_000).When(x => x.Content is not null);
        RuleFor(x => x.Rank).NotEmpty().MaximumLength(255).When(x => x.Rank is not null);
    }
}

public sealed class UpdateNoteValidator : AbstractValidator<UpdateNoteRequest>
{
    public UpdateNoteValidator()
    {
        // Title null = leave unchanged; "" = clear back to untitled (both valid).
        RuleFor(x => x.Title).MaximumLength(500).When(x => x.Title is not null);
        RuleFor(x => x.Content).MaximumLength(100_000).When(x => x.Content is not null);
        RuleFor(x => x.Rank).NotEmpty().MaximumLength(255).When(x => x.Rank is not null);
    }
}

public sealed class ReorderItemsValidator : AbstractValidator<IReadOnlyList<ReorderItem>>
{
    public ReorderItemsValidator()
    {
        RuleFor(x => x).NotEmpty();
        RuleForEach(x => x).ChildRules(item =>
        {
            item.RuleFor(i => i.Id).NotEmpty();
            item.RuleFor(i => i.Rank).NotEmpty().MaximumLength(255);
        });
    }
}
