package com.example.curse.media

import android.content.ContentResolver
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.app.RecoverableSecurityException
import android.provider.MediaStore
/**
 * Результат попытки удаления: успех или запрос подтверждения через системный диалог (FR-6).
 */
sealed class DeleteResult {
    data object Success : DeleteResult()
    data class NeedPermission(val intentSender: IntentSender) : DeleteResult()
}

/**
 * Пытается удалить медиа по URI.
 */
fun tryDeleteMedia(contentResolver: ContentResolver, uri: Uri): DeleteResult {
    return try {
        contentResolver.delete(uri, null, null)
        DeleteResult.Success
    } catch (e: SecurityException) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // На Android 11+ удаление чужого медиа подтверждается через createDeleteRequest.
            val request = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
            DeleteResult.NeedPermission(request.intentSender)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
            DeleteResult.NeedPermission(e.userAction.actionIntent.intentSender)
        } else {
            throw e
        }
    }
}
