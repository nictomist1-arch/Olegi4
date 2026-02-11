package lesson14

import lesson12.GameEvent1
import lesson12.EventBus

class VillageQuestProgress {
    private val graph = VillageQuestGraph()
    private val stateByPlayer = mutableMapOf<String, VillageQuestState>()

    fun getState(playerId: String): VillageQuestState {
        return stateByPlayer.getOrPut(playerId) { VillageQuestState.NOT_STARTED }
    }

    fun handle(playerId: String, event: GameEvent1) {
        val current = getState(playerId)
        val node = graph.getNode(current)
        val next = node.next(event)

        if (next != null) {
            EventBus.post(GameEvent1.QuestStateChanged(
                playerId,
                current.name,
                next.name
            ))

            println("[QUEST BANANA] - $playerId: $current -> $next")
            stateByPlayer[playerId] = next
        } else {
            println("[QUEST GRAPH] - $playerId: событие ${event::class.simpleName} игнорировано Игрок остался в состоянии $current")
        }
    }
}