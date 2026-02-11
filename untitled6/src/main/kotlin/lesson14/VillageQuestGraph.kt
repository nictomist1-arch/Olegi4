package lesson14

import lesson12.GameEvent1

class VillageQuestGraph {
    private val nodes = mutableMapOf<VillageQuestState, StateNodeV2>()

    init {

        VillageQuestState.values().forEach { state ->
            nodes[state] = StateNodeV2(state)
        }

        val start = nodes[VillageQuestState.NOT_STARTED]!!
        val talked = nodes[VillageQuestState.TALKED_TO_ELDER]!!
        val accepted = nodes[VillageQuestState.ACCEPTED_HELP]!!
        val refused = nodes[VillageQuestState.REFUSED_HELP]!!
        val killedKirill = nodes[VillageQuestState.KILLED_KIRILL_SHAMAN]!!
        val madePeace = nodes[VillageQuestState.MADE_PEACE]!!
        val helpedKirill = nodes[VillageQuestState.HELPED_KIRILL]!!
        val killedOrc = nodes[VillageQuestState.KILLED_ORC]!!
        val killedElder = nodes[VillageQuestState.KILLED_ELDER]!!

        start.add(GameEvent1.DialogueStarted::class.java) { event ->
            val dialogueEvent = event as GameEvent1.DialogueStarted
            if (dialogueEvent.npcName == "Старый") VillageQuestState.TALKED_TO_ELDER
            else null
        }

        talked.add(GameEvent1.DialogueChoiceSelected::class.java) { event ->
            val choiceEvent = event as GameEvent1.DialogueChoiceSelected
            when {
                choiceEvent.npcName == "Старый" && choiceEvent.choiceId == "accept" ->
                    VillageQuestState.ACCEPTED_HELP
                choiceEvent.npcName == "Старый" && choiceEvent.choiceId == "refuse" ->
                    VillageQuestState.REFUSED_HELP
                else -> null
            }
        }

        accepted.add(GameEvent1.CharacterDied::class.java) { event ->
            val deathEvent = event as GameEvent1.CharacterDied
            when (deathEvent.characterName) {
                "Kirill_Shaman" -> VillageQuestState.KILLED_KIRILL_SHAMAN
                "Orc_Leader" -> VillageQuestState.KILLED_ORC
                "Elder" -> VillageQuestState.KILLED_ELDER
                else -> null
            }
        }

        accepted.add(GameEvent1.DialogueStarted::class.java) { event ->
            val dialogueEvent = event as GameEvent1.DialogueStarted
            if (dialogueEvent.npcName == "Kirill_Shaman") VillageQuestState.MADE_PEACE
            else null
        }

        refused.add(GameEvent1.DialogueChoiceSelected::class.java) { event ->
            val choiceEvent = event as GameEvent1.DialogueChoiceSelected
            if (choiceEvent.npcName == "Kirill_Shaman" && choiceEvent.choiceId == "help_kirill")
                VillageQuestState.HELPED_KIRILL
            else null
        }

        listOf(killedKirill, madePeace, helpedKirill, killedOrc, killedElder).forEach { node ->
            node.add(GameEvent1.DialogueChoiceSelected::class.java) { event ->
                val choiceEvent = event as GameEvent1.DialogueChoiceSelected
                if (choiceEvent.choiceId == "report") VillageQuestState.HERO_ENDING
                else null
            }
        }

        listOf(killedKirill, madePeace, helpedKirill).forEach { node ->
            node.add(GameEvent1.CharacterDied::class.java) { event ->
                val deathEvent = event as GameEvent1.CharacterDied
                when (deathEvent.characterName) {
                    "Orc_Leader" -> VillageQuestState.KILLED_ORC
                    "Elder" -> VillageQuestState.KILLED_ELDER
                    else -> null
                }
            }
        }

        killedOrc.add(GameEvent1.CharacterDied::class.java) { event ->
            val deathEvent = event as GameEvent1.CharacterDied
            if (deathEvent.characterName == "Elder") VillageQuestState.SECRET_ENDING
            else null
        }

        killedElder.add(GameEvent1.CharacterDied::class.java) { event ->
            val deathEvent = event as GameEvent1.CharacterDied
            if (deathEvent.characterName == "Orc_Leader") VillageQuestState.SECRET_ENDING
            else null
        }
    }

    fun getNode(state: VillageQuestState): StateNodeV2 {
        return nodes[state]!!
    }
}