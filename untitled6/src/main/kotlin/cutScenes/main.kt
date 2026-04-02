package cutScenes

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.modules.audio.synth.SampleNode
import de.fabmax.kool.scene.*
import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.Time
import de.fabmax.kool.pipeline.ClearColorLoad
import de.fabmax.kool.modules.ui2.*

import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.flatMapLatest
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class QuestState {
    START,
    WAIT_HERB,
    GOOD_END,
    EVIL_END,
    CHEST_AVAILABLE
}

enum class WorldObjectType {
    ALCHEMIST,
    HERB_SOURCE,
    CHEST
}

data class WorldObjectDef(
    val id: String,
    val type: WorldObjectType,
    val x: Float,
    val z: Float,
    val interactRadius: Float = 1.7f
)

data class NpcMemory(
    val hasMet: Boolean,
    val timesTalked: Int,
    val receivedHerb: Boolean,
    val sawPlayerNearSource: Boolean = false
)

data class PlayerState(
    val playerId: String,
    val posX: Float,
    val posZ: Float,
    val questState: QuestState,
    val inventory: Map<String, Int>,
    val alchemistMemory: NpcMemory,
    val currentAreaId: String?,
    val hintText: String,
    val gold: Int,
    val nearAlchemist: Boolean,
    val inputLocked: Boolean,
    val cutsceneActive: Boolean,
    val cutsceneText: String,
    val hasOpenedChest: Boolean = false
)

fun herbCount(player: PlayerState): Int {
    return player.inventory["herb"] ?: 0
}

fun distance2D(ax: Float, az: Float, bx: Float, bz: Float): Float {
    val dx = ax - bx
    val dz = az - bz
    return sqrt(dx * dx + dz * dz)
}

fun initialPlayerState(playerId: String): PlayerState {
    return if (playerId == "Stas") {
        PlayerState(
            "Stas",
            0f,
            0f,
            QuestState.START,
            emptyMap(),
            NpcMemory(
                true,
                2,
                false,
                false
            ),
            null,
            "Подойди к одной из локаций",
            0,
            false,
            false,
            false,
            "",
            false
        )
    } else {
        PlayerState(
            "Oleg",
            0f,
            0f,
            QuestState.START,
            emptyMap(),
            NpcMemory(
                false,
                0,
                false,
                false
            ),
            null,
            "Подойди к одной из локаций",
            0,
            false,
            false,
            false,
            "",
            false
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
    val option: List<DialogueOption>
)

fun buildAlchemistDialogue(player: PlayerState): DialogueView {
    if (player.cutsceneActive) {
        return DialogueView(
            "Алхимик",
            "Сейчас идет катсцена",
            emptyList()
        )
    }

    if (!player.nearAlchemist) {
        return DialogueView(
            "Алхимик",
            "Подойди ближе к Алхимику",
            emptyList()
        )
    }

    val herbs = herbCount(player)
    val memory = player.alchemistMemory

    return when (player.questState) {
        QuestState.START -> {
            val greeting =
                if (!memory.hasMet) {
                    "О привет"
                } else {
                    "снова ты... я тебя знаю, ты ${player.playerId}"
                }
            DialogueView(
                "Алхимик",
                "$greeting \n Хочешь помочь - принеси травку",
                listOf(
                    DialogueOption("accept_help", "Я принесу траву"),
                    DialogueOption("threat", "травы не будет, гони товар")
                )
            )
        }

        QuestState.WAIT_HERB -> {
            if (herbs < 3) {
                DialogueView(
                    "Алхимик",
                    "Недостаточно, надо $herbs/3 травы",
                    emptyList()
                )
            } else {
                DialogueView(
                    "Алхимик",
                    "найс, прет как белый, давай сюда",
                    listOf(
                        DialogueOption("give_herb", "Отдать 3 травы")
                    )
                )
            }
        }

        QuestState.CHEST_AVAILABLE -> {
            DialogueView(
                "Алхимик",
                "Ты отлично справился! Я спрятал твою награду в сундуке неподалеку. Найди его и забери золото!",
                emptyList()
            )
        }

        QuestState.GOOD_END -> {
            val text =
                if (memory.receivedHerb) {
                    "Спасибо за помощь! Надеюсь, ты нашел сундук с наградой."
                } else {
                    "Ты завершил квест, но npc все забыл..."
                }
            DialogueView(
                "Алхимик",
                text,
                emptyList()
            )
        }

        QuestState.EVIL_END -> {
            DialogueView(
                "Алхимик",
                "ты проиграл бетмен",
                emptyList()
            )
        }
    }
}

sealed interface GameCommand {
    val playerId: String
}

fun getCirclePosition(
    centerX: Float,
    centerY: Float,
    radius: Float,
    angle: Float
): Pair<Float, Float> {
    val x = centerX + radius * cos(angle)
    val y = centerY + radius * sin(angle)
    return Pair(x, y)
}

data class CmdMovePlayer(
    override val playerId: String,
    val dx: Float,
    val dz: Float
) : GameCommand

data class CmdMoveNpc(
    val dx: Float,
    val dz: Float
)

data class CmdInteract(
    override val playerId: String
) : GameCommand

data class CmdChooseDialogueOption(
    override val playerId: String,
    val optionId: String
) : GameCommand

data class CmdResetPlayer(
    override val playerId: String
) : GameCommand

data class CmdSwitchActivePlayer(
    override val playerId: String
) : GameCommand

sealed interface GameEvent {
    val playerId: String
}

data class EnteredArea(
    override val playerId: String,
    val areaId: String
) : GameEvent

data class LeftArea(
    override val playerId: String,
    val areaId: String
) : GameEvent

data class InteractedWithNpc(
    override val playerId: String,
    val npcId: String
) : GameEvent

data class InteractedWithHerbSource(
    override val playerId: String,
    val sourceId: String
) : GameEvent

data class InteractedWithChest(
    override val playerId: String,
    val sourceId: String
) : GameEvent

data class GoldCountChanged(
    override val playerId: String,
    val countGold: Int
) : GameEvent

data class InventoryChanged(
    override val playerId: String,
    val itemId: String,
    val newCount: Int
) : GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val newState: QuestState
) : GameEvent

data class NpcMemoryChanged(
    override val playerId: String,
    val memory: NpcMemory
) : GameEvent

data class ServerMessage(
    override val playerId: String,
    val text: String
) : GameEvent

data class CutSceneStarted(
    override val playerId: String,
    val cutsceneId: String
) : GameEvent

data class CutSceneStep(
    override val playerId: String,
    val text: String
) : GameEvent

data class CutSceneFinished(
    override val playerId: String,
    val cutsceneId: String
) : GameEvent

class GameServer {
    val worldObjects = mutableListOf(
        WorldObjectDef(
            "alchemist",
            WorldObjectType.ALCHEMIST,
            -3f,
            0f,
            1.7f
        ),
        WorldObjectDef(
            "herb_source",
            WorldObjectType.HERB_SOURCE,
            3f,
            0f,
            1.7f
        )
    )

    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val _commands = MutableSharedFlow<GameCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<GameCommand> = _commands.asSharedFlow()

    fun trySend(cmd: GameCommand): Boolean = _commands.tryEmit(cmd)

    private val _players = MutableStateFlow(
        mapOf(
            "Oleg" to initialPlayerState("Oleg"),
            "Stas" to initialPlayerState("Stas")
        )
    )

    val players: StateFlow<Map<String, PlayerState>> = _players.asStateFlow()

    fun start(scope: kotlinx.coroutines.CoroutineScope) {
        serverScope = scope
        scope.launch {
            commands.collect { cmd ->
                processCommand(cmd)
            }
        }
    }

    fun setPlayerData(playerId: String, data: PlayerState) {
        val map = _players.value.toMutableMap()
        map[playerId] = data
        _players.value = map.toMap()
    }

    fun getPlayerData(playerId: String): PlayerState {
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

    private val cutsceneJobs = mutableMapOf<String, Job>()

    private var serverScope: kotlinx.coroutines.CoroutineScope? = null

    private fun nearestObject(player: PlayerState): WorldObjectDef? {
        val candidates = worldObjects.filter { obj ->
            distance2D(player.posX, player.posZ, obj.x, obj.z) <= obj.interactRadius
        }

        return candidates.minByOrNull { obj ->
            distance2D(player.posX, player.posZ, obj.x, obj.z)
        }
    }

    private suspend fun refreshPlayerArea(playerId: String) {
        val player = getPlayerData(playerId)
        val nearest = nearestObject(player)

        val oldAreaId = player.currentAreaId
        val newAreaId = nearest?.id

        val isChestAvailable = player.questState == QuestState.CHEST_AVAILABLE && !player.hasOpenedChest

        if (!isChestAvailable && worldObjects.any { it.id == "treasure_box" }) {
            worldObjects.removeAll { it.id == "treasure_box" }
            _events.emit(ServerMessage(playerId, "Сундук исчез!"))
        } else if (isChestAvailable && !worldObjects.any { it.id == "treasure_box" }) {
            worldObjects.add(
                WorldObjectDef(
                    "treasure_box",
                    WorldObjectType.CHEST,
                    7f,
                    0f,
                    1.7f
                )
            )
            _events.emit(ServerMessage(playerId, "Появился сундук с сокровищами!"))
        }

        if (oldAreaId != null) {
            _events.emit(LeftArea(playerId, oldAreaId))
        }

        if (newAreaId != null) {
            _events.emit(EnteredArea(playerId, newAreaId))
        }

        val newHint =
            when (newAreaId) {
                "alchemist" -> "Подойди и нажми на алхимика"
                "herb_source" -> "собери траву"
                "treasure_box" -> "Открой сундук и получи награду!"
                else -> "Подойди к одной из локаций"
            }
        updatePlayer(playerId) { p ->
            p.copy(
                hintText = newHint,
                currentAreaId = newAreaId,
                nearAlchemist = newAreaId == "alchemist"
            )
        }
    }

    private suspend fun processCommand(cmd: GameCommand) {
        when (cmd) {
            is CmdMovePlayer -> {
                val player = getPlayerData(cmd.playerId)

                if (player.cutsceneActive){
                    _events.emit(ServerMessage(cmd.playerId, "Управление заблокировано, идет катсцена"))
                    return
                }

                updatePlayer(cmd.playerId) { p ->
                    p.copy(
                        posX = p.posX + cmd.dx,
                        posZ = p.posZ + cmd.dz
                    )
                }
                refreshPlayerArea(cmd.playerId)
            }

            is CmdInteract -> {
                val player = getPlayerData(cmd.playerId)
                val obj = nearestObject(player)

                if (obj == null) {
                    _events.emit(ServerMessage(cmd.playerId, "Рядом нет объектов для взаимодействия"))
                    return
                }

                when (obj.type) {
                    WorldObjectType.ALCHEMIST -> {
                        if (player.alchemistMemory.sawPlayerNearSource) {
                            _events.emit(ServerMessage(cmd.playerId, "Так... ты тут был... ааа трава-то, где?"))
                            return
                        } else {
                            val oldMemory = player.alchemistMemory
                            val newMemory = oldMemory.copy(
                                hasMet = true,
                                timesTalked = oldMemory.timesTalked + 1
                            )

                            updatePlayer(cmd.playerId) { p ->
                                p.copy(alchemistMemory = newMemory)
                            }

                            _events.emit(InteractedWithNpc(cmd.playerId, obj.id))
                            _events.emit(NpcMemoryChanged(cmd.playerId, newMemory))
                        }
                    }

                    WorldObjectType.HERB_SOURCE -> {
                        val oldAlchemistMemory = player.alchemistMemory
                        val newAlchemistMemory = oldAlchemistMemory.copy(
                            sawPlayerNearSource = true
                        )
                        updatePlayer(cmd.playerId) { p ->
                            p.copy(alchemistMemory = newAlchemistMemory)
                        }
                        if (player.questState != QuestState.WAIT_HERB) {
                            _events.emit(ServerMessage(cmd.playerId, "Трава сейчас не нужна, сначала возьми квест"))
                            return
                        }

                        val oldCount = herbCount(player)
                        val newCount = oldCount + 1
                        val newInventory = player.inventory + ("herb" to newCount)

                        updatePlayer(cmd.playerId) { p ->
                            p.copy(inventory = newInventory)
                        }

                        _events.emit(InteractedWithHerbSource(cmd.playerId, obj.id))
                        _events.emit(InventoryChanged(cmd.playerId, "herb", newCount))
                    }

                    WorldObjectType.CHEST -> {
                        val player = getPlayerData(cmd.playerId)

                        if (player.questState != QuestState.CHEST_AVAILABLE) {
                            _events.emit(ServerMessage(cmd.playerId, "Этот сундук заперт..."))
                            return
                        }

                        if (player.hasOpenedChest) {
                            _events.emit(ServerMessage(cmd.playerId, "Сундук уже пуст"))
                            return
                        }

                        val oldCountGold = player.gold
                        val newCountGold = oldCountGold + 10

                        updatePlayer(cmd.playerId) { p ->
                            p.copy(
                                gold = newCountGold,
                                hasOpenedChest = true,
                                questState = QuestState.GOOD_END
                            )
                        }

                        worldObjects.removeAll { it.id == "treasure_box" }

                        _events.emit(InteractedWithChest(cmd.playerId, obj.id))
                        _events.emit(GoldCountChanged(cmd.playerId, newCountGold))
                        _events.emit(ServerMessage(cmd.playerId, "Ты открыл сундук и нашел 10 золотых монет! Квест полностью завершен!"))
                    }
                }
            }

            is CmdChooseDialogueOption -> {
                val player = getPlayerData(cmd.playerId)

                if (player.currentAreaId != "alchemist") {
                    _events.emit(ServerMessage(cmd.playerId, "Сначала подойди к алхимику"))
                    return
                }

                when (cmd.optionId) {
                    "accept_help" -> {
                        val radiusHerb = distance2D(player.posX, player.posZ, 3f, 0f)
                        if (radiusHerb <= 1.7f) {
                            if (player.questState != QuestState.START) {
                                _events.emit(ServerMessage(cmd.playerId, "Путь помощи можно выбрать только в начале квеста"))
                                return
                            }

                            updatePlayer(cmd.playerId) { p ->
                                p.copy(questState = QuestState.WAIT_HERB)
                            }

                            _events.emit(QuestStateChanged(cmd.playerId, QuestState.WAIT_HERB))
                            _events.emit(ServerMessage(cmd.playerId, "Алхимик просит собрать 3 травы"))
                        } else {
                            _events.emit(ServerMessage(cmd.playerId, "Ты отошел слишком далеко от Алхимика"))
                            return
                        }
                    }

                    "give_herb" -> {
                        if (player.questState != QuestState.WAIT_HERB) {
                            _events.emit(ServerMessage(cmd.playerId, "Сейчас нельзя сдать траву"))
                            return
                        }

                        val herbs = herbCount(player)

                        if (herbs < 3) {
                            _events.emit(ServerMessage(cmd.playerId, "Недостаточно травы"))
                            return
                        }

                        
                        startRewardCutscene(cmd.playerId)
                    }

                    else -> {
                        _events.emit(ServerMessage(cmd.playerId, "Неизвестный формат диалога"))
                    }
                }
            }

            is CmdSwitchActivePlayer -> {
                _events.emit(ServerMessage(cmd.playerId, "Переключен на игрока ${cmd.playerId}"))
            }

            is CmdResetPlayer -> {
                updatePlayer(cmd.playerId) { _ -> initialPlayerState(cmd.playerId) }
                _events.emit(ServerMessage(cmd.playerId, "Игрок сброшен к начальному уровню"))
                refreshPlayerArea(cmd.playerId)
            }
        }
    }

    private fun startRewardCutscene(playerId: String) {
        val scope = serverScope ?: return

        if (cutsceneJobs[playerId]?.isActive == true) {
            scope.launch {
                _events.emit(ServerMessage(playerId, "Катсцена уже запущена"))
            }
            return
        }

        val job = scope.launch {
            updatePlayer(playerId) { p ->
                p.copy(
                    inputLocked = true,
                    cutsceneActive = true,
                    cutsceneText = "Алхимик варит траву"
                )
            }

            _events.emit(CutSceneStarted(playerId, "alchemist_reward"))
            _events.emit(CutSceneStep(playerId, "Алхимик варит траву"))

            delay(1200)
            
            val p1 = getPlayerData(playerId)
            val herbs = herbCount(p1)
            val newCount = herbs - 3

            val newInventory =
                if (newCount <= 0) p1.inventory - "herb" else p1.inventory + ("herb" to newCount)

            val newMemory = p1.alchemistMemory.copy(
                receivedHerb = true
            )

            updatePlayer(playerId) { p ->
                p.copy(
                    inventory = newInventory,
                    alchemistMemory = newMemory,
                    cutsceneText = "Алхимик смешивает ингредиенты"
                )
            }

            _events.emit(InventoryChanged(playerId, "herb", newCount))
            _events.emit(NpcMemoryChanged(playerId, newMemory))
            _events.emit(CutSceneStep(playerId, "Алхимик смешивает ингредиенты"))

            delay(1200)

            updatePlayer(playerId) { p ->
                p.copy(
                    gold = p.gold + 5,
                    questState = QuestState.CHEST_AVAILABLE,
                    cutsceneText = "Алхимик дает тебе награду"
                )
            }

            _events.emit(QuestStateChanged(playerId, QuestState.CHEST_AVAILABLE))
            _events.emit(CutSceneStep(playerId, "Алхимик дает тебе награду"))
            _events.emit(ServerMessage(playerId, "Алхимик получил траву и выдал тебе золото! Теперь найди сундук с наградой!"))

            delay(900)

             
            updatePlayer(playerId) { p ->
                p.copy(
                    inputLocked = false,
                    cutsceneActive = false,
                    cutsceneText = "",
                    hintText = "Катсцена завершена"
                )
            }

            _events.emit(CutSceneFinished(playerId, "alchemist_reward"))
            _events.emit(ServerMessage(playerId, "Катсцена завершена и возвращено управление"))
        }
        cutsceneJobs[playerId] = job
    }
}

class HudState{
    val activePlayerIdFlow = MutableStateFlow("Oleg")

    val activePlayerIdUi = mutableStateOf("Oleg")

    val playerSnapShot = mutableStateOf(initialPlayerState("Oleg"))

    val log = mutableStateOf<List<String>>(emptyList())
}

fun hudLog(hud: HudState, line: String){
    hud.log.value = (hud.log.value + line).takeLast(20)
}

fun formatInventory(player:  PlayerState): String{
    return if (player.inventory.isEmpty()){
        "Inventory: пусто"
    }else{
        "Inventory: " + player.inventory.entries.joinToString { "${it.key} x${it.value}" }
    }
}

fun currentObjective(player:  PlayerState): String{
    val herbs = herbCount(player)

    return when (player.questState){
        QuestState.START -> "Подойди к алхимику и начни разговор"
        QuestState.WAIT_HERB -> {
            if (herbs < 3) "Собери 3 травы. Сейчас $herbs / 3"
            else "Вернись к алхимику и отдай 3 травы"
        }
        QuestState.CHEST_AVAILABLE -> "Найди сундук с сокровищами и открой его!"
        QuestState.GOOD_END -> "Квест завершен по хорошей ветке"
        QuestState.EVIL_END -> "Квест завершен по плохой ветке"
    }
}

fun currentZoneText(player:  PlayerState): String{
    return when(player.currentAreaId){
        "alchemist" -> "Зона: Алхимик"
        "herb_source" -> "Зона: Источник травы"
        "treasure_box" -> "Зона: Сундук с сокровищами"
        else -> "Открытое пространство"
    }
}

fun formatMemory(memory:  NpcMemory): String{
    return "Встретился: ${memory.hasMet}, сколько раз поговорил: ${memory.timesTalked}, отдал траву: ${memory.receivedHerb}"
}

fun eventToText(e:  GameEvent): String{
    return when(e){
        is  EnteredArea -> "EnteredArea ${e.areaId}"
        is  LeftArea -> "LeftArea ${e.areaId}"
        is  InteractedWithNpc -> "InteractedWithNpc ${e.npcId}"
        is  InteractedWithHerbSource -> "InteractedWithHerbSource ${e.sourceId}"
        is  InteractedWithChest -> "InteractedWithChest ${e.sourceId}"
        is  InventoryChanged -> "InventoryChanged ${e.itemId} -> ${e.newCount}"
        is  QuestStateChanged -> "QuestStateChanged ${e.newState}"
        is  NpcMemoryChanged -> "NpcMemoryChanged Встретился: ${e.memory.hasMet}, сколько раз поговорил: ${e.memory.timesTalked}, отдал траву: ${e.memory.receivedHerb}"
        is  ServerMessage -> "Server: ${e.text}"
        is CutSceneStep -> "CutSceneStep ${e.text}"
        is CutSceneStarted -> "CutSceneStarted ${e.cutsceneId}"
        is CutSceneFinished -> "CutSceneFinished ${e.cutsceneId}"
        else -> ""
    }
}

fun main() = KoolApplication {
    val hud = HudState()
    val server = GameServer()

    addScene {
        defaultOrbitCamera()

        val playerNode = addColorMesh {
            generate {
                cube {
                    colored()
                }
            }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }

        val alchemistNode = addColorMesh {
            generate {
                cube {
                    colored()
                }
            }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }

        alchemistNode.transform.translate(-3f, 0f, 0f)

        val herbNode = addColorMesh {
            generate {
                cube {
                    colored()
                }
            }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }

        herbNode.transform.translate(3f, 0f, 0f)

        val chestNode = addColorMesh {
            generate {
                cube {
                    colored()
                }
            }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0.5f)
                roughness(0.3f)
            }
        }
        chestNode.transform.translate(7f, 0f, 0f)
        chestNode.isVisible = false

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, -1f))
            setColor(Color.WHITE, 5f)
        }

        server.start(coroutineScope)

        var lastRenderedX = 0f
        var lastRenderedZ = 0f

        playerNode.onUpdate {
            val activeId = hud.activePlayerIdFlow.value
            val player = server.getPlayerData(activeId)

            val dx = player.posX - lastRenderedX
            val dz = player.posZ - lastRenderedZ

            playerNode.transform.translate(dx, 0f, dz)

            lastRenderedX = player.posX
            lastRenderedZ = player.posZ
        }

        var alchemistAngle = 0f
        var isPlayerNearAlchemist = false

        alchemistNode.onUpdate {
            val activeId = hud.activePlayerIdFlow.value
            val player = server.getPlayerData(activeId)

            val speed = if (player.cutsceneActive) 120f else 20f

            transform.rotate(speed.deg * Time.deltaT, Vec3f.X_AXIS)

            val distanceToAlchemist = distance2D(player.posX, player.posZ, -3f, 0f)
            isPlayerNearAlchemist = distanceToAlchemist <= 1.7f

            if (!isPlayerNearAlchemist) {
                alchemistAngle += 0.5f * Time.deltaT
                if (alchemistAngle > 2 * Math.PI.toFloat()) {
                    alchemistAngle -= 2 * Math.PI.toFloat()
                }

                val (x, z) = getCirclePosition(-3f, 0f, 2f, alchemistAngle)
                alchemistNode.transform.translate(x, 0f, z)
            }
        }

        herbNode.onUpdate {
            transform.rotate(20f.deg * Time.deltaT, Vec3f.X_AXIS)
        }

        chestNode.onUpdate {
            if (chestNode.isVisible) {
                transform.rotate(20f.deg * Time.deltaT, Vec3f.Y_AXIS)
                val bobY = (sin(Time.gameTime * 3.0) * 0.1).toFloat()
                transform.translate(0f, bobY, 0f)
            }
        }

        var chestVisible = false
        onUpdate {
            val activeId = hud.activePlayerIdFlow.value
            val player = server.getPlayerData(activeId)

            val shouldShowChest =
                player.questState == QuestState.CHEST_AVAILABLE && !player.hasOpenedChest

            if (shouldShowChest != chestVisible) {
                chestVisible = shouldShowChest
                chestNode.isVisible = chestVisible
            }
        }
    }
    addScene {
        setupUiScene(ClearColorLoad)

        hud.activePlayerIdFlow
            .flatMapLatest { pid ->
                server.players.map { map ->
                    map[pid] ?: initialPlayerState(pid)
                }
            }
            .onEach { player ->
                hud.playerSnapShot.value = player
            }
            .launchIn(coroutineScope)
        hud.activePlayerIdFlow
            .flatMapLatest { pid ->
                server.events.filter { it.playerId == pid}
            }
            .map{ event ->
                eventToText(event)
            }
            .onEach { line ->
                hudLog(hud, "[${hud.activePlayerIdUi.value}] $line")
            }
            .launchIn(coroutineScope)
        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), 14.dp))
                .padding(12.dp)

            Column {
                val player = hud.playerSnapShot.use()
                val dialogue = buildAlchemistDialogue(player)

                Text("Игрок: ${hud.activePlayerIdUi.use()}") {
                    modifier.margin(bottom = sizes.gap)
                }

                Text("Позиция: x=${"%.1f".format(player.posX)} z=${"%.1f".format(player.posZ)}") {}
                Text("Quest State: ${player.questState}") {
                    modifier.font(sizes.smallText)
                }
                Text(currentObjective(player)) {
                    modifier.font(sizes.smallText)
                }
                Text(formatInventory(player)) {
                    modifier.font(sizes.smallText)
                }
                Text("Gold: ${player.gold}") {
                    modifier.font(sizes.smallText)
                }
                Text("Hint: ${player.hintText}") {
                    modifier.font(sizes.smallText)
                }
                Text("Npc Memory: ${formatMemory(player.alchemistMemory)}") {
                    modifier.font(sizes.smallText).margin(bottom = sizes.smallGap)
                }

                if (player.cutsceneActive){
                    Text("CUTSCENE: ${player.cutsceneText}"){
                        modifier
                            .margin(bottom = sizes.gap)
                            .font(sizes.largeText)
                    }
                }

                Row {
                    Button("Сменить игрока") {
                        modifier.margin(end = 8.dp).onClick {
                            val newId = if (hud.activePlayerIdUi.value == "Oleg") "Stas" else "Oleg"

                            hud.activePlayerIdUi.value = newId
                            hud.activePlayerIdFlow.value = newId
                        }
                    }

                    Button("Сбросить игрока") {
                        modifier.onClick {
                            server.trySend(CmdResetPlayer(player.playerId))
                        }
                    }
                }
                Text("Движение по миру") { modifier.margin(top = sizes.gap) }

                Row {
                    Button("Лево") {
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdMovePlayer(player.playerId, dx = -0.5f, dz = 0f))
                        }
                    }
                    Button("Право") {
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdMovePlayer(player.playerId, dx = 0.5f, dz = 0f))
                        }
                    }
                    Button("Вперед") {
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdMovePlayer(player.playerId, dx = 0f, dz = -0.5f))
                        }
                    }
                    Button("Назад") {
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdMovePlayer(player.playerId, dx = 0f, dz = 0.5f))
                        }
                    }
                }
                Text("Взаимодействия") { modifier.margin(top = sizes.gap) }

                Row {
                    Button("Потрогать ближайшего") {
                        modifier.margin(end = 8.dp).onClick{
                            server.trySend(CmdInteract(player.playerId))
                        }
                    }
                }

                Text(dialogue.npcId){ modifier.margin(top = sizes.gap) }

                Text(dialogue.text){ modifier.margin(bottom = sizes.smallGap) }

                if(dialogue.option.isEmpty()){
                    Text ("Нет доступных вариантов ответа"){
                        modifier.font(sizes.smallText).margin(bottom = sizes.gap)
                    }
                }else{
                    Row {
                        for (option in dialogue.option){
                            Button(option.text){
                                modifier.margin(end = 8.dp).onClick{
                                    server.trySend(
                                        CmdChooseDialogueOption(
                                            player.playerId,
                                            option.id
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                Text ("Лог: "){ modifier.margin(top = sizes.gap) }

                for (line in hud.log.use()){
                    Text (line){ modifier.font(sizes.smallText)}
                }
            }
        }
    }
}