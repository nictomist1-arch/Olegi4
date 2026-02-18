package lesson5

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

enum class ItemType{
    POTION,
    QUEST_ITEM,
    MONEY
}

data class Item(
    val id: String,
    val name: String,
    val type: ItemType,
    val maxStack: Int
)

data class ItemStack(
    val item: Item,
    val count: Int
)

val HERB = Item(
    "herb",
    "Herb",
    ItemType.QUEST_ITEM,
    16
)

val HEALING_POTION = Item(
    "potion_heal",
    "Heal Potion",
    ItemType.POTION,
    6
)

class GameState{
    val playerId = mutableStateOf("Oleg")
    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)
    val inventory = mutableStateOf(List<ItemStack?>(5) {null})
    val selectedSlot = mutableStateOf(0)
    val log = mutableStateOf<List<String>>(emptyList())
}

fun pushLog(game: GameState, text: String){
    game.log.value = (game.log.value + text).takeLast(20)
}

sealed interface GameEvent{
    val playerId: String
}

data class TalkedToNpc(
    override val playerId: String,
    val npcId: String
) : GameEvent

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
    val npcId: String,
    val itemId: String,
    val count: Int
): GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val questId: String,
    val newState: String
): GameEvent

data class PlayerProgressSaved(
    override val playerId: String,
    val questId: String,
    val stateName: String
): GameEvent

typealias Listener = (GameEvent) -> Unit

class EventBus{
    private val listeners = mutableStateListOf<Listener>()

    fun subscribe(listener: Listener){
        listeners.add(listener)
    }

    fun publish(event: GameEvent){
        for (l in listeners){
            l(event)
        }
    }
}

enum class QuestState{
    START,
    OFFERED,
    ACCEPTED_HELP,
    ACCEPTED_THREAT,
    HERB_COLLECTED,
    GOOD_END,
    EVIL_END
}

class StateGraph<S: Any, E: Any>(
    private val initial: S
){
    private val transitions = mutableMapOf<S, MutableMap<Class<out E>, (E) -> S>>()

    fun on(from:S, eventClass: Class<out E>, to: (E) -> S){
        val byEvent = transitions.getOrPut(from){ mutableMapOf() }
        byEvent[eventClass] = to
    }

    fun next(current: S, event: E): S{
        val byEvent = transitions[current] ?: return  current
        val eventClass = event::class.java
        val handler = byEvent[eventClass] ?: return current
        return handler(event)
    }

    fun initialState(): S = initial
}

class QuestSystem(
    private val bus: EventBus
) {
    val questId = "q_alchemist"
    val stateByPlayer = mutableStateOf<Map<String, QuestState>>(emptyMap())
    private val graph = StateGraph<QuestState, GameEvent>(QuestState.START)

    init {
        graph.on(QuestState.OFFERED, ChoiceSelected::class.java) { e ->
            val ev = e as ChoiceSelected
            if (ev.choiceId == "help") QuestState.ACCEPTED_HELP else QuestState.ACCEPTED_THREAT
        }

        graph.on(QuestState.ACCEPTED_HELP, ItemCollected::class.java) { e ->
            val ev = e as ItemCollected
            if (ev.itemId == HERB.id) QuestState.HERB_COLLECTED else QuestState.ACCEPTED_HELP
        }

        graph.on(QuestState.HERB_COLLECTED, ItemCollected::class.java) { e ->
            val ev = e as ItemCollected
            if (ev.itemId == HERB.id) QuestState.GOOD_END else QuestState.HERB_COLLECTED
        }

        graph.on(QuestState.ACCEPTED_THREAT, ChoiceSelected::class.java) { e ->
            val ev = e as ChoiceSelected
            if (ev.choiceId == "threaten_confirm") QuestState.EVIL_END else QuestState.ACCEPTED_THREAT
        }

        bus.subscribe { event ->
            advance(event)
        }
    }

    fun getState(playerId: String): QuestState {
        return stateByPlayer.value[playerId] ?: graph.initialState()
    }

    fun setState(playerId: String, state: QuestState) {
        val copy = stateByPlayer.value.toMutableMap()
        copy[playerId] = state
        stateByPlayer.value = copy.toMap()
    }

    private fun advance(event: GameEvent) {
        val pid = event.playerId
        val current = getState(pid)
        val next = graph.next(current, event)

        if (next != current) {
            setState(pid, next)

            bus.publish(
                QuestStateChanged(
                    pid,
                    questId,
                    next.name
                )
            )

            bus.publish(
                PlayerProgressSaved(
                    pid,
                    questId,
                    next.name
                )
            )
        }
    }
}

class SaveSystem(
    private val bus: EventBus,
    private val game: GameState,
    private val quests: QuestSystem
) {
    init {
        bus.subscribe { event ->
            if (event is PlayerProgressSaved) {
                save(event.playerId, event.questId, event.stateName)
            }
        }
    }

    private fun saveFile(playerId: String, questId: String): File {
        val dir = File("saves")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "${playerId}_${questId}.save")
    }

    private fun save(playerId: String, questId: String, stateName: String) {
        val f = saveFile(playerId, questId)
        val text =
            "playerId=$playerId\n" +
                    "questId=${questId}\n" +
                    "state=${stateName}\n" +
                    "hp=${game.hp.value}\n" +
                    "gold=${game.gold.value}"
        f.writeText(text)
    }
}

fun addItem(slots: List<ItemStack?>, item: Item, addCount: Int): Pair<List<ItemStack?>, Int> {
    var left = addCount
    val newSlots = slots.toMutableList()

    for (i in newSlots.indices){
        val s = newSlots[i] ?: continue
        if (s.item.id == item.id && item.maxStack > 1 && left > 0) {
            val free = item.maxStack - s.count
            val toAdd = minOf(left, free)
            newSlots[i] = ItemStack(item, s.count + toAdd)
            left -= toAdd
        }
    }

    for (i in newSlots.indices){
        if(left <= 0) break
        if (newSlots[i] == null) {
            val toPlace = minOf(left, item.maxStack)
            newSlots[i] = ItemStack(item, toPlace)
            left -= toPlace
        }
    }

    return Pair(newSlots, left)
}

fun removeItem(slots: List<ItemStack?>, itemId: String, count: Int): Pair<List<ItemStack?>, Boolean> {
    var need = count
    val newSlots = slots.toMutableList()

    for(i in newSlots.indices) {
        val s = newSlots[i] ?: continue
        if (s.item.id == itemId && need > 0) {
            val take = minOf(need, s.count)
            val leftInStack = s.count - take
            need -= take
            newSlots[i] = if (leftInStack <= 0) null else ItemStack(s.item, leftInStack)
        }
    }

    val success = (need == 0)
    return Pair(newSlots, success)
}

data class DialogueOption(
    val id: String,
    val text: String
)

data class DialogueView(
    val text: String,
    val options: List<DialogueOption>
)

class Npc(
    val id: String,
    val name: String
) {
    fun dialogueFor(state: QuestState): DialogueView {
        return when (state) {
            QuestState.START -> DialogueView(
                "Алхимик: Привет, подходи, перетрем",
                listOf(
                    DialogueOption("talk", "Поговорить")
                )
            )
            QuestState.OFFERED -> DialogueView(
                "Алхимик: Мне нужно, чтобы ты достал мне немного травы",
                listOf(
                    DialogueOption("help", "Окей, помогу тебе за долю"),
                    DialogueOption("threat", "Не, это ты мне давай траву")
                )
            )
            QuestState.ACCEPTED_HELP -> DialogueView(
                "Алхимик: Хорош мужик, жду тебя тут",
                listOf(
                    DialogueOption("collect_herb", "Собрать траву (симуляция)")
                )
            )
            QuestState.HERB_COLLECTED -> DialogueView(
                "Алхимик: Ну че, принес? Давай сюда!",
                listOf(
                    DialogueOption("give_herb", "Отдать траву")
                )
            )
            QuestState.ACCEPTED_THREAT -> DialogueView(
                "Алхимик: Э, малой, ты уверен?",
                listOf(
                    DialogueOption("threaten_confirm", "Да, гони сюда")
                )
            )
            QuestState.GOOD_END -> DialogueView(
                "Алхимик: Хорош мужик, выручил",
                emptyList()
            )
            QuestState.EVIL_END -> DialogueView(
                "Алхимик: Ладно, держи траву, ходи оглядывайся, земляк",
                emptyList()
            )
        }
    }
}

fun main() = KoolApplication {
    val game = GameState()
    val bus = EventBus()
    val quests = QuestSystem(bus)
    val saves = SaveSystem(bus, game, quests)

    val npc = Npc("alchemist", "Alchemist")

    bus.subscribe { e ->
        val line = when (e) {
            is QuestStateChanged -> "QuestStateChanged: ${e.questId} -> ${e.newState}"
            is PlayerProgressSaved -> "Saved: ${e.questId} state=${e.stateName}"
            is TalkedToNpc -> "TalkedToNpc: ${e.npcId}"
            is ChoiceSelected -> "ChoiceSelected: ${e.choiceId}"
            is ItemCollected -> "ItemCollected: ${e.itemId} x${e.count}"
            is ItemGivenToNpc -> "ItemGivenToNpc: ${e.itemId} x${e.count}"
        }
        pushLog(game, "[${e.playerId}] ${line}")
    }

    addScene {
        defaultOrbitCamera()

        addColorMesh {
            generate { cube { colored() } }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0.8f)
                roughness(0.3f)
            }
            onUpdate {
                transform.rotate(45f.deg * Time.deltaT, Vec3f.Z_AXIS)
            }
        }

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, -1f))
            setColor(Color.WHITE, 5f)
        }
    }

    addScene {
        setupUiScene(ClearColorLoad)

        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), 14.dp))
                .padding(12.dp)
                .width(400.dp)

            Column {
                Text("Player: ${game.playerId.use()}"){}
                Text("Hp: ${game.hp.use()}"){}
                Text("Gold: ${game.gold.use()}"){}

                modifier.margin(bottom = 8.dp)

                val state = quests.stateByPlayer.use()[game.playerId.use()] ?: QuestState.START
                Text("Quest State: ${state.name}"){}

                modifier.margin(bottom = 8.dp)

                val view = npc.dialogueFor(state)
                Text("${npc.name}"){}
                Text(view.text){}

                modifier.margin(bottom = 8.dp)

                Row {
                    for (opt in view.options) {
                        Button(opt.text) {
                            modifier.margin(end = 8.dp).onClick {
                                val pid = game.playerId.value

                                when (opt.id) {
                                    "talk" -> {
                                        bus.publish(TalkedToNpc(pid, npc.id))
                                        if (state == QuestState.START) {
                                            quests.setState(pid, QuestState.OFFERED)
                                        }
                                    }
                                    "help" -> {
                                        bus.publish(ChoiceSelected(pid, npc.id, "help"))
                                    }
                                    "threat" -> {
                                        bus.publish(ChoiceSelected(pid, npc.id, "threat"))
                                    }
                                    "collect_herb" -> {
                                        val (updated, left) = addItem(game.inventory.value, HERB, 1)
                                        game.inventory.value = updated
                                        bus.publish(ItemCollected(pid, HERB.id, 1))
                                        if (left > 0) {
                                            val goldEarned = left * 10
                                            game.gold.value += goldEarned
                                            pushLog(game, "Трава не поместилась, продана за $goldEarned золота")
                                        } else {
                                            pushLog(game, "Трава добавлена в инвентарь")
                                        }
                                    }
                                    "give_herb" -> {
                                        val (updated, success) = removeItem(game.inventory.value, HERB.id, 1)
                                        game.inventory.value = updated
                                        if (success) {
                                            bus.publish(ItemGivenToNpc(pid, npc.id, HERB.id, 1))
                                            val (updatedWithReward, leftReward) = addItem(game.inventory.value, HEALING_POTION, 2)
                                            game.inventory.value = updatedWithReward
                                            if (leftReward > 0) {
                                                val goldEarned = leftReward * 25
                                                game.gold.value += goldEarned
                                                pushLog(game, "Зелья не поместились, проданы за $goldEarned золота")
                                            } else {
                                                pushLog(game, "Получено 2 зелья здоровья!")
                                            }
                                        } else {
                                            pushLog(game, "Ошибка: у вас нет травы!")
                                        }
                                    }
                                    "threaten_confirm" -> {
                                        bus.publish(ChoiceSelected(pid, npc.id, "threaten_confirm"))
                                        val (updated, left) = addItem(game.inventory.value, HERB, 3)
                                        game.inventory.value = updated
                                        if (left > 0) {
                                            val goldEarned = left * 10
                                            game.gold.value += goldEarned
                                            pushLog(game, "Часть травы продана за $goldEarned золота")
                                        }
                                        pushLog(game, "Вы получили 3 травы!")
                                    }
                                }
                            }
                        }
                    }
                }

                modifier.margin(bottom = 16.dp)

                Text("Инвентарь:") { modifier.margin(bottom = 4.dp) }

                val inventory = game.inventory.use()
                Column {
                    for (i in inventory.indices) {
                        val slot = inventory[i]
                        val slotText = if (slot != null) {
                            "${slot.item.name} x${slot.count}"
                        } else {
                            "[пусто]"
                        }

                        Row {
                            Text("Слот ${i + 1}: $slotText") {
                                modifier
                                    .padding(vertical = 2.dp)
                            }

                            if (slot != null && slot.item.id == "potion_heal") {
                                Button("Исп.") {
                                    modifier
                                        .width(40.dp)
                                        .margin(start = 8.dp)
                                        .onClick {
                                            if (game.hp.value < 100) {
                                                game.hp.value = minOf(100, game.hp.value + 30)
                                                val (updated, _) = removeItem(game.inventory.value, "potion_heal", 1)
                                                game.inventory.value = updated
                                                pushLog(game, "Использовано зелье лечения +30 HP")
                                            }
                                        }
                                }
                            }
                        }
                    }
                }

                modifier.margin(top = 16.dp)

                Row {
                    Button("+Трава") {
                        modifier.margin(end = 8.dp).onClick {
                            val (updated, left) = addItem(game.inventory.value, HERB, 1)
                            game.inventory.value = updated
                            if (left > 0) {
                                game.gold.value += left * 10
                                pushLog(game, "Трава продана за ${left * 10} золота")
                            }
                        }
                    }

                    Button("+Зелье") {
                        modifier.onClick {
                            val (updated, left) = addItem(game.inventory.value, HEALING_POTION, 1)
                            game.inventory.value = updated
                            if (left > 0) {
                                game.gold.value += left * 25
                                pushLog(game, "Зелье продано за ${left * 25} золота")
                            }
                        }
                    }
                }

                modifier.margin(top = 16.dp)
                Text("Лог событий:") {}
                Column {
                    for (logEntry in game.log.use().reversed()) {
                        Text("• $logEntry") {
                            modifier.padding(vertical = 2.dp)

                        }
                    }
                }
            }
        }
    }
}

// "collect_herb"  "give_herb"  "threat"  "help"  "threaten_confirm"
// Сделать для данных опций - логику

// Привет "collect_herb"
// 1. создать пары переменных val (updated, left) - положить в них с помощью addItem предмет
// 2. обновить инвентарь но уже с полученной травой
// 3. публикуем событие о том, что предмет квестовый получен
// 4. Если предмет не поместился в слоты (left остался) преобразовать остаток в золото

// "give_herb"
// 1. создать пары переменных val (updated, left) - положить в них с помощью addItem предмет
// 2. обновить инвентарь но уже с удаленной травой
// 3. проверка на успех удаления - и публикация в случае успеха события о передаче прс
// 4. кладем в инвентарь награду за квест (зелья здоровья)
// 5. Иначе, если неудача в проверке передачи травы - написать об этом сообщение (в логи)
