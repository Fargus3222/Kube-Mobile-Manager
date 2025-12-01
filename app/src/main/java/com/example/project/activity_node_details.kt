package com.example.project

import android.os.Bundle
import android.util.Base64
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.CircularProgressIndicator
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

class NodeDetailsActivity : AppCompatActivity() {

    private lateinit var tvNodeName: TextView
    private lateinit var cpuIndicator: CircularProgressIndicator
    private lateinit var ramIndicator: CircularProgressIndicator
    private lateinit var tvCpuValue: TextView
    private lateinit var tvRamValue: TextView
    private lateinit var rvPods: RecyclerView
    private lateinit var podsAdapter: PodAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_node_details)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_node_details)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Нода"
        toolbar.setNavigationOnClickListener { finish() }

        tvNodeName = findViewById(R.id.tv_node_name)
        cpuIndicator = findViewById(R.id.cpu_indicator)
        ramIndicator = findViewById(R.id.ram_indicator)
        tvCpuValue = findViewById(R.id.tv_cpu_value)
        tvRamValue = findViewById(R.id.tv_ram_value)

        rvPods = findViewById(R.id.rv_pods_on_node)
        rvPods.layoutManager = LinearLayoutManager(this)
        podsAdapter = PodAdapter(emptyList())
        rvPods.adapter = podsAdapter

        cpuIndicator.max = 100
        ramIndicator.max = 100

        val nodeName = intent.getStringExtra("node_name")
        if (nodeName.isNullOrBlank()) {
            Toast.makeText(this, "Имя ноды не передано", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        tvNodeName.text = nodeName
        loadNodeDetails(nodeName)
    }

    private fun loadNodeDetails(nodeName: String) {
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

                val nodeReq = Request.Builder()
                    .url("$base/api/v1/nodes/$nodeName")
                    .build()
                val nodeResp = client.newCall(nodeReq).execute()
                if (!nodeResp.isSuccessful) {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Ошибка запроса ноды: ${nodeResp.code}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@Thread
                }
                val nodeJson = nodeResp.body?.string() ?: ""

                var metricsJson: String? = null
                try {
                    val mReq = Request.Builder()
                        .url("$base/apis/metrics.k8s.io/v1beta1/nodes/$nodeName")
                        .build()
                    val mResp = client.newCall(mReq).execute()
                    if (mResp.isSuccessful) {
                        metricsJson = mResp.body?.string()
                    }
                } catch (_: Exception) {
                }

                val podsReq = Request.Builder()
                    .url("$base/api/v1/pods?fieldSelector=spec.nodeName=$nodeName")
                    .build()
                val podsResp = client.newCall(podsReq).execute()
                if (!podsResp.isSuccessful) {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Ошибка запроса подов: ${podsResp.code}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@Thread
                }
                val podsJson = podsResp.body?.string() ?: ""

                val (cpuPct, ramPct) = parseNodeUsage(nodeJson, metricsJson)
                val pods = parsePods(podsJson)

                runOnUiThread {
                    val cpu = cpuPct ?: 0
                    val ram = ramPct ?: 0
                    cpuIndicator.progress = cpu
                    ramIndicator.progress = ram
                    tvCpuValue.text = if (cpuPct != null) "$cpuPct%" else "—"
                    tvRamValue.text = if (ramPct != null) "$ramPct%" else "—"
                    podsAdapter.updateData(pods)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Ошибка при получении данных ноды: ${e.message}",
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

    private fun parseNodeUsage(nodeJson: String, metricsJson: String?): Pair<Int?, Int?> {
        val root = JSONObject(nodeJson)
        val status = root.getJSONObject("status")
        val capacity = status.optJSONObject("capacity")
        val capCpuStr = capacity?.optString("cpu", null)
        val capMemStr = capacity?.optString("memory", null)
        val capCpuMillis = capCpuStr?.let { parseCpuToMillis(it) }
        val capMemBytes = capMemStr?.let { parseMemoryToBytes(it) }

        if (metricsJson.isNullOrBlank() || capCpuMillis == null || capMemBytes == null) {
            return null to null
        }

        val mRoot = JSONObject(metricsJson)
        val usage = mRoot.optJSONObject("usage") ?: return null to null
        val cpuStr = usage.optString("cpu", null)
        val memStr = usage.optString("memory", null)
        val usedCpuMillis = cpuStr?.let { parseCpuToMillis(it) } ?: return null to null
        val usedMemBytes = memStr?.let { parseMemoryToBytes(it) } ?: return null to null

        val cpuPct =
            if (capCpuMillis > 0) ((usedCpuMillis.toDouble() / capCpuMillis.toDouble()) * 100.0).roundToInt()
            else null
        val memPct =
            if (capMemBytes > 0) ((usedMemBytes.toDouble() / capMemBytes.toDouble()) * 100.0).roundToInt()
            else null

        return cpuPct to memPct
    }

    private fun parsePods(podsJson: String): List<PodItem> {
        val result = mutableListOf<PodItem>()
        val root = JSONObject(podsJson)
        val items = root.getJSONArray("items")

        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val meta = item.getJSONObject("metadata")
            val statusObj = item.optJSONObject("status")
            val name = meta.getString("name")
            val namespace = meta.optString("namespace", "default")
            val phase = statusObj?.optString("phase", "Unknown") ?: "Unknown"

            result.add(
                PodItem(
                    name = name,
                    namespace = namespace,
                    status = phase
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
