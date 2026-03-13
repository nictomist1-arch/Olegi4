package questJournal2

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

enum class QuestStatus {
    ACTIVE,
    COMPLETED,
    FAILED
}

enum class QuestMarker {
    NEW,
    PINNED,
    COMPLETED,
    NONE
}

data class QuestJournalEntry(
    val questId: String,
    val title: String,
    val status: QuestStatus,
    val objectiveText: String,
    val marker: QuestMarker,
    val markerHint: String
)

sealed interface GameEvent {
    val playerId: String
}

data class QuestJournalUpdated(
    override val playerId: String
) : GameEvent

data class QuestOpened(
    override val playerId: String,
    val questId: String
) : GameEvent

data class QuestPinned(
    override val playerId: String,
    val questId: String
) : GameEvent

data class QuestProgressed(
    override val playerId: String,
    val questId: String
) : GameEvent

data class CommandRejected(
    override val playerId: String,
    val reason: String
) : GameEvent

sealed interface GameCommand {
    val playerId: String
}

data class CmdQuestOpened(
    override val playerId: String,
    val questId: String
) : GameCommand

data class CmdQuestPinned(
    override val playerId: String,
    val questId: String
) : GameCommand

data class CmdQuestProgressed(
    override val playerId: String,
    val questId: String
) : GameCommand

data class CmdSwitchPlayer(
    override val playerId: String,
    val newPlayerId: String
) : GameCommand

data class CmdAddQuest(
    override val playerId: String,
    val questId: String
) : GameCommand

data class CmdGiveGold(
    override val playerId: String,
    val amount: Int
) : GameCommand

data class QuestStateOnServer(
    val questId: String,
    val title: String,
    val step: Int,
    val status: QuestStatus,
    val isNew: Boolean,
    val isPinned: Boolean,
)

class QuestSystem {
    private fun objectiveFor(questId: String, step: Int): String {
        return when (questId) {
            "q_alchemist" -> when (step) {
                0 -> "Поговорить с алхимиком"
                1 -> "Собери траву"
                2 -> "Принеси траву"
                else -> "Квест завершен"
            }

            "q_guard" -> when (step) {
                0 -> "Поговорить со стражем этой двери"
                1 -> "Заплатить 10 золота"
                else -> "Проход открыт"
            }

            "q_blacksmith" -> when (step) {
                0 -> "Найти кузнеца"
                1 -> "Добыть руду"
                2 -> "Выковать меч"
                else -> "Квест завершен"
            }

            else -> "Неизвестный квест"
        }
    }

    private fun markerHintFor(questId: String, step: Int): String {
        return when (questId) {
            "q_alchemist" -> when (step) {
                0 -> "Идти к NPC: Алхимик"
                1 -> "Собрать Herb x2"
                2 -> "Вернись к NPC: Алхимик"
                else -> "Готово"
            }

            "q_guard" -> when (step) {
                0 -> "Идти к NPC: страж"
                1 -> "Найди чем расплатиться со стражником"
                else -> "Готово"
            }

            "q_blacksmith" -> when (step) {
                0 -> "Идти в кузницу"
                1 -> "Отправиться в шахту"
                2 -> "Вернуться к кузнецу"
                else -> "Готово"
            }

            else -> ""
        }
    }

    fun toJournalEntry(quest: QuestStateOnServer): QuestJournalEntry {
        val objective = objectiveFor(quest.questId, quest.step)
        val hint = markerHintFor(quest.questId, quest.step)

        val marker = when {
            quest.status == QuestStatus.COMPLETED -> QuestMarker.COMPLETED
            quest.isPinned -> QuestMarker.PINNED
            quest.isNew -> QuestMarker.NEW
            else -> QuestMarker.NONE
        }

        return QuestJournalEntry(
            quest.questId,
            quest.title,
            quest.status,
            objective,
            marker,
            hint
        )
    }
}

class GameServer {
    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val _commands = MutableSharedFlow<GameCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<GameCommand> = _commands.asSharedFlow()

    fun trySend(cmd: GameCommand): Boolean {
        return _commands.tryEmit(cmd)
    }

    private val _questByPlayer = MutableStateFlow<Map<String, List<QuestStateOnServer>>>(
        mapOf(
            "Oleg" to listOf(
                QuestStateOnServer("q_alchemist", "Алхимик и трава", 0, QuestStatus.ACTIVE, true, false),
                QuestStateOnServer("q_guard", "Тебе сюда нельзя", 0, QuestStatus.ACTIVE, true, false)
            ),
            "Stas" to listOf(
                QuestStateOnServer("q_alchemist", "Алхимик и трава", 0, QuestStatus.ACTIVE, true, false),
                QuestStateOnServer("q_guard", "Тебе сюда нельзя", 0, QuestStatus.ACTIVE, true, false)
            )
        )
    )

    val questByPlayer: StateFlow<Map<String, List<QuestStateOnServer>>> = _questByPlayer.asStateFlow()

    private val _playerGold = MutableStateFlow<Map<String, Int>>(
        mapOf(
            "Oleg" to 500,
            "Stas" to 300
        )
    )
    val playerGold: StateFlow<Map<String, Int>> = _playerGold.asStateFlow()

    fun start(scope: CoroutineScope) {
        scope.launch {
            commands.collect { cmd ->
                process(cmd)
            }
        }
    }

    private suspend fun process(cmd: GameCommand) {
        when (cmd) {
            is CmdQuestOpened -> openQuest(cmd.playerId, cmd.questId)
            is CmdQuestPinned -> pinQuest(cmd.playerId, cmd.questId)
            is CmdQuestProgressed -> progressQuest(cmd.playerId, cmd.questId)
            is CmdSwitchPlayer -> {}
            is CmdAddQuest -> addQuest(cmd.playerId, cmd.questId)
            is CmdGiveGold -> giveGold(cmd.playerId, cmd.amount)
        }
    }

    private suspend fun giveGold(playerId: String, amount: Int) {
        val currentGold = _playerGold.value[playerId] ?: 0
        val newGold = currentGold + amount

        if (newGold > 999) {
            val goldMap = _playerGold.value.toMutableMap()
            goldMap[playerId] = 999
            _playerGold.value = goldMap
            _events.emit(CommandRejected(playerId, "Золото не может быть больше 999. Значение обрезано до 999."))
        } else {
            val goldMap = _playerGold.value.toMutableMap()
            goldMap[playerId] = newGold
            _playerGold.value = goldMap
        }

        _events.emit(QuestJournalUpdated(playerId))
    }

    private fun getPlayerQuests(playerId: String): List<QuestStateOnServer> {
        return _questByPlayer.value[playerId] ?: emptyList()
    }

    private fun setPlayerQuests(playerId: String, quests: List<QuestStateOnServer>) {
        val oldMap = _questByPlayer.value.toMutableMap()
        oldMap[playerId] = quests
        _questByPlayer.value = oldMap
    }

    private suspend fun openQuest(playerId: String, questId: String) {
        val quests = getPlayerQuests(playerId).toMutableList()

        for (i in quests.indices) {
            val q = quests[i]
            if (q.questId == questId) {
                quests[i] = q.copy(isNew = false)
            }
        }

        setPlayerQuests(playerId, quests)

        _events.emit(QuestJournalUpdated(playerId))
    }

    private suspend fun pinQuest(playerId: String, questId: String) {
        val quests = getPlayerQuests(playerId).toMutableList()

        for (i in quests.indices) {
            val q = quests[i]
            quests[i] = q.copy(isPinned = (q.questId == questId))
        }

        setPlayerQuests(playerId, quests)

        _events.emit(QuestJournalUpdated(playerId))
    }

    private suspend fun progressQuest(playerId: String, questId: String) {
        val quests = getPlayerQuests(playerId).toMutableList()

        for (i in quests.indices) {
            val q = quests[i]
            if (q.questId == questId) {
                val newStep = q.step + 1

                val completed = when (q.questId) {
                    "q_alchemist" -> newStep >= 3
                    "q_guard" -> newStep >= 2
                    "q_blacksmith" -> newStep >= 3
                    else -> false
                }

                val newStatus = if (completed) QuestStatus.COMPLETED else QuestStatus.ACTIVE
                quests[i] = q.copy(isNew = false, step = newStep, status = newStatus)
            }
        }
        setPlayerQuests(playerId, quests)

        _events.emit(QuestJournalUpdated(playerId))
    }

    private suspend fun addQuest(playerId: String, questId: String) {
        val quests = getPlayerQuests(playerId).toMutableList()

        if (quests.any { it.questId == questId }) {
            return
        }

        val newQuest = QuestStateOnServer(
            questId = questId,
            title = when (questId) {
                "q_blacksmith" -> "Кузнечное дело"
                else -> "Новый квест"
            },
            step = 0,
            status = QuestStatus.ACTIVE,
            isNew = true,
            isPinned = false
        )

        quests.add(newQuest)
        setPlayerQuests(playerId, quests)

        _events.emit(QuestJournalUpdated(playerId))
    }
}

class HudState {
    val activePlayerIdFlow = MutableStateFlow("Oleg")
    val activePlayerUi = mutableStateOf("Oleg")

    val questEntries = mutableStateOf<List<QuestJournalEntry>>(emptyList())
    val selectedQuestId = mutableStateOf<String?>(null)

    val log = mutableStateOf<List<String>>(emptyList())
}

fun hudLog(hud: HudState, text: String) {
    hud.log.value = (hud.log.value + text).take(20)
}

fun markerSymbol(m: QuestMarker): String {
    return when (m) {
        QuestMarker.NEW -> "🆕"
        QuestMarker.PINNED -> "📌"
        QuestMarker.COMPLETED -> "✅"
        QuestMarker.NONE -> "○"
    }
}

fun main() = KoolApplication {
    val hud = HudState()
    val server = GameServer()
    val quests = QuestSystem()

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
        server.start(coroutineScope)
    }
    addScene {
        setupUiScene(ClearColorLoad)

        coroutineScope.launch {
            server.questByPlayer.collect { map ->
                val pid = hud.activePlayerIdFlow.value
                val serverList = map[pid] ?: emptyList()

                val entries = serverList.map { quests.toJournalEntry(it) }

                hud.questEntries.value = entries

                if (hud.selectedQuestId.value == null) {
                    val pinned = entries.firstOrNull { it.marker == QuestMarker.PINNED }
                    if (pinned != null) hud.selectedQuestId.value = pinned.questId
                }
            }
        }

        hud.activePlayerIdFlow
            .flatMapLatest { pid ->
                server.events.filter { it.playerId == pid }
            }
            .map { e -> "[${e.playerId} ${e::class.simpleName}" }
            .onEach { line -> hudLog(hud, line) }
            .launchIn(coroutineScope)

        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), 14.dp))
                .padding(12.dp)
                .width(300.dp)

            Column {
                Text("Игрок: ${hud.activePlayerUi.use()}") { modifier.margin(bottom = 4.dp) }

                val goldAmount = server.playerGold.value[hud.activePlayerUi.value] ?: 0
                Text("Золото: $goldAmount / 999") {
                    modifier.margin(bottom = 8.dp)
                }

                Row {
                    Button ( "Switch Player" ){
                        modifier.margin(end=8.dp).onClick {
                            val newId = if (hud.activePlayerUi.value == "Oleg") "Stas" else "Oleg"

                            hud.activePlayerUi.value = newId

                            hud.activePlayerIdFlow.value = newId

                            hud.selectedQuestId.value = null
                        }
                    }

                    Button("Add Quest") {
                        modifier.onClick {
                            server.trySend(CmdAddQuest(hud.activePlayerUi.value, "q_blacksmith"))
                        }
                    }

                    Button("+50 Gold") {
                        modifier.margin(start = 8.dp).onClick {
                            server.trySend(CmdGiveGold(hud.activePlayerUi.value, 50))
                        }
                    }

                    Button("+500 Gold") {
                        modifier.margin(start = 8.dp).onClick {
                            server.trySend(CmdGiveGold(hud.activePlayerUi.value, 500))
                        }
                    }
                }

                Text("Активные квесты:") {modifier.margin(top=sizes.gap)}

                val entries = hud.questEntries.use()
                val selectedId = hud.selectedQuestId.use()

                val sortedEntries = entries.sortedWith(
                    compareByDescending<QuestJournalEntry> { it.marker == QuestMarker.PINNED }
                        .thenByDescending { it.marker == QuestMarker.NEW }
                        .thenByDescending { it.status == QuestStatus.ACTIVE }
                        .thenByDescending { it.marker == QuestMarker.COMPLETED }
                )

                for (q in sortedEntries) {
                    val symbol = markerSymbol(q.marker)

                    val line = "$symbol ${q.title}"
                    Button ( line ){
                        modifier.margin(bottom = sizes.smallGap).onClick{
                            hud.selectedQuestId.value = q.questId
                            server.trySend(CmdQuestOpened(hud.activePlayerUi.value, q.questId))
                        }
                    }
                    Text( " - ${q.objectiveText}"){
                        modifier.font(sizes.smallText).margin(bottom = sizes.smallGap)
                    }

                    if (selectedId == q.questId){
                        Text(" marker: ${q.markerHint}"){
                            modifier.font(sizes.smallText).margin(bottom = sizes.gap)
                        }

                        Row {
                            Button("Pin"){
                                modifier.margin(end = 8.dp).onClick{
                                    server.trySend(CmdQuestPinned(hud.activePlayerUi.value, q.questId))
                                }
                            }
                            Button("Progress"){
                                modifier.margin(end = 8.dp).onClick{
                                    server.trySend(CmdQuestProgressed(hud.activePlayerUi.value, q.questId))
                                }
                            }
                        }
                    }
                }
                Text ("Log") {modifier.margin(top = sizes.gap)}
                for (line in hud.log.use()) {
                    Text(line) {
                        modifier
                            .margin(bottom = sizes.smallGap)
                            .font(sizes.smallText)
                    }
                }
            }
        }
    }
}