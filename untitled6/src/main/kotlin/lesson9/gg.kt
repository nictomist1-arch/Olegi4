package lesson9

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.scene.*
import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.Time
import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.modules.ui2.setupUiScene
import de.fabmax.kool.pipeline.ClearColorLoad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import lesson10.AttackPressed
import lesson10.ChoiceSelected
import lesson10.DamageDealt
import lesson10.PoisonApplied
import lesson10.QuestStateChanged
import lesson10.SaveRequested
import lesson10.Shared
import lesson10.TalkedToNpc
import lesson10.hudLog

class GameState {
    val playerId = mutableStateOf("Oleg")
    val hp = mutableStateOf(100)
    val maxHp = 100

    val poisonTicksLeft = mutableStateOf(0)
    val regenTicksLeft = mutableStateOf(0)

    val attackCoolDownMsLeft = mutableStateOf(0L)

    val logLines = mutableStateOf<List<String>>(emptyList())
}

fun pushLog(game: GameState, text: String) {
    game.logLines.value = (game.logLines.value + text).takeLast(20)
}

class EffectManager(
    private val game: GameState,
    private val scope: CoroutineScope
) {
    private var poisonJob: Job? = null
    private var regenJob: Job? = null

    fun applyPoison(ticks: Int, damagePerTick: Int, intervalMs: Long) {
        poisonJob?.cancel()

        game.poisonTicksLeft.value += ticks

        poisonJob = scope.launch {
            while (isActive && game.poisonTicksLeft.value > 0) {
                delay(intervalMs)
                game.poisonTicksLeft.value -= 1
                game.hp.value = (game.hp.value - damagePerTick).coerceAtLeast(0)

                pushLog(game, "Тик яда: -$damagePerTick HP, ${game.hp.value} / ${game.maxHp}")
            }
            pushLog(game, "Эффект яда завершен")
            poisonJob = null
        }
    }

    fun applyRegen(ticks: Int, healPerTick: Int, intervalMs: Long) {
        regenJob?.cancel()

        game.regenTicksLeft.value += ticks
        pushLog(game, "Эффект регена применен на ${game.playerId.value}")

        regenJob = scope.launch {
            while (isActive && game.regenTicksLeft.value > 0) {
                delay(intervalMs)

                game.regenTicksLeft.value -= 1
                game.hp.value = (game.hp.value + healPerTick).coerceAtMost(game.maxHp)
                pushLog(game, "Эффект регена: +$healPerTick HP, ${game.hp.value} / ${game.maxHp}")
            }
            pushLog(game, "Эффект регена завершен")
            regenJob = null
        }
    }

    fun cancelPoison() {
        poisonJob?.cancel()
        poisonJob = null
        game.poisonTicksLeft.value = 0
        pushLog(game, "Яд снят (cancel)")
    }

    fun cancelRegen() {
        regenJob?.cancel()
        regenJob = null
        game.regenTicksLeft.value = 0
        pushLog(game, "Реген снят (cancel)")
    }
}

class CooldownManager(
    private val game: GameState,
    private val scope: CoroutineScope
) {
    private var cooldownJob: Job? = null

    fun startAttackCooldown(totalMs: Long) {
        cooldownJob?.cancel()

        game.attackCoolDownMsLeft.value = totalMs
        pushLog(game, "Кулдаун атаки ${totalMs}мс")

        cooldownJob = scope.launch {
            val step = 100L

            while (isActive && game.attackCoolDownMsLeft.value > 0L) {
                delay(step)
                game.attackCoolDownMsLeft.value = (game.attackCoolDownMsLeft.value - step).coerceAtLeast(0)
            }
            pushLog(game, "Кулдаун атаки завершен")
            cooldownJob = null
        }
    }

    fun canAttack(): Boolean {
        return game.attackCoolDownMsLeft.value <= 0L
    }
}

object SharedActions {
    var effects: EffectManager? = null
    var cooldown: CooldownManager? = null
}

fun main() = KoolApplication {
    val game = GameState()

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

        val effects = EffectManager(game, coroutineScope)
        val cooldown = CooldownManager(game, coroutineScope)

        SharedActions.effects = effects
        SharedActions.cooldown = cooldown
    }

    addScene {
        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), 14.dp))
                .padding(12.dp)
                .width(300.dp)

            Column {
                Text("Игрок: ${game.playerId.use()}") { modifier.margin(bottom = 4.dp) }
                Text("HP: ${game.hp.use()}/${game.maxHp}") { modifier.margin(bottom = 4.dp) }
                Text("Тики яда: ${game.poisonTicksLeft.use()}") { modifier.margin(bottom = 4.dp) }
                Text("Тики регена: ${game.regenTicksLeft.use()}") { modifier.margin(bottom = 4.dp) }
                Text("Кулдаун: ${game.attackCoolDownMsLeft.use()} мс") { modifier.margin(bottom = 16.dp) }

                Row {
                    modifier.margin(bottom = 8.dp)

                    Button("Яд +5") {
                        modifier
                            .margin(end = 8.dp)
                            .onClick {
                                SharedActions.effects?.applyPoison(5, 2, 1000L)
                            }
                    }

                    Button("Отмена яда") {
                        modifier.onClick {
                            SharedActions.effects?.cancelPoison()
                        }
                    }
                }

                Row {
                    modifier.margin(bottom = 8.dp)

                    Button("Реген +5") {
                        modifier
                            .margin(end = 8.dp)
                            .onClick {
                                SharedActions.effects?.applyRegen(5, 2, 1000L)
                            }
                    }

                    Button("Отмена регена") {
                        modifier.onClick {
                            SharedActions.effects?.cancelRegen()
                        }
                    }
                }

                Button("Атаковать (кулдаун 1200мс)") {
                    modifier
                        .margin(bottom = 16.dp)
                        .onClick {
                            val cd = SharedActions.cooldown

                            if (cd == null) {
                                pushLog(game, "CooldownManager еще не готов")
                                return@onClick
                            }

                            if (!cd.canAttack()) {
                                pushLog(game, "Атаковать нельзя: кулдаун еще идет")
                                return@onClick
                            }

                            cd.startAttackCooldown(1200L)
                            pushLog(game, "Атака выполнена!")
                        }
                }

                Text("Логи:") { modifier.margin(bottom = 4.dp) }

                val lines = game.logLines.use()
                Column {
                    for (line in lines) {
                        Text(line) {
                            modifier
                                .margin(bottom = 2.dp)
                                .font(sizes.smallText)
                        }
                    }
                }
            }
        }
    }
}
