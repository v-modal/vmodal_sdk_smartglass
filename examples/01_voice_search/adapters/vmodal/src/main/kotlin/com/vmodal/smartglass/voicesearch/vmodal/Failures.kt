package com.vmodal.smartglass.voicesearch.vmodal

import com.vmodal.sdk.AuthError
import com.vmodal.sdk.ResponseTooLarge
import com.vmodal.sdk.SdkError
import com.vmodal.smartglass.voicesearch.core.*
import kotlinx.coroutines.CancellationException

internal fun failure(stage: Stage, code: FailureCode, retryable: Boolean, message: String, query: SearchQuery? = null) =
    AppException(AppFailure(stage, code, retryable, message, query?.requestId, query?.turnId))

/** Do not include vendor messages, response maps, credentials or temporary URLs in UI failures. */
internal fun mappedFailure(error: Throwable, stage: Stage, query: SearchQuery? = null): Throwable = when (error) {
    is CancellationException, is AppException -> error
    is AuthError -> failure(stage, FailureCode.AuthRequired, false, "Sign in again.", query)
    is ResponseTooLarge -> failure(stage,
        if (stage == Stage.Image) FailureCode.ImageTooLarge else FailureCode.SearchFailed,
        false, "The response is too large.", query)
    else -> {
        val retry = error !is SdkError || error.statusCode == 0 || error.statusCode == 429 || error.statusCode >= 500
        failure(stage, if (stage == Stage.Image) FailureCode.TopImageUnavailable else FailureCode.SearchFailed,
            retry, if (stage == Stage.Image) "The first result image is unavailable." else "Search is unavailable. Try again.", query)
    }
}
