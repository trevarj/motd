package io.github.trevarj.motd.data.db

import androidx.room.TypeConverter

// Enum <-> String converters so enum columns store their stable `name` (matching the string
// literals used in raw @Query predicates like kind IN ('PRIVMSG', ...)).
internal class Converters {
    @TypeConverter
    fun networkRoleToString(v: NetworkRole): String = v.name

    @TypeConverter
    fun stringToNetworkRole(v: String): NetworkRole = NetworkRole.valueOf(v)

    @TypeConverter
    fun bufferTypeToString(v: BufferType): String = v.name

    @TypeConverter
    fun stringToBufferType(v: String): BufferType = BufferType.valueOf(v)

    @TypeConverter
    fun messageKindToString(v: MessageKind): String = v.name

    @TypeConverter
    fun stringToMessageKind(v: String): MessageKind = MessageKind.valueOf(v)

    // Nullable: the obfsMode column is null on legacy/direct rows (plans/20 Phase 1).
    @TypeConverter
    fun obfsModeToString(v: ObfsMode?): String? = v?.name

    @TypeConverter
    fun stringToObfsMode(v: String?): ObfsMode? = v?.let { ObfsMode.valueOf(it) }
}
