package dev.southstar21.vectra.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.southstar21.vectra.core.data.BlobStore
import dev.southstar21.vectra.core.network.createHttpClient
import dev.southstar21.vectra.core.platform.SecretCipher
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.io.files.Path
import okhttp3.Cache
import okhttp3.Dispatcher
import okio.Path.Companion.toPath
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.concurrent.TimeUnit

fun createDataStore(context: Context): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath(
        produceFile = { context.filesDir.resolve("vectra.preferences_pb").absolutePath.toPath() },
    )

/** Large values, one file each, in the same private directory as the preferences file. */
fun createBlobStore(context: Context): BlobStore = BlobStore(Path(context.filesDir.resolve("vectra-blobs").absolutePath))

private const val HttpCacheBytes = 50L * 1024 * 1024

/** OkHttp queues beyond 5 requests per host by default, which map tiles and the cold-start burst exceed. */
fun createAndroidHttpClient(context: Context): HttpClient = createHttpClient(
    HttpClient(OkHttp) {
        engine {
            config {
                dispatcher(Dispatcher().apply { maxRequestsPerHost = 16 })
                cache(Cache(File(context.cacheDir, "http"), HttpCacheBytes))
                // Above the 40 s Futár and 30 s ICS proxy budgets, which enforce their own limits.
                readTimeout(60, TimeUnit.SECONDS)
            }
        }
    },
)

/** AES-GCM with a non-exportable Android Keystore key; the token never sits on disk in clear. */
class KeystoreSecretCipher : SecretCipher {
    private val alias = "vectra.session"

    @Volatile private var cachedKey: SecretKey? = null

    private fun key(): SecretKey = cachedKey ?: loadKey().also { cachedKey = it }

    private fun loadKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(plain.encodeToByteArray())
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    override fun decrypt(cipher: String): String? = runCatching {
        val bytes = Base64.decode(cipher, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        }
        c.doFinal(bytes, 12, bytes.size - 12).decodeToString()
    }.getOrNull()
}
