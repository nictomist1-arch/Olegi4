package lesson15

class DialogNode (
    val state: DialogState,
    val text: String
){
    private val choices = mutableMapOf<String, DialogState>()

    fun addChoice(choiceId: String, nextState: DialogState ){
        choices[choiceId] = nextState
    }

    fun getNextState(choiceId: String): DialogState? {
        return choices[choiceId]
    }

    fun print(){
        println("NPC говорит: \"$text\"")
        println("Варианты: ")
        for (choice in choices.keys){
            println("> $choice")
        }
    }
}