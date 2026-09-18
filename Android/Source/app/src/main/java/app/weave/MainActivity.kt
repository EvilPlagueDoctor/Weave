package app.weave

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WeaveDiagnostics.installCrashHandler(this)
        WeaveDiagnostics.event(
            this,
            "ACTIVITY_CREATE",
            "saved_state=${savedInstanceState != null} task=$taskId"
        )
        // WeaveApp applies the theme and Surface itself, so it is not wrapped here.
        setContent { WeaveApp() }
    }

    override fun onStart() {
        super.onStart()
        WeaveDiagnostics.event(this, "ACTIVITY_START", "task=$taskId")
    }

    override fun onStop() {
        WeaveDiagnostics.event(this, "ACTIVITY_STOP", "changing_config=$isChangingConfigurations task=$taskId")
        super.onStop()
    }

    override fun onDestroy() {
        WeaveDiagnostics.event(this, "ACTIVITY_DESTROY", "changing_config=$isChangingConfigurations finishing=$isFinishing task=$taskId")
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        WeaveDiagnostics.event(this, "TRIM_MEMORY", "level=$level")
        super.onTrimMemory(level)
    }
}
