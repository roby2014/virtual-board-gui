import org.http4k.client.WebsocketClient
import org.http4k.core.Uri
import org.http4k.websocket.Websocket

fun connectToSimulation(block: (Websocket) -> Unit): Websocket {
    val nonBlockingClient = WebsocketClient.nonBlocking(Uri.of("ws://127.0.0.1:8083"))
    block(nonBlockingClient)
    return nonBlockingClient
}
