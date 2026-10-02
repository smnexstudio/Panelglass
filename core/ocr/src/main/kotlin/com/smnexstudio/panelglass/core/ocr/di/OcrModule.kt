package com.smnexstudio.panelglass.core.ocr.di

import com.smnexstudio.panelglass.core.ocr.TextDetector
import com.smnexstudio.panelglass.core.ocr.Inpainter
import com.smnexstudio.panelglass.core.ocr.LamaInpainter
import com.smnexstudio.panelglass.core.ocr.ComicTextDetector
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class OcrModule {
    @Binds abstract fun textDetector(impl: ComicTextDetector): TextDetector
    @Binds abstract fun inpainter(impl: LamaInpainter): Inpainter
}
