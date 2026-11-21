package hnovel.imports

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class SourceDefinitionImporterTest {
    @Test fun localStorageRetentionIsValidatedAsAnExplicitExtension() {
        val importer = SourceDefinitionImporter(SourceDefinitionStore(temp.newFolder().toPath()))
        val raw = json(extra = ",\"preserveLocalStorage\":{\"https://fixture.invalid\":[\"theme\"]}")
        val valid = importer.preview(raw)
        assertTrue(valid.issues.toString(), valid.issues.isEmpty())
        assertFalse(valid.candidates.single().notices.any { it.code == "UnclassifiedField" && it.field == "preserveLocalStorage" })
        add(importer, valid)
        val invalid = importer.preview(raw.replace("[\"theme\"]", "[true]"))
        assertEquals(listOf(ImportIssue(0, ImportCode.InvalidField, "preserveLocalStorage")), invalid.issues)
    }

    @Test fun emptyLegacyRuleArraysAreAbsentRulesButNonemptyArraysRemainInvalid() {
        val importer = SourceDefinitionImporter(SourceDefinitionStore(temp.newFolder().toPath()))
        val original = """{"bookSourceUrl":"https://fixture.invalid","ruleExplore":[],"ruleReview":[]}"""
        val preview = importer.preview(original)
        assertTrue(preview.issues.toString(), preview.issues.isEmpty())
        assertEquals(Json.parseToJsonElement(original), Json.parseToJsonElement(preview.candidates.single().rawJson))
        val invalid = importer.preview(original.replace("\"ruleExplore\":[]", "\"ruleExplore\":[{}]"))
        assertEquals(listOf(ImportIssue(0, ImportCode.InvalidField, "ruleExplore")), invalid.issues)
    }
    @get:Rule val temp = TemporaryFolder()
    private fun json(key: String = "https://fixture.invalid/#a", name: String = "Same title", extra: String = "") =
        """{"bookSourceUrl":"$key","bookSourceName":"$name"$extra}"""

    private fun add(importer: SourceDefinitionImporter, preview: ImportPreview, index: Int = 0): DefinitionReference {
        val result = importer.commit(preview, listOf(ImportSelection(index, ImportDecision.Add)))
        assertNull(result.toString(), result.error)
        assertEquals(ImportOutcome.Added, result.items.single().outcome)
        return result.items.single().reference!!
    }

    @Test fun fragmentsNamesUnknownFieldsAndFlagsSurviveStoreRecreation() {
        val root = temp.newFolder().toPath()
        val store = SourceDefinitionStore(root)
        val importer = SourceDefinitionImporter(store)
        val preview = importer.preview("[${json(extra = ",\"enabled\":false,\"enabledExplore\":false,\"customOrder\":9,\"future\":{\"value\":[1,true]}")},${json("https://fixture.invalid/#b")}]")
        assertTrue(preview.issues.isEmpty())
        assertFalse(preview.candidates[0].enabled)
        assertFalse(preview.candidates[0].enabledExplore)
        assertTrue(preview.candidates[0].notices.any { it.code == "UnclassifiedField" && it.field == "future" })
        assertTrue(preview.candidates[0].notices.any { it.code == "ExternalOrderRetainedNotApplied" })
        assertTrue(store.list().isEmpty())
        add(importer, preview)
        add(importer, preview, 1)
        val reopened = SourceDefinitionStore(root).list()
        assertEquals(2, reopened.size)
        assertNotEquals(reopened[0].sourceId, reopened[1].sourceId)
        assertEquals(reopened[0].displayName, reopened[1].displayName)
        assertEquals("https://fixture.invalid/#a", reopened[0].importKey)
        assertEquals(Json.parseToJsonElement("""{"value":[1,true]}"""), Json.parseToJsonElement(reopened[0].rawJson).jsonObject["future"])
        assertEquals(digest(reopened[0].rawJson), reopened[0].contentDigest)
        assertEquals(1L, reopened[0].revision)
        assertFalse(reopened[0].enabled)
    }

    @Test fun malformedArrayRowsAreReportedAtOriginalIndexesAndNeverAutomaticallyImported() {
        val store = SourceDefinitionStore(temp.newFolder().toPath())
        val importer = SourceDefinitionImporter(store)
        val preview = importer.preview("[null,17,${json()},${json(extra = ",\"enabled\":\"perhaps\"")},{\"bookSourceName\":\"no key\"}]")
        assertEquals(listOf(0,1,3,4), preview.issues.map { it.index })
        assertEquals(listOf(2), preview.candidates.map { it.index })
        assertTrue(store.list().isEmpty())
        assertTrue(importer.commit(preview, emptyList()).items.isEmpty())
        val result = importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add), ImportSelection(2, ImportDecision.Add)))
        assertEquals(listOf(ImportOutcome.Failed, ImportOutcome.Added), result.items.map { it.outcome })
        assertEquals(1, store.list().size)
    }

    @Test fun complexFieldsAreValidationErrorsAndCannotOverwriteExistingDefinition() {
        val root = temp.newFolder().toPath()
        val store = SourceDefinitionStore(root)
        val importer = SourceDefinitionImporter(store)
        val existing = add(importer, importer.preview(json()))
        val before = Files.readAllBytes(root.resolve("definitions.json"))
        for (field in listOf("bookSourceUrl", "bookSourceName", "enabled", "enabledExplore")) {
            for (complex in listOf("{}", "[]")) {
                val values = Json.parseToJsonElement(json()).jsonObject.toMutableMap()
                values[field] = Json.parseToJsonElement(complex)
                val preview = importer.preview(JsonObject(values).toString())
                assertEquals(field, ImportCode.InvalidField, preview.issues.single().code)
                assertEquals(field, preview.issues.single().field)
                assertTrue(preview.candidates.isEmpty())
                val result = importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Replace(existing))))
                assertEquals(ImportOutcome.Failed, result.items.single().outcome)
            }
        }
        assertArrayEquals(before, Files.readAllBytes(root.resolve("definitions.json")))
    }

    @Test fun invalidShapesTypesProfilesAndDuplicateJsonMembersProduceExplicitErrors() {
        val importer = SourceDefinitionImporter(SourceDefinitionStore(temp.newFolder().toPath()))
        val cases = mapOf("bad" to ImportCode.InvalidJson, "true" to ImportCode.InvalidShape,
            "{}" to ImportCode.UnsupportedFormat, json(extra = ",\"ruleSearch\":[{}]") to ImportCode.InvalidField,
            json(extra = ",\"bookSourceType\":1") to ImportCode.UnsupportedType,
            json(extra = ",\"bookSourceUrl\":\"second\"") to ImportCode.DuplicateField,
            "[${json()},]" to ImportCode.InvalidJson, json() + " true" to ImportCode.InvalidJson)
        cases.forEach { (input, code) -> assertEquals(input, code, importer.preview(input).issues.single().code) }
        assertEquals(ImportCode.UnsupportedProfile, importer.preview(json(), "made-up").issues.single().code)
        assertTrue(importer.preview("[]").candidates.isEmpty())
    }

    @Test fun limitsCountEveryArrayRowAndBoundBytesAndDepth() {
        val importer = SourceDefinitionImporter(SourceDefinitionStore(temp.newFolder().toPath()), ImportLimits(maxBytes = 128, maxEntries = 2, maxDepth = 2))
        assertEquals(ImportCode.TooMany, importer.preview("[null,null,null]").issues.single().code)
        assertEquals(ImportCode.TooLarge, importer.preview(" ".repeat(129)).issues.single().code)
        assertEquals(ImportCode.TooDeep, importer.preview("[[[[]]]]").issues.single().code)
        assertEquals(ImportCode.TooLarge, importer.previewStream(ByteArrayInputStream(ByteArray(129)), "huge.json").issues.single().code)
        assertEquals(ImportCode.DuplicateField, importer.preview("""{"bookSourceUrl":"a","nested":{"a":1,"\u0061":2}}""").issues.single().code)
    }

    @Test fun repeatedIdentitiesRequireChoosingOneAndReplacingRequiresCurrentRevision() {
        val store = SourceDefinitionStore(temp.newFolder().toPath())
        val importer = SourceDefinitionImporter(store)
        val preview = importer.preview("[${json()},${json(name = "Renamed")}]")
        assertEquals(listOf(1), preview.candidates[0].duplicateIndexes)
        val both = importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add), ImportSelection(1, ImportDecision.Add)))
        assertTrue(both.items.all { it.error == ImportCode.DuplicateSelection })
        assertTrue(store.list().isEmpty())
        val added = add(importer, preview, 1)
        assertEquals(ImportCode.Conflict, importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add))).items.single().error)
        val later = importer.preview(json(name = "Latest"))
        assertEquals(added, later.candidates.single().existing)
        val changed = importer.commit(later, listOf(ImportSelection(0, ImportDecision.Replace(added)))).items.single()
        assertEquals(ImportOutcome.Replaced, changed.outcome)
        assertEquals(added.sourceId, changed.reference!!.sourceId)
        assertEquals(2L, changed.reference.revision)
        assertEquals(ImportCode.StalePreview, importer.commit(later, listOf(ImportSelection(0, ImportDecision.Replace(added)))).items.single().error)
        val current = changed.reference
        val unchanged = importer.commit(importer.preview(json(name = "Latest")), listOf(ImportSelection(0, ImportDecision.Replace(current)))).items.single()
        assertEquals(ImportOutcome.Unchanged, unchanged.outcome)
        assertEquals(current, unchanged.reference)
    }

    @Test fun changedIdentityRequiresExplicitMappingAndSameKeyAcrossProfilesCanCoexist() {
        val store = SourceDefinitionStore(temp.newFolder().toPath())
        val importer = SourceDefinitionImporter(store)
        val old = add(importer, importer.preview(json()))
        val next = importer.preview(json("non-http-key#fragment"), EXTENSION_PROFILE)
        assertNull(next.candidates.single().existing)
        assertEquals(listOf(old), next.candidates.single().possibleMatches)
        assertEquals(ImportCode.Conflict, importer.commit(next, listOf(ImportSelection(0, ImportDecision.Replace(old)))).items.single().error)
        val mapped = importer.commit(next, listOf(ImportSelection(0, ImportDecision.MapIdentity(old)))).items.single()
        assertEquals(old.sourceId, mapped.reference!!.sourceId)
        assertEquals(EXTENSION_PROFILE, store.list().single().profile)
        assertEquals("non-http-key#fragment", store.list().single().importKey)
        add(importer, importer.preview(json("non-http-key#fragment")))
        assertEquals(2, store.list().size)
        assertNotEquals(store.list()[0].sourceId, store.list()[1].sourceId)
    }

    @Test fun scriptsAreOnlyStoredAndNeverEvaluatedDuringPreviewOrCommit() {
        val store = SourceDefinitionStore(temp.newFolder().toPath())
        val importer = SourceDefinitionImporter(store)
        val preview = importer.preview(json(extra = ",\"jsLib\":\"while(true){}\",\"loginCheckJs\":\"throw 'secret'\",\"exploreUrl\":\"@js:java.ajax('denied')\""))
        add(importer, preview)
        assertEquals("while(true){}", Json.parseToJsonElement(store.list().single().rawJson).jsonObject["jsLib"]!!.jsonPrimitive.content)
        assertFalse(preview.candidates.single().toString().contains("secret"))
        assertFalse(store.list().single().toString().contains("secret"))
    }

    @Test fun quotaFailureDoesNotPartiallyApplyABatch() {
        val root = temp.newFolder().toPath()
        val limits = ImportLimits(maxStoredEntries = 1)
        val store = SourceDefinitionStore(root, limits)
        val importer = SourceDefinitionImporter(store, limits)
        val old = add(importer, importer.preview(json()))
        val bytes = Files.readAllBytes(root.resolve("definitions.json"))
        val preview = importer.preview("[${json(name = "updated")},${json("second")}]")
        val result = importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Replace(old)), ImportSelection(1, ImportDecision.Add)))
        assertEquals(ImportCode.StorageQuota, result.error)
        assertTrue(result.items.all { it.outcome == ImportOutcome.Failed })
        assertArrayEquals(bytes, Files.readAllBytes(root.resolve("definitions.json")))
        assertEquals(old, SourceDefinitionStore(root).list().single().reference())
    }

    @Test fun independentStoreInstancesDoNotLoseConcurrentImports() {
        val root = temp.newFolder().toPath()
        val workers = Executors.newFixedThreadPool(2)
        try {
            workers.invokeAll((1..12).map { index -> Callable {
                val importer = SourceDefinitionImporter(SourceDefinitionStore(root))
                add(importer, importer.preview(json("key-$index")))
            } }).forEach { it.get() }
            assertEquals(12, SourceDefinitionStore(root).list().size)
        } finally { workers.shutdownNow() }
    }

    @Test fun corruptedSnapshotsFailWithoutResetAndTemporaryFilesAreNotActivated() {
        val root = temp.newFolder().toPath()
        val store = SourceDefinitionStore(root)
        val importer = SourceDefinitionImporter(store)
        add(importer, importer.preview(json()))
        Files.write(root.resolve("pending-abandoned.json"), "broken".toByteArray())
        assertEquals(1, SourceDefinitionStore(root).list().size)
        Files.write(root.resolve("definitions.json"), "broken".toByteArray())
        assertEquals(ImportCode.StorageUnavailable, importer.preview(json()).issues.single().code)
        assertEquals("broken", String(Files.readAllBytes(root.resolve("definitions.json"))))
    }
}
