package lesson3

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



// Создать броню - предмет (maxStack = 1)
val LEATHER_ARMOR = Item(
    "leather_armor",
    "Leather armor",
    ItemType.ARMOR,
    1,
    0
)

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

fun hasActiveArmor(slots: List<ItemStack?>): Boolean {
    val lastSlotIndex = 8
    val itemInLastSlot = slots.getOrNull(lastSlotIndex)
    return itemInLastSlot?.item?.type == ItemType.ARMOR
}

fun main() = KoolApplication {
    val game = GameState()

    addScene {
        defaultOrbitCamera()

        addColorMesh {
            generate { cube { colored() } }

            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0.8f)
                roughness(0.2f)
            }

            onUpdate {
                transform.rotate(45f.deg * Time.deltaT, Vec3f.Z_AXIS)
            }
        }
        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, -1f))
            setColor(Color.WHITE, 5f)
        }

        var poisonTimerSec = 0f
        var regenTimerSec = 0f

        if (game.poisonTicksLeft.value > 0) {
            poisonTimerSec += Time.deltaT

            if (poisonTimerSec >= 1f) {
                poisonTimerSec = 0f
                game.poisonTicksLeft.value = game.poisonTicksLeft.value - 1


                val poisonDamage = if (hasActiveArmor(game.hotbar.value)) {
                    1
                } else {
                    2
                }

                game.hp.value = (game.hp.value - poisonDamage).coerceAtLeast(0)
            }
        } else {
            poisonTimerSec = 0f
        }

        if (game.regenTicksLeft.value > 0) {
            regenTimerSec += Time.deltaT

            if (regenTimerSec >= 1f) {
                regenTimerSec = 0f
                game.regenTicksLeft.value -= 1
                game.hp.value = (game.hp.value + 1).coerceAtMost(100)
            }
        } else {
            regenTimerSec = 0f
        }

        addScene {
            setupUiScene(ClearColorLoad)

            addPanelSurface {
                modifier
                    .align(AlignmentX.Start, AlignmentY.Top)
                    .margin(16.dp)
                    .background(RoundRectBackground(
                        Color(0f, 0f, 0f, 0.6f),
                        14.dp)
                    )
                    .padding(12.dp)
                Column {
                    Text("Игрок: ${game.playerId.use()}") {}
                    Text("HP: ${game.hp.use()} Золото: ${game.gold.use()}") {
                        modifier.margin(bottom = sizes.gap)
                    }
                    Text("Яд: ${game.poisonTicksLeft.use()}") {}
                    Text("Реген: ${game.regenTicksLeft.use()}") {}

                    Text("HP Манекена: ${game.dummyHp.use()}") {
                        modifier.margin(bottom = sizes.gap)
                    }

                    val slots = game.hotbar.use()
                    val selected = game.selectedSlot.use()

                    Text("Броня активна: ${if (hasActiveArmor(slots)) "Да" else "Нет"}") {
                        modifier.margin(bottom = sizes.smallGap)
                    }

                    Row {
                        modifier.margin(bottom = sizes.smallGap)

                        for (i in 0 until 9) {
                            val isSelected = (i == selected)

                            Box {
                                modifier
                                    .size(44.dp, 44.dp)
                                    .margin(end = 6.dp)
                                    .background(
                                        RoundRectBackground(
                                            if (isSelected) {
                                                Color(0.2f, 0.6f, 1f, 0.8f)
                                            } else {
                                                Color(0f, 0f, 0f, 0.8f)
                                            },
                                            8.dp
                                        )
                                    )
                                    .onClick {
                                        game.selectedSlot.value = i
                                    }
                                Text("${i + 1}") {
                                    modifier
                                        .padding(4.dp)
                                        .font(sizes.smallText)

                                }

                                val stack = slots[i]
                                if (stack != null) {
                                    Column {
                                        modifier.padding(top = 18.dp, start = 6.dp)
                                        Text(stack.item.name) {
                                            modifier.font(sizes.smallText)
                                        }
                                        Text("x${stack.count}") {
                                            modifier.font(sizes.smallText)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val selectedStack = slots[selected]
                    Text(
                        if (selectedStack == null) "Выбрано: (пусто)"
                        else "Выбрано: ${selectedStack.item.name} x${selectedStack.count}"
                    ) {
                        modifier.margin(top = sizes.gap, bottom = sizes.gap)
                    }

                    Row {
                        modifier.margin(top = sizes.smallGap)

                        Button("Получить зелье") {
                            modifier
                                .margin(end = 8.dp)
                                .onClick {
                                    val idx = game.selectedSlot.value

                                    val (updatedSlots, leftOver) =
                                        putIntoSlot(game.hotbar.value, idx, HEALING_POTION, 6)
                                    game.hotbar.value = updatedSlots

                                    if (leftOver > 0) {
                                        game.gold.value += leftOver
                                    }
                                }
                        }

                        Button("Получить меч") {
                            modifier
                                .margin(end = 8.dp)
                                .onClick {
                                    val idx = game.selectedSlot.value

                                    val (updatedSlots, leftOver) =
                                        putIntoSlot(game.hotbar.value, idx, WOOD_SWORD, 1)
                                    game.hotbar.value = updatedSlots

                                    if (leftOver > 0) {
                                        game.gold.value += 1
                                    }
                                }
                        }

                        Button("Получить броню") {
                            modifier
                                .margin(end = 8.dp)
                                .onClick {
                                    val idx = game.selectedSlot.value

                                    val (updatedSlots, leftOver) =
                                        putIntoSlot(game.hotbar.value, idx, LEATHER_ARMOR, 1)
                                    game.hotbar.value = updatedSlots

                                    if (leftOver > 0) {
                                        game.gold.value += 1
                                    }
                                }
                        }
                    }

                    Row {
                        modifier.margin(top = sizes.smallGap)

                        Button("Использовать предмет") {
                            modifier.onClick {
                                val idx = game.selectedSlot.value
                                game.errorMessage.value = null

                                val (updatedSlots, used) = useSelected(game.hotbar.value, idx)
                                game.hotbar.value = updatedSlots

                                if (used != null && used.item.type == ItemType.POTION) {
                                    game.hp.value = (game.hp.value + 20).coerceAtMost(100)
                                }
                            }
                        }

                        val attackButtonText = remember { mutableStateOf("") }

                        Button("Выбор"){
                            val idx = game.selectedSlot.value
                            val stack = game.hotbar.value[idx]

                            attackButtonText.value = if (stack != null && stack.item.type == ItemType.WEAPON) {
                                "Атаковать Мечом"
                            } else {
                                "Атаковать руками"
                            }
                            attackButtonText.value
                        }
                            modifier.onClick {
                                val idx = game.selectedSlot.value
                                val stack = game.hotbar.value[idx]

                                if (stack != null && stack.item.type == ItemType.WEAPON) {
                                    game.dummyHp.value = (game.dummyHp.value - 10).coerceAtLeast(0)
                                } else {
                                    game.dummyHp.value = (game.dummyHp.value - 3).coerceAtLeast(0)
                                }
                            }
                        }
                    }

                    Row {
                        modifier.margin(top = sizes.smallGap)

                        Button("Наложить яд") {
                            modifier.onClick {
                                game.poisonTicksLeft.value += 5
                            }
                        }
                        Button("Сбросить манекен") {
                            modifier
                                .margin(start = 5.dp)
                                .onClick {
                                    game.dummyHp.value = 50
                                }
                        }
                    }
                }
            }
        }
    }
