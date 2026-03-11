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

// Когда событий становится слишком много в игре появляется проблема
// 1. Если все системы слушают все события код быстро превратится в кашу
// 2. Будет сложно понять кто на что реагирует из систем
// 3. Такие системы сложно дебажить
// 4. И так же надо жестко разделять события игрока Oleg от событий игрока Stas

// Для исправления данных проблем надо использовать flow-операторы
// filter - оставляет в потоке только то что подходит по условию
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
    fun handleDamage(e: DamageDealt) {
        server.updatePlayer(e.targetId) { player ->
            val newHp = (player.hp - e.amount).coerceAtLeast(0)
            player.copy(hp = newHp)
        }
    }

    fun onEvent(e: GameEvent) {
        if (e is DamageDealt) {
            handleDamage(e)
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

    fun startPoison(e: PoisonApplied, publishDamage: (GameEvent) -> Unit) {
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
        }
        poisonJobs[e.playerId] = job
    }
}

class QuestSystem(private val server: GameServer) {
    private val questId = "q_alchemist"
    private val npcId = "alchemist"

    fun handleTalk(e: TalkedToNpc, publish: (GameEvent) -> Unit) {
        if (e.npcId != npcId) return

        val player = server.getPlayer(e.playerId)
        if (player.questState == "START") {
            server.updatePlayer(e.playerId) { it.copy(questState = "OFFERED") }
            publish(QuestStateChanged(e.playerId, questId, "OFFERED"))
        }
    }

    fun handleChoice(e: ChoiceSelected, publish: (GameEvent) -> Unit) {
        if (e.npcId != npcId) return

        val player = server.getPlayer(e.playerId)
        if (player.questState == "OFFERED") {
            val newState = if (e.choiceId == "help") "GOOD_END" else "EVIL_END"
            server.updatePlayer(e.playerId) { it.copy(questState = newState) }
            publish(QuestStateChanged(e.playerId, questId, newState))
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
    val activePlayerIdFlow = MutableStateFlow("Oleg")

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

fun main() = KoolApplication {
    val hud = HudState()

    addScene {
        defaultOrbitCamera()

        addColorMesh {
            generate { cube { colored() } }

            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0.7f)
                roughness(0.4f)
            }

            onUpdate {
                transform.rotate(45f.deg * Time.deltaT, Vec3f.X_AXIS)
            }
        }

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, -1f))
            setColor(Color.WHITE, 5f)
        }

        val server = GameServer()
        val saver = SaveSystem()
        val damage = DamageSystem(server)
        val cooldowns = CooldownSystem(server, coroutineScope)
        val poison = PoisonSystem(server, coroutineScope)
        val quests = QuestSystem(server)

        server.events
            .filter { it is AttackPressed }
            .onEach { event ->
                val e = event as AttackPressed

                if (!cooldowns.canAttack(e.playerId)) {
                    val msg = ServerMessage(e.playerId, "Нельзя атаковать: кулдаун")
                    if (!server.tryPublish(msg)) {
                        coroutineScope.launch { server.publish(msg) }
                    }
                    return@onEach
                }

                val dmg = DamageDealt(e.playerId, e.targetId, 10)

                if (!server.tryPublish(dmg)) {
                    coroutineScope.launch { server.publish(dmg) }
                }

                cooldowns.startCooldown(e.playerId)
            }
            .launchIn(coroutineScope)

        server.events
            .filter { it is DamageDealt }
            .onEach { event ->
                val e = event as DamageDealt
                damage.handleDamage(e)
            }
            .launchIn(coroutineScope)

        server.events
            .filter { it is PoisonApplied }
            .onEach { event ->
                val e = event as PoisonApplied

                poison.startPoison(e) { dmg ->
                    if (!server.tryPublish(dmg)) {
                        coroutineScope.launch { server.publish(dmg) }
                    }
                }
            }
            .launchIn(coroutineScope)

        server.events
            .filter { it is TalkedToNpc }
            .onEach { event ->
                val e = event as TalkedToNpc
                quests.handleTalk(e) { newEvent ->
                    if (!server.tryPublish(newEvent)) {
                        coroutineScope.launch { server.publish(newEvent) }
                    }
                }
            }
            .launchIn(coroutineScope)

        server.events
            .filter { it is ChoiceSelected }
            .onEach { event ->
                val e = event as ChoiceSelected

                quests.handleChoice(e) { newEvent ->
                    if (!server.tryPublish(newEvent)) {
                        coroutineScope.launch { server.publish(newEvent) }
                    }
                }
            }
            .launchIn(coroutineScope)

        server.events
            .filter { it is QuestStateChanged }
            .onEach { event ->
                val e = event as QuestStateChanged
                val save = SaveRequested(e.playerId)

                if (!server.tryPublish(save)) {
                    coroutineScope.launch { server.publish(save) }
                }
            }
            .launchIn(coroutineScope)

        server.events
            .filter { it is SaveRequested }
            .onEach { event ->
                val e = event as SaveRequested
                val snapShot = server.getPlayer(e.playerId)
                saver.save(snapShot)
            }
            .launchIn(coroutineScope)

    }

    addScene {
        setupUiScene(ClearColorLoad)

        val server = GameServer()

        if (server != null) {
            coroutineScope.launch {
                server.players.collect { playersMap ->
                    val pid = hud.activePlayerIdFlow.value
                    val p = playersMap[pid] ?: return@collect

                    hud.hp.value = p.hp
                    hud.gold.value = p.gold
                    hud.dummyHp.value = p.dummyHp
                    hud.poisonTicksLeft.value = p.poisonTicksLeft
                    hud.questState.value = p.questState
                    hud.attackCooldownMsLeft.value = p.attackCooldownMsLeft
                }
            }

            hud.activePlayerIdFlow
                .flatMapLatest { pid ->
                    server.events.filter { it.playerId == pid }
                }
                .map { event ->
                    eventToText(event)
                }
                .onEach { line ->
                    hudLog(hud, line)
                }
                .launchIn(coroutineScope)
        }

        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), 14.dp))
                .padding(12.dp)
                .width(300.dp)

            Column {
                Text("Игрок: ${hud.activePlayerIdUi.use()}") { modifier.margin(bottom = 4.dp) }
                // ИСПРАВЛЕНО: Добавлены скобки к use()
                Text("HP: ${hud.hp.use()}/${hud.gold.use()}") { modifier.margin(bottom = 4.dp) }

                Text("DummyHp: ${hud.dummyHp.use()}") { modifier.margin(bottom = 4.dp) }
                Text("квест: ${hud.questState.use()}") { modifier.margin(bottom = 4.dp) }
                Text("Тики яда: ${hud.poisonTicksLeft.use()}") { modifier.margin(bottom = 4.dp) }
                Text("Кулдаун: ${hud.attackCooldownMsLeft.use()} мс") { modifier.margin(bottom = 16.dp) }

                Row {
                    Button("Смена игрока") {
                        val currentPlayer = hud.activePlayerIdUi.value
                        val newPlayer = if (currentPlayer == "Oleg") "Stas" else "Oleg"
                        hud.activePlayerIdUi.value = newPlayer
                        hudLog(hud, "Переключено на $newPlayer")
                    }
                }
            }
        }
    }
}

fun eventToText(e: GameEvent): String {
    return when (e) {
        is AttackPressed -> "[${e.playerId}] AttackPressed по ${e.targetId}"
        is DamageDealt -> "[${e.playerId}] DamageDealt ${e.amount} по ${e.targetId}"
        is PoisonApplied -> "[${e.playerId}] PoisonApplied ${e.ticks}"
        is ChoiceSelected -> "[${e.playerId}] ChoiceSelected ${e.choiceId}"
        is QuestStateChanged -> "[${e.playerId}] QuestStateChanged ${e.newState}"
        is TalkedToNpc -> "[${e.playerId}] TalkedToNpc с ${e.npcId}"
        is SaveRequested -> "[${e.playerId}] SaveRequested"
        is AttackSpeedBuffApplied -> "[${e.playerId}] AttackSpeedBuffApplied на ${e.ticks} тиков"
        is ServerMessage -> "[${e.playerId}] ServerMessage: ${e.text}"
        is CommandRejected -> "[${e.playerId}] CommandRejected: ${e.reason}"
    }
}

object Shared {
    var server: GameServer? = null
}