// Versi plugin (kombinasi yang cocok):
//   AGP 8.7.0  -> membutuhkan Gradle 8.10 atau lebih baru dan JDK 17
//   Kotlin 2.0.21 + plugin Compose Compiler 2.0.21 (versi harus sama dengan Kotlin)
plugins {
    id("com.android.application") version "8.7.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
