package com.example.photx.core.domain.gallery

import com.example.photx.core.common.AppError
import com.example.photx.core.common.Outcome

/**
 * Validates current runtime media permission state and triggers an idempotent MediaStore-to-Room
 * reconciliation pass through [GalleryRepository].
 */
public class RefreshLocalMediaUseCase(private val repository: GalleryRepository) {
    public suspend operator fun invoke(
        scope: MediaScanScope = MediaScanScope.ALL
    ): Outcome<GalleryReconcileSummary, AppError> {
        val permissionState = repository.evaluatePermissionState()
        if (!permissionState.canScanDeviceMedia) {
            return Outcome.Failure(
                AppError.Unauthorized("Media permission is required to scan photos and videos on this device.")
            )
        }
        return repository.refreshLocalMedia(scope = scope)
    }
}
