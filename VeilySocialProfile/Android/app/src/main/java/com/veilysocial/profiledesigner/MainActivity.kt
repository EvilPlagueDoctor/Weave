package com.veilysocial.profiledesigner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // VeilyApp applies the theme and Surface itself, so it is not wrapped here.
        setContent { VeilyApp() }
    }
}
