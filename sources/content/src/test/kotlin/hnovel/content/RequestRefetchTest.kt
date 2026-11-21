package hnovel.content

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class RequestRefetchTest {
    @Test fun explicitRefetchUsesUpdatedCookiesAndDoesNotReenterLoginCheck() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("X-Session", "new")
                    .setBody("<li><a href='/book'><h2>${if (request.getHeader("Cookie") == "session=new") "New" else "Old"}</h2></a></li>")
            }
            fixture.source { raw -> JsonObject(raw + ("loginCheckJs" to JsonPrimitive("""
                if(source.get('checks')) throw 'recursive loginCheck';
                source.put('checks','1');
                cookie.replaceCookie(result.getUrl(),'session=new');
                var next=java.getStrResponse(null,null);
                if(next.code()!==200 || next.header('X-Session')!=='new' || next.getUrl()!==result.getUrl()) throw 'response metadata';
                next;
            """.trimIndent()))) }.use { source ->
                assertEquals("New", source.search("key").single().title)
                assertEquals(2, fixture.server.requestCount)
                val initial = fixture.server.takeRequest(1, TimeUnit.SECONDS)!!
                val repeated = fixture.server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals(initial.path, repeated.path)
                assertNull(initial.getHeader("Cookie"))
                assertEquals("session=new", repeated.getHeader("Cookie"))
            }
        }
    }

    @Test fun refetchRestartsAtOriginalUrlAndRetainsRedirectPolicy() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.path == "/entry")
                    MockResponse().setResponseCode(302).setHeader("Location", "/final")
                else MockResponse().setBody("<li><a href='/book'><h2>Final</h2></a></li>")
            }
            fixture.source { raw -> JsonObject(raw + mapOf(
                "searchUrl" to JsonPrimitive("/entry"),
                "loginCheckJs" to JsonPrimitive("""
                    var next=java.getStrResponse(null,null);
                    if(next.code()!==200 || !next.getUrl().endsWith('/final')) throw 'redirect'; next;
                """.trimIndent())
            )) }.use { source ->
                assertEquals("Final", source.search("key").single().title)
                assertEquals(4, fixture.server.requestCount)
                assertEquals(listOf("/entry", "/final", "/entry", "/final"), (1..4).map { fixture.server.takeRequest().path })
            }
        }
    }
}
