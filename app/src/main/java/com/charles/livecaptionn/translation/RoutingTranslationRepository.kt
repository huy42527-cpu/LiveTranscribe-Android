package com.charles.livecaptionn.translation

import java.util.logging.Logger
import com.charles.livecaptionn.data.SettingsRepository
import com.charles.livecaptionn.settings.TranslationBackend
import kotlinx.coroutines.flow.first

/**
 * Dispatches each translation call to the backend selected in user settings.
 *
 * Caches the active backend so we don't read settings flow on every call.
 * Settings changes are rare relative to translation call frequency, so a
 * lazy fresh-on-mismatch strategy avoids coroutine overhead per call.
 */
class RoutingTranslationRepository(
    private val settingsRepository: SettingsRepository,
    private val mlKit: TranslationRepository,
    private val libre: TranslationRepository,
    private val googleWeb: TranslationRepository
) : TranslationRepository {

    private var cachedBackend: TranslationBackend? = null
    private var cachedDelegate: TranslationRepository? = null

    override suspend fun translate(
        text: String,
        sourceCode: String,
        targetCode: String,
        autoDetect: Boolean
    ): String {
        val backend = settingsRepository.settingsFlow.first().translationBackend
        val delegate = activeDelegate(backend)
        logger.fine("Dispatching translation: backend=$backend, source=$sourceCode, target=$targetCode, autoDetect=$autoDetect.")
        return try {
            delegate.translate(text, sourceCode, targetCode, autoDetect).also { result ->
                logger.fine("Translation backend completed: backend=$backend, blank=${result.isBlank()}, resultLength=${result.length}.")
            }
        } catch (error: Exception) {
            logger.warning("Translation backend threw: backend=$backend, error=${error.javaClass.simpleName}.")
            throw error
        }
    }

    override suspend fun prewarm(sourceCode: String, targetCode: String) {
        val backend = settingsRepository.settingsFlow.first().translationBackend
        logger.fine("Prewarming translation backend: backend=$backend.")
        activeDelegate(backend).prewarm(sourceCode, targetCode)
    }

    private suspend fun activeDelegate(backend: TranslationBackend): TranslationRepository {
        if (cachedBackend == backend && cachedDelegate != null) {
            return cachedDelegate!!
        }
        val delegate = when (backend) {
            TranslationBackend.ML_KIT -> mlKit
            TranslationBackend.LIBRE_TRANSLATE -> libre
            TranslationBackend.GOOGLE_WEB -> googleWeb
        }
        cachedBackend = backend
        cachedDelegate = delegate
        logger.fine("Active translation backend changed: backend=$backend.")
        return delegate
    }

    override fun close() {
        mlKit.close()
        libre.close()
        googleWeb.close()
    }

    private companion object {
        val logger: Logger = Logger.getLogger("TranslationRouter")
    }
}
