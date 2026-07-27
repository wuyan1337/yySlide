package sh.haven.feature.editor

interface FileContentProvider {
    val backendLabel: String
    val filePath: String
    val fileName: String
    val fileSize: Long
    suspend fun readContent(): ByteArray
    suspend fun writeContent(data: ByteArray)
}
