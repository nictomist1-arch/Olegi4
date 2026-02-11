package lesson12

sealed class GameEvent1(open val playerId: String) {
    data class CharacterDied(
        val characterName: String,
        val killerName: String? = null,
        override val playerId: String
    ) : GameEvent1(playerId)

    data class DamageDealt(
        val attackerName: String,
        val targetName: String,
        val amount: Int,
        override val playerId: String
    ) : GameEvent1(playerId)

    data class EffectApplied(
        val characterName: String,
        val effectName: String,
        override val playerId: String
    ) : GameEvent1(playerId)

    data class EffectEnded(
        val characterName: String,
        val effectName: String,
        override val playerId: String
    ) : GameEvent1(playerId)

    data class QuestStarted(
        val questId: String,
        override val playerId: String
    ) : GameEvent1(playerId)

    data class QuestStepCompleted(
        val questId: String,
        val stepId: String,
        override val playerId: String
    ) : GameEvent1(playerId)

    data class QuestCompleted(
        val questId: String,
        override val playerId: String
    ) : GameEvent1(playerId)

    data class DialogueStarted(
        val npcName: String,
        val playerName: String,
        override val playerId: String
    ) : GameEvent1(playerId)

    data class DialogueLineUnlocked(
        val npcName: String,
        val playerName: String,
        override val playerId: String
    ) : GameEvent1(playerId)

    data class DialogueChoiceSelected(
        val npcName: String,
        val playerName: String,
        val choiceId: String,
        override val playerId: String
    ) : GameEvent1(playerId)

    data class AchievementUnlocked(
        val achievementId: String,
        override val playerId: String
    ) : GameEvent1(playerId)

    data class PlayerProgressSaved(
        val playerName: String,
        val questId: String,
        val stepId: String,
        override val playerId: String
    ) : GameEvent1(playerId)

    data class StateChanged(
        val oldState: String,
        val newState: String,
        override val playerId: String
    ) : GameEvent1(playerId)

    data class QuestStateChanged(
        override val playerId: String,
        val oldState: String,
        val newState: String
    ) : GameEvent1(playerId)
}