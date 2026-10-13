package md.utm.digitalmirror.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {

    @Test
    fun `valorile simple raman neschimbate`() {
        assertEquals("a;12;;true", Csv.row(listOf("a", 12, null, true)))
    }

    @Test
    fun `textul cu separator sau ghilimele se pune intre ghilimele`() {
        assertEquals("\"a;b\"", Csv.field("a;b"))
        assertEquals("\"zice \"\"da\"\"\"", Csv.field("zice \"da\""))
        assertEquals("\"rand\nnou\"", Csv.field("rand\nnou"))
    }

    @Test
    fun `textul care arata a formula primeste apostrof`() {
        assertEquals("'=1+1", Csv.field("=1+1"))
        assertEquals("'@suma", Csv.field("@suma"))
        assertEquals("'-test", Csv.field("-test"))
    }

    @Test
    fun `numerele nu primesc apostrof`() {
        assertEquals("-5", Csv.field(-5))
    }
}