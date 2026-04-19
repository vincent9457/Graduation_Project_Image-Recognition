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
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.mediapipe.examples.poselandmarker.MainViewModel
import com.google.mediapipe.examples.poselandmarker.PoseLandmarkerHelper
import com.google.mediapipe.examples.poselandmarker.R
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentBalanceTestBinding
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class BalanceTestFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    private var _binding: FragmentBalanceTestBinding? = null
    private val binding get() = _binding!!

    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    private lateinit var backgroundExecutor: ExecutorService

    // 測試狀態
    private enum class TestStage { SIDE_BY_SIDE, SEMI_TANDEM, TANDEM, COMPLETED }
    private var currentStage = TestStage.SIDE_BY_SIDE
    private var isTesting = false
    private var isPreparing = false
    private var timer: CountDownTimer? = null
    private var preparationTimer: CountDownTimer? = null
    private var initialAnklePos: Pair<Float, Float>? = null
    private val MOVEMENT_THRESHOLD = 0.05f
    private var currentSeconds = 0f

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBalanceTestBinding.inflate(inflater, container, false)
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
                poseLandmarkerHelperListener = this
            )
        }

        binding.btnDialogOk.setOnClickListener {
            binding.dialogLayout.visibility = View.GONE
            if (currentStage == TestStage.COMPLETED) {
                findNavController().navigateUp()
            } else {
                startInitialPreparation()
            }
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

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return
        val cameraSelector = CameraSelector.Builder().requireLensFacing(cameraFacing).build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(backgroundExecutor) { image -> processImageProxy(image) } }

        cameraProvider.unbindAll()
        camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
        preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        if (!::poseLandmarkerHelper.isInitialized) {
            imageProxy.close()
            return
        }

        val isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
        poseLandmarkerHelper.detectLiveStream(imageProxy, isFrontCamera)
    }

    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        activity?.runOnUiThread {
            if (_binding == null) return@runOnUiThread
            val results = resultBundle.results.firstOrNull() ?: return@runOnUiThread
            checkBalance(results)
            binding.overlay.setPoseResults(results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM)
            
            // 更新 OverlayView 的 leftTop 資訊
            val stageName = when(currentStage) {
                TestStage.SIDE_BY_SIDE -> "並排站立 1/3"
                TestStage.SEMI_TANDEM -> "半並排站立 2/3"
                TestStage.TANDEM -> "直線站立 3/3"
                else -> "測試完成"
            }
            binding.overlay.updateTestInfo(
                count = 0,
                sets = 0,
                message = binding.tvStatus.text.toString(),
                accuracy = 0f,
                label = "", 
                maxSets = -1, // 不顯示組數/次數
                setLabel = "階段: $stageName",
                time = String.format(Locale.US, "%.2f", currentSeconds),
                showAccuracy = false
            )
        }
    }

    private fun checkBalance(results: PoseLandmarkerResult) {
        if (isPreparing) return // 準備中不進行偵測

        val landmarks = results.landmarks().firstOrNull() ?: return

        // 1. 檢查必要節點可見度 (雙肩: 11, 12; 雙腳踝: 27, 28)
        val requiredIndices = intArrayOf(11, 12, 27, 28)
        val isVisible = requiredIndices.all { landmarks[it].visibility().orElse(0f) > 0.5f }

        if (!isVisible) {
            binding.tvStatus.text = "請全身放入畫面(偵測肩膀雙腳)"
            // 如果正在測試中卻偵測不到，停止計時
            if (isTesting) {
                timer?.cancel()
                isTesting = false
                binding.tvTimer.text = "偵測中斷"
            }
            if (isPreparing) {
                preparationTimer?.cancel()
                isPreparing = false
            }
            return
        }

        // 腳踝節點: 27, 28
        val leftAnkle = landmarks[27]
        val rightAnkle = landmarks[28]
        val dx = abs(leftAnkle.x() - rightAnkle.x())
        val dy = abs(leftAnkle.y() - rightAnkle.y())

        if (!isTesting) {
            var isCorrectStance = false
            val msg = when(currentStage) {
                TestStage.SIDE_BY_SIDE -> {
                    isCorrectStance = dx < 0.12f && dy < 0.05f
                    "請並排站立"
                }
                TestStage.SEMI_TANDEM -> {
                    isCorrectStance = dy >= 0.04f
                    "請半並排站立"
                }
                TestStage.TANDEM -> {
                    isCorrectStance = dx < 0.05f && dy >= 0.05f
                    "請直線站立"
                }
                else -> ""
            }

            if (isCorrectStance) {
                binding.tvStatus.text = "姿勢正確，開始測試"
                if (!binding.dialogLayout.isShown) startCountdown()
            } else {
                binding.tvStatus.text = msg
            }
            return
        }

        if (initialAnklePos == null) {
            initialAnklePos = Pair((leftAnkle.x() + rightAnkle.x()) / 2, (leftAnkle.y() + rightAnkle.y()) / 2)
        }

        val currentAnklePos = (leftAnkle.x() + rightAnkle.x()) / 2
        if (abs(currentAnklePos - initialAnklePos!!.first) > MOVEMENT_THRESHOLD) {
            failTest()
        }
    }

    private fun startInitialPreparation() {
        if (isPreparing || isTesting) return
        isPreparing = true
        preparationTimer?.cancel()
        preparationTimer = object : CountDownTimer(3000, 1000) {
            override fun onTick(ms: Long) {
                val sec = (ms / 1000) + 1
                binding.tvStatus.text = "請回定位，準備倒數 $sec..."
            }
            override fun onFinish() {
                isPreparing = false
            }
        }.start()
    }

    private fun startCountdown() {
        if (isTesting) return
        isTesting = true
        initialAnklePos = null
        currentSeconds = 0f
        val timeLimit = 10000L
        timer?.cancel()
        timer = object : CountDownTimer(timeLimit, 100) {
            override fun onTick(ms: Long) {
                binding.tvTimer.text = "倒數: ${(ms/1000) + 1}秒"
                currentSeconds = (10000L - ms) / 1000f
            }
            override fun onFinish() {
                currentSeconds = 10f
                if (isTesting) passStage()
            }
        }.start()
    }

    private fun failTest() {
        isTesting = false
        timer?.cancel()
        val score = if (currentStage == TestStage.TANDEM) {
            // 直線站立特殊判定
            val elapsed = 10 - (binding.tvTimer.text.toString().filter { it.isDigit() }.toLong())
            if (elapsed < 3) "0分" else "1分"
        } else {
            "0分"
        }
        showResult("獲得 $score")
        currentStage = TestStage.COMPLETED
    }

    private fun passStage() {
        isTesting = false
        val score = if (currentStage == TestStage.TANDEM) "2分" else "1分"
        showResult("獲得 $score")
        currentStage = when(currentStage) {
            TestStage.SIDE_BY_SIDE -> TestStage.SEMI_TANDEM
            TestStage.SEMI_TANDEM -> TestStage.TANDEM
            else -> TestStage.COMPLETED
        }
    }

    private fun showResult(text: String) {
        binding.dialogLayout.visibility = View.VISIBLE
        binding.tvDialogResult.text = text
    }

    override fun onError(error: String, errorCode: Int) {
        Log.e("Balance", error)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
        timer?.cancel()
        preparationTimer?.cancel()
        backgroundExecutor.shutdown()
    }
}
