package com.example.project

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // 👇 подключаем тулбар как action bar и задаём заголовок
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_main)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "Kubernetes Mobile Manager (KMM)"

        findViewById<MaterialCardView>(R.id.card_main_settings).setOnClickListener {
            startActivity(Intent(this, Settings::class.java))
        }

        findViewById<MaterialCardView>(R.id.card_main_resources).setOnClickListener {
            startActivity(Intent(this, resurses::class.java))
        }

        findViewById<MaterialCardView>(R.id.card_main_nodes).setOnClickListener {
            startActivity(Intent(this, nodes::class.java))
        }

        findViewById<MaterialCardView>(R.id.card_main_overview).setOnClickListener {
            startActivity(Intent(this, ClusterOverview::class.java))
        }
    }
}
