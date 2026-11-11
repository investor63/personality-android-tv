package com.personality.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

object ScreensaverRepository {
    fun importTree(context: Context, tree: Uri): Int {
        context.contentResolver.takePersistableUriPermission(
            tree, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        val trees = context.getSharedPreferences("trees", Context.MODE_PRIVATE)
        trees.edit().putBoolean(tree.toString(), true).apply()
        return scan(context, tree)
    }

    fun refresh(context: Context): Int {
        val trees = context.getSharedPreferences("trees", Context.MODE_PRIVATE)
        return trees.all.keys.sumOf { scan(context, Uri.parse(it)) }
    }

    private fun scan(context: Context, tree: Uri): Int {
        val resolver = context.contentResolver
        val library = context.getSharedPreferences("videos", Context.MODE_PRIVATE)
        val edit = library.edit()
        val queue = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        queue.add(DocumentsContract.getTreeDocumentId(tree))
        var count = 0
        while (queue.isNotEmpty()) {
            val parent = queue.removeFirst()
            if (!visited.add(parent)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            resolver.query(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val name = cursor.getString(1).orEmpty()
                    val mime = cursor.getString(2).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) queue.add(id)
                    else if (mime.startsWith("video/")) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                        if (!library.contains(uri)) edit.putString(uri, name)
                        count++
                    }
                }
            }
        }
        edit.apply()
        return count
    }
}
