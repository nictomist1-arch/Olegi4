package lesson11

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.scene.*
import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.Time
import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.pipeline.ClearColorLoad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import lesson10.HudState


// Когда событий стаовится слишком много в игре появляется проблема
// 1. Если все системы слушают все события код быстро превратится в кашу
// 2. Будет сложно понять кто на что реагирует из систем
// 3. Такие системы сложно дебажить
// 4. И так же надо жестко разделять события игрока Oleg от событий игрока Stas

// Для исправления данных проблем надо использовать flow-операторы
// filter - оставляет в потоке только то что подхдит по условию
// map - преобразует каждый элемент потока
// onEach - делает нужное действие для каждого элемента в потоке но не изменяет сам поток
// launchIn (scope) - запускает слушателя на фоне в нужным пространстве работы корутин

@Serializable
data class PlayerSave(
    val playerId: String,
    val hp: Int,
    val gold: Int,
    val dummyHp: Int,
    val poisonTicksLeft: Int,
    val attackCooldownMsLeft: Long,
    val questState: String,
    val attackSpeedBuffTicksLeft: Int = 0
)

sealed interface GameEvent {
    val playerId: String
}

data class CommandRejected(
    override val playerId: String,
    val reason: String
) : GameEvent

data class AttackPressed(
    override val playerId: String,
    val targetId: String
) : GameEvent

data class DamageDealt(
    override val playerId: String,
    val targetId: String,
    val amount: Int
) : GameEvent

data class ChoiceSelected(
    override val playerId: String,
    val npcId: String,
    val choiceId: String
) : GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val questId: String,
    val newState: String
) : GameEvent

data class PoisonApplied(
    override val playerId: String,
    val ticks: Int,
    val damagePerTick: Int,
    val intervalMs: Long
) : GameEvent

data class TalkedToNpc(
    override val playerId: String,
    val npcId: String
) : GameEvent

data class SaveRequested(
    override val playerId: String
) : GameEvent

data class AttackSpeedBuffApplied(
    override val playerId: String,
    val ticks: Int
) : GameEvent

data class ServerMessage(
    override val playerId: String,
    val text: String
) : GameEvent

class GameServer {
    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val _players = MutableStateFlow(
        mapOf(
            "Oleg" to PlayerSave("Oleg", 100, 0, 50, 0, 0L, "START", 0),
            "Stas" to PlayerSave("Stas", 100, 0, 50, 0, 0L, "START", 0)
        )
    )
    val players: StateFlow<Map<String, PlayerSave>> = _players.asStateFlow()

    fun tryPublish(event: GameEvent): Boolean {
        return _events.tryEmit(event)
    }

    suspend fun publish(event: GameEvent) {
        _events.emit(event)
    }

    fun updatePlayer(playerId: String, change: (PlayerSave) -> PlayerSave) {
        val oldMap = _players.value
        val oldPlayer = oldMap[playerId] ?: return

        val newPlayer = change(oldPlayer)
        val newMap = oldMap.toMutableMap()
        newMap[playerId] = newPlayer
        _players.value = newMap
    }

    fun getPlayer(playerId: String): PlayerSave {
        return _players.value[playerId] ?: PlayerSave(playerId, 100, 0, 50, 0, 0L, "START", 0)
    }
}

class DamageSystem(
    private val server: GameServer
) {
    fun onEvent(e: GameEvent) {
        if (e is DamageDealt) {
            server.updatePlayer(e.targetId) { player ->
                val newHp = (player.hp - e.amount).coerceAtLeast(0)
                player.copy(hp = newHp)
            }
        }
    }
}

class CooldownSystem(
    private val server: GameServer,
    private val scope: CoroutineScope
) {
    private val cooldownJobs = mutableMapOf<String, Job>()

    private val BASE_COOLDOWN_MS = 1200L
    private val BUFFED_COOLDOWN_MS = 700L

    fun getCurrentCooldown(playerId: String): Long {
        val player = server.getPlayer(playerId)
        return if (player.attackSpeedBuffTicksLeft > 0) {
            BUFFED_COOLDOWN_MS
        } else {
            BASE_COOLDOWN_MS
        }
    }

    fun startCooldown(playerId: String) {
        cooldownJobs[playerId]?.cancel()

        val totalMs = getCurrentCooldown(playerId)

        server.updatePlayer(playerId) { player -> player.copy(attackCooldownMsLeft = totalMs) }

        val job = scope.launch {
            val step = 100L

            while (isActive && server.getPlayer(playerId).attackCooldownMsLeft > 0L) {
                delay(step)

                server.updatePlayer(playerId) { player ->
                    val left = (player.attackCooldownMsLeft - step).coerceAtLeast(0L)
                    player.copy(attackCooldownMsLeft = left)
                }
            }
            cooldownJobs.remove(playerId)
        }

        cooldownJobs[playerId] = job
    }

    fun canAttack(playerId: String): Boolean {
        return server.getPlayer(playerId).attackCooldownMsLeft <= 0L
    }
}

class PoisonSystem(
    private val server: GameServer,
    private val scope: CoroutineScope
) {
    private val poisonJobs = mutableMapOf<String, Job>()

    fun onEvent(e: GameEvent, publishDamage: (DamageDealt) -> Unit) {
        if (e is PoisonApplied) {
            server.updatePlayer(e.playerId) { player ->
                player.copy(poisonTicksLeft = player.poisonTicksLeft + e.ticks)
            }

            val job = scope.launch {
                while (isActive && server.getPlayer(e.playerId).poisonTicksLeft > 0) {
                    delay(e.intervalMs)

                    server.updatePlayer(e.playerId) { player ->
                        player.copy(poisonTicksLeft = (player.poisonTicksLeft - 1).coerceAtLeast(0))
                    }

                    publishDamage(DamageDealt(e.playerId, "self", e.damagePerTick))
                }
                poisonJobs.remove(e.playerId)
            }
            poisonJobs[e.playerId] = job
        }
    }
}

class QuestSystem(private val server: GameServer){
    private val questId = "q_alchemist"
    private val npcId = "alchemist"

    fun handleTalk(e: TalkedToNpc, publish: (GameEvent) -> Unit){
        if(e.npcId != npcId) return

        val player = server.getPlayer(e.playerId)
        if (player.questState == "START"){
            server.updatePlayer(e.playerId) {it.copy(questState = "OFFERED")}
            publish(QuestStateChanged(e.playerId,questId,"OFFERED"))
        }
    }

    fun handleChoice(e: ChoiceSelected, publish: (GameEvent) -> Unit){
        if (e.npcId != npcId) return

        val player = server.getPlayer(e.playerId)
        if(player.questState == "OFFERED"){
            val newState = if(e.choiceId == "help") "GOOD_END" else "EVIL_END"
            server.updatePlayer(e.playerId) {it.copy(questState = newState)}
            publish(QuestStateChanged(e.playerId,questId, newState))
        }
    }
}

class SaveSystem {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    private fun file(playerId: String): File {
        val dir = File("saves")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$playerId.json")
    }

    fun save(player: PlayerSave) {
        val text = json.encodeToString(PlayerSave.serializer(), player)
        file(player.playerId).writeText(text)
    }
}

class HudState {
    val activePlayerId = mutableStateOf("Oleg")

    val activePlayerIdUi = mutableStateOf("Oleg")
    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)
    val dummyHp = mutableStateOf(50)
    val poisonTicksLeft = mutableStateOf(0)
    val questState = mutableStateOf("START")
    val attackCooldownMsLeft = mutableStateOf(0L)
    val attackSpeedBuffTicksLeft = mutableStateOf(0)

    val log = mutableStateOf<List<String>>(emptyList())
}

fun hudLog(hud: HudState, line: String) {
    hud.log.value = (hud.log.value + line).takeLast(20)
}
