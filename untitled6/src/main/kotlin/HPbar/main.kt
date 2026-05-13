package HPbar

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene
import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.pipeline.ClearColorLoad
import de.fabmax.kool.util.Color

class GameState {
    val hp = mutableStateOf(100)
    val isDead = mutableStateOf(false)
}

fun main() = KoolApplication {
    val game = GameState()

    val maxHp = 100
    val barWidthDp = 1530
    val barHeightDp = 30
    val damage = 10

    addScene {
        setupUiScene(ClearColorLoad)

        addPanelSurface {
            val hpValue = game.hp.use().coerceIn(0, maxHp)
            val isDead = game.isDead.use()

            modifier
                .size(Grow.Std, Grow.Std)

            Box {
                modifier.size(Grow.Std, Grow.Std)

                Box {
                    modifier
                        .align(AlignmentX.End, AlignmentY.Bottom)
                        .margin(24.dp)
                        .background(RoundRectBackground(Color(0f, 0f, 0f, 0.7f), 8.dp))
                        .padding(12.dp)

                    Column {
                        val filledWidthDp = ((barWidthDp * (hpValue.toFloat() / maxHp.toFloat())).toInt())
                            .coerceIn(0, barWidthDp)
                        val emptyWidthDp = (barWidthDp - filledWidthDp).coerceAtLeast(0)

                        Row {
                            Box {
                                modifier
                                    .size(filledWidthDp.dp, barHeightDp.dp)
                                    .background(
                                        RoundRectBackground(
                                            Color(
                                                1f - (hpValue.toFloat() / maxHp.toFloat()),
                                                (hpValue.toFloat() / maxHp.toFloat()),
                                                0f,
                                                0.95f
                                            ),
                                            0.dp
                                        )
                                    )
                            }
                            Box {
                                modifier
                                    .size(emptyWidthDp.dp, barHeightDp.dp)
                                    .background(
                                        RoundRectBackground(Color(0.15f, 0.15f, 0.15f, 0.7f), 0.dp)
                                    )
                            }
                        }

                        Text("$hpValue / $maxHp") {
                            modifier.align(AlignmentX.Center)
                        }

                        Button("Отнять HP ($damage)") {
                            modifier.onClick {
                                val newHp = (game.hp.value - damage).coerceAtLeast(0)
                                game.hp.value = newHp
                                if (newHp == 0) {
                                    game.isDead.value = true
                                    DeathLockScreen.show()
                                }
                            }
                        }
                    }
                }

                if (isDead) {
                    Box {
                        modifier
                            .align(AlignmentX.Center, AlignmentY.Center)
                            .size(400.dp, 200.dp)
                            .background(RoundRectBackground(Color(0.15f, 0f, 0f, 0.95f), 16.dp))
                            .padding(24.dp)

                        Column {
                            modifier.align(AlignmentX.Center)

                            Text("GAME OVER") {
                                modifier
                                    .align(AlignmentX.Center)
                                    .margin(bottom = 16.dp)
                            }

                            Text("Вы погибли") {
                                modifier
                                    .align(AlignmentX.Center)
                                    .margin(bottom = 24.dp)
                            }

                            Button("Начать заново") {
                                modifier
                                    .align(AlignmentX.Center)
                                    .onClick {
                                        game.hp.value = maxHp
                                        game.isDead.value = false
                                        DeathLockScreen.hide()
                                    }
                            }
                        }
                    }
                }
            }

        }
    }
}