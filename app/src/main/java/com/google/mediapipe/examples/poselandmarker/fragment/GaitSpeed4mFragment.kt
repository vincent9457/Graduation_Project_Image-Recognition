package com.google.mediapipe.examples.poselandmarker.fragment

import android.app.AlertDialog
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.mediapipe.examples.poselandmarker.PoseLandmarkerHelper
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentGaitSpeed4mBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

// 定義測試狀態
enum class AutoTestState {
    INPUT_HEIGHT,
    WAITING_FOR_4M,
    COUNTDOWN,
    TESTING,
    FINISHED
}

class GaitSpeed4mFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    private var _binding: FragmentGaitSpeed4mBinding? = null
    private val binding get() = _binding!!
    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private lateinit var backgroundExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_BACK

    private var testCount = 0
    private var startTime = 0L
    private var bestTime = Float.MAX_VALUE
    private var bestScore = 0
    private var currentSeconds = 0f

    // 自動化狀態變數
    private var currentState = AutoTestState.INPUT_HEIGHT

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentGaitSpeed4mBinding.inflate(inflater, container, false)
        return binding.root
    }

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

        // 一進入畫面就要求輸入身高
        showHeightDialog()

        binding.btnReset.setOnClickListener {
            resetForNextTrial(true)
        }

        binding.btnUnable.setOnClickListener {
            showResult(0f, 0)
        }

        binding.btnDialogNext.setOnClickListener {
            binding.dialogLayout.visibility = View.GONE
            if (testCount >= 3 || (binding.btnDialogNext.text == "結束")) {
                findNavController().navigateUp()
            } else {
                resetForNextTrial(false)
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

    // 彈出對話框讓使用者輸入身高
    private fun showHeightDialog() {
        currentState = AutoTestState.INPUT_HEIGHT
        val input = EditText(requireContext())
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.hint = "例如: 165"

        AlertDialog.Builder(requireContext())
            .setTitle("準備開始")
            .setMessage("請輸入受測者的身高 (公分)，這將用於精準測距：")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("確定") { _, _ ->
                val heightStr = input.text.toString()
                val height = heightStr.toFloatOrNull() ?: 160f // 預設 160cm
                binding.overlay.userHeightCm = height

                currentState = AutoTestState.WAITING_FOR_4M
                binding.tvCenterStatus.text = "請退後至\n大於 4 公尺處"
                binding.tvCenterStatus.textSize = 50f
            }
            .show()
    }

    // ... (保留原本的 setUpCamera, bindCameraUseCases, processImageProxy) ...

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
        val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).setTargetRotation(binding.viewFinder.display.rotation).build()
        val imageAnalyzer = ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).setTargetRotation(binding.viewFinder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
            .also { it.setAnalyzer(backgroundExecutor) { image -> processImageProxy(image) } }
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
        preview.setSurfaceProvider(binding.viewFinder.surfaceProvider)
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        if (!::poseLandmarkerHelper.isInitialized) { imageProxy.close(); return }
        poseLandmarkerHelper.detectLiveStream(imageProxy, cameraFacing == CameraSelector.LENS_FACING_FRONT)
    }

    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        activity?.runOnUiThread {
            if (_binding != null) {
                val results = resultBundle.results.firstOrNull() ?: return@runOnUiThread

                val currentDistance = binding.overlay.currentDistance

                // === 自動化流程判斷 ===
                when (currentState) {
                    AutoTestState.WAITING_FOR_4M -> {
                        // 判斷是否已經退後超過 4 公尺
                        if (currentDistance >= 4.0f) {
                            currentState = AutoTestState.COUNTDOWN
                            startCountdownSequence()
                        }
                    }
                    AutoTestState.TESTING -> {
                        currentSeconds = (SystemClock.elapsedRealtime() - startTime) / 1000f

                        // 判斷是否已經走到目標距離 (例如從 4.2m 走到 0.2m = 走了 4m)
                        if (currentDistance <= binding.overlay.targetDistance && currentDistance > 0) {
                            finishTrial()
                        }
                    }
                    else -> {}
                }

                binding.overlay.setPoseResults(results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM)

                val msg = when (currentState) {
                    AutoTestState.WAITING_FOR_4M -> "請繼續退後..."
                    AutoTestState.TESTING -> "往前走向手機"
                    else -> "準備中"
                }

                binding.overlay.updateTestInfo(
                    count = testCount + 1,
                    sets = if (testCount > 0) testCount + 1 else 1,
                    message = msg,
                    accuracy = 0f,
                    label = "測試次數",
                    maxSets = 3,
                    setLabel = "測試次數",
                    time = String.format(Locale.US, "%.2f", currentSeconds)
                )
            }
        }
    }

    // 觸發倒數計時與設定終點
    private fun startCountdownSequence() {
        lifecycleScope.launch(Dispatchers.Main) {
            binding.tvCenterStatus.textSize = 100f
            binding.tvCenterStatus.text = "3"
            delay(1000)
            binding.tvCenterStatus.text = "2"
            delay(1000)
            binding.tvCenterStatus.text = "1"
            delay(1000)
            binding.tvCenterStatus.text = "GO!"

            // 計算終點：假設使用者現在在 4.3m 處，走 4m 後應該離手機剩下 0.3m
            val startDist = binding.overlay.currentDistance
            binding.overlay.targetDistance = max(0.2f, startDist - 4.0f) // 確保至少離手機 20cm，避免撞到
            binding.overlay.isTestingGait = true // 告訴 Overlay 畫出紅線

            startTime = SystemClock.elapsedRealtime()
            currentState = AutoTestState.TESTING

            delay(1000)
            binding.tvCenterStatus.text = "" // 清空文字
        }
    }

    private fun finishTrial() {
        currentState = AutoTestState.FINISHED
        binding.overlay.isTestingGait = false
        testCount++

        val duration = (SystemClock.elapsedRealtime() - startTime) / 1000f
        if (duration < bestTime) bestTime = duration

        val score = when {
            bestTime < 4.82f -> 4
            bestTime <= 6.2f -> 3
            bestTime <= 8.7f -> 2
            else -> 1
        }
        if (score > bestScore) bestScore = score

        showResult(duration, score)
    }

    private fun showResult(duration: Float, score: Int) {
        binding.dialogLayout.visibility = View.VISIBLE
        binding.tvResultMsg.text = String.format(Locale.US, "本次時間: %.2fs\n最短時間: %.2fs\n目前最高分: %d分", duration, bestTime, bestScore)
        binding.btnDialogNext.text = if (testCount < 3 && score > 0) "進行第${testCount + 1}次測試" else "結束"
        if (score == 0) binding.tvResultMsg.text = "獲得 0 分"
    }

    private fun resetForNextTrial(askHeight: Boolean) {
        binding.tvCenterStatus.text = ""
        binding.overlay.isTestingGait = false
        currentSeconds = 0f

        if (askHeight) {
            testCount = 0
            bestTime = Float.MAX_VALUE
            bestScore = 0
            showHeightDialog()
        } else {
            currentState = AutoTestState.WAITING_FOR_4M
            binding.tvCenterStatus.text = "請退後至\n大於 4 公尺處"
            binding.tvCenterStatus.textSize = 50f
        }
    }

    override fun onError(error: String, errorCode: Int) { Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show() }
    override fun onDestroyView() { _binding = null; super.onDestroyView(); backgroundExecutor.shutdown() }
}