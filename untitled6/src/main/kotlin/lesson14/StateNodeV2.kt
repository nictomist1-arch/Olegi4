package lesson14

import lesson12.GameEvent1

class StateNodeV2(val state: VillageQuestState) {
    private val transitions = mutableMapOf<Class<out GameEvent1>, (GameEvent1) -> VillageQuestState?>()

    fun add(eventType: Class<out GameEvent1>, next: VillageQuestState) {
        transitions[eventType] = { next }
    }

    fun add(eventType: Class<out GameEvent1>, condition: (GameEvent1) -> VillageQuestState?) {
        transitions[eventType] = condition
    }

    fun next(event: GameEvent1): VillageQuestState? {
        val handler = transitions[event::class.java]
        return handler?.invoke(event)
    }
}