package fr.omronbridge.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class RationaleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rationale)
        findViewById<android.view.View>(R.id.buttonClose).setOnClickListener { finish() }
    }
}
