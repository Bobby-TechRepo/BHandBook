package com.bsoft.bhandbook.di

import android.content.Context
import androidx.room.Room
import com.bsoft.bhandbook.core.math.DefaultMathParser
import com.bsoft.bhandbook.core.math.ExactCalculationEngine
import com.bsoft.bhandbook.core.math.MathCalculationEngine
import com.bsoft.bhandbook.core.math.MathExpressionParser
import com.bsoft.bhandbook.core.recognition.CachingRecognizer
import com.bsoft.bhandbook.core.recognition.FallbackRecognitionEngine
import com.bsoft.bhandbook.core.recognition.HandwritingMathRecognizer
import com.bsoft.bhandbook.core.recognition.MathRecognitionEngine
import com.bsoft.bhandbook.core.recognition.RecognitionCache
import com.bsoft.bhandbook.data.local.CanvasDatabase
import com.bsoft.bhandbook.data.recognition.comer.ComerRuntime
import com.bsoft.bhandbook.data.repository.FileSampleRepository
import com.bsoft.bhandbook.data.repository.RoomCanvasRepository
import com.bsoft.bhandbook.data.repository.RoomExpressionRepository
import com.bsoft.bhandbook.data.repository.RoomStrokeRepository
import com.bsoft.bhandbook.domain.repository.CanvasRepository
import com.bsoft.bhandbook.domain.repository.ExpressionRepository
import com.bsoft.bhandbook.domain.repository.SampleRepository
import com.bsoft.bhandbook.domain.repository.StrokeRepository
import com.bsoft.bhandbook.domain.solver.DefaultSolutionEngine
import com.bsoft.bhandbook.domain.solver.MathSolutionEngine
import com.bsoft.bhandbook.domain.usecase.ProcessExpressionUseCase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class DefaultDispatcher
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun canvasRepository(impl: RoomCanvasRepository): CanvasRepository
    @Binds abstract fun strokeRepository(impl: RoomStrokeRepository): StrokeRepository
    @Binds abstract fun expressionRepository(impl: RoomExpressionRepository): ExpressionRepository
    @Binds abstract fun sampleRepository(impl: FileSampleRepository): SampleRepository
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun database(@ApplicationContext c: Context): CanvasDatabase =
        Room.databaseBuilder(c, CanvasDatabase::class.java, "infinite-canvas.db").build()

    @Provides @DefaultDispatcher fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
    @Provides @IoDispatcher fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides @Singleton fun parser(): MathExpressionParser = DefaultMathParser()
    @Provides @Singleton fun calculator(): MathCalculationEngine = ExactCalculationEngine()
    @Provides @Singleton fun solver(): MathSolutionEngine = DefaultSolutionEngine()
    @Provides @Singleton fun graphPanels(@ApplicationContext context: android.content.Context): com.bsoft.bhandbook.domain.repository.GraphPanelRepository = com.bsoft.bhandbook.data.repository.PrefsGraphPanelRepository(context)
    @Provides @Singleton fun insights(p: MathExpressionParser, s: MathSolutionEngine) = com.bsoft.bhandbook.domain.editor.CanvasInsights(p, s)
    /**
     * CoMER (offline, on-device) is the primary recogniser; the built-in geometric recogniser is only a fallback for a missing or failing
     * model. The cache makes re-recognising identical ink (undo, move, reopen) free.
     */
    @Provides @Singleton fun recognizer(comer: ComerRuntime): MathRecognitionEngine =
        CachingRecognizer(
            FallbackRecognitionEngine(primary = { comer.engine() }, fallback = HandwritingMathRecognizer(), onFallback = { android.util.Log.w("Recognition", it) }),
            RecognitionCache(256),
        )

    @Provides @Singleton fun processExpression(r: MathRecognitionEngine, p: MathExpressionParser, c: MathCalculationEngine, s: MathSolutionEngine) =
        ProcessExpressionUseCase(r, p, c, s)
}
