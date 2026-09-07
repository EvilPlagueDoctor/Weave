package app.weave

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // WeaveApp applies the theme and Surface itself, so it is not wrapped here.
        setContent { WeaveApp() }
    }
}
