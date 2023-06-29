package com.example.rvaudiorecord

import com.example.rvaudiorecord.R
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.speech.v1.*
import com.google.protobuf.ByteString
import com.google.api.gax.core.FixedCredentialsProvider
import com.google.api.gax.rpc.ApiStreamObserver
import com.google.api.gax.rpc.BidiStream
import com.google.api.gax.rpc.BidiStreamingCallable

import java.io.IOException
import kotlin.experimental.and

class AudioCaptureService : Service() {

    private lateinit var audioRecord: AudioRecord
    private var isRecording = false

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recognitionListener: RecognitionListener

    companion object {
        private const val TAG = "AudioCaptureService"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAudioCapture()
        return START_STICKY
    }

    override fun onDestroy() {
        stopAudioCapture()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun startAudioCapture() {
        isRecording = true

        // Calcular el tamaño del búfer de audio
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        val buffer = ShortArray(bufferSize)

        // Inicializar el objeto AudioRecord para capturar audio
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        // Iniciar la captura de audio en un hilo separado
        Thread {
            audioRecord.startRecording()
            processCapturedAudio(buffer, bufferSize)
        }.start()
    }

    private fun stopAudioCapture() {
        isRecording = false
        audioRecord.stop()
        audioRecord.release()
    }


    private fun processCapturedAudio(buffer: ShortArray, bufferSize: Int) {
        try {
            // Crear las credenciales de Google Cloud a partir del archivo JSON de las credenciales
            val credentials = GoogleCredentials.fromStream(resources.openRawResource(R.raw.speechtotext))

            // Crear el cliente de Speech-to-Text de Google Cloud
            val speechClientSettings = SpeechSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                .build()
            val speechClient = SpeechClient.create(speechClientSettings)

            // Configurar el reconocimiento de voz
            val recognitionConfig = RecognitionConfig.newBuilder()
                .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
                .setSampleRateHertz(SAMPLE_RATE)
                .setLanguageCode("es-MX")
                .build()

            // Crear una instancia de ApiStreamObserver para recibir los resultados del reconocimiento
            val responseObserver = object : ApiStreamObserver<StreamingRecognizeResponse> {
                override fun onNext(response: StreamingRecognizeResponse) {
                    // Procesar los resultados del reconocimiento de voz
                    for (result in response.resultsList) {
                        for (alternative in result.alternativesList) {
                            val transcript = alternative.transcript
                            Log.d(TAG, "Resultado del reconocimiento de voz: $transcript")
                        }
                    }
                }

                override fun onError(throwable: Throwable) {
                    Log.e(TAG, "Error en el reconocimiento de voz: ${throwable.message}")
                }

                override fun onCompleted() {
                    // El reconocimiento ha finalizado
                }
            }

            // Crear el stream de reconocimiento de voz
            val requestObserver = speechClient.streamingRecognizeCallable().bidiStreamingCall(responseObserver)

            // Enviar los datos de audio al stream de reconocimiento de voz
            val streamingConfig = StreamingRecognitionConfig.newBuilder()
                .setConfig(recognitionConfig)
                .build()
            val streamingRequest = StreamingRecognizeRequest.newBuilder()
                .setStreamingConfig(streamingConfig)
                .build()
            requestObserver.onNext(streamingRequest)

            // Procesar el audio capturado en tiempo real
            while (isRecording) {
                val bytesRead = audioRecord.read(buffer, 0, bufferSize)
                if (bytesRead != AudioRecord.ERROR_INVALID_OPERATION && bytesRead != AudioRecord.ERROR_BAD_VALUE) {
                    val audioData = ByteString.copyFrom(convertShortArrayToByteArray(buffer, bytesRead), 0, bytesRead * 2)
                    val streamingContent = StreamingRecognizeRequest.newBuilder()
                        .setAudioContent(audioData)
                        .build()
                    requestObserver.onNext(streamingContent)
                }
            }

            // Finalizar el stream de reconocimiento de voz
            requestObserver.onCompleted()

            // Cerrar el cliente de Speech-to-Text
            speechClient.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error en el reconocimiento de voz: ${e.message}")
        }
    }

    private fun convertShortArrayToByteArray(shortArray: ShortArray, size: Int): ByteArray {
        val byteArray = ByteArray(size * 2)
        for (i in 0 until size) {
            val shortValue = shortArray[i]
            byteArray[i * 2] = (shortValue and 0xFF).toByte()
            byteArray[i * 2 + 1] = (shortValue.toInt() shr 8 and 0xFF).toByte()
        }
        return byteArray
    }



    private fun createSpeechClient(): SpeechClient {
        val credentials = GoogleCredentials.fromStream(resources.openRawResource(R.raw.speechtotext))
        val speechClientSettings = SpeechSettings.newBuilder()
            .setCredentialsProvider { credentials }
            .build()
        return SpeechClient.create(speechClientSettings)
    }

    private fun processRecognitionResults(results: List<SpeechRecognitionResult>) {
        // Procesar los resultados del reconocimiento de voz
        for (result in results) {
            val alternatives = result.alternativesList.toList()
            for (alternative in alternatives) {
                val transcript = alternative.transcript
                Log.d(TAG, "Resultado del reconocimiento de voz: $transcript")
            }
        }
    }
}
