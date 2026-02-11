package lesson13

import lesson12.GameEvent1
import lesson12.EventBus

class TrainingNpcSystem {
    private val npc = TrainingNpc("Trainer")

    fun register(){
        EventBus.subscribe { event ->
            when(event){
                is GameEvent1.DialogueStarted -> {
                    npc.onDialogueStarted(event.playerId)
            }
                is GameEvent1.DialogueChoiceSelected -> {
                    npc. onDialogueChoiceSelected(
                        event.playerId,
                        event.choiceId
                    )
                }
                else -> {}
            }
        }
    }
    fun playerApproaches(playerId: String){
        npc.onPlayerApproached(playerId)
    }
}