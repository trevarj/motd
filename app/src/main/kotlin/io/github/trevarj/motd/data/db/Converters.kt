package io.github.trevarj.motd.data.db

import androidx.room.TypeConverter
import io.github.trevarj.motd.data.prefs.LayoutDensity

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

    @TypeConverter
    fun inviteStateToString(v: InviteState?): String? = v?.name

    @TypeConverter
    fun stringToInviteState(v: String?): InviteState? = v?.let { InviteState.valueOf(it) }

    // Nullable: the obfsMode column is null on legacy/direct rows (plans/20 Phase 1).
    @TypeConverter
    fun obfsModeToString(v: ObfsMode?): String? = v?.name

    @TypeConverter
    fun stringToObfsMode(v: String?): ObfsMode? = v?.let { ObfsMode.valueOf(it) }

    @TypeConverter
    fun roomAliasNamespaceToString(v: RoomAliasNamespace): String = v.name

    @TypeConverter
    fun stringToRoomAliasNamespace(v: String): RoomAliasNamespace = RoomAliasNamespace.valueOf(v)

    @TypeConverter
    fun eventAliasNamespaceToString(v: EventAliasNamespace): String = v.name

    @TypeConverter
    fun stringToEventAliasNamespace(v: String): EventAliasNamespace = EventAliasNamespace.valueOf(v)

    @TypeConverter
    fun observationOriginToString(v: ObservationOrigin): String = v.name

    @TypeConverter
    fun stringToObservationOrigin(v: String): ObservationOrigin = ObservationOrigin.valueOf(v)

    @TypeConverter
    fun timeProvenanceToString(v: TimeProvenance): String = v.name

    @TypeConverter
    fun stringToTimeProvenance(v: String): TimeProvenance = TimeProvenance.valueOf(v)

    /** Unknown persisted values deliberately inherit the global setting instead of breaking reads. */
    @TypeConverter
    fun layoutDensityToString(v: LayoutDensity?): String? = v?.name

    @TypeConverter
    fun stringToLayoutDensity(v: String?): LayoutDensity? =
        v?.let { runCatching { LayoutDensity.valueOf(it) }.getOrNull() }
}
