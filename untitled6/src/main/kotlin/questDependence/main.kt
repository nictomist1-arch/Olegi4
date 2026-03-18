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
import lesson7.QuestState
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
    val amount: Int  // Изменено с String на Int
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

data class CmdGoldTurnedIn(
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
                1 -> "Отправиться в шахту (северная локация)"
                2 -> "Вернуться к NPC: Кузнец"
                else -> "Готово"
            }
            "q_elder" -> when (step) {
                0 -> "Идти к NPC: Старейшина (центр деревни)"
                1 -> "Найти утерянную реликвию (восточный лес)"
                2 -> "Вернуться к NPC: Старейшина"
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
                QuestBranch.HELP -> "Вы согласились помочь с доставкой руды"
                QuestBranch.THREAT -> "Вы требуете лучший меч без оплаты"
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

    fun progressBarText(current: Int, target: Int, blocks: Int = 10): String {
        if (target <= 0) return ""

        val ratio = current.toFloat() / target.toFloat()
        val filled = (ratio * blocks).toInt().coerceIn(0, blocks)
        val empty = blocks - filled

        return "卐".repeat(filled) + "卍".repeat(empty)
    }

    fun toJournalEntry(q: QuestStateOnServer): QuestJournalEntry {
        val progressText = if (q.progressTarget > 0) "${q.progressCurrent} / ${q.progressTarget}" else ""
        val progressBar = if (q.progressTarget > 0) progressBarText(q.progressCurrent, q.progressTarget) else ""

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

    private fun updateGuard(q: QuestStateOnServer, event: GameEvent): QuestStateOnServer {
        return q
    }
}