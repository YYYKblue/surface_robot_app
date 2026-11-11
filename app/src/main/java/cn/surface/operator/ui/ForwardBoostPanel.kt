package cn.surface.operator.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cn.surface.operator.core.*

@Composable internal fun ForwardBoostPanel(s:OperatorState,onChange:(Boolean)->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("主电机提速："+when(s.forwardBoostEnabled) {true->"开启";false->"关闭";null->"状态未知"},
                modifier=Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
            Switch(checked=s.forwardBoostEnabled==true,onCheckedChange=onChange,enabled=s.canSetForwardBoost,
                modifier=Modifier.semantics {contentDescription="主电机提速开关"})
        }
        Text(s.forwardBoostStatus,style=MaterialTheme.typography.bodySmall,color=Muted)
        val error=s.task.str("forward_boost_setting_error")
        if(s.forwardBoostSupported && s.fresh && s.task.flag("fresh") && !s.canSetForwardBoost && error.isNotBlank())
            Text(error,style=MaterialTheme.typography.bodySmall,color=Muted)
        Text("任务开始前设置；运行中及暂停时锁定。四主电机按任务配置前进辅助，手动页调速不改变任务参数。纠偏优先；距墙端 80 cm 内（含边界）本墙段不再提速。矩形补洗按实际结束位置提前撤销。",
            style=MaterialTheme.typography.bodySmall,color=Muted)
    }
}
