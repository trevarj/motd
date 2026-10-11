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

public val Icons.Outlined.CloudOff: ImageVector
    get() {
        if (_cloudOff != null) {
            return _cloudOff!!
        }
        _cloudOff =
            materialIcon(name = "Outlined.CloudOff") {
                materialPath {
                    moveTo(24.0f, 15.0f)
                    curveToRelative(0.0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f)
                    curveTo(18.67f, 6.59f, 15.64f, 4.0f, 12.0f, 4.0f)
                    curveToRelative(-1.33f, 0.0f, -2.57f, 0.36f, -3.65f, 0.97f)
                    lineToRelative(1.49f, 1.49f)
                    curveTo(10.51f, 6.17f, 11.23f, 6.0f, 12.0f, 6.0f)
                    curveToRelative(3.04f, 0.0f, 5.5f, 2.46f, 5.5f, 5.5f)
                    verticalLineToRelative(0.5f)
                    horizontalLineTo(19.0f)
                    curveToRelative(1.66f, 0.0f, 3.0f, 1.34f, 3.0f, 3.0f)
                    curveToRelative(0.0f, 0.99f, -0.48f, 1.85f, -1.21f, 2.4f)
                    lineToRelative(1.41f, 1.41f)
                    curveToRelative(1.09f, -0.92f, 1.8f, -2.27f, 1.8f, -3.81f)
                    close()
                    moveTo(4.41f, 3.86f)
                    lineTo(3.0f, 5.27f)
                    lineToRelative(2.77f, 2.77f)
                    horizontalLineToRelative(-0.42f)
                    curveTo(2.34f, 8.36f, 0.0f, 10.91f, 0.0f, 14.0f)
                    curveToRelative(0.0f, 3.31f, 2.69f, 6.0f, 6.0f, 6.0f)
                    horizontalLineToRelative(11.73f)
                    lineToRelative(2.0f, 2.0f)
                    lineToRelative(1.41f, -1.41f)
                    lineTo(4.41f, 3.86f)
                    close()
                    moveTo(6.0f, 18.0f)
                    curveToRelative(-2.21f, 0.0f, -4.0f, -1.79f, -4.0f, -4.0f)
                    reflectiveCurveToRelative(1.79f, -4.0f, 4.0f, -4.0f)
                    horizontalLineToRelative(1.73f)
                    lineToRelative(8.0f, 8.0f)
                    horizontalLineTo(6.0f)
                    close()
                }
            }
        return _cloudOff!!
    }

private var _cloudOff: ImageVector? = null
