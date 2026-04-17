package lessonLineCounterKT

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.pathString

@Serializable
data class LineCountResult(
    val fileName: String,
    val filePath: String,
    val lineCount: Int
)

@Serializable
data class ErrorResult(
    val error: String,
    val usage: String = "Мяу: <program> <path-to-lesson-file>"
)

private val json = Json { prettyPrint = true }

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println(
            json.encodeToString(
                ErrorResult(error = ";(((((")
            )
        )
        return
    }

    val targetPath = Path.of(args[0]).normalize()
    if (!targetPath.isRegularFile()) {
        println(
            json.encodeToString(
                ErrorResult(error = "ОШИБКА: ${targetPath.pathString}")
            )
        )
        return
    }

    val lineCount = Files.newBufferedReader(targetPath).use { reader ->
        var count = 0
        while (reader.readLine() != null) {
            count++
        }
        count
    }

    val result = LineCountResult(
        fileName = targetPath.name,
        filePath = targetPath.pathString,
        lineCount = lineCount
    )

    println(json.encodeToString(result))
}
// cd "C:\Users\User\Documents\clown\project\kotlin\Olegi4\untitled6"
// .\gradlew.bat countLessonLines -PfilePath="src/main/kotlin/lesson1/main.kt"
