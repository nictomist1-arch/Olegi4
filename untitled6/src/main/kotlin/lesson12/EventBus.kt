package lesson12

object EventBus {
    typealias Listener = (GameEvent1) -> Unit


    private val listeners = mutableMapOf<Int, Listener>()
    private var nextId: Int = 1

    private val eventQueue = ArrayDeque<GameEvent1>()

    fun subscribe(listener: Listener): Int {
        val id = nextId
        nextId += 1

        listeners[id] = listener

        println("Подписчик добавлен id=$id Всего подписчиков: ${listeners.size}")
        return id
    }

    fun unsubscribe(id: Int) {
        val removed = listeners.remove(id)

        if (removed != null) {
            println("Подписчик удален. id = $id")
        } else {
            println("Не удалось отписаться, не найден id=$id")
        }
    }

    fun subscribeOnce(listener: Listener): Int {
        var id: Int = -1
        id = subscribe { event ->
            listener(event)

            unsubscribe(id)
        }
        return id

    }

    fun publish(event: GameEvent1){
        println("Событие опубликовано: $event")
        for (listener in listeners.values){
            listener(event)
        }
    }

    fun post(event: GameEvent1){
        eventQueue.addLast(event)
        println("Событие $event добавлено (в очередь ${eventQueue.size}")
    }

    fun processQueue(maxEvent: Int = 10){
        var processed = 0

        while (processed < maxEvent && eventQueue.isNotEmpty()){
            val event = eventQueue.removeFirst()

            publish(event)

            processed++
        }
    }
}