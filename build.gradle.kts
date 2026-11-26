buildscript {
    repositories { google(); mavenCentral() }
    dependencies {
        classpath("com.android.tools.build:gradle:8.7.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24")
    }
}

// C: is very low on space on this machine, so keep generated build output on D:.
layout.buildDirectory.set(file("build/root"))
