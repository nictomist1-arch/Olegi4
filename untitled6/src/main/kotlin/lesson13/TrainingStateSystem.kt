package lesson13

import lesson12.EventBus
import lesson12.GameEvent1

class TrainingStateSystem {
    private val progress = TrainingProgress()

    fun register() {
        EventBus.subscribe { event ->
            when (event) {
                is GameEvent1.DialogueStarted,
                is GameEvent1.DialogueChoiceSelected,
                is GameEvent1.CharacterDied -> {
                    progress.handleEvent(event.playerId, event)
                }
                else -> {}
            }
        }
    }
}