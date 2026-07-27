package sh.haven.core.knock

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class KnockHiltModule {
    @Binds
    @Singleton
    abstract fun bindPortKnocker(impl: DefaultPortKnocker): PortKnocker
}
