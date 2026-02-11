package lesson14

// 1 Путь
// - Поговорить со Старым
// - Согласиться на помощь
// - убить Кирилла-Шамана
// - Вернуться и доложить о выполнении
// КОНЦОВКА - ГЕРОЙ ДЕРЕВНИ

// 2 Путь
// - Говорит
// - Соглашается
// - Не убивает Кирилла
// - Договаривается с ним
// - КОНЦОВКА - МИРНЫЙ ДОГОВОР

// 3 Путь
// Говорит
// Отказывается
// Помогает Кириллу
// КОНЦОВКА - ДРЕВНЯ В ОГНЕ - ОТЛИЧКА КАКОЙ ЦЕНОЙ

enum class VillageQuestState{
    NOT_STARTED,
    TALKED_TO_ELDER,

    ACCEPTED_HELP,
    REFUSED_HELP,

    KILLED_KIRILL_SHAMAN,
    MADE_PEACE,
    HELPED_KIRILL,

    HERO_ENDING,
    PEACE_ENDING,
    BAD_ENDING,

    KILLED_ORC,
    KILLED_ELDER,
    SECRET_ENDING
}