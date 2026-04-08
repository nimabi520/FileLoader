package topview.fileloader

fun main(args: Array<String>) {
    val useSwing = args.any {
        it.equals("--ui=swing", ignoreCase = true) || it.equals("--swing", ignoreCase = true)
    }

    if (useSwing) {
        MonitorUI.main(args)
        return
    }

    startComposeApp()
}
