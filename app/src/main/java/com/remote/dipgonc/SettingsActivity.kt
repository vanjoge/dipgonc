package com.remote.dipgonc

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.king.camera.scan.CameraScan


class SettingsActivity : AppCompatActivity() {

    private lateinit var etSecretKey: EditText
    private lateinit var etAuth: EditText
    private lateinit var btnSave: Button
    private lateinit var btnScan: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        initViews()
        loadSavedKey()
    }

    private fun initViews() {
        etSecretKey = findViewById(R.id.etSecretKey)
        etAuth = findViewById(R.id.etAuth)
        btnSave = findViewById(R.id.btnSave)
        btnScan = findViewById(R.id.btnScan)

        btnSave.setOnClickListener {
            saveSecretKey()
        }

        btnScan.setOnClickListener {
            val intent = Intent(this, QRCodeScanActivity::class.java)
            ActivityCompat.startActivityForResult(
                this,
                intent,
                0x01, null
            )
        }
    }

    private fun loadSavedKey() {
        etSecretKey.setText(P2PManager.getSecretKey())
        etAuth.setText(P2PManager.getAuth())
    }

    private fun saveSecretKey() {
        val secretKey = etSecretKey.text.toString().trim()
        val auth = etAuth.text.toString().trim()

        if (secretKey.isEmpty()) {
            Toast.makeText(this, "请输入密钥", Toast.LENGTH_SHORT).show()
            return
        }

        P2PManager.saveSecretKey(secretKey, auth)
        finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            when (requestCode) {
                1 -> {
                    val result = CameraScan.parseScanResult(data)
                    etSecretKey.setText(result)
                }
            }
        }
    }
}