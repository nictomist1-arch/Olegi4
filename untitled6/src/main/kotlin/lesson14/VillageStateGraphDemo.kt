package lesson14

import lesson12.EventBus
import lesson12.GameEvent1

fun main() {
    val system = VillageQuetSystem()
    system.register()

    EventBus.subscribe { event ->
        if (event is GameEvent1.QuestStateChanged) {
            println("[STATE] ${event.playerId}: ${event.oldState} -> ${event.newState}")
        }
    }

    println("=== Тест 1: Стандартный путь ===")
    EventBus.post(GameEvent1.DialogueStarted("Старый", "Player1", "Player1"))
    EventBus.post(GameEvent1.DialogueChoiceSelected("Старый", "Player1", "accept", "Player1"))
    EventBus.post(GameEvent1.CharacterDied("Kirill_Shaman", "Player1", "Player1"))
    EventBus.post(GameEvent1.DialogueChoiceSelected("Старый", "Player1", "report", "Player1"))

    println("\n=== Тест 2: Секретная концовка ===")
    EventBus.post(GameEvent1.DialogueStarted("Старый", "Player2", "Player2"))
    EventBus.post(GameEvent1.DialogueChoiceSelected("Старый", "Player2", "accept", "Player2"))
    EventBus.post(GameEvent1.CharacterDied("Orc_Leader", "Player2", "Player2"))
    EventBus.post(GameEvent1.CharacterDied("Elder", "Player2", "Player2"))

    println("\n=== Тест 3: Помощь Кириллу ===")
    EventBus.post(GameEvent1.DialogueStarted("Старый", "Player3", "Player3"))
    EventBus.post(GameEvent1.DialogueChoiceSelected("Старый", "Player3", "refuse", "Player3"))
    EventBus.post(GameEvent1.DialogueChoiceSelected("Kirill_Shaman", "Player3", "help_kirill", "Player3"))

    EventBus.processQueue(20)
}