package topview.fileloader.app

fun main(args: Array<String>) {
    if (args.any { it.contains("swing", ignoreCase = true) }) {
        println("[FileLoader] Swing UI has been removed. Starting Compose UI.")
    }
    startComposeApp()
}
