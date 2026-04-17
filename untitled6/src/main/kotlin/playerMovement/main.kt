
// 1.1 - b
// 1.2 - c
// 1.3 - d

package playerMovement

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.scene.*
import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.Time
import de.fabmax.kool.pipeline.ClearColorLoad
import de.fabmax.kool.modules.ui2.*

import kotlinx.coroutines.launch
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
import kotlinx.coroutines.flow.filterIsInstance
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent

import kotlin.math.abs

enum class QuestState {
    START,
    WAIT_HERB,
    GOOD_END,
    EVIL_END
}

enum class WorldObjectType {
    ALCHEMIST,
    HERB_SOURCE,
    CHEST,
    DOOR
}

data class WorldObjectDef(
    val id: String,
    val type: WorldObjectType,
    val worldX: Float,
    val worldZ: Float,
    val interactRadius: Float
)

data class ObstacleDef(
    val centerX: Float,
    val centerZ: Float,
    val halfSize: Float
)

data class NpcMemory(
    val hasMet: Boolean = false,
    val timesTalked: Int = 0,
    val receivedHerb: Boolean = false
)

data class PlayerState(
    val playerId: String,
    val worldX: Float,
    val worldZ: Float,
    val yawDeg: Float,
    val moveSpeed: Float,
    val questState: QuestState,
    val inventory: Map<String, Int>,
    val gold: Int,
    val alchemistMemory: NpcMemory,
    val chestLooted: Boolean,
    val doorOpened: Boolean,
    val currentFocusId: String?,
    val hintText: String,
    val pinnedQuestEnabled: Boolean,
    val pinnedTargetId: String?
)


fun herbCount(playerState: PlayerState): Int {
    return playerState.inventory["herb"] ?: 0
}

fun normalizeOrZero(x: Float, z: Float): Pair<Float, Float> {
    val len = sqrt(x * x + z * z)
    return if (len <= 0.0001f) {
        0f to 0f
    } else {
        (x / len) to (z / len)
    }
}

fun initialPlayerState(playerId: String): PlayerState {
    return if (playerId == "Stas") {
        PlayerState(
            "Stas",
            0f,
            0f,
            0f,
            3.2f,
            QuestState.START,
            emptyMap(),
            2,
            NpcMemory(
                false,
                0,
                false
            ),
            false,
            false,
            null,
            "Подойди к одной из локаций",
            true,
            "alchemist"
        )
    } else {
        PlayerState(
            "Oleg",
            0f,
            0f,
            0f,
            3.2f,
            QuestState.START,
            emptyMap(),
            2,
            NpcMemory(
                false,
                0,
                false
            ),
            false,
            false,
            null,
            "Подойди к одной из локаций",
            true,
            "alchemist"
        )
    }
}

data class DialogueOption(
    val actionId: String,
    val text: String
)

data class DialogueView(
    val speaker: String,
    val message: String,
    val options: List<DialogueOption>
)

fun buildAlchemistDialogue(player: PlayerState): DialogueView {
    val herbs = herbCount(player)
    val memory = player.alchemistMemory

    return when (player.questState) {
        QuestState.START -> {
            val greeting = if (!memory.hasMet) {
                "О привет"
            } else {
                "Снова ты... я тебя знаю, ты ${player.playerId}"
            }
            DialogueView(
                "Алхимик",
                "$greeting\nХочешь помочь — принеси 3 травы.",
                listOf(
                    DialogueOption("accept_help", "Я принесу траву"),
                    DialogueOption("threat", "Травы не будет, гони товар")
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
                    "Отлично, ты собрал все травы. Передавай.",
                    listOf(
                        DialogueOption("give_herb", "Отдать 3 травы")
                    )
                )
            }
        }

        QuestState.GOOD_END -> {
            val text = if (memory.receivedHerb) {
                "Спасибо за помощь! Надеюсь, ты нашел сундук с наградой."
            } else {
                "Ты завершил квест, но NPC все забыл..."
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
                "Ты проиграл, Бэтмен",
                emptyList()
            )
        }
    }
}


sealed interface GameEvent {
    val playerId: String
}



data class PlayerMoved(
    override val playerId: String,
    val newWorldX: Float,
    val newWorldZ: Float
): GameEvent

data class PlayerBlocked(
    override val playerId: String,
    val blockedWorldX: Float,
    val blockedWorldZ: Float
): GameEvent

data class FocusChanged(
    override val playerId: String,
    val newFocusId: String?
): GameEvent

data class InteractedWithNpc(
    override val playerId: String,
    val npcId: String
): GameEvent

data class InteractedWithChest(
    override val playerId: String,
    val chestId: String
): GameEvent

data class InteractedWithHerbSource(
    override val playerId: String,
    val sourceId: String
): GameEvent

data class InteractedWithDoor(
    override val playerId: String,
    val doorId: String
): GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val newState: QuestState
): GameEvent

data class VictoryAchieved(
    override val playerId: String,
    val npcId: String
): GameEvent

data class NpcMemoryChanged(
    override val playerId: String,
    val memory: NpcMemory
): GameEvent

data class ServerMessage(
    override val playerId: String,
    val text: String
): GameEvent

sealed interface GameCommand {
    val playerId: String
}

data class CmdMoveAxis(
    override val playerId: String,
    val moveX: Float = 0f,
    val moveZ: Float = 0f,
    val turnDelta: Float = 0f
): GameCommand

data class CmdResetPlayer(
    override val playerId: String
): GameCommand

data class CmdTogglePinnedQuest(
    override val playerId: String
): GameCommand

data class CmdInteract(
    override val playerId: String
): GameCommand

data class CmdSelectDialogueOption(
    override val playerId: String,
    val actionId: String
): GameCommand

class GameServer {
    private val staticObstacles = listOf(
        ObstacleDef(-1f, 1f, 0.45f),
        ObstacleDef(0f, 1f, 0.45f),
        ObstacleDef(1f, 1f, 0.45f),
        ObstacleDef(1f, 0f, 0.45f)
    )

    private val doorObstacle = ObstacleDef(0f, -3f, 0.45f)

    val worldObjects = listOf(
        WorldObjectDef(
            "alchemist",
            WorldObjectType.ALCHEMIST,
            -3f,
            0f,
            1.4f
        ),
        WorldObjectDef(
            "herb_source",
            WorldObjectType.HERB_SOURCE,
            3f,
            0f,
            1.4f
        ),
        WorldObjectDef(
            "reward_chest",
            WorldObjectType.CHEST,
            0f,
            3f,
            1.4f
        ),
        WorldObjectDef(
            "door",
            WorldObjectType.DOOR,
            0f,
            -3f,
            1.4f
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
        scope.launch {
            commands.collect { cmd ->
                processCommand(cmd)
            }
        }
        scope.launch {
            events
                .filterIsInstance<VictoryAchieved>()
                .collect { event ->
                    _events.emit(
                        ServerMessage(
                            event.playerId,
                            "Победа! Квест успешно сдан NPC ${event.npcId}"
                        )
                    )
                }
        }
    }

    private fun updatePlayer(playerId: String, change: (PlayerState) -> PlayerState) {
        val oldMap = _players.value
        val oldPlayer = oldMap[playerId] ?: return
        val newPlayer = change(oldPlayer)

        val newMap = oldMap.toMutableMap()
        newMap[playerId] = newPlayer
        _players.value = newMap.toMap()
    }

    private fun isPointInsideObstacle(x: Float, z: Float, obstacle: ObstacleDef, playerRadius: Float): Boolean {
        return abs(x - obstacle.centerX) <= (obstacle.halfSize + playerRadius) &&
                abs(z - obstacle.centerZ) <= (obstacle.halfSize + playerRadius)
    }

    private fun isBlockedByAnyObstacle(x: Float, z: Float, includeDoor: Boolean): Boolean {
        if (staticObstacles.any { isPointInsideObstacle(x, z, it, 0.4f) }) {
            return true
        }
        return includeDoor && isPointInsideObstacle(x, z, doorObstacle, 0.4f)
    }

    private fun nearestObject(player: PlayerState): WorldObjectDef? {
        val candidates = worldObjects.filter { obj ->
            val dx = obj.worldX - player.worldX
            val dz = obj.worldZ - player.worldZ
            sqrt(dx * dx + dz * dz) <= obj.interactRadius
        }
        return candidates.minByOrNull { obj ->
            val dx = obj.worldX - player.worldX
            val dz = obj.worldZ - player.worldZ
            sqrt(dx * dx + dz * dz)
        }
    }

    private fun hintForObject(obj: WorldObjectDef): String {
        return when (obj.type) {
            WorldObjectType.ALCHEMIST -> "Нажми взаимодействие: поговорить с алхимиком"
            WorldObjectType.HERB_SOURCE -> "Нажми взаимодействие: собрать траву"
            WorldObjectType.CHEST -> "Нажми взаимодействие: открыть сундук"
            WorldObjectType.DOOR -> "Нажми взаимодействие: открыть дверь"
        }
    }

    private suspend fun refreshPlayerFocus(playerId: String) {
        val player = _players.value[playerId] ?: return
        val nearest = nearestObject(player)
        val newFocus = nearest?.id
        if (newFocus != player.currentFocusId) {
            updatePlayer(playerId) { p ->
                p.copy(
                    currentFocusId = newFocus,
                    hintText = nearest?.let { hintForObject(it) } ?: "Подойди к одной из локаций"
                )
            }
            _events.emit(FocusChanged(playerId, newFocus))
        }
    }

    private suspend fun processCommand(cmd: GameCommand) {
        when (cmd) {
            is CmdMoveAxis -> {
                val oldPlayer = _players.value[cmd.playerId] ?: return
                updatePlayer(cmd.playerId) { player ->
                    val targetYaw = player.yawDeg + cmd.turnDelta
                    val (dirX, dirZ) = normalizeOrZero(cmd.moveX, cmd.moveZ)
                    val nextX = player.worldX + dirX * player.moveSpeed
                    val nextZ = player.worldZ + dirZ * player.moveSpeed
                    val blocked = isBlockedByAnyObstacle(nextX, nextZ, includeDoor = !player.doorOpened)

                    if (blocked) {
                        _events.tryEmit(PlayerBlocked(cmd.playerId, nextX, nextZ))
                        player.copy(yawDeg = targetYaw)
                    } else {
                        _events.tryEmit(PlayerMoved(cmd.playerId, nextX, nextZ))
                        player.copy(worldX = nextX, worldZ = nextZ, yawDeg = targetYaw)
                    }
                }
                if (oldPlayer.currentFocusId != _players.value[cmd.playerId]?.currentFocusId) {
                    refreshPlayerFocus(cmd.playerId)
                } else {
                    refreshPlayerFocus(cmd.playerId)
                }
            }

            is CmdResetPlayer -> {
                updatePlayer(cmd.playerId) { initialPlayerState(cmd.playerId) }
                refreshPlayerFocus(cmd.playerId)
            }

            is CmdTogglePinnedQuest -> {
                updatePlayer(cmd.playerId) { player ->
                    player.copy(pinnedQuestEnabled = !player.pinnedQuestEnabled)
                }
            }

            is CmdInteract,
            is CmdSelectDialogueOption -> {
                if (cmd is CmdInteract) {
                    val player = _players.value[cmd.playerId] ?: return
                    val obj = nearestObject(player)
                    if (obj == null) {
                        _events.emit(ServerMessage(cmd.playerId, "Рядом нет объектов для взаимодействия"))
                    } else {
                        when (obj.type) {
                            WorldObjectType.ALCHEMIST -> _events.emit(InteractedWithNpc(cmd.playerId, obj.id))
                            WorldObjectType.HERB_SOURCE -> {
                                val old = herbCount(player)
                                val updated = player.inventory + ("herb" to (old + 1))
                                updatePlayer(cmd.playerId) { p -> p.copy(inventory = updated, hintText = "Ты собрал траву: ${old + 1}/3") }
                                _events.emit(InteractedWithHerbSource(cmd.playerId, obj.id))
                            }
                            WorldObjectType.CHEST -> {
                                if (!player.chestLooted) {
                                    updatePlayer(cmd.playerId) { p ->
                                        p.copy(chestLooted = true, gold = p.gold + 50, hintText = "Ты открыл сундук и получил 50 золота")
                                    }
                                }
                                _events.emit(InteractedWithChest(cmd.playerId, obj.id))
                            }
                            WorldObjectType.DOOR -> {
                                updatePlayer(cmd.playerId) { p -> p.copy(doorOpened = true, hintText = "Дверь открыта") }
                                _events.emit(InteractedWithDoor(cmd.playerId, obj.id))
                            }
                        }
                    }
                    refreshPlayerFocus(cmd.playerId)
                }
                if (cmd is CmdSelectDialogueOption) {
                    val player = _players.value[cmd.playerId] ?: return
                    when (cmd.actionId) {
                        "accept_help" -> {
                            if (player.questState == QuestState.START) {
                                updatePlayer(cmd.playerId) { state ->
                                    state.copy(
                                        questState = QuestState.WAIT_HERB,
                                        hintText = "Квест начат: собери 3 травы и вернись к алхимику."
                                    )
                                }
                                _events.emit(QuestStateChanged(cmd.playerId, QuestState.WAIT_HERB))
                                _events.emit(ServerMessage(cmd.playerId, "Алхимик: Жду 3 травы."))
                            }
                        }

                        "threat" -> {
                            if (player.questState == QuestState.START || player.questState == QuestState.WAIT_HERB) {
                                updatePlayer(cmd.playerId) { state ->
                                    state.copy(
                                        questState = QuestState.EVIL_END,
                                        hintText = "Ты выбрал путь угроз. Плохая концовка."
                                    )
                                }
                                _events.emit(QuestStateChanged(cmd.playerId, QuestState.EVIL_END))
                                _events.emit(ServerMessage(cmd.playerId, "Алхимик: Уходи, пока цел."))
                            }
                        }

                        "give_herb" -> {
                            if (player.questState == QuestState.WAIT_HERB) {
                                val herbs = herbCount(player)
                                if (herbs >= 3) {
                                    val updatedInventory = player.inventory.toMutableMap().apply {
                                        this["herb"] = herbs - 3
                                        if ((this["herb"] ?: 0) <= 0) {
                                            remove("herb")
                                        }
                                    }
                                    val updatedMemory = player.alchemistMemory.copy(receivedHerb = true)

                                    updatePlayer(cmd.playerId) { state ->
                                        state.copy(
                                            inventory = updatedInventory,
                                            questState = QuestState.GOOD_END,
                                            alchemistMemory = updatedMemory,
                                            hintText = "Квест сдан. Победа!"
                                        )
                                    }
                                    _events.emit(QuestStateChanged(cmd.playerId, QuestState.GOOD_END))
                                    _events.emit(NpcMemoryChanged(cmd.playerId, updatedMemory))
                                    _events.emit(VictoryAchieved(cmd.playerId, npcId = "alchemist"))
                                } else {
                                    _events.emit(ServerMessage(cmd.playerId, "Недостаточно трав: нужно 3."))
                                }
                            }
                        }
                    }
                    refreshPlayerFocus(cmd.playerId)
                }
            }
        }
    }
}

class VictoryOverlayController {
    val winnerPlayerId = mutableStateOf<String?>(null)
    val animPhase = mutableStateOf(0f)

    fun bind(server: GameServer, scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            server.events
                .filterIsInstance<VictoryAchieved>()
                .collect { event ->
                    winnerPlayerId.value = event.playerId
                }
        }
        scope.launch {
            while (true) {
                if (winnerPlayerId.value != null) {
                    animPhase.value += 0.08f
                }
                delay(16)
            }
        }
    }

    fun clear() {
        winnerPlayerId.value = null
        animPhase.value = 0f
    }
}

fun Scene.addVictoryPanelSurface(overlay: VictoryOverlayController) {
    addPanelSurface {
        val winner = overlay.winnerPlayerId.use()
        if (winner == null) return@addPanelSurface
        val phase = overlay.animPhase.use()
        val pulse = (sin(phase.toDouble()) * 0.5 + 0.5).toFloat()
        val alpha = 0.65f + pulse * 0.28f
        val titleSize = 34f + pulse * 16f
        val sparkText = if (pulse > 0.5f) "✦ ✦ ✦" else "✧ ✧ ✧"

        modifier
            .align(AlignmentX.Center, AlignmentY.Center)
            .width(5000.dp)
            .height(5000.dp)
            .background(RoundRectBackground(Color(0f, 0f, 0f, alpha), 0.dp))

        Column {
            modifier.align(AlignmentX.Center, AlignmentY.Center)
            Text(sparkText) {
                modifier.margin(bottom = 8.dp)
            }
            Text("ПОБЕДА!") {
                modifier.margin(bottom = (6f + pulse * 8f).dp)
            }
            Text(sparkText) {
                modifier.margin(bottom = 10.dp)
            }
            Text("Игрок: $winner") { }
        }
    }
}

class GameSoundController {
    private val combat = "sounds/rpg-game-combat-sound.wav"
    private val savoryFx = "sounds/game-effects-sonorous-savory.wav"
    private val bgScene = "sounds/game-scene-background-sound-material.wav"
    private val jumpFx = "sounds/game-scene-jumping-floor-sound-effect-material.wav"
    private val upgradeFx = "sounds/atmospheric-game-upgrade-material-sound-effects.wav"
    private val categoryFx = "sounds/category-selection-sound.wav"
    private val activeHitFx = "sounds/game-effects-active-bright-hit.wav"
    private val selectingFx = "sounds/selecting-the-desired-action.wav"
    private val victoryFx = "sounds/the-sound-of-victory-in-the-game-level.wav"
    private val bgEnvironment = "sounds/game-environment-audio-power-supply-audio-material.wav"
    private val lostMoneyFx = "sounds/lost-money-on-the-game-account.wav"

    private var bgClipMain: Clip? = null
    private var bgClipAmbient: Clip? = null
    private var lastStepSoundMs: Long = 0L

    fun bind(server: GameServer, scope: kotlinx.coroutines.CoroutineScope) {
        bgClipMain = playLoop(bgScene)
        bgClipAmbient = playLoop(bgEnvironment)

        scope.launch {
            server.commands.collect { cmd ->
                when (cmd) {
                    is CmdMoveAxis -> {
                        val now = System.currentTimeMillis()
                        if ((cmd.moveX != 0f || cmd.moveZ != 0f) && now - lastStepSoundMs > 230L) {
                            lastStepSoundMs = now
                            playOneShot(jumpFx)
                        }
                    }
                    is CmdInteract -> playOneShot(selectingFx)
                    is CmdTogglePinnedQuest -> playOneShot(categoryFx)
                    is CmdResetPlayer -> playOneShot(lostMoneyFx)
                    is CmdSelectDialogueOption -> playOneShot(savoryFx)
                }
            }
        }

        scope.launch {
            server.events.collect { event ->
                when (event) {
                    is InteractedWithNpc -> playOneShot(combat)
                    is InteractedWithHerbSource -> playOneShot(activeHitFx)
                    is InteractedWithChest -> playOneShot(upgradeFx)
                    is InteractedWithDoor -> playOneShot(categoryFx)
                    is VictoryAchieved -> {
                        stopBackground()
                        playOneShot(victoryFx)
                    }
                    is QuestStateChanged -> if (event.newState == QuestState.GOOD_END) playOneShot(victoryFx)
                    is ServerMessage -> {
                        if (event.text.contains("Победа", ignoreCase = true)) {
                            playOneShot(victoryFx)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    fun stopBackground() {
        bgClipMain?.stop()
        bgClipMain?.close()
        bgClipMain = null
        bgClipAmbient?.stop()
        bgClipAmbient?.close()
        bgClipAmbient = null
    }

    private fun playLoop(path: String): Clip? {
        return try {
            val file = File(path)
            if (!file.exists()) return null
            val stream = AudioSystem.getAudioInputStream(file)
            val clip = AudioSystem.getClip()
            clip.open(stream)
            clip.loop(Clip.LOOP_CONTINUOUSLY)
            clip.start()
            clip
        } catch (_: Exception) {
            null
        }
    }

    private fun playOneShot(path: String) {
        try {
            val file = File(path)
            if (!file.exists()) return
            val stream = AudioSystem.getAudioInputStream(file)
            val clip = AudioSystem.getClip()
            clip.open(stream)
            clip.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP) {
                    clip.close()
                }
            }
            clip.start()
        } catch (_: Exception) {
        }
    }
}

class PlayerMovementHudState {
    val activePlayerIdFlow = MutableStateFlow("Oleg")
    val activePlayerIdUi = mutableStateOf("Oleg")
    val playerSnapShot = mutableStateOf(initialPlayerState("Oleg"))
    val log = mutableStateOf<List<String>>(emptyList())
}

fun playerMovementHudLog(hud: PlayerMovementHudState, line: String) {
    hud.log.value = (hud.log.value + line).takeLast(25)
}

fun playerMovementEventToText(e: GameEvent): String {
    return when (e) {
        is PlayerMoved -> "Игрок переместился на (${e.newWorldX}, ${e.newWorldZ})"
        is PlayerBlocked -> "Игрок уперся в препятствие (${e.blockedWorldX}, ${e.blockedWorldZ})"
        is FocusChanged -> "Фокус изменен: ${e.newFocusId ?: "нет"}"
        is InteractedWithNpc -> "Разговор с NPC: ${e.npcId}"
        is InteractedWithChest -> "Открыт сундук: ${e.chestId}"
        is InteractedWithHerbSource -> "Собрана трава: ${e.sourceId}"
        is InteractedWithDoor -> "Открыта дверь: ${e.doorId}"
        is QuestStateChanged -> "Состояние квеста: ${e.newState}"
        is NpcMemoryChanged -> "Память NPC обновлена"
        is VictoryAchieved -> "ПОБЕДА! NPC: ${e.npcId}"
        is ServerMessage -> e.text
    }
}

fun main() = KoolApplication {
    val hud = PlayerMovementHudState()
    val server = GameServer()
    val victoryOverlay = VictoryOverlayController()
    val sounds = GameSoundController()

    addScene {
        defaultOrbitCamera()

        val playerNode = addColorMesh {
            generate { cube { colored() } }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }

        val alchemistNode = addColorMesh {
            generate { cube { colored() } }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }
        alchemistNode.transform.translate(-3f, 0f, 0f)

        val herbNode = addColorMesh {
            generate { cube { colored() } }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }
        herbNode.transform.translate(3f, 0f, 0f)

        val chestNode = addColorMesh {
            generate { cube { colored() } }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0.5f)
                roughness(0.3f)
            }
        }
        chestNode.transform.translate(0f, 0f, 3f)

        val doorNode = addColorMesh {
            generate { cube { colored() } }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0.1f)
                roughness(0.4f)
            }
        }
        doorNode.transform.translate(0f, 0f, -3f)

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, -1f))
            setColor(Color.WHITE, 5f)
        }

        server.start(coroutineScope)
        victoryOverlay.bind(server, coroutineScope)
        sounds.bind(server, coroutineScope)

        var lastRenderedX = 0f
        var lastRenderedZ = 0f
        playerNode.onUpdate {
            val activeId = hud.activePlayerIdFlow.value
            val player = server.players.value[activeId] ?: return@onUpdate
            val dx = player.worldX - lastRenderedX
            val dz = player.worldZ - lastRenderedZ
            transform.translate(dx, 0f, dz)
            lastRenderedX = player.worldX
            lastRenderedZ = player.worldZ
        }

        herbNode.onUpdate {
            transform.rotate(25f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }
        chestNode.onUpdate {
            transform.rotate(20f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }
        doorNode.onUpdate {
            if ((hud.playerSnapShot.value.doorOpened)) {
                transform.rotate(30f.deg * Time.deltaT, Vec3f.Y_AXIS)
            }
        }
    }

    addScene {
        setupUiScene(ClearColorLoad)

        hud.activePlayerIdFlow
            .flatMapLatest { pid -> server.players.map { it[pid] ?: initialPlayerState(pid) } }
            .onEach { hud.playerSnapShot.value = it }
            .launchIn(coroutineScope)

        hud.activePlayerIdFlow
            .flatMapLatest { pid -> server.events.filter { it.playerId == pid } }
            .map { playerMovementEventToText(it) }
            .onEach { playerMovementHudLog(hud, "[${hud.activePlayerIdUi.value}] $it") }
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

                Text("Игрок: ${hud.activePlayerIdUi.use()}") { modifier.margin(bottom = sizes.gap) }
                Text("Позиция: x=${"%d".format(player.worldX)} z=${"%d".format(player.worldZ)}") { }
                Text("Квест: ${player.questState}") { modifier.font(sizes.smallText) }
                Text("Травы: ${herbCount(player)}") { modifier.font(sizes.smallText) }
                Text("Золото: ${player.gold}") { modifier.font(sizes.smallText) }
                Text("Подсказка: ${player.hintText}") { modifier.font(sizes.smallText) }

                Row {
                    Button("Сменить игрока") {
                        modifier.margin(end = 8.dp).onClick {
                            val newId = if (hud.activePlayerIdUi.value == "Oleg") "Stas" else "Oleg"
                            hud.activePlayerIdUi.value = newId
                            hud.activePlayerIdFlow.value = newId
                        }
                    }
                    Button("Сбросить") {
                        modifier.onClick { server.trySend(CmdResetPlayer(player.playerId)) }
                    }
                }

                Text("Движение") { modifier.margin(top = sizes.gap) }
                Row {
                    Button("Лево") { modifier.margin(end = 8.dp).onClick { server.trySend(CmdMoveAxis(player.playerId, moveX = -1f)) } }
                    Button("Право") { modifier.margin(end = 8.dp).onClick { server.trySend(CmdMoveAxis(player.playerId, moveX = 1f)) } }
                    Button("Вперед") { modifier.margin(end = 8.dp).onClick { server.trySend(CmdMoveAxis(player.playerId, moveZ = -1f)) } }
                    Button("Назад") { modifier.onClick { server.trySend(CmdMoveAxis(player.playerId, moveZ = 1f)) } }
                }

                Text("Взаимодействия") { modifier.margin(top = sizes.gap) }
                Row {
                    Button("Потрогать ближайшего") {
                        modifier.margin(end = 8.dp).onClick { server.trySend(CmdInteract(player.playerId)) }
                    }
                    Button("Квест-пин") {
                        modifier.onClick { server.trySend(CmdTogglePinnedQuest(player.playerId)) }
                    }
                }

                Text(dialogue.speaker) { modifier.margin(top = sizes.gap) }
                Text(dialogue.message) { modifier.margin(bottom = sizes.smallGap) }

                if (dialogue.options.isEmpty()) {
                    Text("Нет доступных вариантов ответа") {
                        modifier.font(sizes.smallText).margin(bottom = sizes.gap)
                    }
                } else {
                    Row {
                        for (option in dialogue.options) {
                            Button(option.text) {
                                modifier.margin(end = 8.dp).onClick {
                                    server.trySend(CmdSelectDialogueOption(player.playerId, option.actionId))
                                }
                            }
                        }
                    }
                }

                Text("Лог:") { modifier.margin(top = sizes.gap) }
                for (line in hud.log.use()) {
                    Text(line) { modifier.font(sizes.smallText) }
                }
            }
        }

        addVictoryPanelSurface(victoryOverlay)
    }
}
