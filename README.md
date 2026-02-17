# Curse — Камера и медиагалерея

Android-приложение для съёмки фото, записи видео и просмотра медиа в галерее (курсовой проект).

## Описание приложения

`Curse` — мобильное приложение-камера с локальной медиагалереей. Приложение позволяет:

- делать фотографии с предпросмотром в реальном времени;
- записывать видео с таймером и переключением камер;
- автоматически сохранять медиафайлы в системную галерею через MediaStore;
- просматривать фото и видео в единой галерее с полноэкранным режимом;
- удалять медиафайлы с обработкой системных ограничений Android.

Основной сценарий: пользователь открывает экран «Фото» или «Видео», создаёт контент, после чего сразу может перейти в «Галерею» для просмотра и удаления сохранённых материалов.

## Требования к окружению

- **Android Studio** (рекомендуется последняя стабильная)
- **JDK 11+**
- **Android SDK**: compileSdk 35, minSdk 24, targetSdk 35
- **Устройство/эмулятор**: Android 7.0 (API 24) и выше, с камерой

## Сборка и запуск

```bash
./gradlew assembleDebug
```

Установка на подключённое устройство:

```bash
./gradlew installDebug
```

APK (debug): `app/build/outputs/apk/debug/app-debug.apk`

## Разрешения и когда запрашиваются

- **CAMERA** — при первом использовании экрана «Фото» или «Видео» (контекстный запрос: экран показывает кнопку «Разрешить»).
- **RECORD_AUDIO** — при первом использовании экрана «Видео».
- **READ_MEDIA_IMAGES** / **READ_MEDIA_VIDEO** (Android 13+) или **READ_EXTERNAL_STORAGE** (до Android 13) — в составе запроса для фото/видео; нужны для отображения галереи.

Запрос выполняется через `ActivityResultContracts.RequestMultiplePermissions()`. При удалении файла, созданного не приложением, может появиться системный диалог подтверждения (обработка `RecoverableSecurityException`).

## Ограничения по версиям Android / устройствам

- **minSdk 24** (Android 7.0). На API &lt; 29 галерея загружает все изображения/видео из общего хранилища (без фильтра по папке «Curse»).
- Для корректной работы нужна камера; автофокус не обязателен.
- Переключение камеры во время записи видео: текущий клип останавливается и сохраняется, затем привязка к новой камере (seamless-смена без остановки не реализована).

## Стек

- Kotlin, Jetpack Compose, Material 3
- CameraX (Preview, ImageCapture, VideoCapture/Recorder)
- Navigation Compose, MediaStore API, Room (хранение списка галереи), Coil для превью

## Зависимости и версии (что именно использовалось)

Проект использует **Version Catalog** (`gradle/libs.versions.toml`) — это позволяет явно показать, какие версии библиотек были подключены при разработке.

### Плагины сборки

- **Android Gradle Plugin**: 9.0.1
- **Kotlin**: 2.0.21 (плагин `org.jetbrains.kotlin.plugin.compose`)
- **KSP**: 2.0.21-1.0.25 (для генерации кода Room)

### Ключевые библиотеки

- **Jetpack Compose BOM**: 2024.09.00 (UI, Material 3, tooling)
- **Navigation Compose**: 2.7.7 (навигация между экранами)
- **CameraX**: 1.3.1 (камера: превью/снимок/видео)
- **Room**: 2.8.0 (локальный кэш списка галереи)
- **Coil**: 2.5.0 (загрузка превью изображений по `content://` URI)
- **Media3 (ExoPlayer)**: 1.5.1 (проигрывание видео в полноэкранном просмотре)

> Примечание: значения `compileSdk/targetSdk` задаются в `app/build.gradle.kts`. Сейчас это **35**; в комментарии к `targetSdk` оставлена подсказка, что для Android 16 можно увеличить до 36.

## Структура проекта и ответственность модулей (где что реализовано)

Ниже — “доказательная” привязка требований/фич к конкретным файлам. Это удобно, когда нужно показать, **что именно** было использовано и **где** это находится в коде.

### Точка входа и навигация

- `app/src/main/java/com/example/curse/MainActivity.kt`
  - Создаёт Compose‑UI (`setContent`) и регистрирует `ActivityResultContracts`
  - Хранит карту разрешений (permission → granted) и “триггер” обновления галереи
  - Обрабатывает удаление с подтверждением (IntentSender) и обновляет кэш Room
- `app/src/main/java/com/example/curse/navigation/AppNavigation.kt`
  - `Scaffold` + нижняя панель навигации (Material 3)
  - `NavHost` (Navigation Compose): маршруты «Фото» / «Видео» / «Галерея»
  - Хук `onGalleryVisible`, чтобы обновлять список при открытии вкладки
- `app/src/main/java/com/example/curse/navigation/NavRoutes.kt`
  - Строковые маршруты экранов

### Экран «Фото» (CameraX ImageCapture + MediaStore)

- `app/src/main/java/com/example/curse/ui/photo/PhotoScreen.kt`
  - Preview через `PreviewView` в `AndroidView`
  - Съёмка через `ImageCapture.takePicture(...)` с `OutputFileOptions` в `MediaStore`
  - UX: `tap-to-focus` (FocusMeteringAction) и `pinch-to-zoom` (setLinearZoom)
  - Анимация “вспышки” (overlay с `Animatable`)
- `app/src/main/java/com/example/curse/media/MediaStoreHelper.kt`
  - `createImageContentValues(...)`: `DISPLAY_NAME`, `RELATIVE_PATH`, `IS_PENDING` (API 29+)
  - `imageCollectionUri()` и `setImagePendingFalse(...)`
  - `insertGalleryItem(...)`: запись в Room после сохранения

### Экран «Видео» (CameraX Recorder + сохранение в MediaStore)

- `app/src/main/java/com/example/curse/ui/video/VideoScreen.kt`
  - `Recorder` + `VideoCapture.withOutput(recorder)`
  - `MediaStoreOutputOptions` для сохранения в `MediaStore.Video`
  - Таймер записи (корутина + `delay(1000)`)
  - Переключение камеры: **останавливает** текущую запись (см. раздел “Допущения”)
- `app/src/main/java/com/example/curse/media/MediaStoreHelper.kt`
  - `createVideoContentValues(...)` и `setVideoPendingFalse(...)`

### Экран «Галерея» (Room‑кэш + синхронизация с MediaStore + просмотр)

- `app/src/main/java/com/example/curse/ui/gallery/GalleryScreen.kt`
  - Сетка превью: `LazyVerticalGrid`
  - Превью фото: `Coil` (`AsyncImage(model = uri)`)
  - Превью видео: `loadThumbnail(...)` (API 29+) / `MediaStore.Video.Thumbnails` / `MediaMetadataRetriever`
  - Полноэкранный просмотр: `HorizontalPager`
  - Видео‑плеер: `Media3 ExoPlayer` + `PlayerView` в `AndroidView`
- `app/src/main/java/com/example/curse/media/MediaStoreHelper.kt`
  - `loadGalleryItems(...)`: сначала берёт кэш Room, затем пытается синхронизироваться с MediaStore
  - Фильтрация по подпапке приложения:
    - API 29+: через `RELATIVE_PATH LIKE "%Curse%"`
    - API < 29: через `DATA LIKE "%Curse%"`

### Удаление медиа и системные ограничения Android

- `app/src/main/java/com/example/curse/media/MediaDelete.kt`
  - `tryDeleteMedia(...)`:
    - обычное удаление через `ContentResolver.delete`
    - обработка `RecoverableSecurityException` (Android 10)
    - `MediaStore.createDeleteRequest(...)` (Android 11+)
- `MainActivity.kt`
  - Запуск `IntentSender` и повторная попытка удаления после подтверждения
  - Удаление соответствующей записи из Room

### Разрешения (адаптация под Android 13/14+)

- `app/src/main/java/com/example/curse/permission/CameraPermissions.kt`
  - Наборы разрешений для экранов (фото/видео/галерея)
  - Учет Android 13+ (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`)
  - Учет Android 14+ “выбранные фото/видео” (`READ_MEDIA_VISUAL_USER_SELECTED`)
  - Учет старых версий (до Android 10 — `WRITE_EXTERNAL_STORAGE` для записи)
- `app/src/main/AndroidManifest.xml`
  - Декларации разрешений и `uses-feature` для камеры

### Локальная БД (Room)

- `app/src/main/java/com/example/curse/CurseApplication.kt`
  - Создание `Room.databaseBuilder(..., "curse.db")`
- `app/src/main/java/com/example/curse/db/*`
  - `AppDatabase`, `GalleryItemEntity`, `GalleryItemDao`

### UI-тема

- `app/src/main/java/com/example/curse/ui/theme/*`
  - Material 3 тема приложения

## Как воспроизвести сборку “как у автора”

- **Debug APK**:

```bash
./gradlew assembleDebug
```

- **Установка на устройство**:

```bash
./gradlew installDebug
```

- **Запуск тестов (если подключены эмулятор/устройство для androidTest)**:

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
```

---

## Отчёт о реализации

### Закрытые требования

- **FR-1**: Три экрана (Фото, Видео, Галерея), нижняя навигация, переходы между ними.
- **FR-2**: Экран «Фото» — превью CameraX, tap-to-focus, pinch-to-zoom, переключение камеры, кнопка съёмки, анимация вспышки, сохранение через MediaStore (RELATIVE_PATH, DISPLAY_NAME, IS_PENDING).
- **FR-3**: Экран «Видео» — превью, кнопка записи с изменением вида, таймер длительности, переключение камеры (с остановкой и сохранением текущего клипа), автосохранение в галерею.
- **FR-4**: Галерея — сетка превью, тип (фото/видео) и дата, полноэкранный просмотр по нажатию, удаление с обработкой системного подтверждения при необходимости.
- **FR-5**: Контекстный запрос разрешений (на экране фото/видео), проверка → запрос → обработка результата, `ActivityResultContracts`.
- **FR-6**: Сохранение через MediaStore (Scoped Storage), тяжёлые операции (запись, запросы MediaStore) не в UI-потоке.
- **FR-7**: Галерея строится на основе БД Room (uri, тип, дата); при первом запуске выполняется синхронизация из MediaStore; отображаются превью (Coil).
- **NFR**: Material Design (Material 3), Edge-to-Edge с учётом insets (нижняя панель навигации с `windowInsetsPadding`).

### Допущения

- **Переключение камеры во время записи**: реализован минимальный UX по ТЗ — при переключении текущий клип останавливается и сохраняется, затем выбирается другая камера. Без «бесшовной» смены камеры в одном клипе.
- Галерея показывает только медиа из папок приложения (Pictures/Curse, Movies/Curse) на API 29+; на API &lt; 29 — все изображения из MediaStore (ограничение фильтрации по пути).
- Полноэкранный просмотр видео через `ExoPlayer` (Media3).

### Известные ограничения

- На части устройств переключение камеры во время записи может давать короткую задержку до появления превью с новой камеры.
- Для отображения галереи после съёмки нужно перейти на другой экран и снова открыть «Галерею» (обновление при переходе на вкладку реализовано).

---

## Чек-лист соответствия требованиям

Формат: `[x]` — выполнено, `[~]` — выполнено частично/с оговорками, `[ ]` — не выполнено.

### 1) Экран фото

- [x] Превью с камеры занимает основную часть экрана.
- [x] Реализован `tap-to-focus` (фокус по касанию).
- [x] Реализован `pinch-to-zoom` (цифровой масштаб жестом).
- [x] Есть кнопка съёмки и анимация вспышки при создании фото.
- [x] Есть переключение фронтальной/основной камеры.
- [x] Фото сохраняются в память устройства через `MediaStore`.

### 2) Экран видео

- [x] Превью с камеры и отдельный экран записи видео.
- [x] Кнопка записи меняет состояние/вид во время записи.
- [x] Есть индикатор длительности (таймер записи).
- [~] Переключение камеры во время записи: реализовано с остановкой и сохранением текущего клипа, затем запуск на другой камере.
- [x] Записанное видео автоматически сохраняется в галерею приложения.

### 3) Экран галереи

- [x] Медиа отображаются сеткой превью.
- [x] Для элементов показывается тип контента (фото/видео) и дата создания.
- [x] По нажатию открывается полноэкранный просмотр.
- [x] Доступно удаление медиафайлов.

### 4) Разрешения и совместимость Android

- [x] Обработка разрешений камеры/микрофона/чтения медиа реализована адаптивно.
- [x] Учтены Android 13+ (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`).
- [x] Учтены Android 14+ (`READ_MEDIA_VISUAL_USER_SELECTED` для частичного доступа).
- [x] Учтена обратная совместимость для старых версий (`READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE` до нужных API).

### 5) UI/UX и архитектурные требования

- [x] Интерфейс реализован на Material 3.
- [x] Реализован Edge-to-Edge (`enableEdgeToEdge`, работа с `WindowInsets`).
- [x] Есть плавная навигация между экранами через `Navigation Compose`.
- [x] Сохранение/удаление медиа выполнены с учетом Scoped Storage и системных ограничений.
- [x] Есть обработка `RecoverableSecurityException`/`createDeleteRequest` при удалении чужого контента.
