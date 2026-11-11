package dev.southstar21.vectra.platform

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.southstar21.vectra.core.data.BlobStore
import dev.southstar21.vectra.core.data.moveStorage
import dev.southstar21.vectra.core.network.createHttpClient
import dev.southstar21.vectra.core.platform.SecretCipher
import dev.southstar21.vectra.features.transit.CatalogDiskCache
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import okio.Path.Companion.toPath
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLCache
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.Security.SecItemCopyMatching
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateDecryptedData
import platform.Security.SecKeyCreateEncryptedData
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyRef
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrApplicationTag
import platform.Security.kSecAttrIsPermanent
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecClass
import platform.Security.kSecClassKey
import platform.Security.kSecKeyAlgorithmECIESEncryptionCofactorVariableIVX963SHA256AESGCM
import platform.Security.kSecPrivateKeyAttrs
import platform.Security.kSecReturnRef

@OptIn(ExperimentalForeignApi::class)
private fun appDirectory(kind: ULong): String {
    val dir = NSFileManager.defaultManager.URLForDirectory(kind, NSUserDomainMask, null, true, null)
    return requireNotNull(dir?.path) { "No app directory $kind" }
}

private const val PreferencesFile = "vectra.preferences_pb"
private const val BlobsDir = "vectra-blobs"

/**
 * Application Support/vectra, excluded from iCloud and Finder backups like Android's data
 * extraction rules: a restored copy is orphaned on another device, whose Keychain cannot open the
 * session. Releases up to 1.8.3 kept the same files in Documents; the first call moves them here.
 */
private val storageDir: String by lazy {
    val dir = appDirectory(NSApplicationSupportDirectory) + "/vectra"
    moveStorage(Path(appDirectory(NSDocumentDirectory)), Path(dir), listOf(PreferencesFile, BlobsDir))
    SystemFileSystem.createDirectories(Path(dir))
    excludeFromBackup(dir)
    dir
}

/** Set on the directory, so it also covers the files DataStore and BlobStore replace by rename. */
@OptIn(ExperimentalForeignApi::class)
private fun excludeFromBackup(path: String) {
    NSURL.fileURLWithPath(path, isDirectory = true)
        .setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)
}

fun createDataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.createWithPath(
    // A corrupt file would otherwise throw on every read, crashing each launch until the app is reinstalled.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    produceFile = { "$storageDir/$PreferencesFile".toPath() },
)

/** Large values, one file each, next to the preferences file. */
fun createBlobStore(): BlobStore = BlobStore(Path(storageDir, BlobsDir))

/**
 * The transit catalog's offline copy, in Application Support rather than tmp/, which iOS
 * clears while the app isn't running. It is downloadable again, so it stays out of
 * backups. Older builds kept it in tmp/; [CatalogDiskCache] moves that copy over.
 */
@OptIn(ExperimentalForeignApi::class)
fun createCatalogCache(): CatalogDiskCache {
    val dir = appDirectory(NSApplicationSupportDirectory) + "/vectra-catalog"
    runCatching {
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        NSURL.fileURLWithPath(dir, isDirectory = true).setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
    }
    return CatalogDiskCache(dir = Path(dir), legacyDir = Path(SystemTemporaryDirectory, "vectra-catalog"))
}

private const val HttpCacheBytes = 52_428_800uL // 50 MiB, as on Android
private const val HttpMemoryCacheBytes = 8_388_608uL // 8 MiB

/** NSURLSession allows 6 connections per host by default, which map tiles and the cold-start burst exceed. */
@OptIn(ExperimentalForeignApi::class)
fun createIosHttpClient(): HttpClient = createHttpClient(
    HttpClient(Darwin) {
        engine {
            configureSession {
                HTTPMaximumConnectionsPerHost = 16
                URLCache = NSURLCache(
                    memoryCapacity = HttpMemoryCacheBytes,
                    diskCapacity = HttpCacheBytes,
                    directoryURL = NSURL.fileURLWithPath(appDirectory(NSCachesDirectory) + "/http"),
                )
            }
        }
    },
)

/**
 * ECIES (P-256 + AES-GCM) under a private key kept in the Keychain, this device only; the
 * token never sits on disk in clear. The iOS counterpart of Android's KeystoreSecretCipher.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class KeychainSecretCipher : SecretCipher {
    private val tag = "dev.southstar21.vectra.session"
    private val algorithm = kSecKeyAlgorithmECIESEncryptionCofactorVariableIVX963SHA256AESGCM

    private var cachedKey: SecKeyRef? = null

    private fun key(): SecKeyRef = cachedKey ?: (loadKey() ?: createKey()).also { cachedKey = it }

    private fun tagData(): CFTypeRef? =
        CFBridgingRetain(tag.encodeToByteArray().toNSData())

    private fun loadKey(): SecKeyRef? = memScoped {
        val tagRef = tagData()
        val query = cfDictionary(
            kSecClass to kSecClassKey,
            kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrApplicationTag to tagRef,
            kSecReturnRef to kCFBooleanTrue,
        )
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        CFRelease(query)
        tagRef?.let(::CFRelease)
        if (status == errSecSuccess) result.value?.reinterpret() else null
    }

    private fun createKey(): SecKeyRef {
        val tagRef = tagData()
        val bits = CFBridgingRetain(NSNumber(int = 256))
        val privateAttrs = cfDictionary(
            kSecAttrIsPermanent to kCFBooleanTrue,
            kSecAttrApplicationTag to tagRef,
            kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        )
        val attrs = cfDictionary(
            kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits to bits,
            kSecPrivateKeyAttrs to privateAttrs,
        )
        val key = SecKeyCreateRandomKey(attrs, null)
        CFRelease(attrs)
        CFRelease(privateAttrs)
        bits?.let(::CFRelease)
        tagRef?.let(::CFRelease)
        return key ?: error("Keychain key generation failed")
    }

    override fun encrypt(plain: String): String {
        val publicKey = SecKeyCopyPublicKey(key()) ?: error("Keychain key has no public key")
        val input: CFDataRef? = CFBridgingRetain(plain.encodeToByteArray().toNSData())?.reinterpret()
        val output = SecKeyCreateEncryptedData(publicKey, algorithm, input, null)
        input?.let(::CFRelease)
        CFRelease(publicKey)
        val data = CFBridgingRelease(output) as? NSData ?: error("Keychain encryption failed")
        return data.base64EncodedStringWithOptions(0u)
    }

    override fun decrypt(cipher: String): String? {
        val data = NSData.create(base64EncodedString = cipher, options = 0u) ?: return null
        val input: CFDataRef? = CFBridgingRetain(data)?.reinterpret()
        val output = SecKeyCreateDecryptedData(key(), algorithm, input, null)
        input?.let(::CFRelease)
        return (CFBridgingRelease(output) as? NSData)
            ?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() }
    }
}

/** A +1 CFDictionary of CF keys and values; the caller releases it. */
@OptIn(ExperimentalForeignApi::class)
private fun cfDictionary(vararg entries: Pair<CPointer<*>?, CPointer<*>?>): CFDictionaryRef? {
    val dict = CFDictionaryCreateMutable(null, entries.size.convert(), kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
    entries.forEach { (k, v) -> CFDictionaryAddValue(dict, k, v) }
    return dict
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData = usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
