package cn.surface.operator

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import cn.surface.operator.ui.OperatorApp

class MainActivity:ComponentActivity() {
    private val model:OperatorViewModel by viewModels()
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { OperatorApp(model) }
    }
    override fun onResume() { super.onResume();model.foreground(true) }
    override fun onPause() { model.foreground(false);super.onPause() }
    override fun onWindowFocusChanged(hasFocus:Boolean) { super.onWindowFocusChanged(hasFocus);if(!hasFocus) model.focusLost() }
}
