package app.selah.desktop

import app.selah.shared.ProtocolJson
import app.selah.shared.Role
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

/** Un celular aprobado: su papel y el instrumento que se le sugiere. */
@Serializable
data class TeamMember(
    /** SHA-256 del código del celular (el código mismo no se guarda: quien copie el archivo no puede hacerse pasar por él). */
    val idHash: String,
    val name: String,
    val role: Role,
    val instrument: String? = null,
    val lastSeen: Long = 0,
)

@Serializable
data class Team(val members: List<TeamMember> = emptyList())

/** Un celular que pidió entrar y espera que el operador lo apruebe. */
data class PendingDevice(val idHash: String, val name: String, val address: String, val since: Long)

/**
 * El equipo: los celulares que el computador principal aprobó, con su papel (Selah/equipo.json). Los que piden
 * entrar quedan en espera (solo en memoria) hasta que el operador decida.
 */
class TeamStore(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val _team = MutableStateFlow(load())
    val team: StateFlow<Team> = _team.asStateFlow()
    private val _pending = MutableStateFlow<List<PendingDevice>>(emptyList())
    val pending: StateFlow<List<PendingDevice>> = _pending.asStateFlow()

    fun member(idHash: String?): TeamMember? = idHash?.let { h -> _team.value.members.firstOrNull { it.idHash == h } }

    /** Un celular se presentó: si no es del equipo, queda pidiendo entrar. */
    @Synchronized
    fun hello(idHash: String, name: String, address: String) {
        if (member(idHash) != null) {
            update(idHash) { it.copy(lastSeen = clock(), name = name.ifBlank { it.name }) }
            return
        }
        val others = _pending.value.filter { it.idHash != idHash }
        // Pocos a la vez: nadie llena la lista pidiendo entrar con códigos inventados.
        _pending.value = (others + PendingDevice(idHash, name, address, clock())).takeLast(MAX_PENDING)
    }

    @Synchronized
    fun approve(idHash: String, role: Role, instrument: String?, name: String? = null) {
        val asked = _pending.value.firstOrNull { it.idHash == idHash }
        _pending.value = _pending.value.filter { it.idHash != idHash }
        val m = member(idHash)
        val new = (m ?: TeamMember(idHash, name ?: asked?.name ?: "Celular", role)).copy(
            role = role, instrument = instrument?.trim()?.ifEmpty { null }, name = name?.trim()?.ifEmpty { null } ?: m?.name ?: asked?.name ?: "Celular",
            lastSeen = clock(),
        )
        save(Team(_team.value.members.filter { it.idHash != idHash } + new))
    }

    @Synchronized
    fun reject(idHash: String) { _pending.value = _pending.value.filter { it.idHash != idHash } }

    @Synchronized
    fun remove(idHash: String) = save(Team(_team.value.members.filter { it.idHash != idHash }))

    @Synchronized
    fun update(idHash: String, f: (TeamMember) -> TeamMember) {
        if (member(idHash) == null) return
        save(Team(_team.value.members.map { if (it.idHash == idHash) f(it) else it }))
    }

    private fun save(t: Team) {
        _team.value = t
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, ".${file.name}.tmp")
            tmp.writeText(ProtocolJson.encodeToString(Team.serializer(), t))
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { Log.error("No pude guardar el equipo", it) }
    }

    private fun load(): Team = runCatching { ProtocolJson.decodeFromString(Team.serializer(), file.readText()) }.getOrDefault(Team())

    fun reloadFromDisk() { _team.value = load() }

    companion object {
        const val MAX_PENDING = 20

        /** El código que manda la app: 16 a 128 letras, números, "-" o "_". */
        fun validId(id: String?): Boolean = id != null && id.length in 16..128 && id.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }

        fun hash(id: String): String = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }

        /** Nombre limpio para mostrar (sin saltos de línea, corto). */
        fun cleanName(name: String?): String = name.orEmpty().filter { it >= ' ' }.trim().take(40).ifEmpty { "Celular" }
    }
}
