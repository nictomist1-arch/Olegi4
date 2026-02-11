package lesson13

import lesson12.EventBus
import lesson12.GameEvent1

fun main() {
    val system = TrainingStateSystem()
    system.register()
    val progress = TrainingProgress()

    println("=== Запуск демо с несколькими игроками ===\n")

    val playerOleg = "Oleg"
    println("=== Ход игрока $playerOleg ===")

    EventBus.post(GameEvent1.DialogueStarted(playerOleg, "Trainer", playerOleg))
    EventBus.processQueue()
    progress.debugStates()

    EventBus.post(GameEvent1.DialogueChoiceSelected(playerOleg, "Trainer", playerOleg, "accept"))
    EventBus.processQueue()
    progress.debugStates()

    EventBus.post(GameEvent1.CharacterDied(playerOleg, "Dummy", playerOleg))
    EventBus.processQueue()
    progress.debugStates()

    val playerInnokentiy = "Innokentiy"
    println("\n=== Ход игрока $playerInnokentiy ===")

    EventBus.post(GameEvent1.DialogueStarted(playerInnokentiy, "Trainer", playerInnokentiy))
    EventBus.processQueue()
    progress.debugStates()

    EventBus.post(GameEvent1.DialogueChoiceSelected(playerInnokentiy, "Trainer", playerInnokentiy, "refuse"))
    EventBus.processQueue()
    progress.debugStates()

    println("\n=== Итоговые состояния ===")
    progress.debugStates()

    println("=== Продолжение пути Oleg ===")
    EventBus.post(GameEvent1.DialogueStarted(playerOleg, "Trainer", playerOleg))
    EventBus.processQueue()
    progress.debugStates()
}
