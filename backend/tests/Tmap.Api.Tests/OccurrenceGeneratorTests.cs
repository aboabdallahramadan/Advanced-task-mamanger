using FluentAssertions;
using Tmap.Api.Features.Recurrence;
using Tmap.Api.Infrastructure.Entities;
using Xunit;

namespace Tmap.Api.Tests;

public class OccurrenceGeneratorTests
{
    private static OccurrenceRuleData Weekly(int interval, IEnumerable<int> days,
        RecurrenceEndType endType = RecurrenceEndType.Never, int? endCount = null, DateOnly? endDate = null) =>
        new(RecurrenceFrequency.Weekly, interval, days.ToList(), endType, endCount, endDate);

    private static OccurrenceRuleData Daily(int interval,
        RecurrenceEndType endType = RecurrenceEndType.Never, int? endCount = null, DateOnly? endDate = null) =>
        new(RecurrenceFrequency.Daily, interval, new List<int>(), endType, endCount, endDate);

    private static DateOnly D(string s) => DateOnly.Parse(s);

    [Fact]
    public void Weekly_MonWedFri_GeneratesExpectedDatesInRange()
    {
        // Template starts Mon 2026-06-01 (a Monday). Mon=1, Wed=3, Fri=5.
        var rule = Weekly(1, new[] { 1, 3, 5 });
        var result = OccurrenceGenerator.Generate(
            rule, templateStart: D("2026-06-01"),
            rangeStart: D("2026-06-01"), rangeEnd: D("2026-06-07"),
            existingDates: new HashSet<DateOnly>(), exceptionDates: new HashSet<DateOnly>());

        result.Should().Equal(D("2026-06-01"), D("2026-06-03"), D("2026-06-05"));
    }

    [Fact]
    public void Weekly_EveryTwoWeeks_SkipsTheOffWeek()
    {
        var rule = Weekly(2, new[] { 1 }); // every other Monday
        var result = OccurrenceGenerator.Generate(
            rule, D("2026-06-01"), D("2026-06-01"), D("2026-06-30"),
            new HashSet<DateOnly>(), new HashSet<DateOnly>());

        result.Should().Equal(D("2026-06-01"), D("2026-06-15"), D("2026-06-29"));
    }

    [Fact]
    public void Daily_EveryThreeDays_RespectsInterval()
    {
        var rule = Daily(3);
        var result = OccurrenceGenerator.Generate(
            rule, D("2026-06-01"), D("2026-06-01"), D("2026-06-10"),
            new HashSet<DateOnly>(), new HashSet<DateOnly>());

        result.Should().Equal(D("2026-06-01"), D("2026-06-04"), D("2026-06-07"), D("2026-06-10"));
    }

    [Fact]
    public void Count_CapsTotalOccurrencesFromTemplateStart()
    {
        // 4 total daily occurrences; range starts after the first one.
        var rule = Daily(1, RecurrenceEndType.Count, endCount: 4);
        var result = OccurrenceGenerator.Generate(
            rule, templateStart: D("2026-06-01"),
            rangeStart: D("2026-06-02"), rangeEnd: D("2026-06-30"),
            new HashSet<DateOnly>(), new HashSet<DateOnly>());

        // Occurrences are 06-01..06-04; only 06-02..06-04 are in range.
        result.Should().Equal(D("2026-06-02"), D("2026-06-03"), D("2026-06-04"));
    }

    [Fact]
    public void EndDate_IsInclusive()
    {
        var rule = Daily(1, RecurrenceEndType.Date, endDate: D("2026-06-03"));
        var result = OccurrenceGenerator.Generate(
            rule, D("2026-06-01"), D("2026-06-01"), D("2026-06-30"),
            new HashSet<DateOnly>(), new HashSet<DateOnly>());

        result.Should().Equal(D("2026-06-01"), D("2026-06-02"), D("2026-06-03"));
    }

    [Fact]
    public void ExistingAndExceptionDates_AreExcludedButStillCountTowardCount()
    {
        var rule = Daily(1, RecurrenceEndType.Count, endCount: 4);
        var result = OccurrenceGenerator.Generate(
            rule, D("2026-06-01"), D("2026-06-01"), D("2026-06-30"),
            existingDates: new HashSet<DateOnly> { D("2026-06-02") },
            exceptionDates: new HashSet<DateOnly> { D("2026-06-03") });

        // 4 occurrences 06-01..06-04; 06-02 already exists, 06-03 is an exception.
        result.Should().Equal(D("2026-06-01"), D("2026-06-04"));
    }

    [Fact]
    public void Weekly_WithNoDaysOfWeek_GeneratesNothing()
    {
        var rule = Weekly(1, Array.Empty<int>());
        var result = OccurrenceGenerator.Generate(
            rule, D("2026-06-01"), D("2026-06-01"), D("2026-06-30"),
            new HashSet<DateOnly>(), new HashSet<DateOnly>());

        result.Should().BeEmpty();
    }
}
