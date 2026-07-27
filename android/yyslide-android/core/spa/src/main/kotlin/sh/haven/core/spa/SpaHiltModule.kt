package sh.haven.core.spa

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SpaHiltModule {
    @Binds
    @Singleton
    abstract fun bindSpaSender(impl: DefaultSpaSender): SpaSender
}
