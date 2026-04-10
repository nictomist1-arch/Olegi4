package playerMovement

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.modules.audio.synth.SampleNode
import de.fabmax.kool.scene.*
import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.Time
import de.fabmax.kool.pipeline.ClearColorLoad
import de.fabmax.kool.modules.ui2.*

import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.flatMapLatest
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

object DesktopKeyboardState {
    private val pressedKeys = mutableSetOf<Int>()
    private val justPressedKeys = mutableSetOf<Int>()
    private var isInstalled = false

    fun install() {
        if (isInstalled) return  // Fixed: was "is (isInstalled) return"

        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addKeyEventDispatcher(
                object : KeyEventDispatcher {
                    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                        when (e.id) {
                            KeyEvent.KEY_PRESSED -> {
                                if (!pressedKeys.contains(e.keyCode)) {
                                    justPressedKeys.add(e.keyCode)
                                }
                                pressedKeys.add(e.keyCode)
                            }

                            KeyEvent.KEY_RELEASED -> {
                                pressedKeys.remove(e.keyCode)
                                justPressedKeys.remove(e.keyCode)
                            }
                        }
                        return false
                    }
                }
            )
        isInstalled = true
    }

    fun isDown(keyCode: Int): Boolean {
        return keyCode in pressedKeys
    }

    fun consumeJustPressed(keyCode: Int): Boolean {
        return if (keyCode in justPressedKeys) {
            justPressedKeys.remove(keyCode)
            true
        } else {
            false
        }
    }
}

enum class QuestState {
    START,
    WAIT_HERB,
    GOOD_END,
    EVIL_END
}

enum class WorldObjectType {
    ALCHEMIST,
    HERB_SOURCE,
    CHEST,
    DOOR
}

data class WorldObjectDef(
    val id: String,
    val type: WorldObjectType,
    val worldX: Float,
    val worldZ: Float,
    val interactRadius: Float
)

data class ObstacleDef(
    val centerX: Float,
    val centerZ: Float,
    val halfSize: Float
)

data class NpcMemory(
    val hasMet: Boolean = false,
    val timesTalked: Int = 0,
    val receivedHerb: Boolean = false
)

data class PlayerState(
    val playerId: String,
    val worldX: Float,
    val worldZ: Float,
    val yawDeg: Float,
    val moveSpeed: Float,
    val questState: QuestState,
    val inventory: Map<String, Int>,
    val gold: Int,
    val alchemistMemory: NpcMemory,
    val chestLooted: Boolean,
    val doorOpened: Boolean,
    val currentFocusId: String?,
    val hintText: String,
    val pinnedQuestEnabled: Boolean,
    val pinnedTargetId: String?
) {
    fun copyWith(
        worldX: Float = this.worldX,
        worldZ: Float = this.worldZ,
        yawDeg: Float = this.yawDeg,
        questState: QuestState = this.questState,
        inventory: Map<String, Int> = this.inventory,
        gold: Int = this.gold,
        alchemistMemory: NpcMemory = this.alchemistMemory,
        chestLooted: Boolean = this.chestLooted,
        doorOpened: Boolean = this.doorOpened,
        currentFocusId: String? = this.currentFocusId,
        hintText: String = this.hintText,
        pinnedQuestEnabled: Boolean = this.pinnedQuestEnabled,
        pinnedTargetId: String? = this.pinnedTargetId
    ): PlayerState = PlayerState(
        playerId = this.playerId,
        worldX = worldX,
        worldZ = worldZ,
        yawDeg = yawDeg,
        moveSpeed = this.moveSpeed,
        questState = questState,
        inventory = inventory,
        gold = gold,
        alchemistMemory = alchemistMemory,
        chestLooted = chestLooted,
        doorOpened = doorOpened,
        currentFocusId = currentFocusId,
        hintText = hintText,
        pinnedQuestEnabled = pinnedQuestEnabled,
        pinnedTargetId = pinnedTargetId
    )
}

class PlayerMovementController(
    private val initialPlayerState: PlayerState,
    private val worldObjects: List<WorldObjectDef>,
    private val obstacles: List<ObstacleDef>
) {
    private val _playerState = MutableStateFlow(initialPlayerState)
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val _interactionEvent = MutableSharedFlow<WorldObjectDef>()
    val interactionEvent: SharedFlow<WorldObjectDef> = _interactionEvent.asSharedFlow()

    private var movementJob: Job? = null

    companion object KeyCodes {
        const val KEY_W = KeyEvent.VK_W
        const val KEY_A = KeyEvent.VK_A
        const val KEY_S = KeyEvent.VK_S
        const val KEY_D = KeyEvent.VK_D
        const val KEY_E = KeyEvent.VK_E
        const val KEY_Q = KeyEvent.VK_Q
        const val KEY_SPACE = KeyEvent.VK_SPACE
    }

    init {
        DesktopKeyboardState.install()
    }

    fun startMovement() {
        movementJob = kotlinx.coroutines.GlobalScope.launch {
            while (true) {
                updateMovement()
                delay(16) // ~60 FPS
            }
        }
    }

    fun stopMovement() {
        movementJob?.cancel()
        movementJob = null
    }

    private fun updateMovement() {
        var newX = _playerState.value.worldX
        var newZ = _playerState.value.worldZ
        var newYaw = _playerState.value.yawDeg

        if (DesktopKeyboardState.isDown(KEY_Q)) {
            newYaw -= 3.0f
        }
        if (DesktopKeyboardState.isDown(KEY_E)) {
            newYaw += 3.0f
        }

        var moveX = 0f
        var moveZ = 0f

        if (DesktopKeyboardState.isDown(KEY_W)) {
            moveX += sin(Math.toRadians(newYaw.toDouble())).toFloat()
            moveZ += cos(Math.toRadians(newYaw.toDouble())).toFloat()
        }
        if (DesktopKeyboardState.isDown(KEY_S)) {
            moveX -= sin(Math.toRadians(newYaw.toDouble())).toFloat()
            moveZ -= cos(Math.toRadians(newYaw.toDouble())).toFloat()
        }
        if (DesktopKeyboardState.isDown(KEY_A)) {
            moveX -= cos(Math.toRadians(newYaw.toDouble())).toFloat()
            moveZ += sin(Math.toRadians(newYaw.toDouble())).toFloat()
        }
        if (DesktopKeyboardState.isDown(KEY_D)) {
            moveX += cos(Math.toRadians(newYaw.toDouble())).toFloat()
            moveZ -= sin(Math.toRadians(newYaw.toDouble())).toFloat()
        }

        if (moveX != 0f || moveZ != 0f) {
            val length = sqrt(moveX * moveX + moveZ * moveZ)
            moveX /= length
            moveZ /= length
        }

        val speed = _playerState.value.moveSpeed
        newX += moveX * speed
        newZ += moveZ * speed

        if (!isCollidingWithObstacles(newX, newZ)) {
            _playerState.value = _playerState.value.copyWith(
                worldX = newX,
                worldZ = newZ,
                yawDeg = newYaw
            )
        } else {
            if (!isCollidingWithObstacles(newX, _playerState.value.worldZ)) {
                _playerState.value = _playerState.value.copyWith(
                    worldX = newX,
                    yawDeg = newYaw
                )
            } else if (!isCollidingWithObstacles(_playerState.value.worldX, newZ)) {
                _playerState.value = _playerState.value.copyWith(
                    worldZ = newZ,
                    yawDeg = newYaw
                )
            } else {
                _playerState.value = _playerState.value.copyWith(yawDeg = newYaw)
            }
        }

        checkInteractions()

        if (DesktopKeyboardState.consumeJustPressed(KEY_E)) {
            val currentFocus = _playerState.value.currentFocusId
            if (currentFocus != null) {
                val focusedObject = worldObjects.find { it.id == currentFocus }
                focusedObject?.let {
                    kotlinx.coroutines.GlobalScope.launch {
                        _interactionEvent.emit(it)
                    }
                }
            }
        }

        updateFocus()
    }

    private fun isCollidingWithObstacles(x: Float, z: Float): Boolean {
        val playerHalfSize = 0.4f

        for (obstacle in obstacles) {
            val dx = abs(x - obstacle.centerX)
            val dz = abs(z - obstacle.centerZ)

            if (dx < obstacle.halfSize + playerHalfSize &&
                dz < obstacle.halfSize + playerHalfSize) {
                return true
            }
        }
        return false
    }

    private fun checkInteractions() {
        val playerPos = _playerState.value
        val closestObject = worldObjects
            .filter { obj ->
                val dx = obj.worldX - playerPos.worldX
                val dz = obj.worldZ - playerPos.worldZ
                val distance = sqrt(dx * dx + dz * dz)
                distance < obj.interactRadius
            }
            .minByOrNull { obj ->
                val dx = obj.worldX - playerPos.worldX
                val dz = obj.worldZ - playerPos.worldZ
                sqrt(dx * dx + dz * dz)
            }

        if (closestObject != null) {
            val dx = closestObject.worldX - playerPos.worldX
            val dz = closestObject.worldZ - playerPos.worldZ
            val distance = sqrt(dx * dx + dz * dz)

            if (distance < closestObject.interactRadius) {
                if (playerPos.currentFocusId != closestObject.id) {
                    _playerState.value = _playerState.value.copyWith(
                        currentFocusId = closestObject.id,
                        hintText = getHintForObject(closestObject)
                    )
                }
            } else if (playerPos.currentFocusId == closestObject.id) {
                _playerState.value = _playerState.value.copyWith(
                    currentFocusId = null,
                    hintText = ""
                )
            }
        } else if (playerPos.currentFocusId != null) {
            _playerState.value = _playerState.value.copyWith(
                currentFocusId = null,
                hintText = ""
            )
        }
    }

    private fun updateFocus() {
        checkInteractions()
    }

    private fun getHintForObject(obj: WorldObjectDef): String {
        return when (obj.type) {
            WorldObjectType.ALCHEMIST -> "Press E to talk to Alchemist"
            WorldObjectType.HERB_SOURCE -> "Press E to collect herbs"
            WorldObjectType.CHEST -> "Press E to open chest"
            WorldObjectType.DOOR -> "Press E to open door"
        }
    }

    // Game logic methods
    suspend fun interactWithAlchemist() {
        val state = _playerState.value
        when (state.questState) {
            QuestState.START -> {
                _playerState.value = state.copyWith(
                    questState = QuestState.WAIT_HERB,
                    alchemistMemory = state.alchemistMemory.copy(
                        hasMet = true,
                        timesTalked = state.alchemistMemory.timesTalked + 1
                    ),
                    hintText = "Alchemist: Bring me a rare herb from the forest!"
                )
            }
            QuestState.WAIT_HERB -> {
                if (state.inventory.getOrDefault("herb", 0) > 0) {
                    _playerState.value = state.copyWith(
                        questState = QuestState.GOOD_END,
                        inventory = state.inventory.toMutableMap().apply {
                            this["herb"] = (this["herb"] ?: 0) - 1
                        },
                        alchemistMemory = state.alchemistMemory.copy(receivedHerb = true),
                        hintText = "Alchemist: Thank you! Here's your reward!"
                    )
                } else {
                    _playerState.value = state.copyWith(
                        alchemistMemory = state.alchemistMemory.copy(
                            timesTalked = state.alchemistMemory.timesTalked + 1
                        ),
                        hintText = "Alchemist: I still need that herb!"
                    )
                }
            }
            else -> {
                _playerState.value = state.copyWith(
                    hintText = "Alchemist: The ritual is complete..."
                )
            }
        }
    }

    suspend fun interactWithHerbSource() {
        val state = _playerState.value
        val newInventory = state.inventory.toMutableMap()
        newInventory["herb"] = (newInventory["herb"] ?: 0) + 1

        _playerState.value = state.copyWith(
            inventory = newInventory,
            hintText = "You collected a herb!"
        )
    }

    suspend fun interactWithChest() {
        val state = _playerState.value
        if (!state.chestLooted) {
            _playerState.value = state.copyWith(
                chestLooted = true,
                gold = state.gold + 50,
                hintText = "You found 50 gold in the chest!"
            )
        } else {
            _playerState.value = state.copyWith(
                hintText = "The chest is empty."
            )
        }
    }

    suspend fun interactWithDoor() {
        val state = _playerState.value
        if (!state.doorOpened) {
            _playerState.value = state.copyWith(
                doorOpened = true,
                hintText = "You opened the door."
            )
        } else {
            _playerState.value = state.copyWith(
                hintText = "The door is already open."
            )
        }
    }

    fun togglePinnedQuest() {
        _playerState.value = _playerState.value.copyWith(
            pinnedQuestEnabled = !_playerState.value.pinnedQuestEnabled
        )
    }

    fun setPinnedTarget(targetId: String?) {
        _playerState.value = _playerState.value.copyWith(
            pinnedTargetId = targetId
        )
    }
}