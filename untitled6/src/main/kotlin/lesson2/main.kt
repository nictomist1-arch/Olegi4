package lesson2

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

// урок 2 - hotbar инвентарь
enum class ItemType {
    WEAPON,
    ARMOR,
    POTION
}

data class Item(
    val id: String,
    val name: String,
    val type: ItemType,
    val maxStack: Int,
    val damageBonus: Int = 0
)

data class ItemStack(
    val item: Item,
    val count: Int
)

class GameState {
    val playerId = mutableStateOf("Player")
    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)
    val poisonTicksLeft = mutableStateOf(0)
    val damage = mutableStateOf(10) // Базовый урон по умолчанию 10
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

val IRON_SWORD = Item(
    "iron_sword",
    "Iron sword",
    ItemType.WEAPON,
    1,
    20
)

fun putInToSlot(
    slots: List<ItemStack?>,
    slotIndex: Int,
    item: Item,
    addCount: Int,
    onError: ((String) -> Unit)? = null
): List<ItemStack?> {
    val newSlots = slots.toMutableList()
    val current = newSlots[slotIndex]

    if (current == null) {
        val count = minOf(addCount, item.maxStack)
        newSlots[slotIndex] = ItemStack(item, count)
        return newSlots
    }

    if (current.item.id == item.id) {
        if (item.maxStack <= 1) {
            onError?.invoke("Ошибка: предмет '${item.name}' нельзя стакать (максимум 1 в слоте)")
            return slots
        }

        val freeSpace = item.maxStack - current.count
        if (freeSpace <= 0) {
            onError?.invoke("Ошибка: слот для '${item.name}' переполнен (максимум ${item.maxStack})")
            return slots
        }

        val toAdd = minOf(addCount, freeSpace)
        newSlots[slotIndex] = ItemStack(item, current.count + toAdd)
        return newSlots
    } else {
        onError?.invoke("Ошибка: в слоте уже находится другой предмет '${current.item.name}'")
        return slots
    }
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

fun updateDamageBasedOnSelectedSlot(game: GameState) {
    val selectedSlotIndex = game.selectedSlot.value
    val hotbar = game.hotbar.value

    var totalDamage = 10

    val selectedItemStack = hotbar.getOrNull(selectedSlotIndex)

    if (selectedItemStack != null && selectedItemStack.item.type == ItemType.WEAPON) {
        totalDamage += selectedItemStack.item.damageBonus
    }

    game.damage.value = totalDamage
}

fun main() = KoolApplication {
    val game = GameState()

    addScene {
        defaultOrbitCamera()

        addColorMesh {
            generate { cube { colored() } }

            shader = KslPbrShader {
                color {
                    vertexColor()
                }
                metallic(0.7f)
                roughness(0.6f)
            }
            onUpdate {
                transform.rotate(45f.deg * Time.deltaT, Vec3f.X_AXIS)
            }
        }
        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, -1f))
            setColor(Color.WHITE, 5f)
        }

        var poisonTimerSec = 0f

        if (game.poisonTicksLeft.value > 0) {
            poisonTimerSec += Time.deltaT

            if (poisonTimerSec >= 1f) {
                poisonTimerSec = 0f
                game.poisonTicksLeft.value = game.poisonTicksLeft.value - 1
                game.hp.value = (game.hp.value - 2).coerceAtLeast(0)
            }
        } else {
            poisonTimerSec = 0f
        }
    }

    addScene {
        setupUiScene(ClearColorLoad)

        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(20.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.5f), 20.dp))
                .padding(12.dp)

            Column {
                val errorMsg = game.errorMessage.use()
                if (!errorMsg.isNullOrEmpty()) {
                    Box {
                        modifier
                            .margin(bottom = 10.dp)
                            .padding(8.dp)
                            .background(RoundRectBackground(Color(1f, 0.2f, 0.2f, 0.8f), 8.dp))

                        Text(errorMsg) {
                            modifier
                                .font(sizes.normalText)
                                .color(Color.WHITE)
                        }
                    }
                }

                Text("Player: ${game.playerId.use()}") {}
                Text("HP: ${game.hp.use()} Gold: ${game.gold.use()}") {}
                Text("Poison: ${game.poisonTicksLeft.use()}") {}
                Text("Damage: ${game.damage.use()}") {
                    modifier
                        .margin(bottom = 10.dp)
                        .font(sizes.largeText)
                        .color(Color(1f, 0.5f, 0f))
                }

                modifier.padding(10.dp)
                Row {
                    modifier.margin(top = 6.dp)

                    val slots = game.hotbar.use()
                    val selected = game.selectedSlot.use()

                    for (i in 0 until 9) {
                        val isSelected = i == selected
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
                                    game.errorMessage.value = null

                                    updateDamageBasedOnSelectedSlot(game)
                                }
                            val stack = slots[i]
                            if (stack == null) {
                                Text(" ") {}
                            } else {
                                Column {
                                    modifier.padding(6.dp)

                                    Text(stack.item.name) {
                                        modifier.font(sizes.smallText)
                                    }

                                    Text("${stack.count}") {
                                        modifier.font(sizes.smallText)
                                    }
                                }
                            }
                        }
                    }
                }

                Row {
                    modifier.margin(top = 6.dp)

                    Button("Наложить эффект") {
                        modifier
                            .margin(end = 8.dp)
                            .onClick {
                                val idx = game.selectedSlot.value
                                game.errorMessage.value = null

                                val updated = putInToSlot(
                                    game.hotbar.value,
                                    idx,
                                    HEALING_POTION,
                                    1
                                ) { error ->
                                    game.errorMessage.value = error
                                }
                                game.hotbar.value = updated
                            }
                    }

                    Button("Деревянный меч") {
                        modifier
                            .margin(end = 8.dp)
                            .onClick {
                                val idx = game.selectedSlot.value
                                game.errorMessage.value = null

                                val updated = putInToSlot(
                                    game.hotbar.value,
                                    idx,
                                    WOOD_SWORD,
                                    1
                                ) { error ->
                                    game.errorMessage.value = error
                                }
                                game.hotbar.value = updated

                                updateDamageBasedOnSelectedSlot(game)
                            }
                    }

                    Button("Железный меч") {
                        modifier
                            .margin(end = 8.dp)
                            .onClick {
                                val idx = game.selectedSlot.value
                                game.errorMessage.value = null

                                val updated = putInToSlot(
                                    game.hotbar.value,
                                    idx,
                                    IRON_SWORD,
                                    1
                                ) { error ->
                                    game.errorMessage.value = error
                                }
                                game.hotbar.value = updated

                                updateDamageBasedOnSelectedSlot(game)
                            }
                    }

                    Button("Использовать предмет") {
                        modifier.onClick {
                            val idx = game.selectedSlot.value
                            game.errorMessage.value = null

                            val (updatedSlots, used) = useSelected(game.hotbar.value, idx)
                            game.hotbar.value = updatedSlots

                            if (used != null && used.item.type == ItemType.POTION) {
                                game.hp.value = (game.hp.value + 20).coerceAtMost(100)
                            }

                            updateDamageBasedOnSelectedSlot(game)
                        }
                    }
                }

                Row {
                    modifier.margin(top = 6.dp)

                    Button("Poison +5") {
                        modifier.onClick {
                            game.errorMessage.value = null
                            game.poisonTicksLeft.value = game.poisonTicksLeft.value + 5
                        }
                    }

                    Button("Очистить ошибку") {
                        modifier
                            .margin(start = 8.dp)
                            .onClick {
                                game.errorMessage.value = null
                            }
                    }

                    Button("Сбросить урон") {
                        modifier
                            .margin(start = 8.dp)
                            .onClick {
                                game.damage.value = 10
                                game.errorMessage.value = null
                            }
                    }
                }

                Row {
                    modifier.margin(top = 10.dp)

                    Column {
                        modifier.padding(10.dp)
                            .background(RoundRectBackground(Color(0f, 0f, 0f, 0.3f), 8.dp))

                        Text("Информация об уроне:") {
                            modifier
                                .margin(bottom = 5.dp)
                                .font(sizes.normalText)
                                .color(Color(0.8f, 0.8f, 0.8f))
                        }

                        Text("Базовый урон: 10") {
                            modifier
                                .margin(bottom = 2.dp)
                                .font(sizes.smallText)
                        }

                        Text("Деревянный меч: +10 урона") {
                            modifier
                                .margin(bottom = 2.dp)
                                .font(sizes.smallText)
                        }

                        Text("Железный меч: +20 урона") {
                            modifier
                                .font(sizes.smallText)
                        }
                    }
                }
            }
        }
    }
}

private fun <TextModifier> TextModifier.color(white: Color) {}
