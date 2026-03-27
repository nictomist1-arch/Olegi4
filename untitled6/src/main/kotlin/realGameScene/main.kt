package realGameScene

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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlin.random.Random

enum class QuestState{
    START,
    WAIT_HERB,
    GOOD_END,
    EVIL_END
}

enum class WorldObjectType{
    ALCHEMIST,
    HERB_SOURCE,
    CHEST
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
    val receivedHerb: Boolean,
    val sawPlayerNearSource: Boolean
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
    val gold: Int
)

fun herbCount(player: PlayerState): Int{
    return  player.inventory["herb"] ?: 0
}

fun distance2d(ax: Float, az: Float, bx: Float, bz: Float): Float{
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
                false,
                false
            ),
            null,
            "Подойди к одной из локаций",
            0
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
                false,
                false
            ),
            null,
            "Подойди к одной из локаций",
            0
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
            val sourceText = if (memory.sawPlayerNearSource) {
                "\nВижу, ты хотя бы дошел до места, где растет трава, ты ее принес?"
            } else {
                ""
            }

            val greeting =
                if (!memory.hasMet) {
                    "О привет, ты кто"
                } else {
                    "Снова ты я тебя знаю ты же ${player.playerId}"
                }
            DialogueView(
                "Алхимик",
                "$greeting \nХочешь помочь - тащи траву$sourceText",
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
                    "Недостаточно тебе надо $herbs/3 травы",
                    emptyList()
                )
            } else {
                DialogueView(
                    "Алхимик",
                    "ОООООООО это оличный стафф мужик даай сюда",
                    listOf(
                        DialogueOption("give_herb", "Отдать 3 травы")
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

sealed interface GameCommand{
    val playerId: String
}
data class CmdMovePlayer(
    override val playerId: String,
    val dx: Float,
    val dz: Float
): GameCommand

data class CmdInteract(
    override val playerId: String
): GameCommand

data class CmdChooseDialogueOption(
    override val playerId: String,
    val optionId: String
): GameCommand

data class CmdSwitchActivePlayer(
    override val playerId: String,
    val newPlayer: String
): GameCommand

data class CmdResetPlayer(
    override val playerId: String
): GameCommand

data class CmdSearchHerb(
    override val playerId: String
): GameCommand

sealed interface GameEvent{
    val playerId: String
}

data class EnteredArea(
    override val playerId: String,
    val areaId: String
): GameEvent

data class LeftArea(
    override val playerId: String,
    val areaId: String
): GameEvent

data class InteractedWithNpc(
    override val playerId: String,
    val npcId: String
): GameEvent

data class InteractedWithHerbSource(
    override val playerId: String,
    val sourceId: String
): GameEvent

data class InteractedWithChest(
    override val playerId: String,
    val chestId: String
): GameEvent

data class InventoryChanged(
    override val playerId: String,
    val itemId: String,
    val newCount: Int
): GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val newState: QuestState
): GameEvent

data class NpcMemoryChanged(
    override val playerId: String,
    val memory: NpcMemory
): GameEvent

data class ServerMessage(
    override val playerId: String,
    val text: String
): GameEvent

data class GoldChanged(
    override val playerId: String,
    val newGold: Int
): GameEvent

data class HerbSearchResult(
    override val playerId: String,
    val herbsFound: Int
): GameEvent

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
            interactRadius = 2f
        ),
        WorldObjectDef(
            id = "herb_source_2",
            type = WorldObjectType.HERB_SOURCE,
            x = 2f,
            z = -4f,
            interactRadius = 2f
        ),
        WorldObjectDef(
            id = "treasure_box_1",
            type = WorldObjectType.CHEST,
            x = -2f,
            z = -3f,
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

    // Состояние NPC для движения
    data class NpcMovementState(
        var isMoving: Boolean = true,
        var direction: Int = 1,
        var pathIndex: Int = 0,
        var isInteracting: Boolean = false
    )

    private val npcMovement = NpcMovementState()

    // Траектория движения NPC (путь туда-обратно)
    private val npcPath = listOf(
        Vec3f(5f, 0f, 5f),   // Начальная точка
        Vec3f(8f, 0f, 5f),   // Вправо
        Vec3f(8f, 0f, 8f),   // Вперед и вправо
        Vec3f(5f, 0f, 8f),   // Вперед
        Vec3f(2f, 0f, 8f),   // Влево
        Vec3f(2f, 0f, 5f),   // Назад
        Vec3f(5f, 0f, 5f)    // Возврат в начало
    )

    private var npcCurrentPos = npcPath[0]
    private var npcSpeed = 1.5f // Скорость движения NPC

    fun start(scope: CoroutineScope){
        scope.launch {
            commands.collect { cmd ->
                processCommand(cmd)
            }
        }

        // Запускаем движение NPC
        scope.launch {
            while (scope.isActive) {
                if (npcMovement.isMoving && !npcMovement.isInteracting) {
                    updateNpcMovement()
                }
                delay(16) // ~60 FPS
            }
        }
    }

    private fun updateNpcMovement() {
        val target = npcPath[npcMovement.pathIndex]
        val dx = target.x - npcCurrentPos.x
        val dz = target.z - npcCurrentPos.z
        val distance = kotlin.math.sqrt(dx * dx + dz * dz)

        if (distance < 0.1f) {
            // Достигли точки, переходим к следующей
            npcMovement.pathIndex = (npcMovement.pathIndex + 1) % npcPath.size
        } else {
            // Движемся к цели
            val step = npcSpeed * Time.deltaT
            val moveStep = minOf(step, distance)
            // Пропорциональное движение без нормализации
            val moveX = (dx / distance) * moveStep
            val moveZ = (dz / distance) * moveStep
            npcCurrentPos = Vec3f(
                npcCurrentPos.x + moveX,
                npcCurrentPos.y,
                npcCurrentPos.z + moveZ
            )
        }
    }

    fun getNpcPosition(): Vec3f = npcCurrentPos

    fun getNpcWorldObject(): WorldObjectDef {
        return worldObjects.first { it.type == WorldObjectType.ALCHEMIST }.copy(
            x = npcCurrentPos.x,
            z = npcCurrentPos.z
        )
    }

    fun stopNpc() {
        npcMovement.isMoving = false
        npcMovement.isInteracting = true
    }

    fun resumeNpc() {
        npcMovement.isMoving = true
        npcMovement.isInteracting = false
    }

    private fun setPlayer(playerId: String, data: PlayerState) {
        val map = _players.value.toMutableMap()
        map[playerId] = data
        _players.value = map.toMap()
    }

    fun getPlayer(playerId: String): PlayerState {
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
        // Для алхимика используем его текущую позицию
        val updatedObjects = worldObjects.map { obj ->
            if (obj.type == WorldObjectType.ALCHEMIST) {
                obj.copy(x = npcCurrentPos.x, z = npcCurrentPos.z)
            } else {
                obj
            }
        }

        val candidates = updatedObjects.filter { obj ->
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
                    "alchemist_1" -> "Подойти и нажми по алхимику"
                    "herb_source_1", "herb_source_2" -> "Нажми 'Поиск травы'"
                    "treasure_box_1" -> "Открой сундук"
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

            val nearestObj = nearest
            if (nearestObj != null && nearestObj.type == WorldObjectType.HERB_SOURCE) {
                val currentMemory = player.alchemistMemory
                if (!currentMemory.sawPlayerNearSource) {
                    val newMemory = currentMemory.copy(sawPlayerNearSource = true)
                    updatePlayer(playerId) { p ->
                        p.copy(alchemistMemory = newMemory)
                    }
                    _events.emit(NpcMemoryChanged(playerId, newMemory))
                }
            }
        }

        val newHint =
            when (newAreaId){
                "alchemist_1" -> "Подойти и нажми по алхимику"
                "herb_source_1", "herb_source_2" -> "Нажми 'Поиск травы'"
                "treasure_box_1" -> "Открой сундук"
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
                        // Останавливаем NPC при взаимодействии
                        stopNpc()

                        val oldMemory = player.alchemistMemory
                        val newMemory = oldMemory.copy(
                            hasMet = true,
                            timesTalked = oldMemory.timesTalked + 1
                        )

                        updatePlayer(cmd.playerId) { p ->
                            p.copy(alchemistMemory = newMemory)
                        }

                        _events.emit(InteractedWithNpc(cmd.playerId, nearest.id))
                        _events.emit(NpcMemoryChanged(cmd.playerId, newMemory))
                    }

                    WorldObjectType.CHEST -> {
                        val newGold = player.gold + 1
                        updatePlayer(cmd.playerId) { p ->
                            p.copy(gold = newGold)
                        }

                        _events.emit(InteractedWithChest(cmd.playerId, nearest.id))
                        _events.emit(GoldChanged(cmd.playerId, newGold))
                        _events.emit(ServerMessage(cmd.playerId, "Ты нашел сундук и получил 1 золото!"))
                    }

                    else -> {}
                }
            }
            is CmdSearchHerb -> {
                val player = getPlayer(cmd.playerId)
                val nearest = nearestObject(player)

                if (nearest?.type != WorldObjectType.HERB_SOURCE) {
                    _events.emit(ServerMessage(cmd.playerId, "Ты не находишься в зоне сбора травы"))
                    return
                }

                if (player.questState != QuestState.WAIT_HERB) {
                    _events.emit(ServerMessage(cmd.playerId, "Трава тебе сейчас не нужна - сначала возьми квест"))
                    return
                }

                // Шанс найти от 1 до 3 трав
                val herbsFound = Random.nextInt(1, 4)
                val oldCount = herbCount(player)
                val newCount = oldCount + herbsFound
                val newInventory = player.inventory + ("herb" to newCount)

                updatePlayer(cmd.playerId) { p ->
                    p.copy(inventory = newInventory)
                }

                _events.emit(HerbSearchResult(cmd.playerId, herbsFound))
                _events.emit(InventoryChanged(cmd.playerId, "herb", newCount))
                _events.emit(ServerMessage(cmd.playerId, "Ты нашел $herbsFound ${if(herbsFound == 1) "траву" else if(herbsFound in 2..3) "травы" else "трав"}!"))
            }
            is CmdChooseDialogueOption -> {
                val player = getPlayer(cmd.playerId)

                // Проверка, находится ли игрок в зоне алхимика
                val nearest = nearestObject(player)
                val alchemistPos = if (nearest?.type == WorldObjectType.ALCHEMIST) nearest else null

                if (alchemistPos == null) {
                    _events.emit(ServerMessage(cmd.playerId, "Ты отошел слишком далеко от Алхимика"))
                    return
                }

                when(cmd.optionId){
                    "accept_help" -> {
                        if(player.questState != QuestState.START) {
                            _events.emit(ServerMessage(cmd.playerId, "Путь помощи можно выбрать только в начале квеста"))
                            return
                        }

                        updatePlayer(cmd.playerId) { p ->
                            p.copy(questState = QuestState.WAIT_HERB)
                        }

                        _events.emit(QuestStateChanged(cmd.playerId, QuestState.WAIT_HERB))
                        _events.emit(ServerMessage(cmd.playerId, "Алхимик попросил собрать 3 травы"))
                    }
                    "give_herb" -> {
                        if (player.questState != QuestState.WAIT_HERB) {
                            _events.emit(ServerMessage(cmd.playerId, "Сейчас нельзя сдать траву"))
                            return
                        }

                        val herbs = herbCount(player)

                        if (herbs < 3){
                            _events.emit(ServerMessage(cmd.playerId, "Недостаточно травы"))
                            return
                        }

                        val newCount = herbs - 3
                        val newInventory = if(newCount <= 0) player.inventory - "herb" else player.inventory + ("herb" to newCount)

                        val newMemory = player.alchemistMemory.copy(
                            receivedHerb = true
                        )

                        updatePlayer(cmd.playerId) { p ->
                            p.copy(
                                inventory = newInventory,
                                gold = p.gold + 5,
                                questState = QuestState.GOOD_END,
                                alchemistMemory = newMemory
                            )
                        }

                        _events.emit(InventoryChanged(cmd.playerId, "herb", newCount))
                        _events.emit(NpcMemoryChanged(cmd.playerId, newMemory))
                        _events.emit(QuestStateChanged(cmd.playerId, QuestState.GOOD_END))
                        _events.emit(ServerMessage(cmd.playerId, "Алхимик получил траву и выдал тебе золото"))
                    }
                    "threat" -> {
                        updatePlayer(cmd.playerId) { p ->
                            p.copy(questState = QuestState.EVIL_END)
                        }
                        _events.emit(QuestStateChanged(cmd.playerId, QuestState.EVIL_END))
                        _events.emit(ServerMessage(cmd.playerId, "Алхимик разозлился и отказался с тобой разговаривать"))
                    }
                    else -> {
                        _events.emit(ServerMessage(cmd.playerId, "Неизвестный формат диалога "))
                    }
                }

                // Возобновляем движение NPC после завершения диалога
                if (cmd.optionId in listOf("accept_help", "give_herb", "threat")) {
                    resumeNpc()
                }
            }

            is CmdSwitchActivePlayer -> {

            }

            is CmdResetPlayer -> {
                updatePlayer(cmd.playerId) { _ -> initialPlayerState(cmd.playerId)}
                _events.emit(ServerMessage(cmd.playerId, "Игрок сброшен к начальному состоянию"))
            }
        }
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

fun formatInventory(player: PlayerState) : String{
    return if (player.inventory.isEmpty()){
        "Inventory: (пусто)"
    }else{
        "Inventory: " + player.inventory.entries.joinToString { "${it.key} x${it.value}"}
    }
}

fun currentObjective(player: PlayerState) : String{
    val herbs = herbCount(player)

    return when(player.questState){
        QuestState.START -> "Подойди к алхимику и начни разговор"
        QuestState.WAIT_HERB -> {
            if (herbs < 3) "Собери 3 травы. Сейчас $herbs / 3"
            else "Вернись к алхимику и отдай 3 Травы"
        }

        QuestState.GOOD_END -> "Квест завершен по хорошей ветке"
        QuestState.EVIL_END -> "Квест завершен по плохой ветке"
    }
}

fun currentZoneText(player: PlayerState): String{
    return when(player.currentAreaId){
        "alchemist_1" -> "Зона: Алхимик"
        "herb_source_1", "herb_source_2" -> "Зона Источника травы"
        "treasure_box_1" -> "Зона: Сундук с сокровищами"
        else -> "Без зоны :("
    }
}

fun formatMemory(memory: NpcMemory): String{
    return "Встретился = ${memory.hasMet}, Сколько раз поговорил = ${memory.timesTalked}, Отдал траву = ${memory.receivedHerb}, Видел источник = ${memory.sawPlayerNearSource}"
}

fun eventToText(e: GameEvent): String{
    return when(e){
        is EnteredArea -> "EnteredArea ${e.areaId}"
        is LeftArea -> "LeftArea ${e.areaId}"
        is InteractedWithNpc -> "InteractedWithNpc ${e.npcId}"
        is InteractedWithHerbSource -> "InteractedWithHerbSource ${e.sourceId}"
        is InteractedWithChest -> "InteractedWithChest ${e.chestId}"
        is InventoryChanged -> "InventoryChanged ${e.itemId} -> ${e.newCount}"
        is QuestStateChanged -> "QuestStateChanged ${e.newState}"
        is NpcMemoryChanged -> "NpcMemoryChanged ${formatMemory(e.memory)}"
        is ServerMessage -> "Server: ${e.text}"
        is GoldChanged -> "GoldChanged: ${e.newGold}"
        is HerbSearchResult -> "HerbSearchResult: Найдено ${e.herbsFound} трав"
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
                metallic { 0f }
                roughness { 0.25f }
            }
        }

        val alchemistNode = addColorMesh {
            generate {
                cube {
                }
            }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic { 0f }
                roughness { 0.25f }
            }
        }

        val herbNode1 = addColorMesh {
            generate {
                cube {
                }
            }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic { 0f }
                roughness { 0.25f }
            }
        }

        val herbNode2 = addColorMesh {
            generate {
                cube {
                }
            }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic { 0f }
                roughness { 0.25f }
            }
        }

        val chestNode = addColorMesh {
            generate {
                cube {
                }
            }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic { 0f }
                roughness { 0.25f }
            }
        }

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f,-1f,-1f))
            setColor(Color.WHITE, 6f)
        }

        server.start(coroutineScope)

        var lastRenderedX = 0f
        var lastRenderedZ = 0f

        playerNode.onUpdate {
            val activeId = hud.activePlayerIdFlow.value
            val player = server.getPlayer(activeId)

            val dx = player.posX - lastRenderedX
            val dz = player.posZ - lastRenderedZ

            playerNode.transform.translate(dx, 0f, dz)

            lastRenderedX = player.posX
            lastRenderedZ = player.posZ
        }

        alchemistNode.onUpdate {
            val npcPos = server.getNpcPosition()
            transform.rotate(20f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }

        herbNode1.onUpdate {
            transform.rotate(20f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }
        herbNode2.onUpdate {
            transform.rotate(20f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }
        chestNode.onUpdate {
            transform.rotate(20f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }
    }

    addScene {
        setupUiScene(clearColor)

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
                server.events.filter { it.playerId == pid }
            }
            .map { event ->
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
                    modifier.font(sizes.smallText).margin(bottom = sizes.smallGap)
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

                Text("Движение в мире: ") { modifier.margin(top = sizes.gap) }

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

                Text("Взаимодействия: ") { modifier.margin(top = sizes.gap) }

                Row {
                    Button("Потрогать ближайшего") {
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdInteract(player.playerId))
                        }
                    }

                    // Кнопка поиска травы
                    if (player.currentAreaId?.startsWith("herb_source") == true) {
                        Button("🔍 Поиск травы") {
                            modifier.margin(end = 8.dp).onClick {
                                server.trySend(CmdSearchHerb(player.playerId))
                            }
                        }
                    }
                }

                Text(dialogue.npcId) { modifier.margin(top = sizes.gap) }

                Text(dialogue.text) { modifier.margin(bottom = sizes.smallGap) }

                if (dialogue.options.isEmpty()) {
                    Text("Нет доступных вариантов ответа") {
                        modifier.font(sizes.smallText).margin(bottom = sizes.gap)
                    }
                } else {
                    Row {
                        for (option in dialogue.options) {
                            Button(option.text) {
                                modifier.margin(end = 8.dp).onClick {
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
                Text("Лог: ") { modifier.margin(top = sizes.gap) }

                for (line in hud.log.use()) {
                    Text(line) { modifier.font(sizes.smallText) }
                }
            }
        }
    }
}