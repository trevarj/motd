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

package androidx.compose.material.icons.filled

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Filled.FormatBold: ImageVector
    get() {
        if (_formatBold != null) {
            return _formatBold!!
        }
        _formatBold =
            materialIcon(name = "Filled.FormatBold") {
                materialPath {
                    moveTo(15.6f, 10.79f)
                    curveToRelative(0.97f, -0.67f, 1.65f, -1.77f, 1.65f, -2.79f)
                    curveToRelative(0.0f, -2.26f, -1.75f, -4.0f, -4.0f, -4.0f)
                    lineTo(7.0f, 4.0f)
                    verticalLineToRelative(14.0f)
                    horizontalLineToRelative(7.04f)
                    curveToRelative(2.09f, 0.0f, 3.71f, -1.7f, 3.71f, -3.79f)
                    curveToRelative(0.0f, -1.52f, -0.86f, -2.82f, -2.15f, -3.42f)
                    close()
                    moveTo(10.0f, 6.5f)
                    horizontalLineToRelative(3.0f)
                    curveToRelative(0.83f, 0.0f, 1.5f, 0.67f, 1.5f, 1.5f)
                    reflectiveCurveToRelative(-0.67f, 1.5f, -1.5f, 1.5f)
                    horizontalLineToRelative(-3.0f)
                    verticalLineToRelative(-3.0f)
                    close()
                    moveTo(13.5f, 15.5f)
                    lineTo(10.0f, 15.5f)
                    verticalLineToRelative(-3.0f)
                    horizontalLineToRelative(3.5f)
                    curveToRelative(0.83f, 0.0f, 1.5f, 0.67f, 1.5f, 1.5f)
                    reflectiveCurveToRelative(-0.67f, 1.5f, -1.5f, 1.5f)
                    close()
                }
            }
        return _formatBold!!
    }

private var _formatBold: ImageVector? = null
