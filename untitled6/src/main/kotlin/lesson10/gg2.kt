package lesson10

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.scene.*
import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.Time
import de.fabmax.kool.modules.ui2.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay

import kotlinx.coroutines.*                      // для launch, delay и т.д.
import kotlinx.coroutines.flow.MutableSharedFlow    // радиостанция событий
import kotlinx.coroutines.flow.SharedFlow           // чтение для подписчиков
import kotlinx.coroutines.flow.MutableStateFlow     // табло состояний
import kotlinx.coroutines.flow.StateFlow            // только для чтения
import kotlinx.coroutines.flow.asSharedFlow         // отдать наружу только SharedFlow
import kotlinx.coroutines.flow.asStateFlow          // отдать только StateFlow
import kotlinx.coroutines.flow.collect

import kotlinx.serialization.Serializable           // аннотация, что можно сохранять
import kotlinx.serialization.json.Json

import java.io.File

@Serializable
data class PlayerSave(
    val playerId: String,
    val hp: Int,
    val gold: Int,
    val poisonTicksLeft: Int,
    val attackCooldownMsLeft: Long,
    val questState: String
)

// События игровые - Flow будет рассылать их всем системам

sealed interface GameEvent{
    val playerId: String
}

data class AttackPressed(
    override val playerId: String,
    val targetId: String
): GameEvent

data class DamageDealt(
    override val playerId: String,
    val targetId: String,
    val amount: Int
) : GameEvent

data class ChoiceSelected(
    override val playerId: String,
    val npcId: String,
    val choiceId: String
): GameEvent

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
): GameEvent

data class TalkedToNpc(
    override val playerId: String,
    val npcId: String
): GameEvent

data class SaveRequested(
    override val playerId: String,
): GameEvent

class GameServer{
    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    // Дополнительный небольшой буфер, что Emit при рассылке событий чаще проходил не упираясь в ограничения буфера

    val event: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val _players = MutableStateFlow(
        mapOf(
            "Oleg" to PlayerSave("Oleg", 100, 0, 0, 0L, "START"),
            "Stas" to PlayerSave("Oleg", 100, 0, 0, 0L, "START")
        )
    )

    val players: StateFlow<Map<String, PlayerSave>> = _players.asStateFlow()

    fun tryPublish(event: GameEvent): Boolean{
        return _events.tryEmit(event)
    }

    suspend fun publish(event: GameEvent){
        _events.emit(event)
    }

    fun updatePlayer(playerId: String, change: (PlayerSave) -> PlayerSave){
        // change - функция, которая берет старый PlayerSave и возвращает новый

        val oldMap = _players.value
        val oldPlayer = oldMap[playerId] ?: return

        val newPlayer = change(oldPlayer)

        val newMap = oldMap.toMutableMap()
        newMap[playerId] = newPlayer

        _players.value = newMap.toMutableMap()
    }

    fun getPlayer(playerId: String): PlayerSave{
        return _players.value[playerId] ?: PlayerSave(playerId, 100, 0, 0, 0L, "START")
    }
}

class DamageSystem(
    private val server: GameServer
){
    fun onEvent(e: GameEvent){
        if(e is DamageDealt){
            server.updatePlayer(e.playerId) {player ->
                val newHp = (player.hp - e.amount).coerceAtLeast(0)

                player.copy(hp = newHp)
            }
        }
    }
}