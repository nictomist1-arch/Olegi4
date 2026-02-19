package lesson6

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
import de.fabmax.kool.scene.addColorMesh
import de.fabmax.kool.scene.defaultOrbitCamera
import java.io.File
import java.security.Guard

// startWith('quest:') - проверка с чего начинается строка
// substringAfter('quest:') - добавить "кусок" сьроки после префикса
// try {что пытаемся сделать} catch (e: Exception) {сделать то, что произойдет в случае "падения" при загрузке try}
// try catch - не положит весь код fun main если произойдет ошибка

class GameState{
    val playerId = mutableStateOf("Oleg")

    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)

    val log = mutableStateOf<List<String>>(emptyList())
}

fun pushLog(game: GameState, text: String){
    game.log.value = (game.log.value + text).takeLast(20)
}

// sealed - иерархия классов
// Это вид класса, который только хранит в себе другие классы
// Interface - тип класса, обязует все дочерние классы - перезаписать свойства, которые мы положим в вторичный конструкор
sealed interface GameEvent{
    val playerId: String
}

data class TalkedToNpc(
    override val playerId: String,
    val npcId: String
): GameEvent

data class ChoiceSelected(
    override val playerId: String,
    val npcId: String,
    val choiceId: String
): GameEvent

data class ItemCollected(
    override val playerId: String,
    val itemId: String,
    val count: Int
): GameEvent

data class ItemGivenToNpc(
    override val playerId: String,
    val itemId: String,
    val count: Int,
    val npcId: String
): GameEvent

data class GoldPaidToNpc(
    override val playerId: String,
    val count: Int,
    val npcId: String
): GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val questId: String,
    val newStateName: String
): GameEvent

data class PlayerProgressSaved(
    override val playerId: String,
    val reason: String
): GameEvent

//---------------------------------------------------

typealias Listener = (GameEvent) -> Unit

class EventBus{
    private val listeners = mutableListOf<Listener>()

    fun subscribe(listener: Listener){
        listeners.add(listener)
    }

    fun publish(event: GameEvent){
        for (listener in listeners){
            listener(event)
        }
    }
}

data class DialogueOption(
    val id: String,
    val text: String
)

data class DialogueView(
    val npcName: String,
    val text: String,
    val options: List<DialogueOption>
)

// QuestDefinition - описание квеста будет интерфейсом то есть набором правил для всех квсетов при их создании
// Любой новый квест при создании будет наследовать из данного интерфейа, методы

interface QuestDefinition{
    val questId: String

    fun initialStateName(): String
    // Состояние которок будет принимать квест в момент создания

    fun nextStateName(currentStateName: String, event: GameEvent, game: GameState): String
    // Метод который проверяет нынешнее состояние  и возвращает следуещее к которому он прейдет при event событии

    fun stateDescription(stateName: String): String
    // Описание этапа квеста для квестового журнала

    fun npcDialogue(stateName: String) : DialogueView
    // Метод указывает что скажет npc и какие кнопки покажет в диалоге
}

//------------------- Создание квеста с алхимиком (Экземпляр иннтерфейса QuestDefinition) ------------------

enum class AlchemistState{
    START,
    OFFERED,
    HELP_ACCEPTED,
    HERB_COLLECTED,
    THREAT_ACCEPTED,
    GOOD_END,
    EVIL_END
}

class AlchemistQuest: QuestDefinition{
    override val questId: String = "q_alchemist"

    override fun initialStateName(): String {
        return AlchemistState.START.name
    }

    private fun safeState(stateName: String) : AlchemistState{
        // valueOf - можеь "положить" код если строка окажется неправильной
        return try {
            AlchemistState.valueOf(stateName)
        } catch (e: Exception){
            AlchemistState.START
        }
    }

    override fun nextStateName(currentStateName: String, event: GameEvent, game: GameState): String {
        val current = safeState(currentStateName)

        val next: AlchemistState = when(current){
            AlchemistState.START -> when(event){
                is TalkedToNpc -> {
                    if(event.npcId == "Alcemist") AlchemistState.OFFERED else AlchemistState.START
                }
                else -> AlchemistState.START
            }

            AlchemistState.OFFERED -> when(event){
                is ChoiceSelected -> {
                    if(event.npcId != "Alchemist") AlchemistState.OFFERED
                    else if(event.choiceId == "help") AlchemistState.HELP_ACCEPTED
                    else if(event.choiceId == "threat") AlchemistState.THREAT_ACCEPTED
                    else AlchemistState.OFFERED
                }
                else -> AlchemistState.OFFERED

            }

            AlchemistState.HELP_ACCEPTED -> when(event){
                is ItemCollected -> {
                    if (event.itemId == "herb") AlchemistState.HERB_COLLECTED else AlchemistState.HERB_COLLECTED
                }
                else -> AlchemistState.OFFERED
            }

            AlchemistState.HERB_COLLECTED -> when(event){
                is ItemGivenToNpc -> {
                    if(event.npcId == " Alchemist" && event.itemId == "herb" ) AlchemistState.GOOD_END
                    else AlchemistState.HERB_COLLECTED
                }
                else -> AlchemistState.HERB_COLLECTED
            }

            AlchemistState.THREAT_ACCEPTED -> when(event){
                is ChoiceSelected -> {
                    if(event.npcId == "Alcemist" && event.choiceId == "threat_confirm") AlchemistState.EVIL_END
                    else AlchemistState.THREAT_ACCEPTED
                }
                else -> AlchemistState.THREAT_ACCEPTED
            }

            AlchemistState.GOOD_END -> AlchemistState.GOOD_END
            AlchemistState.EVIL_END -> AlchemistState.EVIL_END
        }
        return next.name
    }

    override fun stateDescription(stateName: String): String {
        return when(safeState(stateName)){
            AlchemistState.START -> "Поговорить с Алхимиком"
            AlchemistState.OFFERED -> "Помочь или угрожать"
            AlchemistState.HELP_ACCEPTED -> "Собрать 1 траву"
            AlchemistState.HERB_COLLECTED -> "Отдать траву алхимику"
            AlchemistState.THREAT_ACCEPTED -> "Подтвердить угрозу"
            AlchemistState.GOOD_END -> "Квест завершен (хорошая концовка)"
            AlchemistState.EVIL_END -> "Квест завершен (плохая концовка)"
        }
    }

    override fun npcDialogue(stateName: String): DialogueView {
        return when(safeState(stateName)){
            AlchemistState.START -> DialogueView(
                "Алхимик",
                "Привет! Подойти перестем за траву",
                listOf(DialogueOption("talk", "Поговорить"))
            )
            AlchemistState.OFFERED -> DialogueView(
                "Алхимик",
                "Мне нужна трава подсобишь?",
                listOf(
                    DialogueOption("help", "помочь (принеси травы)"),
                    DialogueOption("threat", "Угрожать")
                )
            )

            AlchemistState.HELP_ACCEPTED -> DialogueView(
                "Алхимик",
                "Принеси 1 траву",
                listOf(
                    DialogueOption("collect_herb", "собрать траву"),
                    DialogueOption("talk", "Поговорить еще")
                )
            )

            AlchemistState.HERB_COLLECTED -> DialogueView(
                "Алхимик",
                "Отлично давай траву",
                listOf(
                    DialogueOption("give_herb", "Отдать 1 траву"),
                )
            )

            AlchemistState.THREAT_ACCEPTED -> DialogueView(
                "Алхимик",
                "Ты уверен мабой",
                listOf(
                    DialogueOption("threat_confirm", "Да гони золото"),
                )
            )

            AlchemistState.GOOD_END -> DialogueView(
                "Алхимик",
                "Спасибо держи зелье здоровья и 50 золота (GOOD END)",
                emptyList()
            )

            AlchemistState.EVIL_END -> DialogueView(
                "Алхимик",
                "Ладно, держи свое золото но ты об этом пожалеешь (EVIL_END)",
                emptyList()
            )

        }
    }
}

//--------- Квест со стражником (вещь или бан) ---------
enum class GuardState{
    START,
    OFFERED,
    WAIT_PAYMENT,
    PASSED,
    BANNED
}

class GuardQuest: QuestDefinition{
    override val questId: String = "q_guard"

    override fun initialStateName(): String = GuardState.START.name

    private fun safeState(stateName: String): GuardState{
        return try {
            GuardState.valueOf(stateName)
        } catch (e: Exception){
            GuardState.START
        }
    }

    override fun nextStateName(currentStateName: String, event: GameEvent, game: GameState): String {
        val current = safeState(currentStateName)

        val next: GuardState = when(current){
            GuardState.START -> when(event){
                is TalkedToNpc -> {
                    if(event.npcId == "guard") GuardState.OFFERED else GuardState.START
                }
                else -> GuardState.START
            }

            GuardState.OFFERED -> when(event){
                is ChoiceSelected ->{
                    if (event.npcId != "guard") GuardState.OFFERED
                    else if (event.choiceId == "pay") GuardState.WAIT_PAYMENT
                    else if (event.choiceId == "refuse") GuardState.BANNED
                    else GuardState.OFFERED
                }
                else -> GuardState.OFFERED
            }

            GuardState.WAIT_PAYMENT -> when(event){
                is GoldPaidToNpc -> {
                    if (event.npcId == "guard" && event.count >= 5) GuardState.PASSED
                    else GuardState.WAIT_PAYMENT
                }
                else -> GuardState.WAIT_PAYMENT
            }

            GuardState.PASSED -> GuardState.PASSED
            GuardState.BANNED -> GuardState.BANNED
        }
        return next.name
    }

    override fun stateDescription(stateName: String): String {
        return when(safeState(stateName)){
            GuardState.START -> "Поговорить со стражником у ворот"
            GuardState.OFFERED -> "Стражник требует плату за проход. Нужно заплатить 5 золота или отказаться"
            GuardState.WAIT_PAYMENT -> "Заплатить стражнику 5 золота"
            GuardState.PASSED -> "Квест завершен - вас пропустили через ворота"
            GuardState.BANNED -> "Квест провален - вас выгнали из города"
        }
    }

    override fun npcDialogue(stateName: String): DialogueView {
        return when(safeState(stateName)){
            GuardState.START -> DialogueView(
                "Стражник",
                "Стоять! Дальше проход только для своих.",
                listOf(
                    DialogueOption("talk", "Поговорить со стражником")
                )
            )

            GuardState.OFFERED -> DialogueView(
                "Стражник",
                "Если хочешь пройти - плати 5 золота. Или убирайся!",
                listOf(
                    DialogueOption("pay", "Заплатить 5 золота"),
                    DialogueOption("refuse", "Отказаться и уйти")
                )
            )

            GuardState.WAIT_PAYMENT -> DialogueView(
                "Стражник",
                "Ну что, есть 5 золота? Плати - проходишь. Нет - проваливай!",
                listOf(
                    DialogueOption("pay_confirm", "Заплатить 5 золота"),
                    DialogueOption("cancel", "Передумать")
                )
            )

            GuardState.PASSED -> DialogueView(
                "Стражник",
                "Проходи, путник. Хорошей дороги!",
                emptyList()
            )

            GuardState.BANNED -> DialogueView(
                "Стражник",
                "Я же сказал - убирайся! И не попадайся мне больше на глаза!",
                emptyList()
            )
        }
    }
}

// ---------- QuestManager ----------
class QuestManager(
    private val bus: EventBus,
    private val game: GameState,
    private val quests: List<QuestDefinition>
){
    val stateByPlayer = mutableStateOf<Map<String, Map<String, String>>>(emptyMap())

    init {
        bus.subscribe { event ->
            handleEvent(event)
        }
    }

    fun getStateName(playerId: String, questId: String ): String{
        val playerMap = stateByPlayer.value[playerId]

        if (playerMap == null){
            val def = quests.firstOrNull{it.questId == questId}
            return def?.initialStateName() ?: "UNKNOWN"
        }

        return playerMap[questId] ?: (quests.firstOrNull{it.questId == questId}?.initialStateName() ?: "UNKNOWN")
    }

    fun setStateName(playerId: String, questId: String, stateName: String){
        val outerCopy = stateByPlayer.value.toMutableMap()

        val innerOld = outerCopy[playerId] ?: emptyMap()

        val innerCopy = innerOld.toMutableMap()
        innerCopy[playerId] = stateName

        outerCopy[playerId] = innerCopy.toMap()
        stateByPlayer.value = outerCopy.toMap()
    }

    private fun handleEvent(event: GameEvent) {
        val playerId = event.playerId

        for (quest in quests) {
            val currentState = getPlayerQuestState(playerId, quest.questId)
                ?: quest.initialStateName()

            val nextState = quest.nextStateName(currentState, event, game)

            if (nextState != currentState) {
                setPlayerQuestState(playerId, quest.questId, nextState)

                bus.publish(
                    QuestStateChanged(
                        playerId = playerId,
                        questId = quest.questId,
                        newStateName = nextState
                    )
                )

                bus.publish(
                    PlayerProgressSaved(
                        playerId = playerId,
                        reason = "quest_${quest.questId}_${nextState}"
                    )
                )
            }
        }
    }

    fun getPlayerQuestState(playerId: String, questId: String): String? {
        return stateByPlayer.value[playerId]?.get(questId)
    }

    fun setPlayerQuestState(playerId: String, questId: String, stateName: String) {
        val currentMap = stateByPlayer.value.toMutableMap()

        val playerQuests = currentMap[playerId]?.toMutableMap() ?: mutableMapOf()
        playerQuests[questId] = stateName

        currentMap[playerId] = playerQuests
        stateByPlayer.value = currentMap
    }

    fun getAllQuestStates(playerId: String): Map<String, String> {
        return stateByPlayer.value[playerId] ?: emptyMap()
    }
}