package com.example.posetracker.fragment

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
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
import androidx.navigation.Navigation
import com.example.posetracker.MainViewModel
import com.example.posetracker.PoseLandmarkerHelper
import com.example.posetracker.R
import com.example.posetracker.databinding.FragmentCameraBinding
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

class CameraFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    companion object {
        private const val TAG = "Pose Landmarker"
        private const val KNEE_INWARD_ANGLE_THRESHOLD = 1.0
        private val MIN_KNEE_DISTANCE_FROM_HIP_THRESHOLD = 0.05f
        private val SQUAT_ANGLE_THRESHOLD = 140.0

    }


    private var squatCount = 0
    private var isSquatInProgress = false
    private val squatAngleThreshold = 90.0
    private val restingThreshold = 170.0

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding
        get() = _fragmentCameraBinding!!

    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    private lateinit var backgroundExecutor: ExecutorService

    override fun onResume() {
        super.onResume()
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(
                requireActivity(), R.id.fragment_container
            ).navigate(R.id.action_camera_to_permissions)
        }

        backgroundExecutor.execute {
            if (this::poseLandmarkerHelper.isInitialized) {
                if (poseLandmarkerHelper.isClosed()) {
                    poseLandmarkerHelper.setupPoseLandmarker()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (this::poseLandmarkerHelper.isInitialized) {
            viewModel.setMinPoseDetectionConfidence(poseLandmarkerHelper.minPoseDetectionConfidence)
            viewModel.setMinPoseTrackingConfidence(poseLandmarkerHelper.minPoseTrackingConfidence)
            viewModel.setMinPosePresenceConfidence(poseLandmarkerHelper.minPosePresenceConfidence)
            viewModel.setDelegate(poseLandmarkerHelper.currentDelegate)

            backgroundExecutor.execute { poseLandmarkerHelper.clearPoseLandmarker() }
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()

        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(
            Long.MAX_VALUE, TimeUnit.NANOSECONDS
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        backgroundExecutor = Executors.newSingleThreadExecutor()

        fragmentCameraBinding.viewFinder.post {
            setUpCamera()
        }

        backgroundExecutor.execute {
            poseLandmarkerHelper = PoseLandmarkerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                minPoseDetectionConfidence = viewModel.currentMinPoseDetectionConfidence,
                minPoseTrackingConfidence = viewModel.currentMinPoseTrackingConfidence,
                minPosePresenceConfidence = viewModel.currentMinPosePresenceConfidence,
                currentDelegate = viewModel.currentDelegate,
                poseLandmarkerHelperListener = this@CameraFragment
            )
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

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build().also {
                it.setAnalyzer(backgroundExecutor) { image -> detectPose(image) }
            }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun detectPose(imageProxy: ImageProxy) {
        if (this::poseLandmarkerHelper.isInitialized) {
            poseLandmarkerHelper.detectLiveStream(
                imageProxy = imageProxy,
                isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
            )
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation = fragmentCameraBinding.viewFinder.display.rotation
    }

    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        activity?.runOnUiThread {
            if (_fragmentCameraBinding != null) {
                val squatProgressBar = fragmentCameraBinding.squatProgressBar
                fragmentCameraBinding.overlay.setResults(
                    resultBundle.results.first(),
                    resultBundle.inputImageHeight,
                    resultBundle.inputImageWidth,
                    RunningMode.LIVE_STREAM
                )

                fragmentCameraBinding.overlay.invalidate()

                try {
                    val temp = resultBundle.results[0].landmarks()[0]
                    val leftKneeAngle = getAngle(
                        temp[23], // Left Hip
                        temp[25], // Left Knee
                        temp[27] // Left Ankle
                    )

                    val rightKneeAngle = getAngle(
                        temp[24], // Right Hip
                        temp[26], // Right Knee
                        temp[28] // Right Ankle
                    )

                    Log.d(TAG, "Left Knee Angle: $leftKneeAngle")
                    Log.d(TAG, "Right Knee Angle: $rightKneeAngle")

                    val squatProgress = if (leftKneeAngle < restingThreshold || rightKneeAngle < restingThreshold) {
                        calculateSquatProgress(
                            min(leftKneeAngle, rightKneeAngle),
                            squatAngleThreshold,
                            true
                        )
                    } else {
                        0
                    }

                    squatProgressBar.progress = squatProgress

                    if ((leftKneeAngle < squatAngleThreshold || rightKneeAngle < squatAngleThreshold) && !isSquatInProgress) {
                        isSquatInProgress = true
                    } else if ((leftKneeAngle > squatAngleThreshold && rightKneeAngle > squatAngleThreshold) && isSquatInProgress) {
                        squatCount++
                        isSquatInProgress = false
                        fragmentCameraBinding.squatCountTextView.text = squatCount.toString()
                    }

                    // Sprawdzenie tylko, gdy osoba jest w pozycji przysiadu
                    if (leftKneeAngle < SQUAT_ANGLE_THRESHOLD || rightKneeAngle < SQUAT_ANGLE_THRESHOLD) {
                        // Sprawdzenie czy kolano jest zbyt blisko linii biodra
                        val leftKneeDistanceFromHip = calculateHorizontalDistance(temp[23], temp[25])
                        val rightKneeDistanceFromHip = calculateHorizontalDistance(temp[24], temp[26])

                        Log.d(TAG, "Left Knee Distance from Hip: $leftKneeDistanceFromHip")
                        Log.d(TAG, "Right Knee Distance from Hip: $rightKneeDistanceFromHip")

                        val isLeftKneeInward = leftKneeDistanceFromHip < MIN_KNEE_DISTANCE_FROM_HIP_THRESHOLD
                        val isRightKneeInward = rightKneeDistanceFromHip < MIN_KNEE_DISTANCE_FROM_HIP_THRESHOLD

                        Log.d(TAG, "Is Left Knee Inward: $isLeftKneeInward")
                        Log.d(TAG, "Is Right Knee Inward: $isRightKneeInward")

                        if (isLeftKneeInward || isRightKneeInward) {
                            fragmentCameraBinding.overlay.setLineColor(Color.RED)
                        } else {
                            fragmentCameraBinding.overlay.setLineColor(Color.YELLOW)
                        }
                    } else {
                        fragmentCameraBinding.overlay.setLineColor(Color.YELLOW)
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "An error occurred: ${e.message}")
                }
            }
        }
    }




    private fun calculateSquatProgress(
        currentAngle: Double,
        thresholdAngle: Double,
        isInProgress: Boolean,
        restingAngle: Double = restingThreshold
    ): Int {
        return if (isInProgress) {
            val progress = ((restingAngle - currentAngle) / (restingAngle - thresholdAngle) * 100).toInt()
            min(max(progress, 0), 100)
        } else {
            0
        }
    }

    private fun getAngle(
        firstPoint: NormalizedLandmark,
        midPoint: NormalizedLandmark,
        lastPoint: NormalizedLandmark
    ): Double {
        val midX = midPoint.x()
        val midY = midPoint.y()

        val firstX = firstPoint.x()
        val firstY = firstPoint.y()

        val lastX = lastPoint.x()
        val lastY = lastPoint.y()

        val angle1 = atan2(firstY - midY, firstX - midX)
        val angle2 = atan2(lastY - midY, lastX - midX)

        var result = Math.toDegrees((angle2 - angle1).toDouble())
        result = abs(result)
        if (result > 180) {
            result = 360.0 - result
        }

        return result
    }

    private fun checkKneeInward(hip: NormalizedLandmark, knee: NormalizedLandmark, ankle: NormalizedLandmark): Boolean {
        val kneeAngle = getAngle(hip, knee, ankle)
        Log.d(TAG, "Knee Angle: $kneeAngle")
        return kneeAngle < KNEE_INWARD_ANGLE_THRESHOLD
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun calculateHorizontalDistance(point1: NormalizedLandmark, point2: NormalizedLandmark): Float {
        return abs(point1.x() - point2.x())
    }

}


