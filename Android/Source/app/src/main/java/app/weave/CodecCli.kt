package app.weave

import java.io.File

fun main(args: Array<String>) {
    if (args.isNotEmpty()) {
        val p = ProfileCodec.load(File(args[0]))
        println("${p.profileName}|${p.pages.size}|${p.defaultPageId}")
    } else {
        val p = makeDefaultProfile()
        print(ProfileCodec.encodeText(p))
    }
}
