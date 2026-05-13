package CubeDupe

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene
import de.fabmax.kool.input.KeyboardInput
import de.fabmax.kool.input.UniversalKeyCode
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.pipeline.ClearColorLoad
import de.fabmax.kool.scene.*
import de.fabmax.kool.util.Color
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class CubeInfo(
    val id: Int,
    val x: Float,
    val y: Float,
    val z: Float,
)

@Serializable
data class CubeSave(
    val cubes: List<CubeInfo>
)

sealed interface CubeEvent

data class CubeSpawned(val cube: CubeInfo) : CubeEvent
data class CubesSaved(val count: Int, val filePath: String) : CubeEvent
data class CubesLoaded(val count: Int, val filePath: String) : CubeEvent
data class CubeActionFailed(val reason: String) : CubeEvent
data class CubesReset(val filePath: String) : CubeEvent

class CubeSaveSystem {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    private fun saveDir(): File = File("saves").also { if (!it.exists()) it.mkdirs() }
    private fun saveFile(): File = File(saveDir(), "cube_dupe.json")

    fun save(cubes: List<CubeInfo>) {
        val text = json.encodeToString(CubeSave(cubes))
        saveFile().writeText(text)
    }

    fun deleteSave(): Boolean {
        val file = saveFile()
        return file.exists() && file.delete()
    }

    fun load(): List<CubeInfo>? {
        val file = saveFile()
        if (!file.exists()) return null
        return try {
            val text = file.readText()
            json.decodeFromString<CubeSave>(text).cubes
        } catch (e: Exception) {
            throw IllegalStateException("Ошибка чтения ${file.absolutePath}: ${e.message}", e)
        }
    }

    fun filePath(): String = saveFile().absolutePath
}

class CubeServer(
    private val saver: CubeSaveSystem
) {
    private val _events = MutableSharedFlow<CubeEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<CubeEvent> = _events.asSharedFlow()

    private val _cubes = MutableStateFlow<List<CubeInfo>>(emptyList())
    val cubes: StateFlow<List<CubeInfo>> = _cubes.asStateFlow()

    fun spawnCubeOnTop() {
        val old = _cubes.value
        val id = old.size + 1

        val baseX = 0f
        val baseZ = 0f
        val baseY = 0f
        val newY = baseY + old.size.toFloat()

        val cube = CubeInfo(id = id, x = baseX, y = newY, z = baseZ)
        _cubes.value = old + cube
        _events.tryEmit(CubeSpawned(cube))
    }

    fun saveNow() {
        val cubes = _cubes.value
        saver.save(cubes)
        _events.tryEmit(CubesSaved(cubes.size, saver.filePath()))
    }

    fun loadNow() {
        val loaded = try {
            saver.load()
        } catch (e: Exception) {
            _events.tryEmit(CubeActionFailed(e.message ?: "Ошибка загрузки"))
            null
        }
        if (loaded == null) {
            _events.tryEmit(CubeActionFailed("Нет сохранения: ${saver.filePath()}"))
            return
        }
        _cubes.value = loaded
        _events.tryEmit(CubesLoaded(loaded.size, saver.filePath()))
    }

    fun reset() {
        saver.deleteSave()
        _cubes.value = emptyList()
        spawnCubeOnTop()
        _events.tryEmit(CubesReset(saver.filePath()))
    }
}

class HudState {
    val log = mutableStateOf<List<String>>(emptyList())
    val hint = mutableStateOf("Нажми E")
    val lastCountUi = mutableStateOf(0)
}

fun hudLog(hud: HudState, text: String) {
    hud.log.value = (hud.log.value + text).takeLast(25)
}

fun main() = KoolApplication {
    val saver = CubeSaveSystem()
    val server = CubeServer(saver)
    val hud = HudState()

    addScene {
        defaultOrbitCamera()

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, -1f))
            setColor(Color.WHITE, 5f)
        }

        val cubeNodes = mutableListOf<Mesh<*>>()

        fun rebuildCubes() {
            val current = server.cubes.value
            while (cubeNodes.size < current.size) {
                val node = addColorMesh {
                    generate { cube { colored() } }
                    shader = KslPbrShader {
                        color { vertexColor() }
                        metallic(0f)
                        roughness(0.25f)
                    }
                }
                cubeNodes.add(node)
            }

            for (i in cubeNodes.indices) {
                val node = cubeNodes[i]
                if (i >= current.size) {
                    node.isVisible = false
                } else {
                    val info = current[i]
                    node.isVisible = true
                    node.transform.setIdentity()
                    node.transform.translate(info.x, info.y, info.z)
                }
            }
        }

        server.loadNow()
        if (server.cubes.value.isEmpty()) {
            server.spawnCubeOnTop()
        }
        rebuildCubes()

        server.cubes
            .onEach {
                saver.save(it)
                rebuildCubes()
            }
            .launchIn(coroutineScope)

        server.events
            .onEach { ev ->
                when (ev) {
                    is CubeSpawned -> hudLog(hud, "Создан куб #${ev.cube.id} на y=${ev.cube.y}")
                    is CubesSaved -> hudLog(hud, "Сохранено кубов: ${ev.count} в ${ev.filePath}")
                    is CubesLoaded -> hudLog(hud, "Загружено кубов: ${ev.count} из ${ev.filePath}")
                    is CubeActionFailed -> hudLog(hud, "Ошибка: ${ev.reason}")
                    is CubesReset -> hudLog(hud, "Reset: сохранение удалено, создан 1 куб (${ev.filePath})")
                }
            }
            .launchIn(coroutineScope)

        KeyboardInput.addKeyListener(
            keyCode = UniversalKeyCode('E'),
            name = "CubeDupe: нажми E",
            filter = { it.isPressed && !it.isRepeated }
        ) {
            server.spawnCubeOnTop()
        }
    }

    addScene {
        setupUiScene(ClearColorLoad)

        server.cubes
            .map { it.size }
            .onEach { hud.lastCountUi.value = it }
            .launchIn(coroutineScope)

        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), 14.dp))
                .padding(12.dp)

            Column {
                val count = hud.lastCountUi.use()
                Text("CubeDupe") { }
                Text("Кубов создано: $count") { modifier.margin(bottom = sizes.gap) }
                Text(hud.hint.use()) { modifier.font(sizes.smallText).margin(bottom = sizes.gap) }
                Button("Reset") {
                    modifier.margin(bottom = sizes.gap).onClick {
                        server.reset()
                    }
                }

                Text("Лог действий") { modifier.margin(top = sizes.gap) }
                for (line in hud.log.use()) {
                    Text(line) { modifier.font(sizes.smallText) }
                }
            }
        }
    }
}
