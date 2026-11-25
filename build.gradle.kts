// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.secrets) apply false
  alias(libs.plugins.google.services) apply false
}

tasks.register<Copy>("packageApkIntoProject") {
  dependsOn(":app:assembleDebug")
  from(project(":app").layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
  into(layout.projectDirectory.dir("apk"))
  rename { "Syniji-Zim-converter-MD.apk" }
}

