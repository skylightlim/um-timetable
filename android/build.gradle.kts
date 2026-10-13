plugins {
    id("com.android.application") version "9.4.1" apply false
    // Not applied anywhere (AGP 9 compiles Kotlin itself); declared only to pin the
    // Kotlin Gradle plugin. AGP 9.4.1 would otherwise pull in 2.2.10; 2.4.20 is the
    // version known to build on this machine's JDK 26.
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
}
