package com.google.mediapipe.examples.poselandmarker.fragment

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Camera
import androidx.camera.core.AspectRatio
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController

import com.google.mediapipe.examples.poselandmarker.PoseLandmarkerHelper
import com.google.mediapipe.examples.objectdetection.ObjectDetectorHelper
import com.google.mediapipe.examples.poselandmarker.MainViewModel
import com.google.mediapipe.examples.poselandmarker.R
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentFigure8WalkingBinding
import com.google.mediapipe.framework.image.BitmapImageBuilder

import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class Figure8WalkingFragment : Fragment() {

    companion object {
        private const val TAG = "Figure8Walking"
        private const val LAPS_PER_SET = 2      // 每組走 2 圈
        private const val TOTAL_SETS = 3        // 共 3 組
        private const val LAP_REST_TIME_MS = 10000L // 圈間休息 10 秒
        private const val SET_REST_TIME_MS = 60000L // 組間休息 60 秒
        private const val VISIBILITY_THRESHOLD = 0.5f // 骨架可見度門檻
    }

    enum class ExerciseState {
        WAITING_BOTTLES, // 等待放置兩瓶水
        WALKING,         // 正在繞 8 字
        LAP_RESTING,     // 圈間休息
        SET_RESTING,     // 組間休息
        COMPLETED        // 測試完成
    }

    private var _binding: FragmentFigure8WalkingBinding? = null
    private val binding get() = _binding!!

    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private lateinit var objectDetectorHelper: ObjectDetectorHelper

    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_BACK

    // 訓練狀態變數
    private var currentLap = 0
    private var currentSet = 1
    private var currentState = ExerciseState.WAITING_BOTTLES
    private var timer: CountDownTimer? = null
    
    private var latestBottleBoxes = listOf<android.graphics.RectF>()
    private var totalAccuracyAccumulated = 0f
    private var accuracyTicks = 0

    // 8 字步路徑追蹤邏輯 (Phase 分段)
    // 0: 起點 -> 1: 繞過左瓶 -> 2: 回到中間 -> 3: 繞過右瓶 -> 4: 回到起點 (完成一圈)
    private var phase = 0 
    private var bottleLeftXNorm = 0f
    private var bottleRightXNorm = 0f
    
    private lateinit var backgroundExecutor: ExecutorService

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFigure8WalkingBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        backgroundExecutor = Executors.newSingleThreadExecutor()
        binding.viewFinder.post { setUpCamera() }

        backgroundExecutor.execute {
            poseLandmarkerHelper = PoseLandmarkerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                minPoseDetectionConfidence = viewModel.currentMinPoseDetectionConfidence,
                minPoseTrackingConfidence = viewModel.currentMinPoseTrackingConfidence,
                minPosePresenceConfidence = viewModel.currentMinPosePresenceConfidence,
                currentDelegate = viewModel.currentDelegate,
                poseLandmarkerHelperListener = poseListener
            )
            objectDetectorHelper = ObjectDetectorHelper(
                context = requireContext(),
                threshold = 0.2f, // 參考舉水瓶：使用 0.4 門檻
                runningMode = RunningMode.LIVE_STREAM,
                currentDelegate = viewModel.currentDelegate,
                objectDetectorListener = objectListener
            )
        }

        binding.btnFinish.setOnClickListener {
            findNavController().navigate(R.id.home_fragment)
        }

        binding.fabSwitchCamera.setOnClickListener {
            cameraFacing = if (cameraFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
            bindCameraUseCases()
        }
    }

    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")
        val cameraSelector = CameraSelector.Builder().requireLensFacing(cameraFacing).build()
        preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation).build()

        imageAnalyzer = ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
            .also { it.setAnalyzer(backgroundExecutor) { image -> processImageProxy(image) } }

        cameraProvider.unbindAll()
        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) { Log.e(TAG, "Use case binding failed", exc) }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageProxy(imageProxy: ImageProxy) {
        val frameTime = SystemClock.uptimeMillis()
        val isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT

        val bitmapBuffer = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
        imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
        imageProxy.close()

        val matrix = Matrix().apply {
            postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
            if (isFrontCamera) postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
        }
        val rotatedBitmap = Bitmap.createBitmap(bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true)

        val mpImageForPose = BitmapImageBuilder(rotatedBitmap).build()
        val mpImageForObject = BitmapImageBuilder(rotatedBitmap).build()

        if (this::poseLandmarkerHelper.isInitialized && !poseLandmarkerHelper.isClose()) {
            poseLandmarkerHelper.detectAsync(mpImageForPose, frameTime)
        }
        if (this::objectDetectorHelper.isInitialized && !objectDetectorHelper.isClosed()) {
            objectDetectorHelper.detectAsync(mpImageForObject, frameTime)
        }
    }

    private val objectListener = object : ObjectDetectorHelper.DetectorListener {
        override fun onError(error: String, errorCode: Int) { showErrorMsg(error) }
        override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle) {
            val results = resultBundle.results.firstOrNull() ?: return
            
            // 參考舉水瓶：過濾出水瓶並記錄座標
            val boxes = mutableListOf<android.graphics.RectF>()
            for (detection in results.detections()) {
                if (detection.categories().any { it.categoryName() == "bottle" }) {
                    boxes.add(detection.boundingBox())
                }
            }
            latestBottleBoxes = boxes.toList()
            
            activity?.runOnUiThread {
                if (_binding != null) {
                    // 傳遞結果給 OverlayView，OverlayView 已設定為只畫 "bottle"
                    binding.overlay.setObjectResults(results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM)
                }
            }
        }
    }

    private val poseListener = object : PoseLandmarkerHelper.LandmarkerListener {
        override fun onError(error: String, errorCode: Int) { showErrorMsg(error) }
        override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
            activity?.runOnUiThread {
                if (_binding != null) {
                    val results = resultBundle.results.firstOrNull() ?: return@runOnUiThread
                    processLogic(results, resultBundle.inputImageWidth, resultBundle.inputImageHeight)
                    binding.overlay.setPoseResults(results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM)
                }
            }
        }
    }

    private fun processLogic(results: PoseLandmarkerResult, imgWidth: Int, imgHeight: Int) {
        if (currentState == ExerciseState.COMPLETED) return

        val landmarks = results.landmarks().firstOrNull()
        val bottlesCount = latestBottleBoxes.size

        // 1. 水瓶偵測檢查
        if (bottlesCount < 2) {
            if (currentState == ExerciseState.WALKING) {
                updateUI("水瓶消失，請保持水瓶在畫面內")
                totalAccuracyAccumulated += 50f 
                accuracyTicks++
            } else if (currentState == ExerciseState.WAITING_BOTTLES) {
                updateUI("請在地上放置兩瓶水 (偵測到: $bottlesCount)")
                return
            }
        } else {
            // 將水瓶座標轉換為歸一化 (0~1)，方便與 PoseLandmarks 比較
            val sortedBottles = latestBottleBoxes.sortedBy { it.centerX() }
            bottleLeftXNorm = sortedBottles.first().centerX() / imgWidth
            bottleRightXNorm = sortedBottles.last().centerX() / imgWidth
        }

        // 2. 人體可見度檢查
        if (landmarks == null) {
            if (currentState == ExerciseState.WALKING) {
                updateUI("請進入畫面中")
                totalAccuracyAccumulated += 0f
                accuracyTicks++
            }
            return
        }

        val hipX = (landmarks[23].x() + landmarks[24].x()) / 2f
        val visibility = (landmarks[23].visibility().orElse(0f) + landmarks[24].visibility().orElse(0f)) / 2f

        if (visibility < VISIBILITY_THRESHOLD) {
            if (currentState == ExerciseState.WALKING) {
                updateUI("請確保全身可見")
                totalAccuracyAccumulated += 50f
                accuracyTicks++
            }
            return
        }

        // 3. 動作狀態機
        when (currentState) {
            ExerciseState.WAITING_BOTTLES -> {
                if (bottlesCount >= 2) {
                    currentState = ExerciseState.WALKING
                    phase = 0
                }
            }
            ExerciseState.WALKING -> {
                // 計算準確率：只要人在畫面內且瓶子在就是 100
                totalAccuracyAccumulated += 100f
                accuracyTicks++

                val midX = (bottleLeftXNorm + bottleRightXNorm) / 2f
                
                when (phase) {
                    0 -> { // 開始，應在中間，往左走
                        updateUI("請繞過左邊的水瓶")
                        if (hipX < bottleLeftXNorm) phase = 1
                    }
                    1 -> { // 在左側，往回走
                        updateUI("請回中間，準備繞右邊水瓶")
                        if (hipX > midX) phase = 2
                    }
                    2 -> { // 在中間，往右走
                        updateUI("請繞過右邊的水瓶")
                        if (hipX > bottleRightXNorm) phase = 3
                    }
                    3 -> { // 在右側，往回走
                        updateUI("請回到起點完成一圈")
                        if (hipX < midX) {
                            phase = 0 // 重置 Phase
                            finishLap()
                        }
                    }
                }
            }
            else -> {} // 休息中不執行邏輯
        }
    }

    private fun finishLap() {
        currentLap++
        if (currentLap >= LAPS_PER_SET) {
            if (currentSet >= TOTAL_SETS) {
                completeTest()
            } else {
                startSetRest()
            }
        } else {
            startLapRest()
        }
    }

    private fun startLapRest() {
        currentState = ExerciseState.LAP_RESTING
        timer?.cancel()
        timer = object : CountDownTimer(LAP_REST_TIME_MS, 100) {
            override fun onTick(ms: Long) {
                updateUI(String.format(Locale.US, "繞完一圈！站立休息 (%.1f s)", ms / 1000f))
            }
            override fun onFinish() {
                currentState = ExerciseState.WALKING
                phase = 0
            }
        }.start()
    }

    private fun startSetRest() {
        currentState = ExerciseState.SET_RESTING
        timer?.cancel()
        timer = object : CountDownTimer(SET_REST_TIME_MS, 100) {
            override fun onTick(ms: Long) {
                updateUI(String.format(Locale.US, "組間休息 (%.1f s)", ms / 1000f))
            }
            override fun onFinish() {
                currentSet++
                currentLap = 0
                currentState = ExerciseState.WALKING
                phase = 0
            }
        }.start()
    }

    private fun completeTest() {
        currentState = ExerciseState.COMPLETED
        val finalAccuracy = calculateAvgAccuracy()
        binding.overlay.updateTestInfo(currentLap, currentSet, "測試完成！", finalAccuracy, true, "圈數", TOTAL_SETS)
        binding.resultPanel.visibility = View.VISIBLE
        binding.tvFinalResult.text = String.format(Locale.US, "總平均準確率: %.1f%%", finalAccuracy)
    }

    private fun updateUI(status: String) {
        binding.overlay.updateTestInfo(currentLap, currentSet, status, calculateAvgAccuracy(), currentState == ExerciseState.COMPLETED, "圈數", TOTAL_SETS)
    }

    private fun calculateAvgAccuracy() = if (accuracyTicks == 0) 0f else (totalAccuracyAccumulated / accuracyTicks).coerceIn(0f, 100f)

    private fun showErrorMsg(error: String) {
        activity?.runOnUiThread { Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show() }
    }

    override fun onResume() {
        super.onResume()
        backgroundExecutor.execute {
            if(this::poseLandmarkerHelper.isInitialized && poseLandmarkerHelper.isClose()) {
                poseLandmarkerHelper.setupPoseLandmarker()
            }
            if(this::objectDetectorHelper.isInitialized && objectDetectorHelper.isClosed()) {
                objectDetectorHelper.setupObjectDetector()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        timer?.cancel()
        if (this::poseLandmarkerHelper.isInitialized) {
            backgroundExecutor.execute { poseLandmarkerHelper.clearPoseLandmarker() }
        }
        if (this::objectDetectorHelper.isInitialized) {
            backgroundExecutor.execute { objectDetectorHelper.clearObjectDetector() }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
        backgroundExecutor.shutdown()
    }
}
