package com.example.project

import android.os.Bundle
import android.util.Base64
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.pkcs.RSAPrivateKey
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class ClusterOverview : AppCompatActivity() {

    private lateinit var tvNodesCount: TextView
    private lateinit var tvPodsCount: TextView
    private lateinit var tvServicesCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_cluster_overview)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_overview)
        toolbar.title = "Обзор кластера"
        toolbar.navigationIcon = getDrawable(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        tvNodesCount = findViewById(R.id.tv_nodes_count)
        tvPodsCount = findViewById(R.id.tv_pods_count)
        tvServicesCount = findViewById(R.id.tv_services_count)

        loadOverview()
    }

    private fun loadOverview() {
        val prefs = getSharedPreferences("kube_prefs", MODE_PRIVATE)
        val kubeconfig = prefs.getString("kubeconfig_content", null)

        if (kubeconfig.isNullOrBlank()) {
            Toast.makeText(this, "kubeconfig не выбран", Toast.LENGTH_SHORT).show()
            return
        }

        val server = extractField(kubeconfig, "server")
        val caData = extractField(kubeconfig, "certificate-authority-data")
        val clientCertData = extractField(kubeconfig, "client-certificate-data")
        val clientKeyData = extractField(kubeconfig, "client-key-data")

        if (server == null || caData == null || clientCertData == null || clientKeyData == null) {
            Toast.makeText(
                this,
                "Не удалось извлечь server/CA/cert/key из kubeconfig",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        Thread {
            try {
                val client = createMtlsClient(caData, clientCertData, clientKeyData)
                val base = server.trimEnd('/')

                val nodesReq = Request.Builder()
                    .url("$base/api/v1/nodes")
                    .build()
                val nodesResp = client.newCall(nodesReq).execute()
                if (!nodesResp.isSuccessful) {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Ошибка получения нод: ${nodesResp.code}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@Thread
                }
                val nodesJson = nodesResp.body?.string() ?: ""
                val nodesCount = JSONObject(nodesJson).getJSONArray("items").length()

                val podsReq = Request.Builder()
                    .url("$base/api/v1/pods")
                    .build()
                val podsResp = client.newCall(podsReq).execute()
                if (!podsResp.isSuccessful) {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Ошибка получения подов: ${podsResp.code}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@Thread
                }
                val podsJson = podsResp.body?.string() ?: ""
                val podsCount = JSONObject(podsJson).getJSONArray("items").length()

                val svcReq = Request.Builder()
                    .url("$base/api/v1/services")
                    .build()
                val svcResp = client.newCall(svcReq).execute()
                if (!svcResp.isSuccessful) {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Ошибка получения сервисов: ${svcResp.code}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@Thread
                }
                val svcJson = svcResp.body?.string() ?: ""
                val servicesCount = JSONObject(svcJson).getJSONArray("items").length()

                runOnUiThread {
                    tvNodesCount.text = nodesCount.toString()
                    tvPodsCount.text = podsCount.toString()
                    tvServicesCount.text = servicesCount.toString()
                }

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Ошибка при получении обзора кластера: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun extractField(text: String, key: String): String? {
        return text.lineSequence()
            .firstOrNull { it.trimStart().startsWith("$key:") }
            ?.substringAfter("$key:")
            ?.trim()
    }

    private fun createMtlsClient(
        caDataB64: String,
        clientCertB64: String,
        clientKeyB64: String
    ): OkHttpClient {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }

        val cf = CertificateFactory.getInstance("X.509")

        val caPemBytes = Base64.decode(caDataB64, Base64.DEFAULT)
        val caCert = cf.generateCertificate(ByteArrayInputStream(caPemBytes)) as X509Certificate

        val clientCertPemBytes = Base64.decode(clientCertB64, Base64.DEFAULT)
        val clientCert = cf.generateCertificate(
            ByteArrayInputStream(clientCertPemBytes)
        ) as X509Certificate

        val privateKey = decodePrivateKeyFromKubeconfig(clientKeyB64)

        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        val password = "changeit".toCharArray()
        keyStore.setKeyEntry("client", privateKey, password, arrayOf(clientCert))

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, password)

        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
        trustStore.load(null, null)
        trustStore.setCertificateEntry("ca", caCert)

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustStore)
        val trustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, arrayOf(trustManager), null)

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .build()
    }

    private fun decodePrivateKeyFromKubeconfig(clientKeyB64: String): PrivateKey {
        val pemText = String(Base64.decode(clientKeyB64, Base64.DEFAULT), Charsets.UTF_8)
        val cleaned = pemText.trim()
        val keyFactory = KeyFactory.getInstance("RSA")

        return when {
            cleaned.contains("BEGIN PRIVATE KEY") -> {
                val base64Body = cleaned
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace("\\s".toRegex(), "")
                val der = Base64.decode(base64Body, Base64.DEFAULT)
                val spec = PKCS8EncodedKeySpec(der)
                keyFactory.generatePrivate(spec)
            }

            cleaned.contains("BEGIN RSA PRIVATE KEY") -> {
                val base64Body = cleaned
                    .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                    .replace("-----END RSA PRIVATE KEY-----", "")
                    .replace("\\s".toRegex(), "")
                val der = Base64.decode(base64Body, Base64.DEFAULT)

                val seq = ASN1Sequence.getInstance(der)
                val rsa = RSAPrivateKey.getInstance(seq)

                val algId = AlgorithmIdentifier(
                    PKCSObjectIdentifiers.rsaEncryption,
                    DERNull.INSTANCE
                )
                val privateKeyInfo = PrivateKeyInfo(algId, rsa)
                val pkcs8 = privateKeyInfo.encoded

                val spec = PKCS8EncodedKeySpec(pkcs8)
                keyFactory.generatePrivate(spec)
            }

            else -> {
                throw IllegalArgumentException("Неизвестный формат ключа в kubeconfig")
            }
        }
    }
}
