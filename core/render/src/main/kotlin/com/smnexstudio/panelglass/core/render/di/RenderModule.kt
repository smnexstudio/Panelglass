package com.smnexstudio.panelglass.core.render.di

import com.smnexstudio.panelglass.core.render.TextEraser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RenderModule {
    @Provides @Singleton fun eraser(): TextEraser = TextEraser()
}
