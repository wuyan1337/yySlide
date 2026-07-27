package sh.haven.core.scan

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt bindings for the scan module. Keeps the production [TextRecognizer.TessEngineFactory]
 * loose from any consumer-side wiring — tests can override via a TestInstallIn
 * if they need to provide a fake engine.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ScanModule {

    @Binds
    abstract fun bindTessEngineFactory(impl: DefaultTessEngineFactory): TextRecognizer.TessEngineFactory
}
