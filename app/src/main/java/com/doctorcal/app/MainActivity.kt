package com.doctorcal.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.doctorcal.app.ui.DoctorCalScreen
import com.doctorcal.app.ui.DoctorCalTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onStart() {
        super.onStart()
        viewModel.onForeground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DoctorCalTheme {
                // Android's standard permission prompt for reading and writing the phone's calendar.
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { result -> viewModel.onPermissionResult(result.isNotEmpty() && result.values.all { it }) }
                val needsPermission by viewModel.needsPermission.collectAsStateWithLifecycle()
                LaunchedEffect(needsPermission) {
                    if (needsPermission) {
                        permissionLauncher.launch(
                            arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                        )
                        viewModel.permissionRequested()
                    }
                }
                DoctorCalScreen(viewModel)
            }
        }
    }
}
