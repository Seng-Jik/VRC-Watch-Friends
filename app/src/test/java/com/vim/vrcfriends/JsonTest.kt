package com.vim.vrcfriends

import com.vim.vrcfriends.json.Json
import com.vim.vrcfriends.json.JsonException
import com.vim.vrcfriends.json.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {

    @Test
    fun parsesNestedObjectsAndArrays() {
        val j = JsonParser.parse(
            """{"a":1,"b":[true,false,null],"c":{"d":"x"},"e":-2.5}"""
        )
        assertEquals("1", j.string("a"))
        assertEquals(1, j.int("a"))
        assertTrue(j.bool("b") == null)
        val arr = j.array("b")!!
        assertEquals(3, arr.size)
        assertEquals(Json.Bool(true), arr[0])
        assertEquals(Json.Null, arr[2])
        assertEquals("x", j["c"]?.string("d"))
        assertEquals(Json.Num(-2.5), j["e"])
    }

    @Test
    fun handlesEscapesAndUnicode() {
        val j = JsonParser.parse("""{"s":"a\"b\\c\/d\n\t\u4e2d\u6587"}""")
        assertEquals("a\"b\\c/d\n\t中文", j.string("s"))
    }

    @Test
    fun emptyContainers() {
        val j = JsonParser.parse("""{"o":{},"a":[]}""")
        assertEquals(0, j["o"]!!.obj()!!.size)
        assertEquals(0, j.array("a")!!.size)
    }

    @Test(expected = JsonException::class)
    fun trailingGarbageRejected() {
        JsonParser.parse("""{"a":1} x""")
    }

    @Test(expected = JsonException::class)
    fun truncatedObjectRejected() {
        JsonParser.parse("""{"a":1""")
    }

    @Test(expected = JsonException::class)
    fun badEscapeRejected() {
        JsonParser.parse("""{"a":"\q"}""")
    }

    @Test
    fun missingKeyIsNull() {
        val j = JsonParser.parse("""{"a":1}""")
        assertNull(j.string("zzz"))
        assertNull(j.array("zzz"))
    }

    @Test
    fun numberFormattingHasNoTrailingZeros() {
        assertEquals("12", Json.formatNumber(12.0))
        assertEquals("12.5", Json.formatNumber(12.5))
    }
}
