using Tmap.Api.Infrastructure.Entities;

namespace Tmap.Api.Features.Recurrence;

/// <summary>Immutable rule snapshot used by the pure occurrence generator.</summary>
public sealed record OccurrenceRuleData(
    RecurrenceFrequency Frequency,
    int Interval,
    IReadOnlyList<int> DaysOfWeek,
    RecurrenceEndType EndType,
    int? EndCount,
    DateOnly? EndDate);

/// <summary>
/// Pure, side-effect-free occurrence date generator mirroring the legacy
/// electron/recurrenceUtils.ts algorithm. Caller supplies the template's
/// original start date and the [rangeStart, rangeEnd] window (both inclusive).
/// existingDates/exceptionDates are removed from the OUTPUT but still consume
/// the EndType.Count budget.
/// </summary>
public static class OccurrenceGenerator
{
    private const int MaxWeeks = 520; // ~10 years safety cap

    public static List<DateOnly> Generate(
        OccurrenceRuleData rule,
        DateOnly templateStart,
        DateOnly rangeStart,
        DateOnly rangeEnd,
        ISet<DateOnly> existingDates,
        ISet<DateOnly> exceptionDates)
    {
        var results = new List<DateOnly>();
        var interval = rule.Interval < 1 ? 1 : rule.Interval;
        var maxCount = rule.EndType == RecurrenceEndType.Count && rule.EndCount is { } c ? c : int.MaxValue;
        var endDate = rule.EndType == RecurrenceEndType.Date ? rule.EndDate : null;
        var totalCount = 0;

        void Emit(DateOnly date)
        {
            totalCount++;
            if (date < rangeStart)
            {
                return;
            }

            if (existingDates.Contains(date) || exceptionDates.Contains(date))
            {
                return;
            }

            results.Add(date);
        }

        bool Stop(DateOnly date) =>
            date > rangeEnd || (endDate is { } ed && date > ed) || totalCount >= maxCount;

        if (rule.Frequency == RecurrenceFrequency.Daily)
        {
            var current = templateStart;
            while (!Stop(current))
            {
                Emit(current);
                current = current.AddDays(interval);
            }
        }
        else // Weekly
        {
            var days = new HashSet<int>(rule.DaysOfWeek);
            if (days.Count == 0)
            {
                return results;
            }

            // Sunday-anchored week containing templateStart. DayOfWeek.Sunday == 0.
            var weekStart = templateStart.AddDays(-(int)templateStart.DayOfWeek);

            for (var week = 0; week <= MaxWeeks; week++)
            {
                for (var dow = 0; dow < 7; dow++)
                {
                    if (!days.Contains(dow))
                    {
                        continue;
                    }

                    var date = weekStart.AddDays(dow);
                    if (date < templateStart)
                    {
                        continue;
                    }

                    if (Stop(date))
                    {
                        return results;
                    }

                    Emit(date);
                }
                weekStart = weekStart.AddDays(7 * interval);
                // Early exit once we've walked entirely past the window/end.
                if (weekStart > rangeEnd || (endDate is { } ed && weekStart > ed))
                {
                    break;
                }
            }
        }

        return results;
    }
}
