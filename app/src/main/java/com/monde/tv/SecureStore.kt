
package com.monde.tv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Almacenamiento cifrado local para MONDE TV.
 * Compatible con Android API 23 o superior.
 */
object SecureStore {

    data class M3uSource(
        val id: String,
        val name: String,
        val url: String = "",
        val uri: String = "",
        val isFile: Boolean = false
    )

    data class XtreamAccount(
        val server: String,
        val username: String,
        val password: String
    )

    private const val PREFS_NAME = "monde_tv_secure_store"
    private const val PREF_M3U = "encrypted_m3u_sources_v1"
    private const val PREF_XTREAM = "encrypted_xtream_account_v1"

    private const val KEY_ALIAS = "monde_tv_secure_aes_v1"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private const val IV_SIZE = 12
    private const val TAG_SIZE_BITS = 128
    private const val FORMAT_VERSION: Byte = 1

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    @Synchronized
    private fun getOrCreateKey(): SecretKey {

        val keyStore = KeyStore.getInstance(
            KEYSTORE_PROVIDER
        ).apply {
            load(null)
        }

        val existingKey =
            keyStore.getKey(KEY_ALIAS, null) as? SecretKey

        if (existingKey != null) return existingKey

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or
                KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(
                KeyProperties.ENCRYPTION_PADDING_NONE
            )
            .setRandomizedEncryptionRequired(true)
            .build()

        generator.init(spec)

        return generator.generateKey()
    }

    private fun encrypt(plainText: String): String {

        val cipher = Cipher.getInstance(TRANSFORMATION)

        cipher.init(
            Cipher.ENCRYPT_MODE,
            getOrCreateKey()
        )

        val iv = cipher.iv

        require(iv.size == IV_SIZE) {
            "IV no compatible"
        }

        val encryptedBytes = cipher.doFinal(
            plainText.toByteArray(StandardCharsets.UTF_8)
        )

        val payload = ByteBuffer.allocate(
            1 + iv.size + encryptedBytes.size
        )
            .put(FORMAT_VERSION)
            .put(iv)
            .put(encryptedBytes)
            .array()

        return Base64.encodeToString(
            payload,
            Base64.NO_WRAP
        )
    }

    private fun decrypt(encodedValue: String): String {

        val payload = Base64.decode(
            encodedValue,
            Base64.NO_WRAP
        )

        require(payload.size > 1 + IV_SIZE) {
            "Datos cifrados incompletos"
        }

        val buffer = ByteBuffer.wrap(payload)

        require(buffer.get() == FORMAT_VERSION) {
            "Versión no compatible"
        }

        val iv = ByteArray(IV_SIZE)
        buffer.get(iv)

        val encryptedBytes = ByteArray(buffer.remaining())
        buffer.get(encryptedBytes)

        val cipher = Cipher.getInstance(TRANSFORMATION)

        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(TAG_SIZE_BITS, iv)
        )

        return String(
            cipher.doFinal(encryptedBytes),
            StandardCharsets.UTF_8
        )
    }

    private fun readEncryptedValue(
        context: Context,
        preferenceKey: String
    ): String? {

        val storedValue =
            preferences(context).getString(preferenceKey, null)
                ?: return null

        return try {
            decrypt(storedValue)
        } catch (_: Exception) {
            preferences(context)
                .edit()
                .remove(preferenceKey)
                .apply()

            null
        }
    }

    private fun writeEncryptedValue(
        context: Context,
        preferenceKey: String,
        plainText: String
    ) {

        val encryptedValue = encrypt(plainText)

        check(
            preferences(context)
                .edit()
                .putString(preferenceKey, encryptedValue)
                .commit()
        ) {
            "No se pudieron guardar los datos cifrados"
        }
    }

    // =====================================
    // LISTAS M3U
    // =====================================

    @Synchronized
    fun getM3uSources(
        context: Context
    ): List<M3uSource> {

        val jsonText =
            readEncryptedValue(context, PREF_M3U)
                ?: return emptyList()

        return try {

            val jsonArray = JSONArray(jsonText)
            val result = mutableListOf<M3uSource>()

            for (index in 0 until jsonArray.length()) {

                val item =
                    jsonArray.optJSONObject(index) ?: continue

                val id = item.optString("id").trim()
                val name = item.optString("name").trim()

                if (id.isEmpty() || name.isEmpty()) continue

                result.add(
                    M3uSource(
                        id = id,
                        name = name,
                        url = item.optString("url"),
                        uri = item.optString("uri"),
                        isFile = item.optBoolean("isFile", false)
                    )
                )
            }

            result

        } catch (_: Exception) {

            preferences(context)
                .edit()
                .remove(PREF_M3U)
                .apply()

            emptyList()
        }
    }

    @Synchronized
    fun saveM3uSources(
        context: Context,
        sources: List<M3uSource>
    ) {

        val jsonArray = JSONArray()

        sources.forEach { source ->

            jsonArray.put(
                JSONObject()
                    .put("id", source.id)
                    .put("name", source.name)
                    .put("url", source.url)
                    .put("uri", source.uri)
                    .put("isFile", source.isFile)
            )
        }

        writeEncryptedValue(
            context,
            PREF_M3U,
            jsonArray.toString()
        )
    }

    @Synchronized
    fun addM3uSource(
        context: Context,
        source: M3uSource
    ) {

        require(source.id.isNotBlank()) {
            "La lista necesita un identificador"
        }

        require(source.name.isNotBlank()) {
            "La lista necesita un nombre"
        }

        val sources =
            getM3uSources(context).toMutableList()

        val existingIndex =
            sources.indexOfFirst { it.id == source.id }

        if (existingIndex >= 0) {
            sources[existingIndex] = source
        } else {
            sources.add(source)
        }

        saveM3uSources(context, sources)
    }

    @Synchronized
    fun removeM3uSource(
        context: Context,
        sourceId: String
    ) {

        val remaining =
            getM3uSources(context)
                .filterNot { it.id == sourceId }

        saveM3uSources(context, remaining)
    }

    @Synchronized
    fun removeAllM3uSources(context: Context) {

        check(
            preferences(context)
                .edit()
                .remove(PREF_M3U)
                .commit()
        ) {
            "No se pudieron eliminar las listas M3U"
        }
    }

    // =====================================
    // XTREAM CODES
    // =====================================

    @Synchronized
    fun saveXtreamAccount(
        context: Context,
        account: XtreamAccount
    ) {

        require(account.server.isNotBlank()) {
            "Falta el servidor Xtream"
        }

        require(account.username.isNotBlank()) {
            "Falta el usuario Xtream"
        }

        require(account.password.isNotBlank()) {
            "Falta la contraseña Xtream"
        }

        val json = JSONObject()
            .put(
                "server",
                account.server.trim().trimEnd('/')
            )
            .put("username", account.username.trim())
            .put("password", account.password)

        writeEncryptedValue(
            context,
            PREF_XTREAM,
            json.toString()
        )
    }

    @Synchronized
    fun getXtreamAccount(
        context: Context
    ): XtreamAccount? {

        val jsonText =
            readEncryptedValue(context, PREF_XTREAM)
                ?: return null

        return try {

            val json = JSONObject(jsonText)

            val server =
                json.optString("server").trim().trimEnd('/')

            val username =
                json.optString("username").trim()

            val password =
                json.optString("password")

            if (
                server.isBlank() ||
                username.isBlank() ||
                password.isBlank()
            ) {
                null
            } else {
                XtreamAccount(
                    server,
                    username,
                    password
                )
            }

        } catch (_: Exception) {

            preferences(context)
                .edit()
                .remove(PREF_XTREAM)
                .apply()

            null
        }
    }

    @Synchronized
    fun removeXtreamAccount(context: Context) {

        check(
            preferences(context)
                .edit()
                .remove(PREF_XTREAM)
                .commit()
        ) {
            "No se pudieron eliminar las credenciales Xtream"
        }
    }

    // =====================================
    // BORRAR TODOS LOS DATOS
    // =====================================

    @Synchronized
    fun clearAll(context: Context) {

        check(
            preferences(context)
                .edit()
                .remove(PREF_M3U)
                .remove(PREF_XTREAM)
                .commit()
        ) {
            "No se pudieron borrar los datos guardados"
        }
    }
}

