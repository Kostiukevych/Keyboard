package com.example.virtualmouse

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Надежный аудио-движок синтеза звуков интерфейса (порт Web Audio API из HTML).
 * - Использует SoundPool с USAGE_MEDIA (всегда слышно через медиа-канал)
 * - Отслеживает OnLoadCompleteListener и статус готовности каждого звука
 * - Мгновенный AudioTrack fallback для звуков, пока SoundPool завершает загрузку
 */
class SoundManager(private val context: Context, private val isSoundEnabled: () -> Boolean) {

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val PREFS_NAME = "virtual_mouse_prefs"
        private const val KEY_SOUND = "soundOn"

        @Volatile
        private var instance: SoundManager? = null

        /**
         * Включает/выключает звук немедленно и для всех (оверлей, клавиатура, настройки).
         * Пишет прямо в SharedPreferences, поэтому сразу видно каждому, кто спрашивает.
         */
        fun setEnabled(context: Context, on: Boolean) {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SOUND, on).apply()
        }

        fun get(context: Context, isSoundEnabled: () -> Boolean): SoundManager {
            return instance ?: synchronized(this) {
                instance ?: SoundManager(context.applicationContext, isSoundEnabled).also { instance = it }
            }
        }
    }

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(10)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val soundIds = ConcurrentHashMap<Int, Int>()
    private val readySoundIds = CopyOnWriteArraySet<Int>()
    private val rawPcmCache = ConcurrentHashMap<Int, ShortArray>()
    private var wheelFlip = false
    private val executor = Executors.newSingleThreadExecutor()

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                readySoundIds.add(sampleId)
            }
        }

        executor.execute {
            try {
                // Пресеты клавиш разных частот
                listOf(120, 180, 220, 260, 340, 420, 500).forEach { freq ->
                    val pcm = renderBeepPcm(freq.toFloat())
                    rawPcmCache[freq] = pcm
                    val file = File(context.cacheDir, "beep_$freq.wav")
                    writeWav(file, pcm, SAMPLE_RATE)
                    val id = soundPool.load(file.absolutePath, 1)
                    soundIds[freq] = id
                }

                // Пресеты колёсика (два чередующихся щелчка)
                val pcm1 = renderWheelTickPcm(1.0f)
                val pcm2 = renderWheelTickPcm(0.93f)
                rawPcmCache[1001] = pcm1
                rawPcmCache[1002] = pcm2

                val file1 = File(context.cacheDir, "wheel_1.wav")
                writeWav(file1, pcm1, SAMPLE_RATE)
                soundIds[1001] = soundPool.load(file1.absolutePath, 1)

                val file2 = File(context.cacheDir, "wheel_2.wav")
                writeWav(file2, pcm2, SAMPLE_RATE)
                soundIds[1002] = soundPool.load(file2.absolutePath, 1)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Мягкий глухой «тук» с тёплым телом и лёгким отзвуком.
     * f: условная частота действия (120..500)
     */
    fun beep(f: Int = 180) {
        if (!enabledNow()) return
        val targetKey = when {
            f <= 140 -> 120
            f <= 200 -> 180
            f <= 240 -> 220
            f <= 300 -> 260
            f <= 380 -> 340
            f <= 460 -> 420
            else -> 500
        }
        playSound(targetKey, 0.85f)
    }

    /**
     * Сухой механический щелчок храповика колёсика с чередованием тональности.
     */
    fun wheelTick() {
        if (!enabledNow()) return
        wheelFlip = !wheelFlip
        val key = if (wheelFlip) 1001 else 1002
        playSound(key, 0.95f)
    }

    /**
     * Источник правды — SharedPreferences, читаются при каждом звуке (это кэш в памяти, дёшево).
     * Раньше решение принимала лямбда того, кто первым создал SoundManager (например клавиатура
     * со своей копией настроек), и она никогда не обновлялась — поэтому звук не выключался.
     * Лямбда осталась только как значение по умолчанию, пока ключа в настройках нет.
     */
    private fun enabledNow(): Boolean = try {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SOUND, isSoundEnabled())
    } catch (_: Exception) {
        isSoundEnabled()
    }

    private fun playSound(key: Int, volume: Float) {
        val id = soundIds[key]
        if (id != null && readySoundIds.contains(id)) {
            soundPool.play(id, volume, volume, 1, 0, 1.0f)
        } else {
            // Если SoundPool ещё не завершил декодирование в фоне, воспроизводим PCM напрямую
            val pcm = rawPcmCache[key] ?: return
            playDirectPcm(pcm, volume)
        }
    }

    private fun playDirectPcm(pcm: ShortArray, volume: Float) {
        executor.execute {
            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufSize = maxOf(minBuf, pcm.size * 2)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(pcm, 0, pcm.size)
                track.setVolume(volume)
                track.play()
                track.setNotificationMarkerPosition(pcm.size)
                track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(t: AudioTrack?) {
                        try {
                            t?.stop()
                            t?.release()
                        } catch (_: Exception) {}
                    }
                    override fun onPeriodicNotification(t: AudioTrack?) {}
                })
            } catch (_: Exception) {
            }
        }
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
            val env1 = if (t < 0.003f) (t / 0.003f) * 0.35f else 0.35f * exp(-(t - 0.003f) * 26f)

            // 2. Треугольный мягкий обертон от base*3.8 к base*2.4
            val f2 = base * 3.8f - (base * 1.4f) * min(1f, t / 0.04f)
            phase2 += 2.0 * PI * f2 / SAMPLE_RATE
            val triangle = (2.0 / PI * kotlin.math.asin(sin(phase2).coerceIn(-1.0, 1.0))).toFloat()
            val env2 = if (t < 0.002f) (t / 0.002f) * 0.08f else 0.08f * exp(-(t - 0.002f) * 55f)

            // 3. Шумовой контакт в начале (первые 6 мс)
            val noise = if (t < 0.006f) {
                val decay = (1f - t / 0.006f).pow(2)
                (Random.nextFloat() * 2f - 1f) * decay * 0.12f
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
                raw * decay * 0.65f
            } else 0f

            // 2. Пластиковый стук квадратного импульса (2..16 мс)
            var square = 0f
            if (t in 0.002f..0.016f) {
                val f = 1250f * k - (430f * k) * ((t - 0.002f) / 0.014f)
                squarePhase += 2.0 * PI * f / SAMPLE_RATE
                val sq = if (sin(squarePhase) >= 0) 1f else -1f
                val envSq = 0.05f * exp(-(t - 0.002f) * 110f)
                square = sq * envSq
            }

            // 3. Отскок пружинки (11..23 мс)
            val noise2 = if (t in 0.011f..0.023f) {
                val dt = t - 0.011f
                val decay = (1f - dt / 0.012f).pow(2)
                (Random.nextFloat() * 2f - 1f) * decay * 0.22f
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
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0 // PCM
        header[20] = 1; header[21] = 0 // Mono
        header[22] = (sampleRate and 0xff).toByte()
        header[23] = ((sampleRate shr 8) and 0xff).toByte()
        header[24] = ((sampleRate shr 16) and 0xff).toByte()
        header[25] = ((sampleRate shr 24) and 0xff).toByte()
        header[26] = (byteRate and 0xff).toByte()
        header[27] = ((byteRate shr 8) and 0xff).toByte()
        header[28] = ((byteRate shr 16) and 0xff).toByte()
        header[29] = ((byteRate shr 24) and 0xff).toByte()
        header[30] = 2; header[31] = 0
        header[32] = 16; header[33] = 0
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
