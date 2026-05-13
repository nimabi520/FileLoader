package topview.fileloader.app

import java.io.File

internal data class UploadTask(val file: File, val batchId: String)

internal data class UploadRecordUi(
    val time: String,
    val fileName: String,
    val batchId: String,
    val status: String
)

internal data class BatchStatusUi(
    val batchId: String,
    val status: String,
    val downloadable: Boolean,
    val createdAt: String = ""
)
