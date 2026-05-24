package com.remote.dipgonc

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.king.camera.scan.CameraScan


class SettingsActivity : AppCompatActivity() {

    private lateinit var etSecretKey: EditText
    private lateinit var etAuth: EditText
    private lateinit var rgTunnelMode: RadioGroup
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
        rgTunnelMode = findViewById(R.id.rgTunnelMode)
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
        rgTunnelMode.check(
            when (P2PManager.getTunnelMode()) {
                P2PManager.TunnelMode.LEGACY -> R.id.rbTunnelLegacy
                P2PManager.TunnelMode.LINK -> R.id.rbTunnelLink
                else -> R.id.rbTunnelNc
            }
        )
    }

    private fun saveSecretKey() {
        val secretKey = etSecretKey.text.toString().trim()
        val auth = etAuth.text.toString().trim()

        if (secretKey.isEmpty()) {
            Toast.makeText(this, "请输入密钥", Toast.LENGTH_SHORT).show()
            return
        }

        val tunnelMode = when (rgTunnelMode.checkedRadioButtonId) {
            R.id.rbTunnelLegacy -> P2PManager.TunnelMode.LEGACY
            R.id.rbTunnelLink -> P2PManager.TunnelMode.LINK
            R.id.rbTunnelNc -> P2PManager.TunnelMode.NC
            else -> P2PManager.TunnelMode.LEGACY
        }
        P2PManager.saveSettings(secretKey, auth, tunnelMode)
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
