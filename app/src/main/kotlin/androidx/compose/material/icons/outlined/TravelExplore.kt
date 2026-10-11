/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.material.icons.outlined

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Outlined.TravelExplore: ImageVector
    get() {
        if (_travelExplore != null) {
            return _travelExplore!!
        }
        _travelExplore =
            materialIcon(name = "Outlined.TravelExplore") {
                materialPath {
                    moveTo(19.3f, 16.9f)
                    curveToRelative(0.4f, -0.7f, 0.7f, -1.5f, 0.7f, -2.4f)
                    curveToRelative(0.0f, -2.5f, -2.0f, -4.5f, -4.5f, -4.5f)
                    reflectiveCurveTo(11.0f, 12.0f, 11.0f, 14.5f)
                    reflectiveCurveToRelative(2.0f, 4.5f, 4.5f, 4.5f)
                    curveToRelative(0.9f, 0.0f, 1.7f, -0.3f, 2.4f, -0.7f)
                    lineToRelative(3.2f, 3.2f)
                    lineToRelative(1.4f, -1.4f)
                    lineTo(19.3f, 16.9f)
                    close()
                    moveTo(15.5f, 17.0f)
                    curveToRelative(-1.4f, 0.0f, -2.5f, -1.1f, -2.5f, -2.5f)
                    reflectiveCurveToRelative(1.1f, -2.5f, 2.5f, -2.5f)
                    reflectiveCurveToRelative(2.5f, 1.1f, 2.5f, 2.5f)
                    reflectiveCurveTo(16.9f, 17.0f, 15.5f, 17.0f)
                    close()
                    moveTo(12.0f, 20.0f)
                    verticalLineToRelative(2.0f)
                    curveTo(6.48f, 22.0f, 2.0f, 17.52f, 2.0f, 12.0f)
                    curveTo(2.0f, 6.48f, 6.48f, 2.0f, 12.0f, 2.0f)
                    curveToRelative(4.84f, 0.0f, 8.87f, 3.44f, 9.8f, 8.0f)
                    horizontalLineToRelative(-2.07f)
                    curveToRelative(-0.64f, -2.46f, -2.4f, -4.47f, -4.73f, -5.41f)
                    verticalLineTo(5.0f)
                    curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
                    horizontalLineToRelative(-2.0f)
                    verticalLineToRelative(2.0f)
                    curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
                    horizontalLineTo(8.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(3.0f)
                    horizontalLineTo(9.0f)
                    lineToRelative(-4.79f, -4.79f)
                    curveTo(4.08f, 10.79f, 4.0f, 11.38f, 4.0f, 12.0f)
                    curveTo(4.0f, 16.41f, 7.59f, 20.0f, 12.0f, 20.0f)
                    close()
                }
            }
        return _travelExplore!!
    }

private var _travelExplore: ImageVector? = null
