using FluentValidation;

namespace Tmap.Api.Features.NoteGroups;

public sealed class CreateNoteGroupValidator : AbstractValidator<CreateNoteGroupRequest>
{
    public CreateNoteGroupValidator()
    {
        RuleFor(x => x.Name).NotEmpty().MaximumLength(200);
        RuleFor(x => x.Emoji).MaximumLength(16);
        RuleFor(x => x.Rank).NotEmpty().MaximumLength(255).When(x => x.Rank is not null);
    }
}

public sealed class UpdateNoteGroupValidator : AbstractValidator<UpdateNoteGroupRequest>
{
    public UpdateNoteGroupValidator()
    {
        RuleFor(x => x.Name).NotEmpty().MaximumLength(200).When(x => x.Name is not null);
        RuleFor(x => x.Emoji).MaximumLength(16).When(x => x.Emoji is not null);
        RuleFor(x => x.Rank).NotEmpty().MaximumLength(255).When(x => x.Rank is not null);
    }
}
