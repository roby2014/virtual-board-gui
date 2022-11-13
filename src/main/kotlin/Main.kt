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
import org.http4k.client.WebsocketClient
import org.http4k.core.Uri
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
data class SevSeg(
    var a: String = "",
    var b: String = "",
    var c: String = "",
    var d: String = "",
    var e: String = "",
    var f: String = "",
    var g: String = "",
    var p: String = ""
)

@Serializable
data class Board(
    val boardName: String = "Virtual Board",
    val leds: List<IOComponent> = listOf<IOComponent>(),
    val switches: List<IOComponent> = listOf<IOComponent>(),
    val buttons: List<IOComponent> = listOf<IOComponent>(),
    val otherPins: List<IOComponent> = listOf<IOComponent>(),
    val sevseg: List<SevSeg> = listOf<SevSeg>()
)

fun Board.getPinValue(pinId: String): MutableState<Boolean> {
    if (pinId.isEmpty())
        return mutableStateOf(false)

    this.leds.forEach { led ->
        if (led.pinId == pinId) {
            return led.status
        }
    }
    this.switches.forEach { sw ->
        if (sw.pinId == pinId) {
            return sw.status
        }
    }
    this.buttons.forEach { btn ->
        if (btn.pinId == pinId) {
            return btn.status
        }
    }
    this.otherPins.forEach { p ->
        if (p.pinId == pinId) {
            return p.status
        }
    }

    throw Exception("could not find '$pinId' (seven segment linked pin)")
}

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

fun main(args: Array<String>) {
    var ws: Websocket? = null

    application {

        //val board = remember { mutableStateOf(Board()) }
        //val boardLoaded = remember { mutableStateOf(false) }
        val board = remember { mutableStateOf(Json.decodeFromString<Board>
            (File("/home/roby/repos/virtual-board-gui/src/main/resources/board.json").readText())) }
        val boardLoaded = remember { mutableStateOf(true) }

        val wsConnected = remember { mutableStateOf(false) }
        val sevSegValues = remember { mutableStateOf(mutableListOf(listOf(false))) }
        val sevSegValue = List(board.value.sevseg.size) {
            List(8) { mutableStateOf(false) }
        }

        board.value.sevseg.forEachIndexed { idx, sev ->
            sevSegValue[idx][0].value = remember { board.value.getPinValue(sev.a) }.value
            sevSegValue[idx][1].value = remember { board.value.getPinValue(sev.b) }.value
            sevSegValue[idx][2].value = remember { board.value.getPinValue(sev.c) }.value
            sevSegValue[idx][3].value = remember { board.value.getPinValue(sev.d) }.value
            sevSegValue[idx][4].value = remember { board.value.getPinValue(sev.e) }.value
            sevSegValue[idx][5].value = remember { board.value.getPinValue(sev.f) }.value
            sevSegValue[idx][6].value = remember { board.value.getPinValue(sev.g) }.value
            sevSegValue[idx][7].value = remember { board.value.getPinValue(sev.p) }.value
        }

        val wsHandler = {
            val conn = WebsocketClient.nonBlocking(Uri.of("ws://127.0.0.1:8083")) {
                it.run {
                    println("runn")
                    ws = it
                    wsConnected.value = true
                    board.value.switches.forEach { sw ->
                        // TODO: do we want to reset all switches when connecting to simul
                        send(WsMessage("CHANGE ${sw.pinId} ${if (sw.status.value) 1 else 0}"))
                    }
                }
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

                    // the gui app only receives output led pins and hex(otherpins)
                    val ledIdx = board.value.leds.indexOfFirst { led -> led.pinId == pinId }
                    if (ledIdx != -1) {
                        board.value.leds[ledIdx].status.value = newValue == "1"
                    } else {
                        val pinIdx = board.value.otherPins.indexOfFirst { p -> p.pinId == pinId }
                        if (pinIdx != -1) {
                            board.value.otherPins[pinIdx].status.value = newValue == "1"
                        }
                    }

                }
            }
            conn.onClose {
                wsConnected.value = false
                ws = null
                println("closing websocket connection")
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
                        // output
                        sevsegRow(sevSegValue)
                        ledsRow(board.value.leds)

                        // input
                        switchesRow(board.value.switches) { pinId, value ->
                            ws?.send(WsMessage("CHANGE $pinId $value"))
                        }

                        buttonsRow(board.value.buttons) { pinId, value ->
                            ws?.send(WsMessage("CHANGE $pinId $value"))
                        }

                        // connect to simulation button
                        Row {
                            Button(onClick = {
                                if (ws == null) {
                                    wsHandler()
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
fun sevsegRow(sevSegValue: List<List<MutableState<Boolean>>>) {
    Row {
        sevSegValue.forEachIndexed { idx, seg ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .size(100.dp)
            ) {
                Row {
                    Text("HEX$idx")
                }
                Row {
                    Box {
                        Image(
                            painter = painterResource("7seg_base.png"),
                            contentDescription = "Switch Image",
                        )
                        seg.forEachIndexed { index, mutableState ->
                            if (mutableState.value) {
                                if (index == 7) {
                                    showSevSegNode('p')
                                } else {
                                    showSevSegNode('a' + index)
                                }
                            }
                        }
                    }
                }

            }
        }


    }
}

@Composable
fun showSevSegNode(node: Char) {
    Image(
        painter = painterResource("7seg_$node.png"),
        contentDescription = "Seven Segment $node",
    )
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