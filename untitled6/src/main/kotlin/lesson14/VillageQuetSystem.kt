package lesson14

import lesson12.EventBus
import lesson12.GameEvent1

class VillageQuetSystem{
    private val progress = VillageQuestProgress()

    fun register(){
        EventBus.subscribe { event ->
            when(event){
                is GameEvent1.DialogueStarted,
                is GameEvent1.DialogueChoiceSelected,
                is GameEvent1.CharacterDied -> {
                    progress.handle(event.playerId, event)
                }
                else -> {}
            }
        }
    }
}