package lesson13

import lesson12.GameEvent1

class TrainingStateGraph {
    private val nodes = mutableMapOf<TrainingState, StateNode>()

    init {
        val start = StateNode(TrainingState.START)
        val approached = StateNode(TrainingState.APPROACHED)
        val talking = StateNode(TrainingState.TALKING)
        val accepted = StateNode(TrainingState.ACCEPTED)
        val refused = StateNode(TrainingState.REFUSED)
        val dummyKilled = StateNode(TrainingState.DUMMY_KILLED)
        val completed = StateNode(TrainingState.COMPLETED)
        val failed = StateNode(TrainingState.FAILED)

        talking.addConditionalTransition(GameEvent1.DialogueChoiceSelected::class.java) { event ->
            if (event is GameEvent1.DialogueChoiceSelected) {
                when (event.choiceId) {
                    "accept" -> TrainingState.ACCEPTED
                    "refuse" -> TrainingState.FAILED
                    else -> null
                }
            } else null
        }

        accepted.addConditionalTransition(GameEvent1.DialogueChoiceSelected::class.java) { event ->
            if (event is GameEvent1.DialogueChoiceSelected && event.choiceId == "complete") {
                TrainingState.COMPLETED
            } else null
        }

        start.addTransition(
            GameEvent1.DialogueStarted::class.java,
            TrainingState.APPROACHED
        )
        approached.addTransition(
            GameEvent1.DialogueStarted::class.java,
            TrainingState.TALKING
        )

        accepted.addTransition(
            GameEvent1.CharacterDied::class.java,
            TrainingState.DUMMY_KILLED
        )

        dummyKilled.addTransition(
            GameEvent1.DialogueStarted::class.java,
            TrainingState.COMPLETED
        )

        nodes[start.state] = start
        nodes[approached.state] = approached
        nodes[talking.state] = talking
        nodes[accepted.state] = accepted
        nodes[refused.state] = refused
        nodes[dummyKilled.state] = dummyKilled
        nodes[completed.state] = completed
        nodes[failed.state] = failed
    }

    fun getNode(state: TrainingState): StateNode {
        return nodes[state] ?: throw IllegalStateException("Нету: $state")
    }
}