package app.mccdroid.core

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.mutableStateOf
import app.mccdroid.logic.Json
import app.mccdroid.logic.asArr
import app.mccdroid.logic.asObj
import app.mccdroid.logic.str
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Penyimpanan API key Gemini terenkripsi Android Keystore. */
object GeminiStore {
    private const val PREFS = "gemini_prefs"
    private const val KEY_ALIAS = "mccdroid_gemini_key"
    private const val CIPHER = "AES/GCM/NoPadding"
    private lateinit var ctx: Context

    private val _apiKey = mutableStateOf("")
    private val _model = mutableStateOf("gemini-2.0-flash")
    private val _models = mutableStateOf<List<GeminiModel>>(emptyList())

    val apiKey: String get() = _apiKey.value
    var selectedModel: String
        get() = _model.value
        set(v) {
            _model.value = v
            prefs().edit().putString("model", v).apply()
        }
    val models: List<GeminiModel> get() = _models.value

    fun init(c: Context) {
        ctx = c.applicationContext
        val p = prefs()
        _apiKey.value = decrypt(p.getString("apiKey", "") ?: "")
        _model.value = p.getString("model", "gemini-2.0-flash") ?: "gemini-2.0-flash"
        _models.value = Json.parseOrNull(p.getString("models", "") ?: "").asArr().mapNotNull {
            val o = it.asObj()
            val id = o.str("id")
            if (id.isBlank()) null else GeminiModel(id, o.str("displayName", id), o.str("description"))
        }
    }

    fun setApiKey(value: String) {
        _apiKey.value = value.trim()
        prefs().edit().putString("apiKey", encrypt(_apiKey.value)).apply()
    }

    fun setModels(value: List<GeminiModel>) {
        _models.value = value
        prefs().edit().putString("models", Json.stringify(value.map {
            mapOf("id" to it.id, "displayName" to it.displayName, "description" to it.description)
        })).apply()
    }

    private fun prefs() = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(256)
        generator.generateKey()
        return (KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(KEY_ALIAS, null) as SecretKey)
    }

    private fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val packed = cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            Base64.encodeToString(packed, Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }

    private fun decrypt(value: String): String {
        if (value.isEmpty()) return ""
        return try {
            val packed = Base64.decode(value, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, packed, 0, 12))
            String(cipher.doFinal(packed, 12, packed.size - 12), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }
}

data class GeminiModel(val id: String, val displayName: String, val description: String = "")
