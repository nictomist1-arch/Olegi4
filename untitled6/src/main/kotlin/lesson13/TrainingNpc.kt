package lesson13

class TrainingNpc(
    val name: String,
    var state: NpcState = NpcState.IDLE
    // Здесь хранится активно состояние нпс (по умолчанию IDLE)
){
    fun onPlayerApproached(playerId: String){

        if (state == NpcState.IDLE){
            println("$name: Привет, $playerId")
            state = NpcState.WAITING
            println("[INFO] NPC теперь в состоянии WAITING")
        }
    }
    fun onDialogueStarted(playerId: String){
        if (state == NpcState.WAITING){
            println("$name: Я научу тебя драться")
            state = NpcState.TALKING
            println("[INFO] NPC теперь в состоянии TALKING")
        }
    }

    fun onDialogueChoiceSelected(playerId: String, choiceId: String){
        if (state == NpcState.TALKING && choiceId == "accept"){
            println("$name: Харош! Вот твоя награда")
            state = NpcState.REWARDED
            println("[INFO]")
        }
    }
}
