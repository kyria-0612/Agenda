package com.agenda.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.agenda.app.design.*
import com.agenda.domain.AgendaMaterial
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun MaterialDetails(item:AgendaMaterial,repository:AgendaViewModel,initialEditing:Boolean,
    onDirty:(Boolean)->Unit,onSaving:(Boolean)->Unit,onDelete:()->Unit,onOpen:()->Unit) {
    var editing by rememberSaveable(item.id) {mutableStateOf(initialEditing)}
    var title by rememberSaveable(item.id) {mutableStateOf(item.title)}
    var description by rememberSaveable(item.id) {mutableStateOf(item.description)}
    var saving by remember(item.id) {mutableStateOf(false)}
    var error by rememberSaveable(item.id) {mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    SideEffect {onDirty(editing && (title!=item.title || description!=item.description));onSaving(saving)}
    AgendaText(tr("原文件：{0}",item.name),size=12,color=Ink.Muted,modifier=Modifier.padding(top=12.dp))
    AgendaText(tr("{0} 字节",item.sizeBytes),size=12,color=Ink.Muted,modifier=Modifier.padding(top=4.dp,bottom=16.dp))
    if(editing) {
        AgendaOutlinedTextField(title,{title=it;error=null},label={AgendaText(tr("显示标题（可选）"),size=12)},
            placeholder={AgendaText(tr("留空使用原文件名"),size=13,color=Ink.Muted)},
            modifier=Modifier.fillMaxWidth().semantics {contentDescription=tr("材料显示标题")},
            enabled=!saving,shape=RoundedCornerShape(14.dp),singleLine=true)
        Spacer(Modifier.height(14.dp))
        AgendaOutlinedTextField(description,{description=it;error=null},label={AgendaText(tr("文字说明（可选）"),size=12)},
            placeholder={AgendaText(tr("记下这份材料的内容或用途…"),size=13,color=Ink.Muted)},
            modifier=Modifier.fillMaxWidth().semantics {contentDescription=tr("材料文字说明")},
            enabled=!saving,shape=RoundedCornerShape(14.dp),minLines=3,maxLines=8)
        Button({
            val savedTitle=title;val savedDescription=description;saving=true;error=null
            scope.launch {
                try {repository.updateMaterial(item.id,savedTitle,savedDescription);editing=false}
                catch(c:CancellationException) {throw c}
                catch(e:Exception) {error=saveError(e)}
                finally {saving=false}
            }
        },enabled=!saving,shape=RoundedCornerShape(11.dp),modifier=Modifier.padding(top=16.dp)) {
            AgendaText(if(saving) tr("保存中…") else tr("保存材料信息"),color=Ink.Surface)
        }
    } else {
        if(item.description.isNotBlank()) AgendaText(item.description,size=14,modifier=Modifier.padding(bottom=16.dp))
        else AgendaText(tr("还没有文字说明"),size=13,color=Ink.Muted,modifier=Modifier.padding(bottom=12.dp))
        Button(onOpen,shape=RoundedCornerShape(11.dp)) {AgendaText(if(item.isImage) tr("查看图片") else tr("打开材料"),color=Ink.Surface)}
        TextButton({title=item.title;description=item.description;editing=true},modifier=Modifier.padding(top=4.dp)) {
            AgendaText(tr("编辑标题与说明"),size=13,color=Ink.Primary)
        }
        TextButton(onDelete) {AgendaText(tr("删除材料"),size=13,color=Ink.Danger)}
    }
    error?.let {AgendaText(it,size=12,color=Ink.Danger,modifier=Modifier.padding(top=10.dp).semantics {liveRegion=LiveRegionMode.Polite})}
}

