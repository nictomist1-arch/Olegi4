
//1.1 | ﷽) Я... гей
//1.2 | c) Для дикого флекса
//1.3 | ﷽) Хихихи ха

package questDependence

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
import java.time.temporal.TemporalAmount


enum class QuestStatus {
    ACTIVE,
    COMPLETED,
    FAILED,
    LOCKED
}

enum class QuestMarker {
    NEW,
    PINNED,
    COMPLETED,
    LOCKED,
    NONE
}

enum class QuestBranch {
    NONE,
    HELP,
    THREAT
}

data class QuestStateOnServer(
    val questId: String,
    val title: String,
    val status: QuestStatus,
    val step: Int,
    val branch: QuestBranch,
    val progressCurrent: Int,
    val progressTarget: Int,
    val isNew: Boolean,
    val isPinned: Boolean,
    val unlockRequiredQuestId: String?
)

data class QuestJournalEntry(
    val questId: String,
    val title: String,
    val status: QuestStatus,
    val objectiveText: String,
    val progressText: String,
    val progressBar: String,
    val marker: QuestMarker,
    val markerHint: String,
    val branchText: String,
    val lockedReason: String
)

sealed interface GameEvent {
    val playerId: String
}

data class QuestBranchChosen(
    override val playerId: String,
    val questId: String,
    val branch: QuestBranch
) : GameEvent

data class ItemCollected(
    override val playerId: String,
    val itemId: String,
    val countAdded: Int
) : GameEvent

data class GoldTurnedIn(
    override val playerId: String,
    val questId: String,
    val amount: Int
) : GameEvent

data class QuestCompleted(
    override val playerId: String,
    val questId: String
) : GameEvent

data class QuestUnlocked(
    override val playerId: String,
    val questId: String
) : GameEvent

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

data class ServerMessage(
    override val playerId: String,
    val questId: String
) : GameEvent

sealed interface GameCommand {
    val playerId: String
}

data class CmdBranchChosen(
    override val playerId: String,
    val questId: String,
    val branch: QuestBranch
) : GameCommand

data class CmdItemCollected(
    override val playerId: String,
    val itemId: String,
    val countAdded: Int
) : GameCommand

data class CmdTurnInGold(
    override val playerId: String,
    val amount: Int,
    val questId: String
) : GameCommand

data class CmdGoldTurnedIn(
    override val playerId: String,
    val amount: Int
) : GameCommand

data class CmdGiveGold(
    override val playerId: String,
    val amount: Int
) : GameCommand

data class CmdQuestCompleted(
    override val playerId: String,
    val questId: String
) : GameCommand

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

data class PlayerData(
    val playerId: String,
    val gold: Int,
    val inventory: Map<String, Int>
)

class QuestSystem {
    fun objectiveFor(q: QuestStateOnServer): String {

        if (q.status == QuestStatus.LOCKED) {
            return "Квест пока не доступен"
        }

        if (q.questId == "q_alchemist") {
            return when (q.step) {
                0 -> "Поговори с Алхимиком"
                1 -> {
                    when (q.branch) {
                        QuestBranch.NONE -> "Выбери путь: Help или Threat"
                        QuestBranch.HELP -> "Собери Траву ${q.progressCurrent} / ${q.progressTarget}"
                        QuestBranch.THREAT -> "Собери золото ${q.progressCurrent} / ${q.progressTarget}"
                    }
                }
                2 -> "Вернись к Алхимику и заверши квест"
                else -> "Квест завершен"
            }
        }
        if (q.questId == "q_guard") {
            return when (q.step) {
                0 -> "Поговори со Стражником"
                1 -> "Заплати стражнику золото: ${q.progressCurrent} / ${q.progressTarget}"
                2 -> "Сдай квест у стражника"
                else -> "Квест завершен"
            }
        }
        if (q.questId == "q_blacksmith") {
            return when (q.step) {
                0 -> "Поговори с Кузнецом"
                1 -> "Принеси руду кузнецу: ${q.progressCurrent} / ${q.progressTarget}"
                2 -> "Вернись к Кузнецу и заверши квест"
                else -> "Квест завершен"
            }
        }
        return "Неизвестный квест"
    }

    private fun markerHintFor(questId: String, step: Int): String {
        return when (questId) {
            "q_alchemist" -> when (step) {
                0 -> "Идти к NPC: Алхимик"
                1 -> "Собрать ресурсы (зависит от выбранной ветки)"
                2 -> "Вернись к NPC: Алхимик"
                else -> "Готово"
            }
            "q_guard" -> when (step) {
                0 -> "Идти к NPC: Стражник (ворота)"
                1 -> "Найди чем расплатиться со стражником"
                2 -> "Вернись к NPC: Стражник для завершения"
                else -> "Готово"
            }
            "q_blacksmith" -> when (step) {
                0 -> "Идти к NPC: Кузнец (кузница)"
                1 -> "Добыть руду в шахте (северная локация)"
                2 -> "Вернуться к NPC: Кузнец"
                else -> "Готово"
            }
            else -> "Подсказка недоступна"
        }
    }

    private fun branchTextFor(questId: String, branch: QuestBranch): String {
        return when (questId) {
            "q_alchemist" -> when (branch) {
                QuestBranch.NONE -> "Выберите дальнейшие действия"
                QuestBranch.HELP -> "Вы выбрали помочь Алхимику с травами"
                QuestBranch.THREAT -> "Вы выбрали угрожать Алхимику и требовать золото"
            }
            "q_guard" -> when (branch) {
                QuestBranch.NONE -> "Стражник ждет вашего решения"
                QuestBranch.HELP -> "Вы предложили помощь стражнику"
                QuestBranch.THREAT -> "Вы пытаетесь запугать стражника"
            }
            "q_blacksmith" -> when (branch) {
                QuestBranch.NONE -> "Кузнец ожидает вашего ответа"
                QuestBranch.HELP -> "Вы согласились помочь с добычей руды"
                QuestBranch.THREAT -> "Вы требуете меч бесплатно"
            }
            else -> ""
        }
    }

    private fun lockedReasonFor(questId: String, unlockRequiredQuestId: String?): String {
        return when (questId) {
            "q_alchemist" -> "Сначала откройте квест 'Помощь Алхимику'"
            "q_guard" -> {
                when (unlockRequiredQuestId) {
                    "q_alchemist" -> "Сначала завершите квест Алхимика"
                    "q_elder" -> "Сначала поговорите со Старейшиной"
                    else -> "Квест временно недоступен. Выполните предыдущие задания."
                }
            }
            "q_blacksmith" -> {
                when (unlockRequiredQuestId) {
                    "q_guard" -> "Сначала помогите стражнику"
                    "q_alchemist" -> "Сначала завершите дела с Алхимиком"
                    else -> "Квест откроется после выполнения предыдущих заданий"
                }
            }
            "q_elder" -> {
                when (unlockRequiredQuestId) {
                    "q_blacksmith" -> "Кузнец должен подтвердить вашу надежность"
                    else -> "Поговорите с жителями деревни, чтобы получить этот квест"
                }
            }
            else -> "Квест заблокирован. Выполните предыдущие задания."
        }
    }

    fun branchTextFor(branch: QuestBranch): String {
        return when (branch) {
            QuestBranch.NONE -> "Путь не выбран"
            QuestBranch.HELP -> "Путь помощи"
            QuestBranch.THREAT -> "Путь угрозы"
        }
    }

    fun lockedReasonFor(q: QuestStateOnServer): String {
        if (q.status != QuestStatus.LOCKED) return ""

        return if (q.unlockRequiredQuestId == null) {
            "Причина блокировки неизвестна"
        } else {
            "Нужно завершить квест ${q.unlockRequiredQuestId}"
        }
    }

    fun markerFor(q: QuestStateOnServer): QuestMarker {
        return when {
            q.status == QuestStatus.LOCKED -> QuestMarker.LOCKED
            q.status == QuestStatus.COMPLETED -> QuestMarker.COMPLETED
            q.isPinned -> QuestMarker.PINNED
            q.isNew -> QuestMarker.NEW
            else -> QuestMarker.NONE
        }
    }

    fun progressBarWithPercent(current: Int, target: Int, width: Int = 10): String {
        if (target <= 0) return ""

        val percent = (current.toFloat() / target.toFloat() * 100).toInt()
        val filled = (percent * width / 100).coerceIn(0, width)
        val empty = width - filled

        val bar = "卐".repeat(filled) + "卍".repeat(empty)
        return "$bar $percent%"
    }

    fun toJournalEntry(q: QuestStateOnServer): QuestJournalEntry {
        val progressText = if (q.progressTarget > 0) "${q.progressCurrent} / ${q.progressTarget}" else ""
        val progressBar = if (q.progressTarget > 0) progressBarWithPercent(q.progressCurrent, q.progressTarget) else ""

        return QuestJournalEntry(
            q.questId,
            q.title,
            q.status,
            objectiveFor(q),
            progressText,
            progressBar,
            markerFor(q),
            markerHintFor(q.questId, q.step),
            branchTextFor(q.questId, q.branch),
            lockedReasonFor(q)
        )
    }

    fun applyEvent(
        quests: List<QuestStateOnServer>,
        event: GameEvent
    ): List<QuestStateOnServer> {
        val copy = quests.toMutableList()

        for (i in copy.indices) {
            val q = copy[i]

            if (q.status == QuestStatus.LOCKED) continue
            if (q.status == QuestStatus.COMPLETED) continue

            if (q.questId == "q_alchemist") {
                copy[i] = updateAlchemist(q, event)
            }

            if (q.questId == "q_guard") {
                copy[i] = updateGuard(q, event)
            }

            if (q.questId == "q_blacksmith") {
                copy[i] = updateBlacksmith(q, event)
            }
        }
        return copy.toList()
    }

    private fun updateAlchemist(q: QuestStateOnServer, event: GameEvent): QuestStateOnServer {
        if (q.step == 0 && event is QuestBranchChosen && event.questId == q.questId) {
            return when (event.branch) {
                QuestBranch.HELP -> q.copy(
                    step = 1,
                    branch = QuestBranch.HELP,
                    progressCurrent = 0,
                    progressTarget = 3,
                    isNew = false
                )
                QuestBranch.THREAT -> q.copy(
                    step = 1,
                    branch = QuestBranch.THREAT,
                    progressCurrent = 0,
                    progressTarget = 10,
                    isNew = false
                )
                QuestBranch.NONE -> q
            }
        }

        if (q.step == 1 && q.branch == QuestBranch.HELP && event is ItemCollected && event.itemId == "Herb") {
            val newCurrent = (q.progressCurrent + event.countAdded).coerceAtMost(q.progressTarget)
            val update = q.copy(progressCurrent = newCurrent, isNew = false)

            if (newCurrent >= q.progressTarget) {
                return update.copy(step = 2, progressCurrent = 0, progressTarget = 0)
            }
            return update
        }

        if (q.step == 1 && q.branch == QuestBranch.THREAT && event is GoldTurnedIn && event.questId == q.questId) {
            val newCurrent = (q.progressCurrent + event.amount).coerceAtMost(q.progressTarget)
            val update = q.copy(progressCurrent = newCurrent, isNew = false)

            if (newCurrent >= q.progressTarget) {
                return update.copy(step = 2, progressCurrent = 0, progressTarget = 0)
            }
            return update
        }

        return q
    }

    fun updateGuard(q: QuestStateOnServer, event: GameEvent): QuestStateOnServer {
        val base = if (q.step == 0){
            q.copy(step = 1, progressCurrent = 0, progressTarget = 5, isNew = false)
        }else q

        if (base.step == 1 && event is GoldTurnedIn && event.questId == base.questId){
            val newCurrent = (base.progressCurrent + event.amount).coerceAtMost(base.progressTarget)
            val updated = base.copy(progressCurrent = newCurrent, isNew = false)

            if (newCurrent >= base.progressTarget){
                return updated.copy(step = 2, progressCurrent = 0, progressTarget = 0)
            }
            return updated
        }
        return base
    }

    fun updateBlacksmith(q: QuestStateOnServer, event: GameEvent): QuestStateOnServer {
        val base = if (q.step == 0){
            q.copy(step = 1, progressCurrent = 0, progressTarget = 5, isNew = false)
        }else q

        if (base.step == 1 && event is ItemCollected && event.itemId == "Ore") {
            val newCurrent = (base.progressCurrent + event.countAdded).coerceAtMost(base.progressTarget)
            val updated = base.copy(progressCurrent = newCurrent, isNew = false)

            if (newCurrent >= base.progressTarget) {
                return updated.copy(step = 2, progressCurrent = 0, progressTarget = 0)
            }
            return updated
        }

        return base
    }
}

class GameServer {
    private val _event = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    val event: SharedFlow<GameEvent> = _event.asSharedFlow()

    private val _commands = MutableSharedFlow<GameCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<GameCommand> = _commands.asSharedFlow()

    fun trySend(cmd: GameCommand): Boolean = _commands.tryEmit(cmd)

    private val _players = MutableStateFlow(
        mapOf(
            "Oleg" to PlayerData("Oleg", 0, emptyMap()),
            "Stas" to PlayerData("Stas", 0, emptyMap())
        )
    )
    val players: StateFlow<Map<String, PlayerData>> = _players.asStateFlow()

    private val _questsByPlayer = MutableStateFlow(
        mapOf(
            "Oleg" to initialQuestList(),
            "Stas" to initialQuestList()
        )
    )
    val questsByPlayer: StateFlow<Map<String, List<QuestStateOnServer>>> = _questsByPlayer.asStateFlow()

    fun start(scope: kotlinx.coroutines.CoroutineScope, questSystem: QuestSystem){
        scope.launch {
            commands.collect { cmd ->
                processCommand(cmd, questSystem)
            }
        }
    }
    private fun setPlayerData(playerId: String, data: PlayerData){
        val map = _players.value.toMutableMap()
        map[playerId] = data
        _players.value = map.toMap()
    }
    private fun getPlayerData(playerId: String): PlayerData{
        return _players.value[playerId] ?: PlayerData(playerId, 0, emptyMap())
    }
    private fun setQuests(playerId: String, quests: List<QuestStateOnServer>){
        val map = _questsByPlayer.value.toMutableMap()
        map[playerId] = quests
        _questsByPlayer.value = map.toMap()
    }
    private fun getQuests(playerId: String): List<QuestStateOnServer>{
        return _questsByPlayer.value[playerId] ?: emptyList()
    }

    private suspend fun processCommand(cmd: GameCommand, questSystem: QuestSystem){
        when(cmd){
            is CmdQuestOpened -> {
                val list = getQuests(cmd.playerId).toMutableList()

                for(i in list.indices){
                    if (list[i].questId == cmd.questId){
                        list[i] = list[i].copy(isNew = false)
                    }
                }
                setQuests(cmd.playerId,list)
                _event.emit(QuestJournalUpdated(cmd.playerId))
            }
            is CmdQuestPinned-> {
                val list = getQuests(cmd.playerId).toMutableList()

                for(i in list.indices){
                    if (list[i].questId == cmd.questId){
                        list[i] = list[i].copy(isNew = false)
                    }
                }
                setQuests(cmd.playerId,list)
                _event.emit(QuestJournalUpdated(cmd.playerId))
            }
            is CmdBranchChosen -> {
                val quests = getQuests(cmd.playerId)
                val target = quests.find { it.questId == cmd.questId }

                if (target == null){
                    _event.emit(ServerMessage(cmd.playerId, "Квест ${cmd.questId} не найден"))
                    return
                }
                if (target.status != QuestStatus.ACTIVE){
                    _event.emit(ServerMessage(cmd.playerId, "Квест ${cmd.questId} сейчас не активен"))
                }

                val ev = QuestBranchChosen(cmd.playerId, cmd.questId, cmd.branch)
                _event.emit(ev)

                val updated = questSystem.applyEvent(quests,ev)
                setQuests(cmd.playerId, updated)

                _event.emit(QuestJournalUpdated(cmd.playerId))
            }
            is CmdGiveGold -> {
                val player = getPlayerData(cmd.playerId)
                setPlayerData(cmd.playerId, player.copy(gold = player.gold + cmd.amount))
                _event.emit(ServerMessage(cmd.playerId, "Выдано злото + ${cmd.amount}"))
            }
            is CmdTurnInGold -> {
                val player = getPlayerData(cmd.playerId)

                if(player.gold < cmd.amount){
                    _event.emit(ServerMessage(cmd.playerId, "Недостаточно богат, нужно ${cmd.amount}"))
                    return
                }

                setPlayerData(cmd.playerId, player.copy(gold = player.gold - cmd.amount))

                val ev = GoldTurnedIn(cmd.playerId, cmd.questId, cmd.amount)
                _event.emit(ev)

                val updated = questSystem.applyEvent(getQuests(cmd.playerId), ev)
                setQuests(cmd.playerId, updated)

                _event.emit(QuestJournalUpdated(cmd.playerId))
            }
            is CmdItemCollected -> {
                val ev = ItemCollected(cmd.playerId, cmd.itemId, cmd.countAdded)
                _event.emit(ev)

                val updated = questSystem.applyEvent(getQuests(cmd.playerId), ev)
                setQuests(cmd.playerId, updated)

                _event.emit(QuestJournalUpdated(cmd.playerId))
            }

            is CmdQuestCompleted -> {
                finishQuest(cmd.playerId, cmd.questId)
            }
            else -> {}
        }
    }

    private suspend fun finishQuest(playerId: String, questId: String){
        val list = getQuests(playerId).toMutableList()

        val index = list.indexOfFirst { it.questId == questId }

        if (index == -1){
            _event.emit(ServerMessage(playerId, "Квест ${questId} не найден"))
            return
        }
        val q = list[index]

        if (q.status != QuestStatus.ACTIVE){
            _event.emit(ServerMessage(playerId, "Нельзя завершить $questId статус: ${q.status}"))
            return
        }

        if (q.step != 2){
            _event.emit(ServerMessage(playerId, "Нельзя завершить $questId - сначала дойди до этапа 2"))
            return
        }

        list[index] = q.copy(
            status = QuestStatus.COMPLETED,
            step = 3,
            isNew = false
        )

        setQuests(playerId, list)
        _event.emit(QuestCompleted(playerId,questId))

        unlockDependentQuests(playerId, questId)

        _event.emit(QuestJournalUpdated(playerId))
    }

    private suspend fun unlockDependentQuests(playerId: String, comletedQuestId: String){
        val list = getQuests(playerId).toMutableList()
        var changed = false

        for(i in list.indices){
            val q = list[i]

            if(q.status == QuestStatus.LOCKED && q.unlockRequiredQuestId == comletedQuestId){
                list[i] = q.copy(
                    status = QuestStatus.ACTIVE,
                    isNew = true
                )
                changed = true

                _event.emit(QuestUnlocked(playerId, q.questId))
            }
        }
        if (changed){
            setQuests(playerId, list)
        }
    }
}

fun initialQuestList(): List<QuestStateOnServer>{
    return listOf(
        QuestStateOnServer(
            "q_alchemist",
            "Помочь Хайзенбергу",
            QuestStatus.ACTIVE,
            0,
            QuestBranch.NONE,
            0,
            0,
            true,
            false,
            null
        ),
        QuestStateOnServer(
            "q_guard",
            "Подкупить стражника",
            QuestStatus.LOCKED,
            0,
            QuestBranch.NONE,
            0,
            0,
            false,
            false,
            "q_alchemist"
        ),
        QuestStateOnServer(
            "q_blacksmith",
            "Помощь кузнецу",
            QuestStatus.LOCKED,
            0,
            QuestBranch.NONE,
            0,
            0,
            false,
            false,
            "q_guard"
        )
    )
}

class HudState{
    val activePlayerFlow = MutableStateFlow("Oleg")
    val activePlayerIdUI = mutableStateOf("Oleg")

    val gold = mutableStateOf(0)
    val inventoryText = mutableStateOf("Inventory(empty)")

    val questEntries = mutableStateOf<List<QuestJournalEntry>>(emptyList())
    val selectedQuests = MutableStateFlow<String?>(null)

    val log = mutableStateOf<List<String>>(emptyList())
}

fun hudLog(hud: HudState, text: String) {
    hud.log.value = (hud.log.value + text).takeLast(25)
}

fun markerSymbol(marker: QuestMarker): String {
    return when (marker) {
        QuestMarker.NEW -> "🆕"
        QuestMarker.PINNED -> "📌"
        QuestMarker.COMPLETED -> "✅"
        QuestMarker.LOCKED -> "🔒"
        QuestMarker.NONE -> "○"
    }
}

fun journalSortRank(entry: QuestJournalEntry): Int{
    return when{
        entry.marker == QuestMarker.PINNED -> 0
        entry.marker == QuestMarker.NEW -> 1
        entry.status == QuestStatus.ACTIVE -> 2
        entry.status == QuestStatus.LOCKED -> 3
        entry.status == QuestStatus.COMPLETED -> 4
        else -> 5
    }
}

fun eventToText(event: GameEvent): String {
    return when (event) {
        is QuestBranchChosen -> "QuestBranchChosen ${event.questId} -> ${event.branch}"
        is ItemCollected -> "ItemCollected ${event.itemId} x ${event.countAdded}"
        is GoldTurnedIn -> "GoldTurnedIn ${event.questId} - ${event.amount}"
        is QuestCompleted -> "QuestCompleted ${event.questId}"
        is QuestUnlocked -> "QuestUnlocked ${event.questId}"
        is QuestJournalUpdated -> "QuestJournalUpdated ${event.playerId}"
        is QuestOpened -> "QuestOpened ${event.questId}"
        is QuestPinned -> "QuestPinned ${event.questId}"
        is QuestProgressed -> "QuestProgressed ${event.questId}"
        is ServerMessage -> "ServerMessage ${event.playerId}: ${event.questId}"
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
        server.start(coroutineScope, quests)
    }
    addScene {
        setupUiScene(ClearColorLoad)

        coroutineScope.launch {
            server.questsByPlayer.collect { map ->
                val pid = hud.activePlayerFlow.value
                val serverList = map[pid] ?: emptyList()

                val entries = serverList.map { quests.toJournalEntry(it) }

                hud.questEntries.value = entries

                if (hud.selectedQuests.value == null) {
                    val pinned = entries.firstOrNull { it.marker == QuestMarker.PINNED }
                    if (pinned != null) hud.selectedQuests.value = pinned.questId
                }
            }
        }

        hud.activePlayerFlow
            .flatMapLatest { pid ->
                server.event.filter { it.playerId == pid }
            }
            .map { e -> "[${e.playerId} ${e::class.simpleName}" }
            .onEach { line -> hudLog(hud, line) }
            .launchIn(coroutineScope)

        addPanelSurface {
            modifier
                .align(AlignmentX.End, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), 14.dp))
                .padding(12.dp)
                .width(300.dp)

            Column {
                Text("Игрок: ${hud.activePlayerIdUI.use()}") { modifier.margin(bottom = 4.dp) }

                val goldAmount = server.players.value[hud.activePlayerIdUI.value]?.gold ?: 0
                Text("Золото: $goldAmount / 999") {
                    modifier.margin(bottom = 8.dp)
                }

                Row {
                    Button("Switch Player") {
                        modifier.margin(end = 8.dp).onClick {
                            val newId = if (hud.activePlayerIdUI.value == "Oleg") "Stas" else "Oleg"

                            hud.activePlayerIdUI.value = newId

                            hud.activePlayerFlow.value = newId

                            hud.selectedQuests.value = null
                        }
                    }

                    Button("+50 Gold") {
                        modifier.margin(start = 8.dp).onClick {
                            server.trySend(CmdGiveGold(hud.activePlayerFlow.value, 50))
                        }
                    }
                }

                Row {
                    Button("Collect Herb") {
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdItemCollected(hud.activePlayerFlow.value, "Herb", 1))
                        }
                    }

                    Button("Collect Ore") {
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdItemCollected(hud.activePlayerFlow.value, "Ore", 1))
                        }
                    }
                }

                Text("Активные квесты:") { modifier.margin(top = sizes.gap) }

                val entries = hud.questEntries.use()
                val selectedId = hud.selectedQuests.value

                val sortedEntries = entries.sortedWith(
                    compareByDescending<QuestJournalEntry> { it.marker == QuestMarker.PINNED }
                        .thenByDescending { it.marker == QuestMarker.NEW }
                        .thenByDescending { it.status == QuestStatus.ACTIVE }
                        .thenByDescending { it.marker == QuestMarker.COMPLETED }
                )

                for (q in sortedEntries) {
                    val symbol = markerSymbol(q.marker)

                    val line = "$symbol ${q.title}"
                    Button(line) {
                        modifier.margin(bottom = sizes.smallGap).onClick {
                            hud.selectedQuests.value = q.questId
                            server.trySend(CmdQuestOpened(hud.activePlayerFlow.value, q.questId))
                        }
                    }
                    Text(" - ${q.objectiveText}") {
                        modifier.font(sizes.smallText).margin(bottom = sizes.smallGap)
                    }

                    if (selectedId == q.questId) {
                        if (q.status == QuestStatus.LOCKED) {
                            Text(" ${q.lockedReason}") {
                                modifier.font(sizes.smallText).margin(bottom = sizes.gap)
                            }
                        } else {
                            Text(" ${q.markerHint}") {
                                modifier.font(sizes.smallText).margin(bottom = sizes.smallGap)
                            }

                            if (q.branchText.isNotBlank()) {
                                Text(" ${q.branchText}") {
                                    modifier.font(sizes.smallText).margin(bottom = sizes.smallGap)
                                }
                            }

                            if (q.progressBar.isNotBlank()) {
                                Text("Прогресс: ${q.progressBar}") {
                                    modifier.font(sizes.smallText).margin(bottom = sizes.smallGap)
                                }
                            }

                            if (q.progressText.isNotBlank()) {
                                Text(" ${q.progressText}") {
                                    modifier.font(sizes.smallText).margin(bottom = sizes.gap)
                                }
                            }
                        }

                        Row {
                            Button("Pin") {
                                modifier.margin(end = 8.dp).onClick {
                                    server.trySend(CmdQuestPinned(hud.activePlayerFlow.value, q.questId))
                                }
                            }

                            if (q.questId == "q_alchemist") {
                                Button("Help") {
                                    modifier.margin(end = 8.dp).onClick {
                                        server.trySend(
                                            CmdBranchChosen(
                                                hud.activePlayerFlow.value,
                                                q.questId,
                                                QuestBranch.HELP
                                            )
                                        )
                                    }
                                }
                                Button("Threat") {
                                    modifier.margin(end = 8.dp).onClick {
                                        server.trySend(
                                            CmdBranchChosen(
                                                hud.activePlayerFlow.value,
                                                q.questId,
                                                QuestBranch.THREAT
                                            )
                                        )
                                    }
                                }
                            }

                            if (q.questId == "q_guard") {
                                Button("Pay 1 Gold") {
                                    modifier.margin(end = 8.dp).onClick {
                                        server.trySend(CmdTurnInGold(hud.activePlayerFlow.value, 1, q.questId))
                                    }
                                }
                            }

                            Button("Complete") {
                                modifier.margin(end = 8.dp).onClick {
                                    server.trySend(CmdQuestCompleted(hud.activePlayerFlow.value, q.questId))
                                }
                            }
                        }
                    }
                }
                Text("Log") { modifier.margin(top = sizes.gap) }
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