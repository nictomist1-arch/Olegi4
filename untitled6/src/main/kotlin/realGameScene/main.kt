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
    HERB_SOURCE,
    CHEST  //
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
    val sawPlayerNearSource: Boolean  //
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
                false,
                false  //
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
                false  //
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
            //
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
        ),
        WorldObjectDef(  //
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

    fun start(scope: kotlinx.coroutines.CoroutineScope){
        scope.launch {
            commands.collect { cmd ->
                processCommand(cmd)
            }
        }
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
                    "alchemist_1" -> "Подойти и нажми по алхимику"
                    "herb_source_1", "herb_source_2" -> "Собери траву"
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

            // Если игрок зашел в зону источника травы, обновляем память NPC
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
                "herb_source_1", "herb_source_2" -> "Собери траву"
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

                    WorldObjectType.HERB_SOURCE -> {
                        if (player.questState != QuestState.WAIT_HERB){
                            _events.emit(ServerMessage(cmd.playerId, "Трава тебе сейчас не нужна - сначала возьми квест"))
                            return
                        }

                        val oldCount = herbCount(player)
                        val newCount = oldCount + 1
                        val newInventory = player.inventory + ("herb" to newCount)

                        updatePlayer(cmd.playerId) { p ->
                            p.copy(inventory = newInventory)
                        }

                        _events.emit(InteractedWithHerbSource(cmd.playerId, nearest.id))
                        _events.emit(InventoryChanged(cmd.playerId, "herb", newCount))
                    }

                    WorldObjectType.CHEST -> {  //
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
            is CmdChooseDialogueOption -> {
                val player = getPlayer(cmd.playerId)

                //
                if (player.currentAreaId != "alchemist_1"){
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
                        _events.emit(ServerMessage(cmd.playerId, "Алхимик получил траву и выдал тбе золото"))
                    }

                    else -> {
                        _events.emit(ServerMessage(cmd.playerId, "Неизвестный формат диалога "))
                    }
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
        QuestState.START -> "Подойди к алхимику и начни разгоор"
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
    return "Втретился = ${memory.hasMet}, Сколько раз поговорил = ${memory.timesTalked}, Отдал траву = ${memory.receivedHerb}, Видел источник = ${memory.sawPlayerNearSource}"
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
        is NpcMemoryChanged -> "NpcMemoryChanged Встретился = ${e.memory.hasMet}, Сколько раз поговорил = ${e.memory.timesTalked}, Отдал траву = ${e.memory.receivedHerb}, Видел источник = ${e.memory.sawPlayerNearSource}"
        is ServerMessage -> "Server: ${e.text}"
        is GoldChanged -> "GoldChanged: ${e.newGold}"
    }
}

fun main() = KoolApplication {
    val hud = HudState()
    val server = GameServer()

    addScene {
        defaultOrbitCamera()

        val playerNode = addColorMesh {
            generate {
                cube{
                    colored()
                }
            }
            shader = KslPbrShader{
                color { vertexColor() }
                metallic { 0f }
                roughness { 0.25f }
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
                metallic { 0f }
                roughness { 0.25f }
            }
        }

        alchemistNode.transform.translate(5f,0f,5f)  //

        val herbNode1 = addColorMesh {
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

        herbNode1.transform.translate(-3f,0f,4f)  //

        val herbNode2 = addColorMesh {
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

        herbNode2.transform.translate(2f,0f,-4f)  //

        val chestNode = addColorMesh {  //
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

        chestNode.transform.translate(-2f,0f,-3f)  //

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f,-1f,-1f))
            setColor(Color.WHITE, 6f)
        }

        server.start(coroutineScope)

        var lastRenderedX = 0f
        var lastRenderedZ = 0f

        playerNode.onUpdate{
            val activeId = hud.activePlayerIdFlow.value
            val player = server.getPlayer(activeId)

            val dx = player.posX - lastRenderedX
            val dz = player.posZ - lastRenderedZ

            playerNode.transform.translate(dx,0f,dz)

            lastRenderedX = player.posX
            lastRenderedZ = player.posZ
        }

        alchemistNode.onUpdate{
            transform.rotate(20f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }
        herbNode1.onUpdate{
            transform.rotate(20f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }
        herbNode2.onUpdate{
            transform.rotate(20f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }
        chestNode.onUpdate{  //
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
                    Button("Сменить игрока"){
                        modifier.margin(end=8.dp).onClick{
                            val newId = if (hud.activePlayerIdUi.value == "Oleg") "Stas" else "Oleg"

                            hud.activePlayerIdUi.value  = newId
                            hud.activePlayerIdFlow.value = newId
                        }
                    }

                    Button("Сбросить игрока"){
                        modifier.onClick{
                            server.trySend(CmdResetPlayer(player.playerId))
                        }
                    }
                }


                Text("Движение в мире: "){modifier.margin(top=sizes.gap)}

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

                Text("Взаимодействия: ") {modifier.margin(top = sizes.gap) }

                Row {
                    Button ("Потрогать ближайшего"){
                        modifier.margin(end = 8.dp).onClick{
                            server.trySend(CmdInteract(player.playerId))
                        }
                    }
                }

                Text( dialogue.npcId) { modifier.margin(top = sizes.gap)}

                Text(dialogue.text) { modifier.margin(bottom = sizes.smallGap)}

                if(dialogue.options.isEmpty()){
                    Text("Нет доступных вариантов ответа"){
                        modifier.font(sizes.smallText).margin(bottom = sizes.gap)
                    }
                }else{
                    Row{
                        for (option in dialogue.options){
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
                Text("Лог: "){ modifier.margin(top = sizes.gap)}

                for (line in hud.log.use()){
                    Text(line){modifier.font(sizes.smallText)}
                }
            }
        }
    }
}