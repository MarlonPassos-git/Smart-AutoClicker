/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.common

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** Keeps one-shot overlay results until a lifecycle collector becomes active. */
internal class RecoverableOverlayResultQueue<T> {

    private val pendingResults = Channel<T>(Channel.BUFFERED)
    val results: Flow<T> = pendingResults.receiveAsFlow()

    suspend fun enqueue(result: T) {
        pendingResults.send(result)
    }
}
