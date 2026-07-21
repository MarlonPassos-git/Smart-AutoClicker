package com.buzbuz.smartautoclicker.feature.smart.config.ui.action.areaclick

import com.buzbuz.smartautoclicker.core.domain.model.action.AreaClickDistribution

data class AreaClickUiState(
    val canBeSaved: Boolean,
    val name: String?,
    val clickCount: String,
    val pressDuration: String,
    val interval: String,
    val distribution: AreaClickDistribution,
    val verticesCount: Int,
)
