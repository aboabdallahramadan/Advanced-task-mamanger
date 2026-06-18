package net.qmindtech.tmap.data.local

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class Converters {

    private val json = Json
    private val stringListSerializer = ListSerializer(String.serializer())

    // LocalDate <-> "yyyy-MM-dd"
    @TypeConverter
    fun fromLocalDate(value: LocalDate?): String? = value?.format(DateTimeFormatter.ISO_LOCAL_DATE)

    @TypeConverter
    fun toLocalDate(value: String?): LocalDate? =
        value?.let { LocalDate.parse(it, DateTimeFormatter.ISO_LOCAL_DATE) }

    // Instant <-> ISO-8601 (e.g. 2026-06-18T09:30:00Z)
    @TypeConverter
    fun fromInstant(value: Instant?): String? = value?.toString()

    @TypeConverter
    fun toInstant(value: String?): Instant? = value?.let { Instant.parse(it) }

    // List<String> <-> JSON array string
    @TypeConverter
    fun fromStringList(value: List<String>): String = json.encodeToString(stringListSerializer, value)

    @TypeConverter
    fun toStringList(value: String): List<String> = json.decodeFromString(stringListSerializer, value)

    // TaskStatus <-> canonical PascalCase name (read path is case-insensitive, defaults to Inbox)
    @TypeConverter
    fun fromTaskStatus(value: TaskStatus): String = value.name

    @TypeConverter
    fun toTaskStatus(value: String): TaskStatus = TaskStatus.parse(value) ?: TaskStatus.Inbox
}
