package com.example.curse.permission

import android.Manifest
import android.os.Build

/**
 * Набор разрешений для экрана «Фото»: камера и чтение медиа.
 * До Android 10 (Q) добавляется WRITE_EXTERNAL_STORAGE для сохранения в общее хранилище.
 */
fun photoScreenPermissions(): Array<String> = buildList {
    add(Manifest.permission.CAMERA)
    addMediaRead()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}.toTypedArray()

/**
 * Набор разрешений для экрана «Видео»: камера, микрофон, чтение медиа.
 * До Android 10 (Q) добавляется WRITE_EXTERNAL_STORAGE для сохранения видео в MediaStore.
 */
fun videoScreenPermissions(): Array<String> = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    addMediaRead()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}.toTypedArray()

/**
 * Набор разрешений только для чтения медиа (экран «Галерея»).
 */
fun galleryPermissions(): Array<String> = buildList {
    addMediaRead()
}.toTypedArray()

/**
 * Все разрешения, состояние которых нужно учитывать при проверке (для построения карты в MainActivity).
 * Включает READ_MEDIA_VISUAL_USER_SELECTED на Android 14+, т.к. при выборе «только выбранные» выдаётся оно.
 */
fun allPermissionsToCheck(): Array<String> = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    addMediaRead()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}.distinct().toTypedArray()

/**
 * Проверяет, достаточно ли разрешений для съёмки и сохранения фото.
 * На API &lt; Q для записи в общее хранилище нужен WRITE_EXTERNAL_STORAGE.
 */
fun hasPhotoCapturePermission(permissions: Map<String, Boolean>): Boolean {
    val hasCamera = permissions[Manifest.permission.CAMERA] == true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return hasCamera
    return hasCamera && permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
}

/**
 * Проверяет, достаточно ли разрешений для записи и сохранения видео.
 * На API &lt; Q для записи в MediaStore нужен WRITE_EXTERNAL_STORAGE.
 */
fun hasVideoCapturePermission(permissions: Map<String, Boolean>): Boolean {
    val hasCamera = permissions[Manifest.permission.CAMERA] == true
    val hasAudio = permissions[Manifest.permission.RECORD_AUDIO] == true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return hasCamera && hasAudio
    return hasCamera && hasAudio && permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
}

/**
 * Проверяет, выдано ли разрешение на чтение медиа (для галереи).
 * Учитывает: Android 14+ — полный доступ (READ_MEDIA_IMAGES + READ_MEDIA_VIDEO) или частичный (READ_MEDIA_VISUAL_USER_SELECTED);
 * Android 13 — только READ_MEDIA_IMAGES + READ_MEDIA_VIDEO; до Android 13 — READ_EXTERNAL_STORAGE.
 */
fun hasGalleryPermission(permissions: Map<String, Boolean>): Boolean {
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
            (permissions[Manifest.permission.READ_MEDIA_IMAGES] == true &&
                permissions[Manifest.permission.READ_MEDIA_VIDEO] == true) ||
                permissions[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            permissions[Manifest.permission.READ_MEDIA_IMAGES] == true &&
                permissions[Manifest.permission.READ_MEDIA_VIDEO] == true
        }
        else -> {
            permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        }
    }
}

/** Добавляет в список разрешения на чтение медиа в зависимости от версии Android (13+ — раздельные, иначе READ_EXTERNAL_STORAGE). */
private fun MutableList<String>.addMediaRead() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.READ_MEDIA_IMAGES)
        add(Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}
