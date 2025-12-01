package com.example.project

import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.ChipGroup
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

class resurses : AppCompatActivity() {

    private lateinit var rvResources: RecyclerView
    private lateinit var adapter: ResourceAdapter
    private lateinit var chipGroup: ChipGroup

    private enum class ResourceKind {
        PODS, REPLICASETS, DEPLOYMENTS, STATEFULSETS
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_resurses)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_resources)
        toolbar.title = "Ресурсы кластера"
        toolbar.navigationIcon = getDrawable(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        rvResources = findViewById(R.id.rv_resources)
        rvResources.layoutManager = LinearLayoutManager(this)
        adapter = ResourceAdapter(emptyList())
        rvResources.adapter = adapter

        chipGroup = findViewById(R.id.chip_group_resources)

        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val kind = when (id) {
                R.id.chip_pods -> ResourceKind.PODS
                R.id.chip_replicasets -> ResourceKind.REPLICASETS
                R.id.chip_deployments -> ResourceKind.DEPLOYMENTS
                R.id.chip_statefulsets -> ResourceKind.STATEFULSETS
                else -> ResourceKind.PODS
            }
            loadResources(kind)
        }

        loadResources(ResourceKind.PODS)
    }

    private fun loadResources(kind: ResourceKind) {
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

                val path = when (kind) {
                    ResourceKind.PODS -> "/api/v1/pods"
                    ResourceKind.REPLICASETS -> "/apis/apps/v1/replicasets"
                    ResourceKind.DEPLOYMENTS -> "/apis/apps/v1/deployments"
                    ResourceKind.STATEFULSETS -> "/apis/apps/v1/statefulsets"
                }

                val req = Request.Builder()
                    .url(base + path)
                    .build()

                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Ошибка запроса ресурсов: ${resp.code}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@Thread
                }

                val body = resp.body?.string() ?: ""
                val resources = parseResources(kind, body)

                runOnUiThread {
                    adapter.updateData(resources)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Ошибка при получении ресурсов: ${e.message}",
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

    private fun parseResources(kind: ResourceKind, json: String): List<ResourceItem> {
        val result = mutableListOf<ResourceItem>()
        val root = JSONObject(json)
        val items = root.getJSONArray("items")

        val typeLabel = when (kind) {
            ResourceKind.PODS -> "Pod"
            ResourceKind.REPLICASETS -> "ReplicaSet"
            ResourceKind.DEPLOYMENTS -> "Deployment"
            ResourceKind.STATEFULSETS -> "StatefulSet"
        }

        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)

            val meta = item.getJSONObject("metadata")
            val name = meta.getString("name")
            val namespace = meta.optString("namespace", "default")

            val statusText = when (kind) {
                ResourceKind.PODS -> {
                    val st = item.optJSONObject("status")
                    " " + (st?.optString("phase", "Unknown") ?: "Unknown")
                }

                ResourceKind.REPLICASETS -> {
                    val st = item.optJSONObject("status")
                    val ready = st?.optInt("readyReplicas", 0) ?: 0
                    val replicas = st?.optInt("replicas", 0) ?: 0
                    " Ready $ready/$replicas"
                }

                ResourceKind.DEPLOYMENTS -> {
                    val st = item.optJSONObject("status")
                    val ready = st?.optInt(
                        "readyReplicas",
                        st?.optInt("availableReplicas", 0) ?: 0
                    ) ?: 0
                    val replicas = st?.optInt("replicas", 0) ?: 0
                    " Ready $ready/$replicas"
                }

                ResourceKind.STATEFULSETS -> {
                    val st = item.optJSONObject("status")
                    val ready = st?.optInt("readyReplicas", 0) ?: 0
                    val replicas = st?.optInt("replicas", 0) ?: 0
                    " Ready $ready/$replicas"
                }
            }

            result.add(
                ResourceItem(
                    name = name,
                    namespace = namespace,
                    type = typeLabel,
                    status = statusText
                )
            )
        }

        return result
    }
}
