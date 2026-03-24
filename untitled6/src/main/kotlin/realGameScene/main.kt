package realGameScene

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.math.sqrDistancePointToLine
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

enum class QuestState{
    START,
    WAIT_HERB,
    GOOD_END,
    EVIL_END
}

enum class WorldObjectType{
    ALCHEMIST,
    HERB_SOURCE
}

data class WorldObjectDef(
    val id: String,
    val type: WorldObjectType,
    val x: Float,
    val z: Float,
    val interactRadius: Float

)

data class NpcMemory(
    val hasMet: Boolean,
    val timesTalked: Int,
    val receivedHerb: Boolean
)

data class PlayerState(
    val playerId: String,
    val posX: Float,
    val posZ: Float,
    val questState: QuestState,
    val inventory: Map<String, Int>,
    val alchemistMemory: NpcMemory,
    val currentAreaId: String?,
    val hintText: String
)

fun herbCount(player: PlayerState): Int{
    return  player.inventory["herb"] ?: 0
}

fun distance2d(ax: Float, az: Float, bx: Float, bz: Float): Float{
    // sqrt((dx * dx) + (dz * dz))
    val dx = ax - bx
    val dz = az - bz
    return kotlin.math.sqrt(dx*dx + dz*dz)
}

fun initialPlayerState(playerId: String): PlayerState{
    return if(playerId == "Stas"){
        PlayerState(
            "Stas",
            0f,
            0f,
            QuestState.START,
            emptyMap(),
            NpcMemory(
                true,
                2,
                false
            ),
            null,
            "Подойди к одной из локаций"
        )
    }else{
        PlayerState(
            "Oleg",
            0f,
            0f,
            QuestState.START,
            emptyMap(),
            NpcMemory(
                false,
                0,
                false
            ),
            null,
            "Подойди к одной из локаций"
        )
    }
}

data class DialogueOption(
    val id: String,
    val text: String
)

data class DialogueView(
    val npcId: String,
    val text: String,
    val options: List<DialogueOption>
)

fun buildAlchemistDialogue(player: PlayerState): DialogueView {
    val herbs = herbCount(player)
    val memory = player.alchemistMemory

    return when (player.questState) {
        QuestState.START -> {
            val greeting =
                if (!memory.hasMet) {
                    "О привет, ты кто"
                } else {
                    "Снова ты я тебя знаю ты же ${player.playerId}"
                }
            DialogueView(
                "Алхимик",
                "$greeting \nХочешь помочь - тащи траву",
                listOf(
                    DialogueOption("accept_help", "Я принесу траву"),
                    DialogueOption("threat", "Травы не будет давай товар")
                )
            )
        }

        QuestState.WAIT_HERB -> {
            if (herbs < 3) {
                DialogueView(
                    "Алхимик",
                    "Недостаточно тебе надо $herbs/4 травы",
                    emptyList()
                )
            } else {
                DialogueView(
                    "Алхимик",
                    "ОООООООО это оличный стафф мужик даай сюда",
                    listOf(
                        DialogueOption("give_herb", "Отдать 4 травы")
                    )
                )
            }
        }

        QuestState.GOOD_END -> {
            val text =
                if (memory.receivedHerb) {
                    "Спасибо спасибо спасибо"
                } else {
                    "Ты завершил квест, но память нпс обновилась иди чени код"
                }
            DialogueView(
                "Алимик",
                text,
                emptyList()
            )
        }

        QuestState.EVIL_END -> {
            val text = when {
                memory.receivedHerb -> "Ты обманул меня! Я больше не доверяю тебе..."
                else -> "Ты не получишь от меня ничего! Убирайся!"
            }
            DialogueView(
                "Алхимик",
                text,
                emptyList()
            )
        }
    }
}

sealed interface GameCommand {
    val playerId: String
}

data class CmdMovePlayer(
    override val playerId: String,
    val dx: Float,
    val dz: Float
) : GameCommand

data class CmdInteract(
    override val playerId: String,
    val objectId: String
) : GameCommand

data class CmdChoose(
    override val playerId: String,
    val npcId: String,
    val optionId: String
) : GameCommand

data class CmdCollectItem(
    override val playerId: String,
    val itemId: String,
    val sourceId: String
) : GameCommand

data class CmdUseItem(
    override val playerId: String,
    val itemId: String,
    val targetId: String? = null
) : GameCommand

data class CmdRequestDialogue(
    override val playerId: String,
    val npcId: String
) : GameCommand

data class CmdSetQuestState(
    override val playerId: String,
    val newState: QuestState
) : GameCommand

sealed interface GameEvent {
    val playerId: String
}

data class EvtPlayerMoved(
    override val playerId: String,
    val newX: Float,
    val newZ: Float
) : GameEvent

data class EvtPlayerInteracted(
    override val playerId: String,
    val objectId: String,
    val objectType: WorldObjectType
) : GameEvent

data class EvtDialogueSelected(
    override val playerId: String,
    val npcId: String,
    val optionId: String
) : GameEvent

data class EvtItemCollected(
    override val playerId: String,
    val itemId: String,
    val amount: Int
) : GameEvent

data class EvtQuestStateChanged(
    override val playerId: String,
    val oldState: QuestState,
    val newState: QuestState
) : GameEvent

data class LeftArea(
    override val playerId: String,
    val areaId: String
) : GameEvent

data class EnteredArea(
    override val playerId: String,
    val message: String
) : GameEvent

class GameServer {
    val worldObjects = listOf(
        WorldObjectDef(
            id = "alchemist_1",
            type = WorldObjectType.ALCHEMIST,
            x = 5f,
            z = 5f,
            interactRadius = 2f
        ),
        WorldObjectDef(
            id = "herb_source_1",
            type = WorldObjectType.HERB_SOURCE,
            x = -3f,
            z = 4f,
            interactRadius = 1.5f
        ),
        WorldObjectDef(
            id = "herb_source_2",
            type = WorldObjectType.HERB_SOURCE,
            x = 2f,
            z = -4f,
            interactRadius = 1.5f
        )
    )

    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val _commands = MutableSharedFlow<GameCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<GameCommand> = _commands.asSharedFlow()

    fun trySend(cmd: GameCommand): Boolean {
        return _commands.tryEmit(cmd)
    }

    private val _players = MutableStateFlow<Map<String, PlayerState>>(
        mapOf(
            "Oleg" to initialPlayerState("Oleg"),
            "Stas" to initialPlayerState("Stas")
        )
    )

    val players: StateFlow<Map<String, PlayerState>> = _players.asStateFlow()

    fun start(scope: kotlinx.coroutines.CoroutineScope){
        scope.launch {
            commands.collect { cmd ->

            }
        }
    }

    private fun setPlayer(playerId: String, data: PlayerState) {
        val map = _players.value.toMutableMap()
        map[playerId] = data
        _players.value = map.toMap()
    }

    private fun getPlayer(playerId: String): PlayerState {
        return _players.value[playerId] ?: initialPlayerState(playerId)
    }

    private fun updatePlayer(playerId: String, change: (PlayerState) -> PlayerState) {
        val oldMap = _players.value
        val oldPlayer = oldMap[playerId] ?: return

        val newPlayer = change(oldPlayer)

        val newMap = oldMap.toMutableMap()
        newMap[playerId] = newPlayer
        _players.value = newMap.toMap()
    }

    private fun nearestObject(player: PlayerState): WorldObjectDef? {
        val candidates = worldObjects.filter { obj ->
            distance2d(player.posX, player.posZ, obj.x, obj.z) <= obj.interactRadius
        }
        return candidates.minByOrNull { obj ->
            distance2d(player.posX, player.posZ, obj.x, obj.z)
        }
    }
    private suspend fun refreshPlayerArea(playerId: String){
        val player = getPlayer(playerId)
        val nearest = nearestObject(player)

        val oldAreaId = player.currentAreaId
        val newAreaId = nearest?.id

        if (oldAreaId == newAreaId){
            val newHint =
                when (newAreaId){
                    "alchemist" -> "Подойти и нажми по алхимику"
                    "herb_source" -> "Собери траву"
                    else -> "Подойди к одной из локаций"
                }
            updatePlayer(playerId) {p -> p.copy(hintText = newHint)}
            return
        }

        if (oldAreaId != null){
            _events.emit(LeftArea(playerId,oldAreaId))
        }

        if(newAreaId != null){
            _events.emit(EnteredArea(playerId, newAreaId))
        }

        val newHint =
            when (newAreaId){
                "alchemist" -> "Подойти и нажми по алхимику"
                "herb_source" -> "Собери траву"
                else -> "Подойди к одной из локаций"
            }
        updatePlayer(playerId) { p ->
            p.copy(
                hintText = newHint,
                currentAreaId = newAreaId
            )
        }
    }

    private suspend fun processCommand(cmd: GameCommand){
        when(cmd) {
            is CmdMovePlayer -> {
                updatePlayer(cmd.playerId) { p ->
                    p.copy(
                        posX = p.posX + cmd.dx,
                        posZ = p.posZ + cmd.dz
                    )
                }
                refreshPlayerArea(cmd.playerId)
            }
            is CmdInteract -> {
                val player = getPlayer(cmd.playerId)
                val nearest = nearestObject(player)

                when (nearest?.type) {
                    WorldObjectType.ALCHEMIST -> {
                        _events.emit(EvtPlayerInteracted(cmd.playerId, nearest.id, WorldObjectType.ALCHEMIST))
                        println(buildAlchemistDialogue(player).text)
                    }

                    WorldObjectType.HERB_SOURCE -> {
                        _events.emit(EvtPlayerInteracted(cmd.playerId, nearest.id, WorldObjectType.HERB_SOURCE))
                        updatePlayer(cmd.playerId) { p ->
                            val herbs = (p.inventory["herb"] ?: 0) + 1
                            p.copy(
                                inventory = mapOf("herb" to herbs),
                                hintText = "Травы: $herbs/4"
                            )
                        }
                    }

                    null -> {
                        updatePlayer(cmd.playerId) { p ->
                            p.copy(hintText = "Нет объектов рядом")
                        }
                    }
                }
            }
            else -> {}
        }
    }
}