// Versions match what is already in the local Gradle cache (AGP 8.13.0, Gradle 8.14.3).
//
// Note: the project is kept in Dropbox but built from a copy in ~/Observability-android
// (prepare_android.command), because Gradle cannot copy files out of Dropbox's File Provider folder.
plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
}
