package uk.gov.dwp.dataworks.export.batch

import org.apache.hadoop.hbase.TableName
import org.apache.hadoop.hbase.client.*
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.BDDMockito.given
import org.mockito.Mockito
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit4.SpringRunner
import uk.gov.dwp.dataworks.export.domain.EncryptionBlock
import uk.gov.dwp.dataworks.export.domain.RecordId
import uk.gov.dwp.dataworks.export.domain.SourceRecord
import uk.gov.dwp.dataworks.export.exceptions.MissingFieldException
import java.nio.charset.Charset

@RunWith(SpringRunner::class)
@ActiveProfiles("phoneyServices", "unitTest")
@SpringBootTest
class HBaseReaderTest {

    @Before
    fun reset() {
        logger.info("Resetting '$connection'.")
        Mockito.reset(connection)
    }

    @Test
    fun read() {
        val table: Table = Mockito.mock(Table::class.java)
        val scanner: ResultScanner = Mockito.mock(ResultScanner::class.java)
        val result: Result = Mockito.mock(Result::class.java)
        hbaseReader.resetScanner()

        val expectedId = "EXPECTED_ID"
        val timestamp = "EXPECTED_TIMESTAMP"
        val topic = "EXPECTED_TOPIC"
        val encryptionKeyId = "EXPECTED_ENCRYPTION_KEY_ID"
        val encryptedEncryptionKey = "EXPECTED_ENCRYPTED_ENCRYPTION_KEY"
        val keyEncryptionKeyId = "EXPECTED_KEY_ENCRYPTION_KEY_ID"
        val dbObject = "EXPECTED_DB_OBJECT"
        val initialisationVector = "EXPECTED_INITIALISATION_VECTOR"

        val cell = """{
            |    "id": "$expectedId", 
            |     "timestamp": "$timestamp", 
            |     "topic": "$topic", 
            |     "encryption": {
            |       "encryptionKeyId": "$encryptionKeyId", 
            |       "encryptedEncryptionKey": "$encryptedEncryptionKey", 
            |       "initialisationVector": "$initialisationVector", 
            |       "keyEncryptionKeyId": "$keyEncryptionKeyId"
            |    }, 
            |    "dbObject": "$dbObject"
            |}""".trimMargin()

        given(result.getValue("cf".toByteArray(), "data".toByteArray())).willReturn(cell.toByteArray(Charset.defaultCharset()))
        given(scanner.next()).willReturn(result)
        given(table.getScanner(ArgumentMatchers.any(Scan::class.java))).willReturn(scanner)
        given(connection.getTable(TableName.valueOf("ucdata"))).willReturn(table)

        val recordId  = RecordId(expectedId)
        val encryption = EncryptionBlock(keyEncryptionKeyId, encryptedEncryptionKey)
        val expected = SourceRecord(recordId, timestamp, encryption, dbObject)
        val actual = hbaseReader.read()
        assertEquals(expected, actual)

    }

    @Test(expected = MissingFieldException::class)
    fun reject() {
        val table: Table = Mockito.mock(Table::class.java)
        val scanner: ResultScanner = Mockito.mock(ResultScanner::class.java)
        val result: Result = Mockito.mock(Result::class.java)
        hbaseReader.resetScanner()

        val expectedId = "ID"
        val timestamp = "TIMESTAMP"
        val topic = "TOPIC"
        val encryptionKeyId = "ENCRYPTION_KEY_ID"
        val encryptedEncryptionKey = "ENCRYPTED_ENCRYPTION_KEY"
        val keyEncryptionKeyId = "KEY_ENCRYPTION_KEY_ID"
        val initialisationVector = "INITIALISATION_VECTOR"

        val cell = """{
            |    "id": "$expectedId", 
            |     "timestamp": "$timestamp", 
            |     "topic": "$topic", 
            |     "encryption": {
            |       "encryptionKeyId": "$encryptionKeyId", 
            |       "encryptedEncryptionKey": "$encryptedEncryptionKey", 
            |       "initialisationVector": "$initialisationVector", 
            |       "keyEncryptionKeyId": "$keyEncryptionKeyId"
            |    } 
            |}""".trimMargin()

        given(result.getValue("cf".toByteArray(), "data".toByteArray())).willReturn(cell.toByteArray(Charset.defaultCharset()))
        given(scanner.next()).willReturn(result)
        given(table.getScanner(ArgumentMatchers.any(Scan::class.java))).willReturn(scanner)
        given(connection.getTable(TableName.valueOf("ucdata"))).willReturn(table)
        hbaseReader.read()
    }

    @Autowired
    private lateinit var hbaseReader: HBaseReader

    @Autowired
    private lateinit var connection: Connection

    companion object {
        val logger: Logger = LoggerFactory.getLogger(HBaseReaderTest::class.toString())
    }

}
