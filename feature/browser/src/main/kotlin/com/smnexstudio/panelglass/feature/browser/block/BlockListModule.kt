package com.smnexstudio.panelglass.feature.browser.block

import com.smnexstudio.panelglass.core.data.block.BlockListUpdater
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Lets Settings ask for fresh block lists without depending on the reader's module. */
@Module
@InstallIn(SingletonComponent::class)
abstract class BlockListModule {
    @Binds
    abstract fun updater(repo: BlockListRepository): BlockListUpdater
}
