package io.github.trevarj.motd.data.db

import androidx.room.TypeConverter
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.sidecar.SidecarSecurityState

// Enum <-> String converters so enum columns store their stable `name` (matching the string
// literals used in raw @Query predicates like kind IN ('PRIVMSG', ...)).
internal class Converters {
    @TypeConverter
    fun networkRoleToString(v: NetworkRole): String = v.name

    @TypeConverter
    fun stringToNetworkRole(v: String): NetworkRole = NetworkRole.valueOf(v)

    @TypeConverter
    fun connectionTransportToString(v: ConnectionTransport): String = v.name

    @TypeConverter
    fun stringToConnectionTransport(v: String): ConnectionTransport = ConnectionTransport.valueOf(v)

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

    @TypeConverter
    fun dccDirectionToString(v: DccDirection): String = v.name

    @TypeConverter
    fun stringToDccDirection(v: String): DccDirection = DccDirection.valueOf(v)

    @TypeConverter
    fun dccTransferProtocolToString(v: DccTransferProtocol): String = v.name

    @TypeConverter
    fun stringToDccTransferProtocol(v: String): DccTransferProtocol = DccTransferProtocol.valueOf(v)

    @TypeConverter
    fun dccAddressKindToString(v: DccAddressKind): String = v.name

    @TypeConverter
    fun stringToDccAddressKind(v: String): DccAddressKind = DccAddressKind.valueOf(v)

    @TypeConverter
    fun dccTransferStateToString(v: DccTransferState): String = v.name

    @TypeConverter
    fun stringToDccTransferState(v: String): DccTransferState = DccTransferState.valueOf(v)

    // Nullable: the obfsMode column is null on legacy/direct rows.
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

    @TypeConverter
    fun sidecarSecurityToString(v: SidecarSecurityState?): String? = v?.name

    @TypeConverter
    fun stringToSidecarSecurity(v: String?): SidecarSecurityState? = v?.let { runCatching { SidecarSecurityState.valueOf(it) }.getOrNull() }

    /** Unknown persisted values deliberately inherit the global setting instead of breaking reads. */
    @TypeConverter
    fun layoutDensityToString(v: LayoutDensity?): String? = v?.name

    @TypeConverter
    fun stringToLayoutDensity(v: String?): LayoutDensity? = v?.let { runCatching { LayoutDensity.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun presenceModeToString(v: PresenceMode?): String? = v?.name

    @TypeConverter
    fun stringToPresenceMode(v: String?): PresenceMode? = v?.let { runCatching { PresenceMode.valueOf(it) }.getOrNull() }
}
