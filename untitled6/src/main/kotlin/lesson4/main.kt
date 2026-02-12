package lesson4

import lesson2.Item
import lesson2.ItemStack
import lesson2.ItemType

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

class GameState {
    val playerId = mutableStateOf("Player")
    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)
    val poisonTicksLeft = mutableStateOf(0)
    val regenTicksLeft = mutableStateOf(0)
    val damage = mutableStateOf(10)

    val dummyHp = mutableStateOf(50)

    val hotbar = mutableStateOf(
        List<ItemStack?>(9) { null }
    )
    val selectedSlot = mutableStateOf(0)
    val errorMessage = mutableStateOf<String?>(null)
    val eventLog = mutableStateOf<List<String >>(emptyList())
}

val HEALING_POTION = Item(
    "potion_heal",
    "Healing potion",
    ItemType.POTION,
    12,
    0
)

val WOOD_SWORD = Item(
    "wood_sword",
    "Wood sword",
    ItemType.WEAPON,
    1,
    10
)

// Наша игра будет состоять из связки
// Event System -> Quest System -> HUD log + progress
// Почему это вообще надо
// Сейчас кнопки напрямую меняют состояния hp, hotbar, dummyHp
// Если бы мы остановились при написании игры на этой систме, то:
// 1. нопка удар, напрямую бы вычитала Hp У МОБА
// 2. Квесты, NPC и тд не знали бы что удар по мобу произашел
// 3. Система сохранений не знала бы что шаг произошел и его надо зафиксировать
// События решают проблему: кнопка/логика публикуют "произошло X", а другие системы (npc, log, quest)
// подписаны и в зависимости от внутренней логики - реагируют на эти события

// Система событий
// Созданем интерфейс, чтобы все наши события имели playerId
sealed interface GameEvent{
    val playerId: String
}

// События для квеста и логов
// data class - просто удобство, он хранит даннные, как пакет. и автмаатически применяет toString

data class ItemAdded(
    override val playerId: String,
    val itemId: String,
    val countAdded: Int,
    val leftOver: Int
): GameEvent

data class ItemUsed(
    override val playerId: String,
    val itemId: String
): GameEvent

data class DamageDealt(
    override val playerId: String,
    val targetId: String,
    val amount: Int,

): GameEvent

data class EffectApplied(
    override val playerId: String,
    val effectId: String,
    val tick: Int
): GameEvent

data class QuestStepComplete(
    override val playerId: String,
    val questId: String,
    val stepIndex: Int
): GameEvent

class EventBus{
    typealias Listener = (GameEvent) -> Unit
    // Функция принимающая GameEvent возвращает пустоту

    private val listeners = mutableListOf<Listener>()

    fun subscribe(listener: Listener){
        listeners.add(listener)
    }

    fun publish(event: GameEvent){
        for(listener in listeners){
            listener(event)
        }
    }
}

class QuestSystem(
    private val bus: EventBus // Шина события - через нее будет

){
    val questId = "q_training"

    val progressByPlayer = mutableStateOf<Map<String, Int>>(emptyMap())

    init {
        bus.subscribe { event ->
            hadleEvent(event)
        }
    }

    private fun getStep(playerId: String): Int{
        return progressByPlayer.value[playerId] ?: 0
        // ?: - если ключа не найдется вернуть 0
    }

    private fun setStep(playerId: String, step: Int){
        val newMap = progressByPlayer.value.toMutableMap()
        // Создаем новый словарь, чтобы состояние изменило и UI его прочитал
        newMap [playerId] = step
        progressByPlayer.value = newMap.toMap()
    }

    private fun completeStep(playerId: String, stepIndex: Int){
        setStep(playerId, stepIndex + 1)
        // Публикуем событие "шаг квеста выполнен "
        bus.publish(
            QuestStepComplete(
                playerId,
                questId,
                stepIndex
            )
        )
    }

    private fun hadleEvent(event: GameEvent){
        val player = event.playerId
        val step = getStep(player)

        // Если квест уже выполнен
        if (step >= 2) return

        when(event){
            is ItemAdded -> {
                if (step == 0 && event.itemId == WOOD_SWORD.id){
                    completeStep(player, 0)
                }
            }

            is DamageDealt -> {
                // Шаг квеста 1 ударить манекен мечом
                if (step == 1 && event.targetId == "dummy" && event.amount >= 10){
                    completeStep(player,1)
                }
            }
            else -> {}
        }
    }
}

// Функии

fun putIntoSlot(
    slots: List<ItemStack?>,
    slotIndex: Int,
    item: Item,
    addCount: Int
): Pair<List<ItemStack?>, Int> {
    val newSlots = slots.toMutableList()
    val current = newSlots[slotIndex]

    if (current == null) {
        val countToPlace = minOf(addCount, item.maxStack)
        newSlots[slotIndex] = ItemStack(item, countToPlace)
        val leftOver = addCount - countToPlace
        return Pair(newSlots, leftOver)
    }

    if (current.item.id == item.id && item.maxStack > 1) {
        val freeSpace = item.maxStack - current.count
        val toAdd = minOf(addCount, freeSpace)
        newSlots[slotIndex] = ItemStack(item, current.count + toAdd)
        val leftOver = addCount - toAdd
        return Pair(newSlots, leftOver)
    }
    return Pair(newSlots, addCount)
}

fun useSelected(
    slots: List<ItemStack?>,
    slotIndex: Int
): Pair<List<ItemStack?>, ItemStack?> {
    val newSlots = slots.toMutableList()
    val current = newSlots[slotIndex] ?: return Pair(newSlots, null)

    val newCount = current.count - 1

    if (newCount <= 0) {
        newSlots[slotIndex] = null
    } else {
        newSlots[slotIndex] = ItemStack(current.item, newCount)
    }

    return Pair(newSlots, current)
}

fun pushLog(game: GameState, text: String){
    val old = game.eventLog.value

    val updated = old + text

    game.eventLog.value = updated.takeLast(20)
}