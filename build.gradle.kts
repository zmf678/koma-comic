plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

tasks.register<Exec>("assembleWindows") {
    group = "build"
    description = "Builds the portable Windows desktop executable (KomaComic.exe) and release zip."
    commandLine(
        "powershell",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        file("windows/build-windows.ps1").absolutePath
    )
}
