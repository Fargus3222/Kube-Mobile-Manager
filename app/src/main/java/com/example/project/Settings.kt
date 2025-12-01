package com.example.project

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class Settings : AppCompatActivity() {

    private lateinit var tvKubeconfigPath: TextView
    private lateinit var switchDarkTheme: SwitchMaterial

    private val openKubeconfigLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                handleKubeconfigUri(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_settings)
        toolbar.title = "Настройки"
        toolbar.navigationIcon = getDrawable(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        tvKubeconfigPath = findViewById(R.id.tv_kubeconfig_path)
        switchDarkTheme = findViewById(R.id.switch_dark_theme)
        val btnChooseKubeconfig = findViewById<MaterialButton>(R.id.btn_choose_kubeconfig)
        val btnSaveSettings = findViewById<MaterialButton>(R.id.btn_save_settings)

        val prefs = getSharedPreferences("kube_prefs", MODE_PRIVATE)

        val savedName = prefs.getString("kubeconfig_name", null)
        if (savedName != null) {
            tvKubeconfigPath.text = savedName
        }

        val isDark = prefs.getBoolean("dark_theme", false)
        switchDarkTheme.isChecked = isDark

        btnChooseKubeconfig.setOnClickListener {
            openKubeconfigLauncher.launch(arrayOf("*/*"))
        }

        btnSaveSettings.setOnClickListener {
            prefs.edit()
                .putBoolean("dark_theme", switchDarkTheme.isChecked)
                .apply()

            AppCompatDelegate.setDefaultNightMode(
                if (switchDarkTheme.isChecked)
                    AppCompatDelegate.MODE_NIGHT_YES
                else
                    AppCompatDelegate.MODE_NIGHT_NO
            )

            Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleKubeconfigUri(uri: Uri) {
        val fileName = queryFileName(uri) ?: uri.lastPathSegment ?: "kubeconfig.yml"
        val lower = fileName.lowercase()

        if (!lower.endsWith(".yml") && !lower.endsWith(".yaml")) {
            Toast.makeText(this, "Выберите файл .yml или .yaml", Toast.LENGTH_SHORT).show()
            return
        }

        val content = readTextFromUri(uri)

        val prefs = getSharedPreferences("kube_prefs", MODE_PRIVATE)

        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }

        prefs.edit()
            .putString("kubeconfig_uri", uri.toString())
            .putString("kubeconfig_name", fileName)
            .putString("kubeconfig_content", content)
            .apply()

        tvKubeconfigPath.text = fileName
        Toast.makeText(this, "kubeconfig выбран", Toast.LENGTH_SHORT).show()
    }

    private fun queryFileName(uri: Uri): String? {
        val cursor = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx != -1) {
                    return it.getString(idx)
                }
            }
        }
        return null
    }

    private fun readTextFromUri(uri: Uri): String {
        return contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader().readText()
        } ?: ""
    }
}
