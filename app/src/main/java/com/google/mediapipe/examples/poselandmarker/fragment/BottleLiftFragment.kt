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
import com.google.mediapipe.examples.objectdetection.ObjectDetectorHelper // 新增
import com.google.mediapipe.examples.poselandmarker.MainViewModel
import com.google.mediapipe.examples.poselandmarker.R
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentBottleLiftBinding
import com.google.mediapipe.framework.image.BitmapImageBuilder // 新增

import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2

// 移除原本直接實作的 Listener，改用匿名物件
class BottleLiftFragment : Fragment() {

    companion object {
        private const val TAG = "BottleLift"
        private const val TOTAL_REPS_PER_SET = 12
        private const val TOTAL_SETS = 3
        private const val SET_REST_TIME_MS = 60000L
        private const val VISIBILITY_THRESHOLD = 0.5f
        private const val LIFT_ANGLE_THRESHOLD = 60.0
        private const val DOWN_ANGLE_THRESHOLD = 150.0
    }

    private var _binding: FragmentBottleLiftBinding? = null
    private val binding get() = _binding!!

    // 宣告兩個 Helper
    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private lateinit var objectDetectorHelper: ObjectDetectorHelper

    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    // Test Variables
    private var currentRep = 0
    private var currentSet = 1
    private var isLifting = false
    private var isRestingBetweenSets = false
    private var isTestCompleted = false

    // 水瓶偵測標記
    private var isBottleDetected = false

    private var timer: CountDownTimer? = null
    private var totalAccuracyAccumulated = 0f
    private var accuracyTicks = 0
    private var currentTimerStatusText = ""

    private lateinit var backgroundExecutor: ExecutorService

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBottleLiftBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        backgroundExecutor = Executors.newSingleThreadExecutor()
        binding.viewFinder.post { setUpCamera() }

        backgroundExecutor.execute {
            // 初始化骨架模型
            poseLandmarkerHelper = PoseLandmarkerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                minPoseDetectionConfidence = viewModel.currentMinPoseDetectionConfidence,
                minPoseTrackingConfidence = viewModel.currentMinPoseTrackingConfidence,
                minPosePresenceConfidence = viewModel.currentMinPosePresenceConfidence,
                currentDelegate = viewModel.currentDelegate,
                poseLandmarkerHelperListener = poseListener // 使用下方定義的 Listener
            )
            // 初始化物件偵測模型
            objectDetectorHelper = ObjectDetectorHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                currentDelegate = viewModel.currentDelegate,
                objectDetectorListener = objectListener // 使用下方定義的 Listener
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

        // 替換成共用影像處理邏輯
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

    // --- 影像分配中心 ---
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

        // 關鍵修復：建立兩個獨立的 MPImage 實例，避免底層記憶體互相搶奪被提早關閉
        val mpImageForPose = BitmapImageBuilder(rotatedBitmap).build()
        val mpImageForObject = BitmapImageBuilder(rotatedBitmap).build()

        if (this::poseLandmarkerHelper.isInitialized && !poseLandmarkerHelper.isClose()) {
            poseLandmarkerHelper.detectAsync(mpImageForPose, frameTime)
        }
        if (this::objectDetectorHelper.isInitialized && !objectDetectorHelper.isClosed()) {
            objectDetectorHelper.detectAsync(mpImageForObject, frameTime)
        }
    }

    // --- Object Detector Listener (處理水瓶偵測) ---
    private val objectListener = object : ObjectDetectorHelper.DetectorListener {
        override fun onError(error: String, errorCode: Int) {
            showErrorMsg(error)
        }

        override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle) {
            activity?.runOnUiThread {
                if (_binding != null) {
                    val results = resultBundle.results.firstOrNull() ?: return@runOnUiThread

                    // 尋找畫面中是否有 "bottle"
                    var foundBottle = false
                    for (detection in results.detections()) {
                        for (category in detection.categories()) {
                            // COCO 資料集中的水瓶標籤為 "bottle"
                            if (category.categoryName() == "bottle" && category.score() > 0.5f) {
                                foundBottle = true
                                break
                            }
                        }
                        if (foundBottle) break
                    }
                    isBottleDetected = foundBottle

                    // 畫出物件紅框
                    binding.overlay.setObjectResults(
                        results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM
                    )
                }
            }
        }
    }

    // --- Pose Landmarker Listener (處理骨架與運動邏輯) ---
    private val poseListener = object : PoseLandmarkerHelper.LandmarkerListener {
        override fun onError(error: String, errorCode: Int) {
            showErrorMsg(error)
        }

        override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
            activity?.runOnUiThread {
                if (_binding != null) {
                    val results = resultBundle.results.firstOrNull() ?: return@runOnUiThread
                    processLogic(results)
                    // 畫出骨架
                    binding.overlay.setPoseResults(
                        results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM
                    )
                }
            }
        }
    }

    private fun processLogic(results: PoseLandmarkerResult) {
        if (isTestCompleted || results.landmarks().isEmpty() || isRestingBetweenSets) return

        val landmarks = results.landmarks()[0]

        // 1. 可見度檢查
        val requiredIndices = intArrayOf(11, 12, 13, 14, 15, 16)
        val isVisible = requiredIndices.all { landmarks[it].visibility().orElse(0f) > VISIBILITY_THRESHOLD }

        if (!isVisible) {
            binding.overlay.updateTestInfo(currentRep, currentSet, "請將上半身放入畫面", calculateAvgAccuracy(), isTestCompleted, "次數")
            return
        }

        // 2. 水瓶檢查 (這就是我們剛剛透過 Object Detector 拿到的結果)
        if (!isBottleDetected) {
            binding.overlay.updateTestInfo(currentRep, currentSet, "請拿好水瓶", calculateAvgAccuracy(), isTestCompleted, "次數")
            return
        }

        // 3. 角度計算
        val leftAngle = calculateAngle(landmarks[11], landmarks[13], landmarks[15])
        val rightAngle = calculateAngle(landmarks[12], landmarks[14], landmarks[16])

        // 4. 舉水瓶動作判定
        if (!isLifting) {
            if (leftAngle < LIFT_ANGLE_THRESHOLD && rightAngle < LIFT_ANGLE_THRESHOLD) {
                isLifting = true
            }
        } else {
            if (leftAngle > DOWN_ANGLE_THRESHOLD && rightAngle > DOWN_ANGLE_THRESHOLD) {
                isLifting = false
                currentRep++
                if (currentRep >= TOTAL_REPS_PER_SET) {
                    if (currentSet < TOTAL_SETS) startSetRestTimer() else completeTest()
                }
            }
        }

        // 5. 準確率計算
        val symmetry = 1.0f - abs(leftAngle - rightAngle).toFloat() / 180f
        val liftScore = if (isLifting) {
            val leftWristShoulderDiff = abs(landmarks[15].y() - landmarks[11].y())
            val rightWristShoulderDiff = abs(landmarks[16].y() - landmarks[12].y())
            1.0f - (leftWristShoulderDiff + rightWristShoulderDiff) / 2f
        } else {
            1.0f
        }

        totalAccuracyAccumulated += (symmetry * 0.5f + liftScore * 0.5f) * 100f
        accuracyTicks++

        val status = if (isRestingBetweenSets) {
            currentTimerStatusText
        } else {
            if (isLifting) "請放下水瓶" else "請將水瓶舉至肩高"
        }

        binding.overlay.updateTestInfo(currentRep, currentSet, status, calculateAvgAccuracy(), isTestCompleted, "次數")
    }

    private fun calculateAngle(a: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
                               b: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
                               c: com.google.mediapipe.tasks.components.containers.NormalizedLandmark): Double {
        val ang = Math.toDegrees(
            (atan2(c.y() - b.y(), c.x() - b.x()) - atan2(a.y() - b.y(), a.x() - b.x())).toDouble()
        )
        return abs(if (ang > 180) ang - 360 else if (ang < -180) ang + 360 else ang)
    }

    private fun calculateAvgAccuracy() = if (accuracyTicks == 0) 0f else (totalAccuracyAccumulated / accuracyTicks).coerceIn(0f, 100f)

    private fun startSetRestTimer() {
        isRestingBetweenSets = true
        timer?.cancel()
        timer = object : CountDownTimer(SET_REST_TIME_MS, 1000) {
            override fun onTick(ms: Long) {
                currentTimerStatusText = "組間休息 (${ms/1000}s)"
                binding.overlay.updateTestInfo(currentRep, currentSet, currentTimerStatusText, calculateAvgAccuracy(), isTestCompleted, "次數")
            }
            override fun onFinish() {
                isRestingBetweenSets = false
                currentSet++
                currentRep = 0
            }
        }.start()
    }

    private fun completeTest() {
        isTestCompleted = true
        val finalAccuracy = calculateAvgAccuracy()
        binding.overlay.updateTestInfo(currentRep, currentSet, "測試完成！", finalAccuracy, true, "次數")
        binding.resultPanel.visibility = View.VISIBLE
        binding.tvFinalResult.text = String.format(Locale.US, "總平均準確率: %.1f%%", finalAccuracy)
    }

    private fun showErrorMsg(error: String) {
        activity?.runOnUiThread { Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show() }
    }

    override fun onResume() {
        super.onResume()
        // 當 App 從背景回到前景時，重新喚醒兩個模型
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