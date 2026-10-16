package com.example.virtualmouse

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Аудио-движок синтеза звуков интерфейса (порт Web Audio API из HTML).
 * Генерирует звуки клавиш (beep) и механического колёсика (wheelTick)
 * с нулевой задержкой через SoundPool.
 */
class SoundManager(private val context: Context, private val isSoundEnabled: () -> Boolean) {

    companion object {
        private const val SAMPLE_RATE = 44100
        @Volatile
        private var instance: SoundManager? = null

        fun get(context: Context, isSoundEnabled: () -> Boolean): SoundManager {
            return instance ?: synchronized(this) {
                instance ?: SoundManager(context.applicationContext, isSoundEnabled).also { instance = it }
            }
        }
    }

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val soundIds = HashMap<Int, Int>()
    private var wheelFlip = false
    private val executor = Executors.newSingleThreadExecutor()

    init {
        executor.execute {
            try {
                // Пресеты клавиш разных частот
                loadBeep(120) // Низкий (правый клик, отмена)
                loadBeep(180) // Стандартный (нажатие)
                loadBeep(220) // Модификаторы, удержание
                loadBeep(260) // Буквы / цифры
                loadBeep(340) // Переключение слоёв
                loadBeep(420) // Эмодзи, спецсимволы
                loadBeep(500) // Фиксация ЛКМ, BiDi

                // Колёсико: два чередующихся щелчка
                loadWheelTick(1.0f, 1)
                loadWheelTick(0.93f, 2)
            } catch (_: Exception) {
            }
        }
    }

    private fun loadBeep(freq: Int) {
        val pcm = renderBeepPcm(freq.toFloat())
        val wavFile = File(context.cacheDir, "beep_$freq.wav")
        writeWav(wavFile, pcm, SAMPLE_RATE)
        val id = soundPool.load(wavFile.absolutePath, 1)
        synchronized(soundIds) { soundIds[freq] = id }
    }

    private fun loadWheelTick(k: Float, idx: Int) {
        val pcm = renderWheelTickPcm(k)
        val wavFile = File(context.cacheDir, "wheel_$idx.wav")
        writeWav(wavFile, pcm, SAMPLE_RATE)
        val id = soundPool.load(wavFile.absolutePath, 1)
        synchronized(soundIds) { soundIds[1000 + idx] = id }
    }

    /**
     * Мягкий глухой «тук» с тёплым телом и лёгким отзвуком.
     * f: условная частота действия (120..500)
     */
    fun beep(f: Int = 180) {
        if (!isSoundEnabled()) return
        val targetKey = when {
            f <= 140 -> 120
            f <= 200 -> 180
            f <= 240 -> 220
            f <= 300 -> 260
            f <= 380 -> 340
            f <= 460 -> 420
            else -> 500
        }
        val id = synchronized(soundIds) { soundIds[targetKey] ?: soundIds[180] } ?: return
        soundPool.play(id, 0.75f, 0.75f, 1, 0, 1.0f)
    }

    /**
     * Сухой механический щелчок храповика колёсика с чередованием тональности.
     */
    fun wheelTick() {
        if (!isSoundEnabled()) return
        wheelFlip = !wheelFlip
        val key = if (wheelFlip) 1001 else 1002
        val id = synchronized(soundIds) { soundIds[key] } ?: return
        soundPool.play(id, 0.85f, 0.85f, 1, 0, 1.0f)
    }

    private fun renderBeepPcm(f: Float): ShortArray {
        val dur = 0.11f
        val numSamples = (SAMPLE_RATE * dur).toInt()
        val out = ShortArray(numSamples)
        val base = (190f + f * 0.32f)

        var phase1 = 0.0
        var phase2 = 0.0

        for (i in 0 until numSamples) {
            val t = i.toFloat() / SAMPLE_RATE

            // 1. Тело: синусоида со скольжением частоты от base*1.9 к base
            val f1 = base * 1.9f - (base * 0.9f) * min(1f, t / 0.06f)
            phase1 += 2.0 * PI * f1 / SAMPLE_RATE
            val sine = sin(phase1).toFloat()
            val env1 = if (t < 0.003f) (t / 0.003f) * 0.30f else 0.30f * exp(-(t - 0.003f) * 28f)

            // 2. Треугольный мягкий обертон от base*3.8 к base*2.4
            val f2 = base * 3.8f - (base * 1.4f) * min(1f, t / 0.04f)
            phase2 += 2.0 * PI * f2 / SAMPLE_RATE
            val triangle = (2.0 / PI * kotlin.math.asin(sin(phase2).coerceIn(-1.0, 1.0))).toFloat()
            val env2 = if (t < 0.002f) (t / 0.002f) * 0.05f else 0.05f * exp(-(t - 0.002f) * 60f)

            // 3. Шумовой контакт в начале (первые 6 мс)
            val noise = if (t < 0.006f) {
                val decay = (1f - t / 0.006f).pow(2)
                (Random.nextFloat() * 2f - 1f) * decay * 0.10f
            } else 0f

            val sample = (sine * env1 + triangle * env2 + noise).coerceIn(-1f, 1f)
            out[i] = (sample * 32767).toInt().toShort()
        }
        return out
    }

    private fun renderWheelTickPcm(k: Float): ShortArray {
        val dur = 0.035f
        val numSamples = (SAMPLE_RATE * dur).toInt()
        val out = ShortArray(numSamples)

        var squarePhase = 0.0

        for (i in 0 until numSamples) {
            val t = i.toFloat() / SAMPLE_RATE

            // 1. Резкий щелчок зубца (0..4 мс)
            val noise1 = if (t < 0.004f) {
                val decay = (1f - t / 0.004f).pow(2)
                val raw = (Random.nextFloat() * 2f - 1f)
                raw * decay * 0.55f
            } else 0f

            // 2. Пластиковый стук квадратного импульса (2..16 мс)
            var square = 0f
            if (t in 0.002f..0.016f) {
                val f = 1250f * k - (430f * k) * ((t - 0.002f) / 0.014f)
                squarePhase += 2.0 * PI * f / SAMPLE_RATE
                val sq = if (sin(squarePhase) >= 0) 1f else -1f
                val envSq = 0.035f * exp(-(t - 0.002f) * 120f)
                square = sq * envSq
            }

            // 3. Отскок пружинки (11..23 мс)
            val noise2 = if (t in 0.011f..0.023f) {
                val dt = t - 0.011f
                val decay = (1f - dt / 0.012f).pow(2)
                (Random.nextFloat() * 2f - 1f) * decay * 0.18f
            } else 0f

            val sample = (noise1 + square + noise2).coerceIn(-1f, 1f)
            out[i] = (sample * 32767).toInt().toShort()
        }
        return out
    }

    private fun writeWav(file: File, pcm: ShortArray, sampleRate: Int) {
        val byteData = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) byteData.putShort(s)
        val dataBytes = byteData.array()

        val totalDataLen = dataBytes.size + 36
        val byteRate = sampleRate * 2

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0 // PCM format
        header[20] = 1; header[21] = 0 // Mono
        header[22] = (sampleRate and 0xff).toByte()
        header[23] = ((sampleRate shr 8) and 0xff).toByte()
        header[24] = ((sampleRate shr 16) and 0xff).toByte()
        header[25] = ((sampleRate shr 24) and 0xff).toByte()
        header[26] = (byteRate and 0xff).toByte()
        header[27] = ((byteRate shr 8) and 0xff).toByte()
        header[28] = ((byteRate shr 16) and 0xff).toByte()
        header[29] = ((byteRate shr 24) and 0xff).toByte()
        header[30] = 2; header[31] = 0 // block align (1 * 16 / 8)
        header[32] = 16; header[33] = 0 // 16 bits per sample
        header[34] = 'd'.code.toByte(); header[35] = 'a'.code.toByte(); header[36] = 't'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = (dataBytes.size and 0xff).toByte()
        header[39] = ((dataBytes.size shr 8) and 0xff).toByte()
        header[40] = ((dataBytes.size shr 16) and 0xff).toByte()
        header[41] = ((dataBytes.size shr 24) and 0xff).toByte()

        FileOutputStream(file).use { fos ->
            fos.write(header)
            fos.write(dataBytes)
        }
    }
}
