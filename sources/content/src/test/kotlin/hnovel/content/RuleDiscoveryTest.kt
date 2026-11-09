package hnovel.content

import hnovel.network.BrowserExecutor
import hnovel.network.BrowserOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RuleDiscoveryTest {
    @Test fun catalogueNoticeWaitUsesTheOriginalLibraryCallingConventionWithoutFetching() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source { definition(it, """
                @js:sleepToast('Catalogue ready');
                [{title:'Latest',url:'/latest'}, {title:'Popular',url:'/popular'}];
            """.trimIndent(), buildJsonObject { put("jsLib", """
                function sleep(seconds) { return Packages.java.lang.Thread.sleep(1000*seconds); }
                function sleepToast(text, seconds) {
                    let {java} = this;
                    java.log(text); java.longToast(text);
                    if (seconds === undefined) seconds = 0.01;
                    this.sleep(seconds);
                }
            """.trimIndent()) }) }.use { source ->
                assertEquals(listOf("Latest", "Popular"), source.openDiscovery("notice").catalog().rows.map { it.title })
            }
            assertEquals(0, fixture.server.requestCount)
        }
    }

    @Test fun lenientCataloguesDiscardNullPlaceholdersIncludingTrailingCommas() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source { definition(it, "[null,{title:'Latest',url:'/search'},null,]") }.use { source ->
                assertTrue(source.canFeed)
                val page = source.openDiscovery("trailing-comma")
                val catalog = page.catalog()
                assertEquals(listOf("Latest"), catalog.rows.map { it.title })
                for (number in listOf(1, 65, 66)) {
                    assertEquals("Same title", page.page(catalog.rows.single().url, number, emptyMap()).single().title)
                }
            }
            fixture.source { definition(it, "[null,]") }.use { source ->
                assertFalse(source.canFeed)
                assertFalse(source.canCategorize)
                assertTrue(source.openDiscovery("empty").catalog().rows.isEmpty())
            }
            fixture.source { definition(it, "[{title:'Latest',url:'/search'},42]") }.use { source ->
                try { source.openDiscovery("invalid-row").catalog(); fail("Invalid rows must remain errors") }
                catch (failure: SourceContentException) { assertEquals("exploreUrl[1]", failure.field) }
            }
        }
    }

    @Test fun bookListsHaveRoomForApiMetadataBeforePerBookFieldExtraction() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val response = buildJsonObject { putJsonObject("data") { putJsonArray("data") {
                repeat(25) { i -> add(buildJsonObject {
                    put("id", "/book/$i"); put("name", "Book $i"); put("metadata", "x".repeat(12000))
                }) }
            } } }.toString()
            fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                    okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json").setBody(response)
            }
            fixture.source { raw -> JsonObject(raw + mapOf(
                "exploreUrl" to JsonPrimitive("Latest::/list"),
                "ruleExplore" to buildJsonObject { put("bookList", "$.data.data.*"); put("name", "name"); put("bookUrl", "id") }
            )) }.use { source ->
                val rows = source.openDiscovery("metadata").page("/list", 1, emptyMap())
                assertEquals(25, rows.size)
                assertEquals("Book 24", rows.last().title)
            }
        }
    }
    private fun definition(raw: JsonObject, url: String, extra: JsonObject = JsonObject(emptyMap())) =
        JsonObject(raw + mapOf("exploreUrl" to JsonPrimitive(url), "ruleExplore" to raw.getValue("ruleSearch")) + extra)

    @Test fun dynamicCataloguesOwnResultWhileBookRulesKeepTheirInput() = runBlocking {
        RuleSourceFixture().use { fixture ->
            for (declaration in listOf("var", "let", "const")) {
                fixture.source { definition(it, """
                    @js:$declaration result = [{title:'Books '+infoMap.Sort,url:'/search?page={{page}}'}];
                    JSON.stringify(result);
                """.trimIndent()) }.use { source ->
                    val a = source.openDiscovery("a", mapOf("Sort" to "new"))
                    val b = source.openDiscovery("b", mapOf("Sort" to "popular"))
                    assertEquals("Books new", a.catalog().rows.single().title)
                    assertEquals("Books popular", b.catalog().rows.single().title)
                    assertEquals("Books new", a.catalog(refresh = true).rows.single().title)
                    assertEquals("/search?page={{page}}", a.catalog().rows.single().url)
                    assertEquals("Same title", a.page(a.catalog().rows.single().url, 2, emptyMap()).single().title)
                }
            }
        }
    }

    @Test fun sourceCallbacksCanDeclareResultAndResolveLibraryNames() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source { definition(it, """
                @js:const result = [
                  {id:'Mode',title:'Mode',type:'text',default:'first',viewName:"const result='Mode '+infoMap.Mode;result"},
                  {id:'apply',title:libraryName(),type:'button',action:"const result='second';infoMap.Mode=result;java.refreshExplore();"}
                ];result;
            """.trimIndent(), buildJsonObject { put("jsLib", "var label='Library';function libraryName(){return label;}") }) }.use { source ->
                val page = source.openDiscovery("callbacks")
                assertEquals(listOf("Mode first", "Library"), page.catalog().rows.map { it.title })
                assertTrue(page.interact("apply").refresh)
                assertEquals("Mode second", page.catalog().rows.first().title)
            }
            fixture.source { definition(it, "@js:[{title:typeof result,url:'/search'}]") }.use { source ->
                assertEquals("undefined", source.openDiscovery("absent-input").catalog().rows.single().title)
            }
            fixture.source { definition(it, "@js:[{title:result,url:'/search'}]",
                buildJsonObject { put("jsLib", "var result='Library';") }) }.use { source ->
                assertEquals("Library", source.openDiscovery("library-input").catalog().rows.single().title)
            }
        }
    }

    @Test fun nestedRulesRestoreAbsentAndInheritedCatalogueResultBindings() = runBlocking {
        RuleSourceFixture().use { fixture ->
            for ((library, expected) in listOf("" to "Nested", "var result='Library';" to "Library")) {
                for (operation in listOf("java.getString('@js:result', 'nested');",
                    "try { java.getString('@js:throw new Error(\"fixture\")', 'nested'); } catch (error) {}")) {
                    fixture.source { definition(it, """
                        @js:$operation
                        typeof result === 'undefined'
                            ? eval("const result = [{title:'Nested',url:'/search'}]; result")
                            : [{title:result,url:'/search'}]
                    """.trimIndent(), buildJsonObject { put("jsLib", library) }) }.use { source ->
                        assertEquals(expected, source.openDiscovery("nested-result").catalog().rows.single().title)
                    }
                }
            }
        }
    }

    @Test fun requestTemplatesRestoreAbsentAndInheritedCatalogueResultBindings() = runBlocking {
        RuleSourceFixture().use { fixture ->
            for ((library, expected) in listOf("" to "Template", "var result='Library';" to "Library")) {
                for (operation in listOf("java.ajax('/search?q={{1+1}}');",
                    """try { java.ajax("@js:throw new Error('fixture')"); } catch (error) {}""")) {
                    fixture.source { definition(it, """
                        @js:$operation
                        typeof result === 'undefined'
                            ? eval("const result = [{title:'Template',url:'/search'}]; result")
                            : [{title:result,url:'/search'}]
                    """.trimIndent(), buildJsonObject { put("jsLib", library) }) }.use { source ->
                        assertEquals(expected, source.openDiscovery("template-result").catalog().rows.single().title)
                    }
                }
            }
        }
    }

    @Test fun nestedInputsDoNotReplaceCatalogueLocalResultDeclarations() = runBlocking {
        RuleSourceFixture().use { fixture ->
            for (declaration in listOf("var", "let", "const")) {
                fixture.source { definition(it, """
                    @js:$declaration result = 'Outer';
                    var nested = java.getString('@js:result', 'Inner');
                    var request = host.call('request.prepare', '/books?value={{result === null ? 42 : 0}}')[0];
                    [{title:result+':'+nested+':'+request,url:'/search'}]
                """.trimIndent()) }.use { source ->
                    assertEquals(declaration, "Outer:Inner:/books?value=42",
                        source.openDiscovery("local-result").catalog().rows.single().title)
                }
            }
        }
    }

    @Test fun staticCatalogKeepsOrderAndDoesNotFetchBookPreviews() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val source = fixture.source { definition(it, "Heading\nNew::/search?page={{page}}&&Completed::/complete") }
            val page = source.openDiscovery("page-a")
            assertTrue(source.canSearch)
            assertTrue(source.canDiscover)
            val catalog = page.catalog()
            assertEquals(listOf("Heading", "New", "Completed"), catalog.rows.map { it.title })
            assertEquals(listOf("", "/search?page={{page}}", "/complete"), catalog.rows.map { it.url })
            assertEquals(0, fixture.documents.get())
            assertEquals("Same title", page.page(catalog.rows[1].url, 1, emptyMap()).single().title)
            assertEquals(1, fixture.documents.get())
        }
    }

    @Test fun dynamicCategoriesAndUrlFiltersUseIndependentPageDrafts() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val source = fixture.source { definition(it, """
                @js:[{title:'Sort',type:'select',chars:['new','popular'],default:'new'},
                {title:'Books '+infoMap.get('Sort'),url:'/search?sort={{infoMap.Sort}}&page={{page}}'}]
            """.trimIndent()) }
            val a = source.openDiscovery("a", mapOf("Sort" to "new"))
            val b = source.openDiscovery("b", mapOf("Sort" to "popular"))
            val first = a.catalog()
            val second = b.catalog()
            assertEquals("Books new", first.rows.last().title)
            assertEquals("Books popular", second.rows.last().title)
            assertEquals(first.rows.last().id, second.rows.last().id)
            a.page(first.rows.last().url, 1, mapOf("Sort" to "new"))
            b.page(second.rows.last().url, 2, mapOf("Sort" to "popular"))
            assertEquals("/search?sort=new&page=1", fixture.server.takeRequest().path)
            assertEquals("/search?sort=popular&page=2", fixture.server.takeRequest().path)
            a.interact("Sort", "popular")
            assertEquals("new", first.values["Sort"])
            assertEquals("popular", b.catalog().values["Sort"])
        }
    }

    @Test fun infoMapSetBracketWritesAndExplicitSaveSurviveOnlyThroughSourceSettings() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val source = fixture.source { definition(it, """[
                {"title":"Query","type":"text","default":"initial"},
                {"id":"save","title":"Save","type":"button","action":"infoMap.set({Query:'saved'});infoMap.Query+=' value';infoMap.save(0);java.refreshExplore();source.refreshExplore();"},
                {"id":"draft","title":"Draft","type":"button","action":"infoMap.Query='draft';infoMap.save(0,false);"}
            ]""") }
            val a = source.openDiscovery("a")
            val b = source.openDiscovery("b")
            assertEquals("initial", b.catalog().values["Query"])
            val updated = a.interact("save")
            assertTrue(updated.refresh)
            assertEquals("saved value", updated.catalog.values["Query"])
            assertEquals("initial", b.catalog().values["Query"])
            assertEquals("saved value", source.openDiscovery("restored").catalog().values["Query"])
            a.interact("draft")
            assertEquals("draft", a.catalog().values["Query"])
            assertEquals("saved value", source.openDiscovery("after-draft").catalog().values["Query"])
        }
    }

    @Test fun nonInteractiveCatalogAndViewNameSaveOnlyAfterSuccessfulValidation() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val source = fixture.source { definition(it, """
                @js:infoMap.catalog='saved';infoMap.save();
                [{title:'Query',type:'text',default:'start',
                  viewName:"infoMap.rendered='saved';infoMap.save();if(infoMap.fail==='yes')throw 'fixture failure';'Search '+infoMap.Query"}]
            """.trimIndent()) }
            val catalog = source.openDiscovery("catalog").catalog()
            assertEquals("Search start", catalog.rows.single().title)
            val saved = source.discoveryState("info")!!
            assertEquals(buildJsonObject {
                put("catalog", "saved"); put("Query", "start"); put("rendered", "saved")
            }, Json.parseToJsonElement(saved).jsonObject["values"])
            val failure = try { source.openDiscovery("failed", mapOf("fail" to "yes")).catalog(); null }
                catch (e: SourceContentException) { e }
            assertEquals("exploreUrl[0].viewName", failure?.field)
            assertEquals(saved, source.discoveryState("info"))
        }
    }

    @Test fun inputUpdatesConfigurationAndBrowserIntentsHaveRealEffectsThroughExistingPorts() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val source = fixture.source { definition(it, """[
                {"title":"Query","type":"text"},
                {"id":"configure","title":"Configure","type":"button","action":"java.upLoginData({Query:'filled'});java.upConfig({category:'new'});java.showBrowser('/login',null,null,'{\"title\":\"Sign in\"}');"},
                {"id":"inspect","title":"Inspect","type":"button","action":"infoMap.config=source.getVariable();java.upConfig();java.open('explore','/search','Books',source.getKey());java.searchBook('A title',java.getSource());"}
            ]""") }
            val page = source.openDiscovery("controls")
            val updated = page.interact("configure")
            assertEquals("filled", updated.catalog.values["Query"])
            assertEquals(listOf("configuration", "browser"), updated.actions.map { it.kind })
            assertTrue(updated.refresh)
            assertEquals(fixture.server.url("/login").toString(), updated.actions.last().value)
            assertEquals("Sign in", updated.actions.last().title)
            val inspected = page.interact("inspect")
            assertEquals("{\"category\":\"new\"}", inspected.catalog.values["config"])
            assertEquals(listOf("settings", "results", "search"), inspected.actions.map { it.kind })
            // A foreground browser still passes through the host's origin grants.
            val denied = try { source.openDiscoveryBrowser("https://denied.invalid/"); null } catch (e: SourceContentException) { e }
            assertEquals(ContentError.PermissionDenied, denied?.code)
            assertEquals(0, fixture.documents.get())
        }
    }

    @Test fun unknownRowsAndCrossSourceActionsProduceFieldDiagnosticsWithoutCommittingDrafts() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val unknown = fixture.source("unknown") { definition(it, "[{\"title\":\"Unknown\",\"type\":\"nativeView\"}]") }
            val bad = try { unknown.openDiscovery("bad").catalog(); null } catch (e: SourceContentException) { e }
            assertEquals("exploreUrl[0].type", bad?.field)
            val source = fixture.source("foreign") { definition(it, """[
                {"id":"foreign","title":"Foreign","type":"button","action":"infoMap.secret='draft';infoMap.save();java.open('login',null,null,'https://another.invalid/');"}
            ]""") }
            val error = try { source.openDiscovery("foreign").interact("foreign"); null } catch (e: SourceContentException) { e }
            assertEquals(ContentError.InvalidRule, error?.code)
            assertEquals("discovery.actions[0].open", error?.field)
            assertNull(source.discoveryState("info"))
        }
    }

    @Test fun disabledDiscoveryKeepsSearchAndRetiredSessionsCannotPublish() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val source = fixture.source { definition(it, "Books::/search", buildJsonObject { put("enabledExplore", false) }) }
            assertTrue(source.canSearch)
            assertFalse(source.canDiscover)
            val missing = try { source.openDiscovery("disabled").catalog(); null } catch (e: SourceContentException) { e }
            assertEquals(ContentError.MissingCapability, missing?.code)
            source.close()
            val retired = try { source.openDiscovery("retired").catalog(); null } catch (e: SourceContentException) { e }
            assertEquals(ContentError.Unavailable, retired?.code)
        }
    }

    @Test fun screenControlsAndViewNamesUseTheSameFiniteContract() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val source = fixture.source { definition(it, "Books::/search", buildJsonObject {
                put("exploreScreen", """[{"id":"presentation-id","title":"Query","type":"text","default":"start","viewName":"'Search '+infoMap.Query"}]""")
            }) }
            val page = source.openDiscovery("screen")
            val rows = page.catalog().rows
            assertEquals(listOf("url", "text"), rows.map { it.type })
            assertEquals("Query", rows.last().id)
            assertEquals("Search start", rows.last().title)
            page.interact("Query", "edited")
            val updated = page.catalog(refresh = true)
            assertEquals("Query", updated.rows.last().id)
            assertEquals("Search edited", updated.rows.last().title)
            assertEquals(mapOf("Query" to "edited"), updated.values)
        }
    }

    @Test fun dynamicLibraryHelpersReceiveEachPagesCurrentDraftWithoutSharingIt() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val source = fixture.source { definition(it, "@js:catalog(infoMap)", buildJsonObject {
                put("jsLib", """
                    function catalog(values){return [{title:'Books '+values.name,url:'/search'},
                    {id:'update',title:'Update',type:'button',action:'update(infoMap,java)'}];}
                    function update(values,host){values.name+=' changed';host.refreshExplore();}
                """.trimIndent())
            }) }
            val first = source.openDiscovery("first", mapOf("name" to "A"))
            val second = source.openDiscovery("second", mapOf("name" to "B"))
            assertEquals("Books A", first.catalog().rows.first().title)
            assertEquals("Books B", second.catalog().rows.first().title)
            first.interact("update")
            assertEquals("Books A changed", first.catalog().rows.first().title)
            assertEquals("Books B", second.catalog().rows.first().title)
            assertEquals(0, fixture.documents.get())
        }
    }

    @Test fun expiredInfoMapSettingsAreNotRestoredIntoNewPageDrafts() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val source = fixture.source { definition(it, "[{\"title\":\"Query\",\"type\":\"text\",\"default\":\"new\"}]") }
            source.saveDiscoveryState("info", "{\"values\":{\"Query\":\"expired\"},\"expires\":1}")
            assertEquals("new", source.openDiscovery("restored").catalog().values["Query"])
        }
    }

    @Test fun browserActionsReachTheExistingSourceBoundPortWithHeadersAndOptions() = runBlocking {
        var observed: BrowserOptions? = null
        var owner: String? = null
        val browser = BrowserExecutor { session, request, options, guard, _ ->
            observed = options; owner = session.scope.sourceId
            assertEquals("A", request.headers["X-Source"])
            session.execute(request.copy(browser = null), guard)
        }
        RuleSourceFixture(browser).use { fixture ->
            val source = fixture.source { definition(it, """[
                {"id":"browser","title":"Browser","type":"button","action":"java.showBrowser('/search','<p>fixture</p>','document.title','{\"title\":\"Source browser\"}')"}
            ]""") }
            val action = source.openDiscovery("browser").interact("browser").actions.single()
            source.openDiscoveryBrowser(action.value, action.html, action.script, action.title)
            assertEquals(source.definition.sourceId, owner)
            assertEquals(BrowserOptions(interactive = true, html = "<p>fixture</p>", script = "document.title", title = "Source browser"), observed)
            assertEquals(1, fixture.documents.get())
        }
    }
}
