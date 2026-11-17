package com.agenda.app.design

private val UiArgument=Regex("\\{(\\d+)\\}")

/** Only expand placeholders from the template; never reprocess inserted user content. */
fun formatUiText(template:String,vararg arguments:Any?):String=UiArgument.replace(template) {match->
    arguments.getOrNull(match.groupValues[1].toInt())?.toString().orEmpty()
}

fun tr(chinese:String,vararg arguments:Any?):String = formatUiText(
    if(InterfaceSettings.english) EnglishCopy[chinese] ?: chinese else chinese,*arguments)

private val BackupAttachmentProgress=Regex("(正在保存附件|正在校验附件|正在复核附件) (\\d+)/(\\d+)")
fun backupProgressText(message:String):String {
    val match=BackupAttachmentProgress.matchEntire(message) ?: return tr(message)
    return tr(match.groupValues[1]+" {0}/{1}",match.groupValues[2],match.groupValues[3])
}
