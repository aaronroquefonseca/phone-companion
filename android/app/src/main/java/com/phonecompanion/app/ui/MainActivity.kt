package com.phonecompanion.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.phonecompanion.app.R

/** Launcher screen (skeleton): navigation only. Logic lands in later PRs. */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.pairButton).setOnClickListener {
            startActivity(Intent(this, PairActivity::class.java))
        }
        findViewById<Button>(R.id.widgetsButton).setOnClickListener {
            startActivity(Intent(this, WidgetsActivity::class.java))
        }
    }
}
