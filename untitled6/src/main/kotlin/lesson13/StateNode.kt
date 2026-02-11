package lesson13

import lesson12.GameEvent1

class StateNode(
    val state: TrainingState
) {
    private val transitions = mutableMapOf<Class<out GameEvent1>, TrainingState>()
    private val conditionalTransitions = mutableMapOf<Class<out GameEvent1>, (GameEvent1) -> TrainingState?>()

    fun addTransition(
        eventType: Class<out GameEvent1>,
        nextState: TrainingState
    ) {
        transitions[eventType] = nextState
    }

    fun addConditionalTransition(
        eventType: Class<out GameEvent1>,
        conditionHandler: (GameEvent1) -> TrainingState?
    ) {
        conditionalTransitions[eventType] = conditionHandler
    }

    fun getNextState(event: GameEvent1): TrainingState? {
        val handler = conditionalTransitions[event::class.java]
        if (handler != null) {
            val result = handler(event)
            if (result != null) return result
        }

        return transitions[event::class.java]
    }
}