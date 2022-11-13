import org.http4k.client.WebsocketClient
import org.http4k.core.Uri
import org.http4k.websocket.Websocket
import org.http4k.websocket.WsMessage

fun connectToSimulation(block: (Websocket) -> Unit, switches: List<IOComponent>): Websocket {
    val conn = WebsocketClient.nonBlocking(Uri.of("ws://127.0.0.1:8083")) {
        it.run {

        }
    }
    block(conn)
    return conn
}
