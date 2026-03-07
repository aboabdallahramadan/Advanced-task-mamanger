"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.generateOccurrenceDates = generateOccurrenceDates;
function addDaysToDate(dateStr, days) {
    const [y, m, d] = dateStr.split('-').map(Number);
    const date = new Date(Date.UTC(y, m - 1, d + days));
    return date.toISOString().split('T')[0];
}
function getDayOfWeek(dateStr) {
    const [y, m, d] = dateStr.split('-').map(Number);
    return new Date(Date.UTC(y, m - 1, d)).getUTCDay();
}
function dateToNum(dateStr) {
    const [y, m, d] = dateStr.split('-').map(Number);
    return Date.UTC(y, m - 1, d);
}
function generateOccurrenceDates(rule, startDate, rangeStart, rangeEnd, existingDates, exceptionDates) {
    const results = [];
    const rangeStartNum = dateToNum(rangeStart);
    const rangeEndNum = dateToNum(rangeEnd);
    const endDateNum = rule.endDate ? dateToNum(rule.endDate) : Infinity;
    let totalCount = 0;
    const maxCount = rule.endType === 'count' && rule.endCount ? rule.endCount : Infinity;
    if (rule.frequency === 'daily') {
        let current = startDate;
        while (dateToNum(current) <= rangeEndNum && dateToNum(current) <= endDateNum) {
            if (totalCount >= maxCount)
                break;
            totalCount++;
            if (dateToNum(current) >= rangeStartNum) {
                if (!existingDates.has(current) && !exceptionDates.has(current)) {
                    results.push(current);
                }
            }
            current = addDaysToDate(current, rule.interval);
        }
    }
    else if (rule.frequency === 'weekly') {
        const daysSet = new Set(rule.daysOfWeek);
        if (daysSet.size === 0)
            return results;
        // Find the start of the week containing startDate (Sunday)
        const startDow = getDayOfWeek(startDate);
        let weekStart = addDaysToDate(startDate, -startDow);
        let weekIndex = 0;
        while (true) {
            // For each day in this week
            for (let dow = 0; dow < 7; dow++) {
                if (!daysSet.has(dow))
                    continue;
                const date = addDaysToDate(weekStart, dow);
                if (dateToNum(date) < dateToNum(startDate))
                    continue;
                if (dateToNum(date) > rangeEndNum)
                    return results;
                if (dateToNum(date) > endDateNum)
                    return results;
                if (totalCount >= maxCount)
                    return results;
                totalCount++;
                if (dateToNum(date) >= rangeStartNum) {
                    if (!existingDates.has(date) && !exceptionDates.has(date)) {
                        results.push(date);
                    }
                }
            }
            // Advance by interval weeks
            weekStart = addDaysToDate(weekStart, 7 * rule.interval);
            weekIndex++;
            // Safety cap
            if (weekIndex > 520)
                break; // ~10 years
        }
    }
    return results;
}
