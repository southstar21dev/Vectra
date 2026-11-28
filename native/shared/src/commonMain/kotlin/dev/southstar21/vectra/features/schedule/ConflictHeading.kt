package dev.southstar21.vectra.features.schedule

/** The editor's clash title, naming what the item overlaps: "Clashes with a task", "Clashes with 2 classes", "Clashes with 3 items". */
internal fun conflictHeading(conflicts: List<ScheduleConflict>): String {
    val types = conflicts.map { it.otherType }.distinct()
    val n = conflicts.size
    // A class and an exam are both "classes" on the schedule.
    val noun = when {
        types.all { it == "task" } -> "task" to "tasks"
        types.all { it == "shift" } -> "shift" to "shifts"
        types.all { it == "class" || it == "exam" } -> "class" to "classes"
        else -> "item" to "items"
    }
    return if (n == 1) "Clashes with a ${noun.first}" else "Clashes with $n ${noun.second}"
}
