package app.cann

import app.cann.data.Composio
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assume.assumeTrue
import org.junit.Test

class McpLiveTest {
    @Test fun live() = runBlocking {
        val k = System.getenv("CK_KEY").orEmpty()
        assumeTrue(k.isNotBlank())
        val api = Composio(k, "default")
        println("kits=" + api.toolkitPage("goo").items.map { it.slug })
        println("gmail tools=" + api.toolPage("gmail").items.map { it.slug })
        println("search=" + api.toolPage(null, "send slack message").items.map { it.slug + "/" + it.toolkit })
        val t = api.tool("GMAIL_SEND_EMAIL"); println("tool=${t.name} params=${t.params.take(3).map { it.name }}")
        println("accounts=" + api.accounts())
        val r = api.execute("GMAIL_GET_PROFILE", JsonObject(emptyMap()), null)
        println("exec ok=${r.ok} data=${r.data.toString().take(120)}")
        val bad = api.execute("GMAIL_NOPE_X", JsonObject(emptyMap()), null)
        println("bad ok=${bad.ok} err=${bad.error?.take(80)}")
    }
}
