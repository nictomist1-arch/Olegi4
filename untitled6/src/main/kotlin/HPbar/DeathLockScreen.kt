package HPbar

import java.awt.Color
import java.awt.Graphics
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.max

object DeathLockScreen {
    @Volatile
    private var frame: JFrame? = null

    @Volatile
    private var focusTimer: Timer? = null

    fun show() {
        SwingUtilities.invokeLater {
            if (frame != null) return@invokeLater

            val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val device = ge.defaultScreenDevice
            val bounds = device.defaultConfiguration.bounds

            val cursorImg = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
            val blankCursor = Toolkit.getDefaultToolkit().createCustomCursor(cursorImg, Point(0, 0), "blank")

            val bsodBlue = Color(0, 120, 215)
            val panel = object : JPanel() {
                override fun paintComponent(g: Graphics) {
                    super.paintComponent(g)
                    g.color = Color.WHITE

                    val lines = listOf(
                        ":(  Your PC ran into a problem and needs to restart.",
                        "We're just collecting some error info, and then we'll restart for you.",
                        "",
                        "Press ESC to exit."
                    )

                    val fm = g.fontMetrics
                    var y = 120
                    for (line in lines) {
                        g.drawString(line, 120, y)
                        y += fm.height + 8
                    }
                }
            }.apply {
                background = bsodBlue
                isOpaque = true
            }

            val f = JFrame(" ").apply {
                isUndecorated = true
                isAlwaysOnTop = true
                defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
                background = bsodBlue
                contentPane = panel
                cursor = blankCursor
                setLocation(bounds.x, bounds.y)
                setSize(max(1, bounds.width), max(1, bounds.height))

                // Consume key events while focused
                addKeyListener(object : java.awt.event.KeyAdapter() {
                    override fun keyPressed(e: KeyEvent) {
                        if (e.keyCode == KeyEvent.VK_ESCAPE) {
                            e.consume()
                            hide()
                            kotlin.system.exitProcess(0)
                            return
                        }
                        e.consume()
                    }

                    override fun keyReleased(e: KeyEvent) {
                        e.consume()
                    }

                    override fun keyTyped(e: KeyEvent) {
                        e.consume()
                    }
                })

                // Consume mouse events while inside window
                val mouseAdapter = object : java.awt.event.MouseAdapter() {
                    override fun mousePressed(e: java.awt.event.MouseEvent) = e.consume()
                    override fun mouseReleased(e: java.awt.event.MouseEvent) = e.consume()
                    override fun mouseClicked(e: java.awt.event.MouseEvent) = e.consume()
                    override fun mouseMoved(e: java.awt.event.MouseEvent) = e.consume()
                    override fun mouseDragged(e: java.awt.event.MouseEvent) = e.consume()
                    override fun mouseWheelMoved(e: java.awt.event.MouseWheelEvent) = e.consume()
                    override fun mouseEntered(e: java.awt.event.MouseEvent) = e.consume()
                    override fun mouseExited(e: java.awt.event.MouseEvent) = e.consume()
                }
                addMouseListener(mouseAdapter)
                addMouseMotionListener(mouseAdapter)
                addMouseWheelListener(mouseAdapter)
            }

            frame = f
            f.isVisible = true
            f.toFront()
            f.requestFocus()
            f.requestFocusInWindow()

            focusTimer = Timer(250) {
                val cur = frame ?: run {
                    focusTimer?.stop()
                    focusTimer = null
                    return@Timer
                }
                cur.toFront()
                cur.requestFocus()
                cur.requestFocusInWindow()
            }.also { it.start() }
        }
    }

    fun hide() {
        SwingUtilities.invokeLater {
            focusTimer?.stop()
            focusTimer = null

            frame?.dispose()
            frame = null
        }
    }
}

