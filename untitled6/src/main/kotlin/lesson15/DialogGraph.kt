package lesson15

class DialogGraph {
    private val nodes = mutableMapOf<DialogState, DialogNode>()

    init {
        val greeting = DialogNode(
            DialogState.GREETING,
            "Привет доходяги"
        )
        val offer = DialogNode(
            DialogState.OFFER_QUEST,
            "бИМБИМ бам бам"
        )
        val accepted = DialogNode(
            DialogState.ACCEPTED,
            "МяуМяУ"
        )
        val refused = DialogNode(
            DialogState.REFUSED,
            "шАКлака"
        )
        val completed = DialogNode(
            DialogState.QUEST_COMPLETED,
            "Кирилл завали е"
        )

        greeting.addChoice("work", DialogState.OFFER_QUEST)
        greeting.addChoice("work", DialogState.END)

        offer.addChoice("work", DialogState.ACCEPTED)
        offer.addChoice("work", DialogState.REFUSED)

        accepted.addChoice("work", DialogState.END)
        refused.addChoice("work", DialogState.END)
        completed.addChoice("work", DialogState.END)

        listOf(greeting, offer, accepted, refused, completed).forEach {
            nodes[it.state] = it
        }
    }
    fun getNode(state: DialogState): DialogNode{
        return nodes[state]!!
    }
}