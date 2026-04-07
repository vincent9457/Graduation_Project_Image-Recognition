package com.google.mediapipe.examples.poselandmarker.fragment

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.mediapipe.examples.poselandmarker.MainViewModel
import com.google.mediapipe.examples.poselandmarker.PoseLandmarkerHelper
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentGalleryBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class GalleryFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    enum class MediaType {
        IMAGE, VIDEO, UNKNOWN
    }

    private var _fragmentGalleryBinding: FragmentGalleryBinding? = null
    private val fragmentGalleryBinding get() = _fragmentGalleryBinding!!
    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()

    // Gait Analysis Variables
    private var currentStep = 0
    private var lastLeadingFoot = -1
    private var totalLineDeviation = 0f
    private var validContactCount = 0

    private var backgroundExecutor: ScheduledExecutorService? = null

    private val getContent =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { mediaUri ->
                when (val mediaType = loadMediaType(mediaUri)) {
                    MediaType.IMAGE -> runDetectionOnImage(mediaUri)
                    MediaType.VIDEO -> runDetectionOnVideo(mediaUri)
                    MediaType.UNKNOWN -> {
                        updateDisplayView(mediaType)
                        Toast.makeText(requireContext(), "不支持的文件格式", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _fragmentGalleryBinding = FragmentGalleryBinding.inflate(inflater, container, false)
        return fragmentGalleryBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fragmentGalleryBinding.fabGetContent.setOnClickListener {
            getContent.launch(arrayOf("image/*", "video/*"))
        }
        initBottomSheetControls()
    }

    override fun onPause() {
        super.onPause()
        stopAnalysis()
    }

    private fun stopAnalysis() {
        backgroundExecutor?.shutdownNow()
        backgroundExecutor = null
        if (_fragmentGalleryBinding != null && fragmentGalleryBinding.videoView.isPlaying) {
            fragmentGalleryBinding.videoView.stopPlayback()
        }
        _fragmentGalleryBinding?.let {
            it.videoView.visibility = View.GONE
            it.progress.visibility = View.GONE
            it.overlay.clear()
        }
        setUiEnabled(true)
    }

    private fun initBottomSheetControls() {
        // 更新顯示文字的輔助函式
        fun updateText() {
            fragmentGalleryBinding.bottomSheetLayout.detectionThresholdValue.text =
                String.format(Locale.US, "%.2f", viewModel.currentMinPoseDetectionConfidence)
            fragmentGalleryBinding.bottomSheetLayout.trackingThresholdValue.text =
                String.format(Locale.US, "%.2f", viewModel.currentMinPoseTrackingConfidence)
            fragmentGalleryBinding.bottomSheetLayout.presenceThresholdValue.text =
                String.format(Locale.US, "%.2f", viewModel.currentMinPosePresenceConfidence)
        }

        updateText()

        // Detection Threshold
        fragmentGalleryBinding.bottomSheetLayout.detectionThresholdMinus.setOnClickListener {
            if (viewModel.currentMinPoseDetectionConfidence >= 0.2f) {
                viewModel.setMinPoseDetectionConfidence(viewModel.currentMinPoseDetectionConfidence - 0.1f)
                updateControlsUi()
            }
        }
        fragmentGalleryBinding.bottomSheetLayout.detectionThresholdPlus.setOnClickListener {
            if (viewModel.currentMinPoseDetectionConfidence <= 0.8f) {
                viewModel.setMinPoseDetectionConfidence(viewModel.currentMinPoseDetectionConfidence + 0.1f)
                updateControlsUi()
            }
        }

        // Tracking Threshold
        fragmentGalleryBinding.bottomSheetLayout.trackingThresholdMinus.setOnClickListener {
            if (viewModel.currentMinPoseTrackingConfidence >= 0.2f) {
                viewModel.setMinPoseTrackingConfidence(viewModel.currentMinPoseTrackingConfidence - 0.1f)
                updateControlsUi()
            }
        }
        fragmentGalleryBinding.bottomSheetLayout.trackingThresholdPlus.setOnClickListener {
            if (viewModel.currentMinPoseTrackingConfidence <= 0.8f) {
                viewModel.setMinPoseTrackingConfidence(viewModel.currentMinPoseTrackingConfidence + 0.1f)
                updateControlsUi()
            }
        }

        // Presence Threshold
        fragmentGalleryBinding.bottomSheetLayout.presenceThresholdMinus.setOnClickListener {
            if (viewModel.currentMinPosePresenceConfidence >= 0.2f) {
                viewModel.setMinPosePresenceConfidence(viewModel.currentMinPosePresenceConfidence - 0.1f)
                updateControlsUi()
            }
        }
        fragmentGalleryBinding.bottomSheetLayout.presenceThresholdPlus.setOnClickListener {
            if (viewModel.currentMinPosePresenceConfidence <= 0.8f) {
                viewModel.setMinPosePresenceConfidence(viewModel.currentMinPosePresenceConfidence + 0.1f)
                updateControlsUi()
            }
        }

        fragmentGalleryBinding.bottomSheetLayout.spinnerDelegate.setSelection(viewModel.currentDelegate)
        fragmentGalleryBinding.bottomSheetLayout.spinnerDelegate.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
                viewModel.setDelegate(p2)
                updateControlsUi()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
    }

    private fun updateControlsUi() {
        stopAnalysis()
        fragmentGalleryBinding.bottomSheetLayout.detectionThresholdValue.text = String.format(Locale.US, "%.2f", viewModel.currentMinPoseDetectionConfidence)
        fragmentGalleryBinding.bottomSheetLayout.trackingThresholdValue.text = String.format(Locale.US, "%.2f", viewModel.currentMinPoseTrackingConfidence)
        fragmentGalleryBinding.bottomSheetLayout.presenceThresholdValue.text = String.format(Locale.US, "%.2f", viewModel.currentMinPosePresenceConfidence)
        fragmentGalleryBinding.tvPlaceholder.visibility = View.VISIBLE
        fragmentGalleryBinding.imageResult.visibility = View.GONE
    }

    private fun runDetectionOnImage(uri: Uri) {
        stopAnalysis()
        setUiEnabled(false)
        backgroundExecutor = Executors.newSingleThreadScheduledExecutor()
        updateDisplayView(MediaType.IMAGE)

        val bitmap = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(requireActivity().contentResolver, uri))
            } else {
                MediaStore.Images.Media.getBitmap(requireActivity().contentResolver, uri)
            }.copy(Bitmap.Config.ARGB_8888, true)
        } catch (e: Exception) {
            setUiEnabled(true)
            return
        }

        bitmap?.let {
            fragmentGalleryBinding.imageResult.setImageBitmap(it)
            backgroundExecutor?.execute {
                poseLandmarkerHelper = PoseLandmarkerHelper(
                    context = requireContext(), runningMode = RunningMode.IMAGE,
                    minPoseDetectionConfidence = viewModel.currentMinPoseDetectionConfidence,
                    minPoseTrackingConfidence = viewModel.currentMinPoseTrackingConfidence,
                    minPosePresenceConfidence = viewModel.currentMinPosePresenceConfidence,
                    currentDelegate = viewModel.currentDelegate
                )
                poseLandmarkerHelper.detectImage(it)?.let { result ->
                    activity?.runOnUiThread {
                        if (_fragmentGalleryBinding != null) {
                            fragmentGalleryBinding.overlay.setResults(result.results[0], it.height, it.width, RunningMode.IMAGE)
                            setUiEnabled(true)
                        }
                    }
                } ?: activity?.runOnUiThread { setUiEnabled(true) }
                poseLandmarkerHelper.clearPoseLandmarker()
            }
        }
    }

    private fun runDetectionOnVideo(uri: Uri) {
        stopAnalysis()
        setUiEnabled(false)
        updateDisplayView(MediaType.VIDEO)

        currentStep = 0
        lastLeadingFoot = -1
        totalLineDeviation = 0f
        validContactCount = 0

        fragmentGalleryBinding.videoView.setVideoURI(uri)
        fragmentGalleryBinding.videoView.setOnPreparedListener { it.setVolume(0f, 0f) }

        backgroundExecutor = Executors.newSingleThreadScheduledExecutor()
        backgroundExecutor?.execute {
            poseLandmarkerHelper = PoseLandmarkerHelper(
                context = requireContext(), runningMode = RunningMode.VIDEO,
                minPoseDetectionConfidence = viewModel.currentMinPoseDetectionConfidence,
                minPoseTrackingConfidence = viewModel.currentMinPoseTrackingConfidence,
                minPosePresenceConfidence = viewModel.currentMinPosePresenceConfidence,
                currentDelegate = viewModel.currentDelegate
            )

            activity?.runOnUiThread {
                fragmentGalleryBinding.videoView.visibility = View.GONE
                fragmentGalleryBinding.progress.visibility = View.VISIBLE
            }

            val resultBundle = poseLandmarkerHelper.detectVideoFile(uri, VIDEO_INTERVAL_MS)

            activity?.runOnUiThread {
                if (_fragmentGalleryBinding != null) {
                    fragmentGalleryBinding.progress.visibility = View.GONE
                    if (resultBundle != null) {
                        displayVideoResult(resultBundle)
                    } else {
                        setUiEnabled(true)
                        Toast.makeText(requireContext(), "影片分析失敗", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            poseLandmarkerHelper.clearPoseLandmarker()
        }
    }

    private fun displayVideoResult(result: PoseLandmarkerHelper.ResultBundle) {
        fragmentGalleryBinding.videoView.visibility = View.VISIBLE
        fragmentGalleryBinding.videoView.start()
        val videoStartTimeMs = SystemClock.uptimeMillis()

        backgroundExecutor?.scheduleAtFixedRate({
            activity?.runOnUiThread {
                if (_fragmentGalleryBinding == null || fragmentGalleryBinding.videoView.visibility == View.GONE) {
                    backgroundExecutor?.shutdown()
                    return@runOnUiThread
                }

                val videoElapsedTimeMs = SystemClock.uptimeMillis() - videoStartTimeMs
                val resultIndex = videoElapsedTimeMs.div(VIDEO_INTERVAL_MS).toInt()

                if (resultIndex >= result.results.size) {
                    backgroundExecutor?.shutdown()
                    setUiEnabled(true)
                } else {
                    val poseResult = result.results[resultIndex]
                    processGaitLogic(poseResult)
                    fragmentGalleryBinding.overlay.setResults(poseResult, result.inputImageHeight, result.inputImageWidth, RunningMode.VIDEO)
                }
            }
        }, 0, VIDEO_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun processGaitLogic(results: PoseLandmarkerResult) {
        if (results.landmarks().isEmpty()) return
        val landmarks = results.landmarks()[0]

        // 腳步關鍵點可見度檢查
        val leftVisible = landmarks[29].visibility().orElse(0f) > VISIBILITY_THRESHOLD && landmarks[31].visibility().orElse(0f) > VISIBILITY_THRESHOLD
        val rightVisible = landmarks[30].visibility().orElse(0f) > VISIBILITY_THRESHOLD && landmarks[32].visibility().orElse(0f) > VISIBILITY_THRESHOLD

        if (!leftVisible || !rightVisible) {
            fragmentGalleryBinding.overlay.updateTestInfo(currentStep, 1, "分析中 (請確保雙腳在畫面內)", calculateAccuracy())
            return
        }

        val leftHeel = landmarks[29]; val rightHeel = landmarks[30]
        val leftToe = landmarks[31]; val rightToe = landmarks[32]

        val yDiff = leftHeel.y() - rightHeel.y()
        val currentLeadingFoot = when {
            yDiff > MIN_STEP_DISTANCE -> 0
            yDiff < -MIN_STEP_DISTANCE -> 1
            else -> lastLeadingFoot
        }

        if (lastLeadingFoot != -1 && currentLeadingFoot != lastLeadingFoot) {
            currentStep++
            val contactDist = if (currentLeadingFoot == 0) abs(rightToe.y() - leftHeel.y()) else abs(leftToe.y() - rightHeel.y())
            if (contactDist < CONTACT_THRESHOLD) validContactCount++
            totalLineDeviation += abs(((leftHeel.x() + rightHeel.x()) / 2f) - 0.5f)
        }
        lastLeadingFoot = currentLeadingFoot

        fragmentGalleryBinding.overlay.updateTestInfo(currentStep, 1, "影片分析中", calculateAccuracy())
    }

    private fun calculateAccuracy(): Float {
        if (currentStep <= 0) return 0f
        val lineScore = (1f - (totalLineDeviation / (currentStep * LINE_THRESHOLD))).coerceIn(0f, 1f)
        val contactScore = (validContactCount.toFloat() / currentStep).coerceIn(0f, 1f)
        return (lineScore * 0.6f + contactScore * 0.4f) * 100f
    }

    private fun updateDisplayView(mediaType: MediaType) {
        fragmentGalleryBinding.imageResult.visibility = if (mediaType == MediaType.IMAGE) View.VISIBLE else View.GONE
        fragmentGalleryBinding.videoView.visibility = if (mediaType == MediaType.VIDEO) View.VISIBLE else View.GONE
        fragmentGalleryBinding.tvPlaceholder.visibility = if (mediaType == MediaType.UNKNOWN) View.VISIBLE else View.GONE
    }

    private fun loadMediaType(uri: Uri): MediaType {
        val mimeType = context?.contentResolver?.getType(uri)
        mimeType?.let {
            if (it.startsWith("image")) return MediaType.IMAGE
            if (it.startsWith("video")) return MediaType.VIDEO
        }
        return MediaType.UNKNOWN
    }

    private fun setUiEnabled(enabled: Boolean) {
        _fragmentGalleryBinding?.let {
            it.fabGetContent.isEnabled = enabled
            it.bottomSheetLayout.spinnerDelegate.isEnabled = enabled
        }
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            stopAnalysis()
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
        }
    }


    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {}

    companion object {
        private const val VIDEO_INTERVAL_MS = 100L
        private const val CONTACT_THRESHOLD = 0.12f
        private const val LINE_THRESHOLD = 0.08f
        private const val MIN_STEP_DISTANCE = 0.02f
        private const val VISIBILITY_THRESHOLD = 0.5f
    }
}