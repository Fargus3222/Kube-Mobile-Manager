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
import kotlin.math.roundToInt

class nodes : AppCompatActivity() {

    private lateinit var rvNodes: RecyclerView
    private lateinit var adapter: NodeAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_nodes)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_nodes)
        toolbar.title = "Ноды"
        toolbar.navigationIcon = getDrawable(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        rvNodes = findViewById(R.id.rv_nodes)
        rvNodes.layoutManager = LinearLayoutManager(this)
        adapter = NodeAdapter(emptyList())
        rvNodes.adapter = adapter

        loadNodes()
    }

    private fun loadNodes() {
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
                            "Ошибка запроса нод: ${nodesResp.code}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@Thread
                }
                val nodesJson = nodesResp.body?.string() ?: ""

                var metricsJson: String? = null
                try {
                    val metricsReq = Request.Builder()
                        .url("$base/apis/metrics.k8s.io/v1beta1/nodes")
                        .build()
                    val metricsResp = client.newCall(metricsReq).execute()
                    if (metricsResp.isSuccessful) {
                        metricsJson = metricsResp.body?.string()
                    }
                } catch (_: Exception) {
                }

                val nodes = parseNodes(nodesJson, metricsJson)

                runOnUiThread {
                    adapter.updateData(nodes)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Ошибка при получении нод: ${e.message}",
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

    private fun parseNodes(nodesJson: String, metricsJson: String?): List<NodeItem> {
        val usageMap = mutableMapOf<String, Pair<Long, Long>>()

        if (!metricsJson.isNullOrBlank()) {
            val mRoot = JSONObject(metricsJson)
            val mItems = mRoot.getJSONArray("items")
            for (i in 0 until mItems.length()) {
                val item = mItems.getJSONObject(i)
                val meta = item.getJSONObject("metadata")
                val name = meta.getString("name")
                val usage = item.getJSONObject("usage")
                val cpuStr = usage.optString("cpu", null)
                val memStr = usage.optString("memory", null)

                val cpuMillis = cpuStr?.let { parseCpuToMillis(it) }
                val memBytes = memStr?.let { parseMemoryToBytes(it) }

                if (cpuMillis != null && memBytes != null) {
                    usageMap[name] = cpuMillis to memBytes
                }
            }
        }

        val result = mutableListOf<NodeItem>()
        val root = JSONObject(nodesJson)
        val items = root.getJSONArray("items")

        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)

            val metadata = item.getJSONObject("metadata")
            val name = metadata.getString("name")

            val labels = metadata.optJSONObject("labels")
            val roles = mutableListOf<String>()
            labels?.let {
                if (it.has("node-role.kubernetes.io/control-plane")) roles.add("control-plane")
                if (it.has("node-role.kubernetes.io/master")) roles.add("master")
                if (it.has("node-role.kubernetes.io/worker")) roles.add("worker")
            }
            val rolesText = if (roles.isEmpty()) "none" else roles.joinToString(",")

            val statusObj = item.getJSONObject("status")

            val conditions = statusObj.getJSONArray("conditions")
            var readyStatus = "Unknown"
            for (j in 0 until conditions.length()) {
                val cond = conditions.getJSONObject(j)
                if (cond.getString("type") == "Ready") {
                    readyStatus =
                        if (cond.getString("status") == "True") "Ready" else "NotReady"
                }
            }

            val capacity = statusObj.optJSONObject("capacity")
            val capCpuStr = capacity?.optString("cpu", null)
            val capMemStr = capacity?.optString("memory", null)

            val capCpuMillis = capCpuStr?.let { parseCpuToMillis(it) }
            val capMemBytes = capMemStr?.let { parseMemoryToBytes(it) }

            val usagePair = usageMap[name]
            val usedCpuMillis = usagePair?.first
            val usedMemBytes = usagePair?.second

            val cpuPct: Int? =
                if (capCpuMillis != null && usedCpuMillis != null && capCpuMillis > 0) {
                    ((usedCpuMillis.toDouble() / capCpuMillis.toDouble()) * 100.0).roundToInt()
                } else null

            val memPct: Int? =
                if (capMemBytes != null && usedMemBytes != null && capMemBytes > 0) {
                    ((usedMemBytes.toDouble() / capMemBytes.toDouble()) * 100.0).roundToInt()
                } else null

            val cpuText = cpuPct?.let { "$it%" } ?: "—"
            val memText = memPct?.let { "$it%" } ?: "—"
            val usageText = " CPU $cpuText  •  RAM $memText"

            result.add(
                NodeItem(
                    name = name,
                    roles = rolesText,
                    status = readyStatus,
                    usage = usageText
                )
            )
        }

        return result
    }

    private fun parseCpuToMillis(value: String): Long {
        val v = value.trim()
        return if (v.endsWith("m")) {
            v.removeSuffix("m").toLongOrNull() ?: 0L
        } else {
            ((v.toDoubleOrNull() ?: 0.0) * 1000.0).toLong()
        }
    }

    private fun parseMemoryToBytes(value: String): Long {
        val v = value.trim()
        if (v.isEmpty()) return 0L

        val numPart = v.takeWhile { it.isDigit() }
        val suffix = v.drop(numPart.length).uppercase()

        val base = numPart.toLongOrNull() ?: return 0L

        val multiplier = when (suffix) {
            "", "B" -> 1L
            "KI" -> 1024L
            "MI" -> 1024L * 1024L
            "GI" -> 1024L * 1024L * 1024L
            "TI" -> 1024L * 1024L * 1024L * 1024L
            "PI" -> 1024L * 1024L * 1024L * 1024L * 1024L
            "EI" -> 1024L * 1024L * 1024L * 1024L * 1024L * 1024L
            "K" -> 1000L
            "M" -> 1000L * 1000L
            "G" -> 1000L * 1000L * 1000L
            "T" -> 1000L * 1000L * 1000L * 1000L
            else -> 1L
        }

        return base * multiplier
    }
}
