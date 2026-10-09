package fr.omronbridge.app

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.FileProvider
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * v1.6 streamlined hybrid workflow.
 *
 * Live CameraX is only an acquisition aid. Every final value coming from the
 * live mode is re-read from a real still JPEG using exactly the same automatic
 * locator + projective warp + template reader as the validated v1.4 photo mode.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var mainScroll: ScrollView
    private lateinit var homePanel: View
    private lateinit var livePanel: View
    private lateinit var photoPanel: View
    private lateinit var confirmPanel: View

    private lateinit var previewView: PreviewView
    private lateinit var textSysLive: TextView
    private lateinit var textDiaLive: TextView
    private lateinit var textPulseLive: TextView
    private lateinit var textLiveStatus: TextView
    private lateinit var buttonCaptureNow: Button

    private lateinit var cropView: QuadCropView
    private lateinit var buttonAnalyze: Button
    private lateinit var textPhotoHint: TextView

    private lateinit var textDetectedOriginal: TextView
    private lateinit var editSystolic: EditText
    private lateinit var editDiastolic: EditText
    private lateinit var editPulse: EditText
    private lateinit var textMeasurementTime: TextView
    private lateinit var textStatus: TextView
    private lateinit var buttonSave: Button
    private lateinit var buttonNewMeasurement: Button

    private val healthWriter by lazy { HealthConnectWriter(this) }
    private val autoLocator = AutoOmronLocator()
    private val pipelineMutex = Mutex()

    private var currentPhotoBitmap: Bitmap? = null
    private var pendingCaptureUri: Uri? = null
    private var measurementDateTime: LocalDateTime = LocalDateTime.now()
    private var pendingSave: Triple<Int, Int, Int>? = null
    private var confirmationCameFromPhoto = false

    // CameraX live acquisition.
    private var cameraController: LifecycleCameraController? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val analyzingLive = AtomicBoolean(false)
    private var liveScanning = false
    private var capturingFinal = false
    private var lastLiveFrameStartedAt = 0L

    private val sysConsensus = TemporalConsensus(window = 5, required = 3)
    private val diaConsensus = TemporalConsensus(window = 5, required = 3)
    private val pulseConsensus = TemporalConsensus(window = 5, required = 3)
    private var lastCompleteTriple: Triple<Int, Int, Int>? = null
    private var completeTripleStreak = 0
    private var stableLiveTriple: Triple<Int, Int, Int>? = null

    private data class AnalysisResult(
        val reading: OmronTemplateReader.ScreenReading,
        val located: AutoOmronLocator.Result,
    )

    private val requiredHealthPermissions by lazy {
        setOf(
            HealthPermission.getWritePermission(BloodPressureRecord::class),
            HealthPermission.getWritePermission(HeartRateRecord::class),
        )
    }

    private val requestHealthPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        if (granted.containsAll(requiredHealthPermissions)) {
            val values = pendingSave
            pendingSave = null
            if (values != null) writeToHealthConnect(values)
        } else {
            pendingSave = null
            buttonSave.isEnabled = true
            setStatus("Autorisation Santé Connect refusée. Aucune donnée n'a été enregistrée.")
        }
    }

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            showLivePanel()
            startLiveCamera()
        } else {
            showHome()
            setStatus("Caméra non autorisée. Photo, galerie et saisie manuelle restent disponibles.")
        }
    }

    private val takePicture = registerForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingCaptureUri
        pendingCaptureUri = null
        if (success && uri != null) {
            loadPhoto(uri)
        } else {
            setStatus("Prise de photo annulée.")
        }
    }

    private val choosePicture = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) loadPhoto(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val mainContent = findViewById<View>(R.id.mainContent)
        val density = resources.displayMetrics.density
        val horizontal = (16 * density).roundToInt()
        val topBase = (12 * density).roundToInt()
        val bottomBase = (16 * density).roundToInt()
        ViewCompat.setOnApplyWindowInsetsListener(mainContent) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(horizontal, topBase + bars.top, horizontal, bottomBase + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(mainContent)

        mainScroll = findViewById(R.id.mainScroll)
        homePanel = findViewById(R.id.homePanel)
        livePanel = findViewById(R.id.livePanel)
        photoPanel = findViewById(R.id.photoPanel)
        confirmPanel = findViewById(R.id.confirmPanel)

        previewView = findViewById(R.id.previewView)
        textSysLive = findViewById(R.id.textSysLive)
        textDiaLive = findViewById(R.id.textDiaLive)
        textPulseLive = findViewById(R.id.textPulseLive)
        textLiveStatus = findViewById(R.id.textLiveStatus)
        buttonCaptureNow = findViewById(R.id.buttonCaptureNow)

        cropView = findViewById(R.id.cropView)
        buttonAnalyze = findViewById(R.id.buttonAnalyze)
        textPhotoHint = findViewById(R.id.textPhotoHint)

        textDetectedOriginal = findViewById(R.id.textDetectedOriginal)
        editSystolic = findViewById(R.id.editSystolic)
        editDiastolic = findViewById(R.id.editDiastolic)
        editPulse = findViewById(R.id.editPulse)
        textMeasurementTime = findViewById(R.id.textMeasurementTime)
        textStatus = findViewById(R.id.textStatus)
        buttonSave = findViewById(R.id.buttonSave)
        buttonNewMeasurement = findViewById(R.id.buttonNewMeasurement)

        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        findViewById<Button>(R.id.buttonLive).setOnClickListener { launchLiveMode() }
        findViewById<Button>(R.id.buttonTakePhoto).setOnClickListener { launchCameraPhoto() }
        findViewById<Button>(R.id.buttonChoosePhoto).setOnClickListener { choosePicture.launch("image/*") }
        findViewById<Button>(R.id.buttonManual).setOnClickListener {
            stopLiveCamera()
            confirmationCameFromPhoto = false
            showConfirmationNullable(
                Triple(null, null, null),
                "Saisie manuelle : vérifiez les trois valeurs avant l'enregistrement.",
            )
        }

        buttonCaptureNow.setOnClickListener { captureHighResolution(autoTriggered = false) }
        findViewById<Button>(R.id.buttonLiveGallery).setOnClickListener {
            stopLiveCamera()
            choosePicture.launch("image/*")
        }
        findViewById<Button>(R.id.buttonLiveManual).setOnClickListener {
            stopLiveCamera()
            confirmationCameFromPhoto = false
            showConfirmationNullable(
                Triple(null, null, null),
                "Saisie manuelle : vérifiez les trois valeurs avant l'enregistrement.",
            )
        }
        findViewById<Button>(R.id.buttonLiveBack).setOnClickListener { showHome() }

        findViewById<Button>(R.id.buttonPhotoAgain).setOnClickListener { resumeDefaultAcquisition() }
        buttonAnalyze.setOnClickListener { autoLocateAndAnalyze() }

        findViewById<Button>(R.id.buttonBackFromConfirm).setOnClickListener {
            resumeDefaultAcquisition()
        }
        findViewById<Button>(R.id.buttonChangeTime).setOnClickListener { chooseMeasurementDateTime() }
        buttonSave.setOnClickListener { validateAndSave() }
        buttonNewMeasurement.setOnClickListener { startNewMeasurement() }
        findViewById<Button>(R.id.buttonOpenHealthConnect).setOnClickListener { openHealthConnectSettings() }

        updateTimeLabel()

        when (healthWriter.sdkStatus()) {
            HealthConnectClient.SDK_AVAILABLE -> setStatus("Prêt — visez l'écran de l'Omron.")
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                setStatus("Santé Connect doit être installé ou mis à jour avant l'enregistrement.")
            else -> setStatus("Santé Connect n'est pas disponible sur cet appareil.")
        }

        if (hasCameraPermission()) {
            showLivePanel()
        } else {
            showHome()
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStart() {
        super.onStart()
        if (::livePanel.isInitialized && livePanel.visibility == View.VISIBLE && hasCameraPermission()) {
            startLiveCamera()
        }
    }

    override fun onStop() {
        if (::livePanel.isInitialized && livePanel.visibility == View.VISIBLE) {
            stopLiveCamera()
        }
        super.onStop()
    }

    // ---------------------------------------------------------------------
    // Live CameraX mode
    // ---------------------------------------------------------------------

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun launchLiveMode() {
        if (!hasCameraPermission()) {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        showLivePanel()
        startLiveCamera()
    }

    private fun ensureCameraController(): LifecycleCameraController {
        cameraController?.let { return it }

        val analysisResolution = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1280, 960),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()

        return LifecycleCameraController(this).also { controller ->
            controller.cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            controller.setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
            controller.setImageAnalysisOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            controller.setImageAnalysisBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            controller.setImageAnalysisResolutionSelector(analysisResolution)
            controller.setImageCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            // A flash aimed at the LCD is more likely to create a reflection than help.
            controller.setImageCaptureFlashMode(ImageCapture.FLASH_MODE_OFF)
            controller.setImageAnalysisAnalyzer(cameraExecutor) { image -> analyzeCameraFrame(image) }
            cameraController = controller
        }
    }

    private fun startLiveCamera() {
        if (!hasCameraPermission() || livePanel.visibility != View.VISIBLE) return
        try {
            val controller = ensureCameraController()
            previewView.controller = controller
            controller.bindToLifecycle(this)
            liveScanning = true
            capturingFinal = false
            buttonCaptureNow.isEnabled = true
            textLiveStatus.text = "Visez l'écran : capture automatique dès que la lecture est stable."
        } catch (e: Exception) {
            liveScanning = false
            textLiveStatus.text = "Impossible de démarrer CameraX : ${e.message ?: e.javaClass.simpleName}"
            setStatus(textLiveStatus.text.toString())
        }
    }

    private fun stopLiveCamera() {
        liveScanning = false
        try {
            cameraController?.unbind()
        } catch (_: Exception) {
        }
    }

    private fun analyzeCameraFrame(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (
            !liveScanning || capturingFinal ||
            now - lastLiveFrameStartedAt < LIVE_ANALYSIS_INTERVAL_MS ||
            !analyzingLive.compareAndSet(false, true)
        ) {
            image.close()
            return
        }
        lastLiveFrameStartedAt = now

        val bitmap = try {
            val raw = image.toBitmap()
            rotateBitmapIfNeeded(raw, image.imageInfo.rotationDegrees)
        } catch (e: Exception) {
            image.close()
            analyzingLive.set(false)
            return
        }
        image.close()

        lifecycleScope.launch {
            try {
                val result = analyzeBitmap(bitmap)
                if (liveScanning && !capturingFinal) updateLiveReading(result.reading)
            } catch (_: Exception) {
                if (liveScanning && !capturingFinal) updateLiveFailure()
            } finally {
                bitmap.recycle()
                analyzingLive.set(false)
            }
        }
    }

    private fun rotateBitmapIfNeeded(source: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees % 360 == 0) return source
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) source.recycle()
        return rotated
    }

    private fun updateLiveReading(reading: OmronTemplateReader.ScreenReading) {
        val sys = sysConsensus.push(reading.sys.value)
        val dia = diaConsensus.push(reading.dia.value)
        val pulse = pulseConsensus.push(reading.pulse.value)

        textSysLive.text = formatLive(sys)
        textDiaLive.text = formatLive(dia)
        textPulseLive.text = formatLive(pulse)

        val complete = if (
            reading.sys.value != null && reading.dia.value != null && reading.pulse.value != null
        ) {
            Triple(reading.sys.value!!, reading.dia.value!!, reading.pulse.value!!)
        } else {
            null
        }

        if (complete != null && complete == lastCompleteTriple) {
            completeTripleStreak++
        } else if (complete != null) {
            lastCompleteTriple = complete
            completeTripleStreak = 1
        } else {
            completeTripleStreak = 0
        }

        val stable = complete != null && completeTripleStreak >= 3 && sys.stable && dia.stable && pulse.stable
        if (stable) {
            stableLiveTriple = complete
            textLiveStatus.text =
                "Lecture stable : ${complete.first} / ${complete.second}, pouls ${complete.third}. Capture de contrôle…"
            captureHighResolution(autoTriggered = true)
        } else {
            stableLiveTriple = null
            textLiveStatus.text = when {
                complete != null -> "Lecture en cours : ${complete.first} / ${complete.second}, pouls ${complete.third}…"
                sys.candidate != null || dia.candidate != null || pulse.candidate != null ->
                    "Écran trouvé. Stabilisation de la lecture…"
                else -> "Cherche SYS / DIA / PULSE… Rapprochez légèrement l'écran si nécessaire."
            }
        }
    }

    private fun updateLiveFailure() {
        val sys = sysConsensus.push(null)
        val dia = diaConsensus.push(null)
        val pulse = pulseConsensus.push(null)
        textSysLive.text = formatLive(sys)
        textDiaLive.text = formatLive(dia)
        textPulseLive.text = formatLive(pulse)
        completeTripleStreak = 0
        textLiveStatus.text = "Écran non trouvé sur cette image — ajustez légèrement le cadrage."
    }

    private fun formatLive(state: TemporalConsensus.State): String = when {
        state.candidate == null -> "--"
        state.stable -> "${state.candidate} ✓"
        else -> "${state.candidate} ?"
    }

    /**
     * Captures a real still JPEG and sends it through the validated v1.4 photo
     * pipeline. The live values are never written directly to Health Connect.
     */
    private fun captureHighResolution(autoTriggered: Boolean) {
        if (capturingFinal) return
        val controller = cameraController ?: return

        capturingFinal = true
        liveScanning = false
        buttonCaptureNow.isEnabled = false
        textLiveStatus.text = if (autoTriggered) {
            "Lecture stable — capture photo haute résolution…"
        } else {
            "Capture photo haute résolution…"
        }

        val dir = File(cacheDir, "images").apply { mkdirs() }
        val file = File(dir, "omron-live-${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()

        controller.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    lifecycleScope.launch { analyzeFinalCameraXPhoto(file) }
                }

                override fun onError(exception: ImageCaptureException) {
                    resumeLiveAfterCaptureFailure(
                        "Capture impossible : ${exception.message ?: exception.javaClass.simpleName}",
                    )
                }
            },
        )
    }

    private suspend fun analyzeFinalCameraXPhoto(file: File) {
        var bitmap: Bitmap? = null
        try {
            textLiveStatus.text = "Photo capturée — vérification haute résolution…"
            bitmap = withContext(Dispatchers.IO) { decodeBitmap(file) }
            val result = analyzeBitmap(bitmap)
            val reading = result.reading
            if (!reading.complete) {
                bitmap.recycle()
                bitmap = null
                resumeLiveAfterCaptureFailure("La photo finale n'a pas donné trois valeurs complètes. Recadrez légèrement.")
                return
            }

            stopLiveCamera()
            replaceCurrentPhoto(bitmap)
            bitmap = null
            cropView.setBitmap(currentPhotoBitmap)
            cropView.setAutoQuad(result.located.quad)

            Log.d("OmronAutoCrop", result.located.debug)
            Log.d("OmronContour", reading.sys.debug)
            Log.d("OmronContour", reading.dia.debug)
            Log.d("OmronContour", reading.pulse.debug)

            val finalValues = Triple(reading.sys.value, reading.dia.value, reading.pulse.value)
            val live = stableLiveTriple
            val description = buildString {
                append("Photo finale : ")
                append(reading.sys.value)
                append(" / ")
                append(reading.dia.value)
                append(" mmHg, pouls ")
                append(reading.pulse.value)
                append(".")
                if (live != null && (
                        live.first != reading.sys.value ||
                        live.second != reading.dia.value ||
                        live.third != reading.pulse.value
                    )
                ) {
                    append(" La lecture directe était ${live.first}/${live.second}, pouls ${live.third}; vérifiez particulièrement les valeurs.")
                } else {
                    append(" Vérifiez et corrigez si nécessaire.")
                }
            }
            confirmationCameFromPhoto = true
            capturingFinal = false
            showConfirmationNullable(finalValues, description)
            setStatus("Vérifiez les trois valeurs puis appuyez sur Enregistrer.")
        } catch (e: Exception) {
            bitmap?.recycle()
            resumeLiveAfterCaptureFailure(
                "Vérification de la photo impossible : ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    private fun resumeLiveAfterCaptureFailure(message: String) {
        capturingFinal = false
        resetLiveConsensus()
        buttonCaptureNow.isEnabled = true
        textLiveStatus.text = message
        if (livePanel.visibility == View.VISIBLE && hasCameraPermission()) {
            liveScanning = true
        }
    }

    private fun resetLiveConsensus() {
        sysConsensus.clear()
        diaConsensus.clear()
        pulseConsensus.clear()
        lastCompleteTriple = null
        completeTripleStreak = 0
        stableLiveTriple = null
        textSysLive.text = "--"
        textDiaLive.text = "--"
        textPulseLive.text = "--"
    }

    // ---------------------------------------------------------------------
    // Photo / gallery mode (validated v1.4 workflow)
    // ---------------------------------------------------------------------

    private fun launchCameraPhoto() {
        stopLiveCamera()
        try {
            val dir = File(cacheDir, "images").apply { mkdirs() }
            val file = File(dir, "omron-${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            pendingCaptureUri = uri
            takePicture.launch(uri)
        } catch (e: Exception) {
            setStatus("Impossible d'ouvrir l'appareil photo : ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun loadPhoto(uri: Uri) {
        stopLiveCamera()
        lifecycleScope.launch {
            setStatus("Chargement de la photo…")
            try {
                val bitmap = withContext(Dispatchers.IO) { decodeBitmap(uri) }
                replaceCurrentPhoto(bitmap)
                cropView.setBitmap(bitmap)
                showPhotoPanel()
                autoLocateAndAnalyze()
            } catch (e: Exception) {
                setStatus("Impossible de lire la photo : ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun replaceCurrentPhoto(bitmap: Bitmap) {
        currentPhotoBitmap?.takeIf { it !== bitmap }?.recycle()
        currentPhotoBitmap = bitmap
    }

    private fun decodeBitmap(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(contentResolver, uri)
        return decodeSource(source)
    }

    private fun decodeBitmap(file: File): Bitmap = decodeSource(ImageDecoder.createSource(file))

    private fun decodeSource(source: ImageDecoder.Source): Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val w = info.size.width
        val h = info.size.height
        val longest = max(w, h)
        val maxSide = 2400
        if (longest > maxSide) {
            val scale = maxSide.toFloat() / longest
            decoder.setTargetSize(
                (w * scale).roundToInt().coerceAtLeast(1),
                (h * scale).roundToInt().coerceAtLeast(1),
            )
        }
    }

    private suspend fun analyzeBitmap(bitmap: Bitmap): AnalysisResult = pipelineMutex.withLock {
        val located = autoLocator.locate(bitmap)
        val normalized = withContext(Dispatchers.Default) {
            OmronImagePipeline.rectify(bitmap, located.quad)
                ?: throw IllegalArgumentException("Le cadre LCD détecté n'est pas valide")
        }
        val reading = withContext(Dispatchers.Default) {
            try {
                OmronTemplateReader.readScreen(normalized)
            } finally {
                normalized.recycle()
            }
        }
        AnalysisResult(reading, located)
    }

    private fun autoLocateAndAnalyze() {
        val bitmap = currentPhotoBitmap
        if (bitmap == null) {
            setStatus("Aucune photo chargée.")
            return
        }

        buttonAnalyze.isEnabled = false
        textPhotoHint.text = "Recherche automatique de l'écran LCD…"
        setStatus("Recherche de SYS / DIA / PULSE et cadrage automatique…")

        lifecycleScope.launch {
            try {
                val result = analyzeBitmap(bitmap)
                if (!cropView.setAutoQuad(result.located.quad) || !cropView.hasValidQuad()) {
                    throw IllegalArgumentException("Le cadre LCD détecté n'est pas valide")
                }

                Log.d("OmronAutoCrop", result.located.debug)
                Log.d("OmronContour", result.reading.sys.debug)
                Log.d("OmronContour", result.reading.dia.debug)
                Log.d("OmronContour", result.reading.pulse.debug)

                val reading = result.reading
                confirmationCameFromPhoto = true
                val values = Triple(reading.sys.value, reading.dia.value, reading.pulse.value)
                val summary = buildString {
                    append("Lecture proposée : ")
                    append(reading.sys.value ?: "?")
                    append(" / ")
                    append(reading.dia.value ?: "?")
                    append(" mmHg, pouls ")
                    append(reading.pulse.value ?: "?")
                    append(". Vérifiez et corrigez si nécessaire.")
                }
                showConfirmationNullable(values, summary)
                setStatus("Vérifiez puis enregistrez.")
            } catch (e: Exception) {
                textPhotoHint.text =
                    "Écran non localisé. Reprenez la photo en montrant nettement l'écran et les mots SYS, DIA et PULSE."
                setStatus("Cadrage/lecture automatique impossible : ${e.message ?: e.javaClass.simpleName}")
            } finally {
                buttonAnalyze.isEnabled = true
            }
        }
    }

    // ---------------------------------------------------------------------
    // UI navigation and Health Connect
    // ---------------------------------------------------------------------

    private fun showHome() {
        stopLiveCamera()
        dismissKeyboard()
        homePanel.visibility = View.VISIBLE
        livePanel.visibility = View.GONE
        photoPanel.visibility = View.GONE
        confirmPanel.visibility = View.GONE
        buttonNewMeasurement.visibility = View.GONE
        buttonSave.isEnabled = true
        buttonSave.text = getString(R.string.save_short)
        scrollToTop()
    }

    private fun showLivePanel() {
        dismissKeyboard()
        homePanel.visibility = View.GONE
        livePanel.visibility = View.VISIBLE
        photoPanel.visibility = View.GONE
        confirmPanel.visibility = View.GONE
        resetLiveConsensus()
        capturingFinal = false
        buttonCaptureNow.isEnabled = true
        textLiveStatus.text = "Visez l'écran : capture automatique dès que les trois valeurs sont stables."
        scrollToTop()
    }

    private fun showPhotoPanel() {
        stopLiveCamera()
        homePanel.visibility = View.GONE
        livePanel.visibility = View.GONE
        photoPanel.visibility = View.VISIBLE
        confirmPanel.visibility = View.GONE
        textPhotoHint.text = getString(R.string.photo_hint)
        scrollToTop()
    }

    private fun showConfirmationNullable(
        values: Triple<Int?, Int?, Int?>,
        description: String,
    ) {
        stopLiveCamera()
        homePanel.visibility = View.GONE
        livePanel.visibility = View.GONE
        photoPanel.visibility = View.GONE
        confirmPanel.visibility = View.VISIBLE

        measurementDateTime = LocalDateTime.now()
        updateTimeLabel()
        editSystolic.setText(values.first?.toString() ?: "")
        editDiastolic.setText(values.second?.toString() ?: "")
        editPulse.setText(values.third?.toString() ?: "")
        textDetectedOriginal.text = description
        buttonSave.isEnabled = true
        buttonSave.text = getString(R.string.save_short)
        buttonNewMeasurement.visibility = View.GONE
        scrollToTop()
    }

    private fun validateAndSave() {
        val systolic = editSystolic.text.toString().toIntOrNull()
        val diastolic = editDiastolic.text.toString().toIntOrNull()
        val pulse = editPulse.text.toString().toIntOrNull()

        if (systolic == null || diastolic == null || pulse == null) {
            setStatus("Les trois valeurs doivent être renseignées.")
            return
        }
        if (systolic !in 1..500 || diastolic !in 1..500 || pulse !in 1..500) {
            setStatus("Vérifiez la saisie : les valeurs doivent être des entiers positifs.")
            return
        }

        val values = Triple(systolic, diastolic, pulse)
        if (systolic <= diastolic) {
            AlertDialog.Builder(this)
                .setTitle("Valeurs inhabituelles")
                .setMessage(
                    "La systolique n'est pas supérieure à la diastolique. " +
                        "Vérifiez l'écran de l'Omron. Vous pouvez néanmoins enregistrer " +
                        "si ces valeurs sont bien celles affichées.",
                )
                .setNegativeButton("Corriger", null)
                .setPositiveButton("Enregistrer quand même") { _, _ -> requestHealthWrite(values) }
                .show()
            return
        }
        requestHealthWrite(values)
    }

    private fun requestHealthWrite(values: Triple<Int, Int, Int>) {
        val client = healthWriter.clientOrNull()
        if (client == null) {
            setStatus("Santé Connect n'est pas disponible. Installez/mettez à jour Santé Connect puis réessayez.")
            return
        }

        buttonSave.isEnabled = false
        buttonSave.text = getString(R.string.saving)

        lifecycleScope.launch {
            try {
                val granted = client.permissionController.getGrantedPermissions()
                if (!granted.containsAll(requiredHealthPermissions)) {
                    pendingSave = values
                    requestHealthPermissions.launch(requiredHealthPermissions)
                } else {
                    writeToHealthConnect(values)
                }
            } catch (e: Exception) {
                buttonSave.isEnabled = true
                buttonSave.text = getString(R.string.save_short)
                setStatus("Impossible de vérifier les autorisations : ${e.message ?: "erreur inconnue"}")
            }
        }
    }

    private fun writeToHealthConnect(values: Triple<Int, Int, Int>) {
        val instant = measurementDateTime.atZone(ZoneId.systemDefault()).toInstant()
        lifecycleScope.launch {
            try {
                healthWriter.insertMeasurement(
                    systolic = values.first,
                    diastolic = values.second,
                    pulse = values.third,
                    time = instant,
                )
                buttonSave.isEnabled = false
                buttonSave.text = getString(R.string.saved)
                buttonNewMeasurement.visibility = View.GONE
                setStatus(
                    "Enregistré : ${values.first}/${values.second} mmHg, pouls ${values.third}. Retour au viseur…",
                )
                Toast.makeText(this@MainActivity, "Mesure enregistrée", Toast.LENGTH_SHORT).show()
                delay(1200)
                if (confirmPanel.visibility == View.VISIBLE && !buttonSave.isEnabled) {
                    startNewMeasurement()
                }
            } catch (e: Exception) {
                buttonSave.isEnabled = true
                buttonSave.text = getString(R.string.save_short)
                setStatus("Échec de l'enregistrement : ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun startNewMeasurement() {
        currentPhotoBitmap?.recycle()
        currentPhotoBitmap = null
        pendingSave = null
        confirmationCameFromPhoto = false
        measurementDateTime = LocalDateTime.now()
        updateTimeLabel()
        resetLiveConsensus()
        resumeDefaultAcquisition()
        setStatus("Prêt — visez l'écran de l'Omron.")
    }

    private fun resumeDefaultAcquisition() {
        if (hasCameraPermission()) {
            showLivePanel()
            startLiveCamera()
        } else {
            showHome()
        }
    }

    private fun chooseMeasurementDateTime() {
        val calendar = Calendar.getInstance().apply {
            set(
                measurementDateTime.year,
                measurementDateTime.monthValue - 1,
                measurementDateTime.dayOfMonth,
                measurementDateTime.hour,
                measurementDateTime.minute,
            )
        }

        DatePickerDialog(
            this,
            { _, year, month, day ->
                TimePickerDialog(
                    this,
                    { _, hour, minute ->
                        measurementDateTime = LocalDateTime.of(year, month + 1, day, hour, minute)
                        updateTimeLabel()
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    true,
                ).show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    private fun updateTimeLabel() {
        if (!::textMeasurementTime.isInitialized) return
        val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
        textMeasurementTime.text = measurementDateTime.format(formatter)
    }

    private fun openHealthConnectSettings() {
        try {
            startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun scrollToTop() {
        if (::mainScroll.isInitialized) {
            mainScroll.post { mainScroll.smoothScrollTo(0, 0) }
        }
    }

    private fun dismissKeyboard() {
        val token = currentFocus?.windowToken
        currentFocus?.clearFocus()
        if (token != null) {
            getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(token, 0)
        }
    }

    private fun setStatus(message: String) {
        textStatus.text = message
    }

    override fun onDestroy() {
        stopLiveCamera()
        try {
            cameraController?.clearImageAnalysisAnalyzer()
        } catch (_: Exception) {
        }
        previewView.controller = null
        cameraExecutor.shutdownNow()
        currentPhotoBitmap?.recycle()
        currentPhotoBitmap = null
        autoLocator.close()
        super.onDestroy()
    }

    companion object {
        private const val LIVE_ANALYSIS_INTERVAL_MS = 650L
    }
}
