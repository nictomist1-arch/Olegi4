package lesson13

import lesson12.GameEvent1
import lesson12.EventBus

class TrainingProgress {
    private val graph = TrainingStateGraph()
    private val currentStateByPlayer = mutableMapOf<String, TrainingState>()

    fun getState(playerId: String): TrainingState {
        return currentStateByPlayer.getOrPut(playerId) { TrainingState.START }
    }

    fun handleEvent(playerId: String, event: GameEvent1) {
        val currentState = getState(playerId)
        val node = graph.getNode(currentState)

        val nextState = node.getNextState(event)

        if (nextState != null && nextState != currentState) {
            val oldState = currentState
            currentStateByPlayer[playerId] = nextState

            println("[STATE GRAPH] $playerId перешел из состояния ($oldState) -> в ($nextState)")

            EventBus.post(GameEvent1.StateChanged(
                oldState = oldState.name,
                newState = nextState.name,
                playerId = playerId
            ))
        } else {
            println("[STATE GRAPH] $playerId проигнорировал событие ${event::class.simpleName} состояние ($currentState) не изменено")
        }
    }

    fun debugStates() {
        println("\n=== Текущие состояния игроков ===")
        currentStateByPlayer.forEach { (player, state) ->
            println("$player: $state")
        }
        println("================================\n")
    }
}