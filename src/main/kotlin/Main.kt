import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.http4k.websocket.Websocket
import org.http4k.websocket.WsMessage
import java.awt.FileDialog
import java.io.File

@Serializable
data class IOComponent(
    val pinName: String,
    val pinId: String,
    val status: MutableState<Boolean> = mutableStateOf(false)
)

@Serializable
data class Board(
    val boardName: String = "Virtual Board",
    val leds: List<IOComponent> = listOf<IOComponent>(),
    val switches: List<IOComponent> = listOf<IOComponent>(),
    val buttons: List<IOComponent> = listOf<IOComponent>()
)

fun openFileDialog(
    window: ComposeWindow,
    title: String,
    allowedExtensions: List<String>,
    allowMultiSelection: Boolean = true
): Set<File> {
    return FileDialog(window, title, FileDialog.LOAD).apply {
        isMultipleMode = allowMultiSelection

        // windows
        file = allowedExtensions.joinToString(";") { "*$it" } // e.g. '*.jpg'

        // linux
        setFilenameFilter { _, name ->
            allowedExtensions.any {
                name.endsWith(it)
            }
        }

        isVisible = true
    }.files.toSet()
}

fun main(args: Array<String>) = application {
    val board = remember { mutableStateOf(Board()) }
    val boardLoaded = remember { mutableStateOf(false) }
    val wsConnected = remember { mutableStateOf(false) }

    var ws: Websocket? = null
    val wsHandler = { conn: Websocket ->
        conn.run {
            wsConnected.value = true
        }
        conn.onMessage { it ->
            if (boardLoaded.value) {
                val msg = it.bodyString()
                if (msg.contains("[ERROR]")) {
                    println(it.bodyString())
                    return@onMessage
                }

                val words = msg.split(" ")
                val pinId = words[0]
                val newValue = words[2]
                // the gui app only receives output led pins and hex
                val ledIdx = board.value.leds.indexOfFirst { led -> led.pinId == pinId }
                if (ledIdx != -1) {
                    board.value.leds[ledIdx].status.value = newValue == "1"
                }
            }
        }
        conn.onClose {
            wsConnected.value = false
            ws = null
        }

    }

    Window(
        onCloseRequest = ::exitApplication,
        title = board.value.boardName
    ) {
        MaterialTheme {
            menuBar {
                val files = openFileDialog(window, "Load Board Configuration", listOf("json"))
                if (files.isNotEmpty()) {
                    val fp = files.first().readText()
                    board.value = Json.decodeFromString<Board>(fp)
                    boardLoaded.value = true
                }
            }

            if (!boardLoaded.value) {
                showInitialWindow()
            } else {
                Column {
                    ledsRow(board.value.leds)
                    switchesRow(board.value.switches) { pinId, value ->
                        ws?.send(WsMessage("CHANGE $pinId $value"))
                    }
                    buttonsRow(board.value.buttons) { pinId, value ->
                        ws?.send(WsMessage("CHANGE $pinId $value"))
                    }
                    Row {
                        Button(onClick = {
                            if (ws == null) {
                                ws = connectToSimulation(wsHandler)
                            }
                        }) {
                            Text(if (wsConnected.value) "Connected" else "Connect to Simulation")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FrameWindowScope.menuBar(onClick: () -> Unit) {
    MenuBar {
        Menu("Simulation", mnemonic = 'T') {
            Item(
                "Load board.json",
                onClick = onClick
            )
        }
    }
}

@Composable
fun showInitialWindow() {
    Column(modifier = Modifier.background(Color.Black).fillMaxSize()) {
        Image(
            painter = painterResource("logo.png"),
            contentDescription = "ISEL logo",
            modifier = Modifier.fillMaxSize().padding(25.dp)
        )
    }
}

@Composable
fun ledsRow(leds: List<IOComponent>) {
    Row {
        leds.forEach { led ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .size(100.dp)
                    .clickable(onClick = { })
            ) {
                Row {
                    Text(led.pinId)
                }
                Row {
                    Text(led.pinName)
                }
                Row {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentSize(Alignment.Center)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(25.dp)
                                .clip(CircleShape)
                                .background(if (led.status.value) Color.Red else Color.Gray)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun switchesRow(switches: List<IOComponent>, onClick: (String, Int) -> Unit) {
    Row {
        switches.forEach { switch ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.size(100.dp)
                    .clickable(onClick = { })
            ) {
                Row {
                    Text(switch.pinId)
                }
                Row {
                    Text(switch.pinName)
                }
                Row {
                    val img = if (switch.status.value) "switch_on.png" else "switch_off.png"
                    Image(
                        painter = painterResource(img),
                        contentDescription = "Switch Image",
                        modifier = Modifier
                            .fillMaxSize()
                            .onPointerEvent(PointerEventType.Press) {
                                switch.status.value = !switch.status.value
                                onClick(switch.pinId, if (switch.status.value) 1 else 0)
                            }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun buttonsRow(buttons: List<IOComponent>, onClick: (String, Int) -> Unit) {
    Row {
        buttons.forEach { btn ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .size(100.dp)
                    .clickable(onClick = { })
            ) {
                Row {
                    Text(btn.pinId)
                }
                Row {
                    Text(btn.pinName)
                }
                Row {
                    val img = if (btn.status.value) "button_pressed.png" else "button.png"
                    Image(
                        painter = painterResource(img),
                        contentDescription = "Switch Image",
                        modifier = Modifier
                            .fillMaxSize()
                            .onPointerEvent(PointerEventType.Press) {
                                btn.status.value = true
                                onClick(btn.pinId, 1)
                            }
                            .onPointerEvent(PointerEventType.Release) {
                                btn.status.value = false
                                onClick(btn.pinId, 0)
                            }
                    )
                }
            }
        }
    }
}