package net.qmindtech.tmap.data.recurrence

import net.qmindtech.tmap.data.remote.dto.RecurrenceRuleInput
import net.qmindtech.tmap.data.remote.dto.UpdateRuleRequest
import java.time.format.DateTimeFormatter

/** Pure draft -> wire-DTO normalization (mirrors desktop TaskDetailDialog save + backend coercions). */
object RecurrenceMapper {

    private val DATE = DateTimeFormatter.ISO_LOCAL_DATE

    fun ruleInput(draft: RecurrenceDraft, id: String): RecurrenceRuleInput = RecurrenceRuleInput(
        frequency = draft.frequency.name,
        interval = draft.interval.coerceAtLeast(1),
        daysOfWeek = if (draft.frequency == RecurrenceFrequency.Weekly) draft.daysOfWeek else emptyList(),
        endType = draft.endType.name,
        endCount = if (draft.endType == RecurrenceEndType.Count) draft.endCount else null,
        endDate = if (draft.endType == RecurrenceEndType.Date) draft.endDate?.format(DATE) else null,
        id = id,
    )

    fun updateRule(draft: RecurrenceDraft): UpdateRuleRequest = UpdateRuleRequest(
        frequency = draft.frequency.name,
        interval = draft.interval.coerceAtLeast(1),
        daysOfWeek = if (draft.frequency == RecurrenceFrequency.Weekly) draft.daysOfWeek else emptyList(),
        endType = draft.endType.name,
        endCount = if (draft.endType == RecurrenceEndType.Count) draft.endCount else null,
        endDate = if (draft.endType == RecurrenceEndType.Date) draft.endDate?.format(DATE) else null,
    )
}
